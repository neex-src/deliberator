(ns deliberator.cilocal
  "The local pipeline (M5.4): `(run! ws {:repo :workflow :job ...})` runs ONE job of a GitHub Actions workflow with `act` on the
  runtime's Podman, with the `local` environment's Floci endpoint, fake keys and region injected, so a pipeline that registers a task
  definition and drives CodeDeploy (the estate's `estate/.github/workflows/deploy.yml`) runs end to end on this box and reaches nothing
  else: the environment must be of kind `local` (`:ci/not-local` otherwise) and no other AWS variable is passed to the job.

    `repo`      a directory (absolute path) or the name of a recorded repository (an attached checkout; a bare clone needs `worktree`,
                the name of one of its worktrees under `worktrees/<repo>/`). It must be a git repository with a commit (act reads the ref).
    `workflow`  a file path (absolute, or relative to the directory), else a name looked up in `.github/workflows/` (`deploy`, `deploy.yml`).
    `job`       the job to run; act runs the jobs it `needs` first.
    `event`     default `workflow_dispatch`. `image` the runner image of `ubuntu-latest` (default `catthehacker/ubuntu:act-22.04`, which has
                node for `uses:` steps and the docker CLI; a workflow with only `run:` steps can pass a plain `debian:stable-slim`).

  `act` is found on the daemon's PATH (the sandbox installs it with mise) and talks to the runtime's Podman socket
  (`DOCKER_HOST`, from runtime.edn; the socket is also mounted into the job, so a `docker build` step builds through Podman).

  The output is the follow machinery of `logs.fetch` (`logfetch/follow-run!`): `logs.line` events as the lines arrive, ONE text file
  `sessions/<k>/ci/<target>-<ts>.log` appended per line, adopted as a raw blob at the end. A failing job is a RESULT (`:exit`, `:conclusion
  \"failure\"`), not an error: the log is what the caller came for. Cancelling kills act's process tree and removes the `act-*` containers
  it left behind."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.git :as git]
            [deliberator.logfetch :as logfetch]
            [deliberator.podman :as podman]
            [deliberator.process :as process]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.worktree :as worktree])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def default-image "docker.io/catthehacker/ubuntu:act-22.04")

(def default-budget-ms "Thirty minutes: a first run pulls images and downloads Maven dependencies." 1800000)

;; arguments -------------------------------------------------------------------------------------------

(defn resolve-dir
  "The directory to run in: an absolute path; else the recorded repository `target` (its checkout, or the worktree `worktree` of it)."
  [ws target worktree]
  (let [f (io/file (str target))]
    (cond
      (and (not worktree) (.isAbsolute f))
      (if (.isDirectory f)
        (.getCanonicalPath f)
        (fail :ci/no-dir (str target " is not a directory") :hint "an absolute directory, or the name of a repository from repo.list"))
      (str/blank? (str target))
      (fail :ci/no-dir "which repository or directory?" :hint "dlb ci local <repo-or-dir> <workflow> <job>")
      :else
      (let [e (repo/find ws target)]
        (if worktree
          (let [d (io/file (str (worktree/dir ws (:name e))) (str worktree))]
            (if (.isDirectory d)
              (.getCanonicalPath d)
              (fail :ci/no-dir (str "no worktree " worktree " of " target) :hint "worktree.list shows them; worktree.start makes one")))
          (if (:bare e)
            (fail :ci/bare-repo (str target " is a bare clone: there is no work tree to run a workflow in")
                  :hint "pass worktree (worktree.start <repo> makes one), or an absolute directory")
            (:path e)))))))

(defn resolve-workflow
  "The workflow file (absolute path) named `wf` for directory `dir`: as a path, else in `.github/workflows/` (with `.yml`/`.yaml` added)."
  [dir wf]
  (let [wf (str wf)
        cands (if (.isAbsolute (io/file wf))
                [(io/file wf)]
                (concat [(io/file dir wf)]
                        (for [base [(str ".github/workflows/" wf)] ext ["" ".yml" ".yaml"]] (io/file dir (str base ext)))))]
    (or (some #(when (.isFile ^java.io.File %) (.getCanonicalPath ^java.io.File %)) cands)
        (let [wd (io/file dir ".github" "workflows")]
          (fail :ci/no-workflow (str "no workflow file " (pr-str wf) " under " dir)
                :known (vec (sort (map #(.getName ^java.io.File %) (when (.isDirectory wd) (.listFiles wd)))))
                :hint "a path relative to the directory (estate/.github/workflows/deploy.yml), or a file name under .github/workflows/")))))

(defn injected-env
  "What the job gets from the `local` environment `e`: the Floci endpoint, the fake keys and the region, nothing else of AWS."
  [e]
  (let [{:keys [endpoint region credentials]} (:aws e)]
    {"AWS_ENDPOINT_URL" endpoint
     "AWS_DEFAULT_REGION" region
     "AWS_REGION" region
     "AWS_ACCESS_KEY_ID" (or (:access-key-id credentials) "test")
     "AWS_SECRET_ACCESS_KEY" (or (:secret-access-key credentials) "test")
     "AWS_EC2_METADATA_DISABLED" "true"
     "AWS_PAGER" ""}))

(defn act-argv
  "The `act` command line as data: `{:event :workflow :job :image :env :pull?}` -> argv. `pull?` false adds `--pull=false` (the image
  is on the sandbox); `--rm` removes the job containers at the end."
  [{:keys [event workflow job image env pull?]}]
  (vec (concat ["act" event "-W" workflow "-j" job
                "-P" (str "ubuntu-latest=" image) "-P" (str "ubuntu-22.04=" image)
                "--rm"]
               (when-not pull? ["--pull=false"])
               (mapcat (fn [[k v]] ["--env" (str k "=" v)]) (sort-by key env)))))

;; run --------------------------------------------------------------------------------------------------------

(defn- act-containers
  "Ids and names of the containers act made (`act-*`)."
  [pc]
  (into {} (for [c (podman/list-containers pc :all true)
                 :let [n (some-> (first (:Names c)) (str/replace #"^/" ""))]
                 :when (and n (str/starts-with? n "act-"))]
             [(:Id c) n])))

(defn run!
  [ws {:keys [repo worktree workflow job event env image session emit! cancelled? budget-ms]}]
  (when (str/blank? (str job))
    (fail :ci/no-job "which job?" :hint "dlb ci local <repo-or-dir> <workflow> <job>"))
  (let [dir (resolve-dir ws repo worktree)
        wf (resolve-workflow dir workflow)
        _ (when-not (git/ok? dir "rev-parse" "--verify" "-q" "HEAD")
            (fail :ci/not-a-git-repo (str dir " has no git commit") :hint "act reads the ref of the repository: git init and commit, or use a worktree"))
        e (env/load ws (or (not-empty env) "local"))
        _ (when-not (= :local (:kind e))
            (fail :ci/not-local (str "environment " (:name e) " is not a local one")
                  :hint "ci.local injects the local runtime's endpoint and fake keys; it never reaches real AWS"))
        _ (when (str/blank? (get-in e [:aws :endpoint]))
            (fail :ci/no-endpoint (str "environment " (:name e) " has no endpoint") :hint "runtime.up sets the local environment's Floci endpoint"))
        cfg (runtime/read-config ws)
        docker-host (runtime/podman-endpoint ws cfg)
        pc (runtime/podman-client ws cfg :persist? false)
        _ (when-not (podman/available? pc)
            (fail :ci/no-podman (str "Podman does not answer at " docker-host)
                  :hint "systemctl --user enable --now podman.socket (the runtime needs it too)"))
        image (or (not-empty image) default-image)
        event (or (not-empty event) "workflow_dispatch")
        argv (act-argv {:event event :workflow wf :job job :image image :env (injected-env e)
                        :pull? (not (podman/image-exists? pc image))})
        before (act-containers pc)
        result (atom nil)
        killer (atom nil)
        end :deliberator.steps.logs/end
        target (str "ci-local-" (str/replace (str (.getName (io/file dir)) "-" (.getName (io/file wf)) "-" job) #"[^A-Za-z0-9._-]+" "-"))
        start! (fn [^LinkedBlockingQueue q]
                 (.put q (str "[deliberator] " (str/join " " (remove #(str/starts-with? % "AWS_") argv))))
                 (.put q (str "[deliberator] DOCKER_HOST=" docker-host " in " dir "; the job sees AWS_ENDPOINT_URL " (get-in e [:aws :endpoint])
                              ", region " (get-in e [:aws :region]) ", fake keys"))
                 (let [th (Thread.
                           ^Runnable
                           (bound-fn []
                             (try
                               (reset! result (process/run! argv {:dir dir :extra-env {"DOCKER_HOST" docker-host}
                                                                  :budget-ms (or budget-ms default-budget-ms)
                                                                  :on-cancel! (fn [k] (reset! killer k))
                                                                  :line! (fn [l] (.put q l))
                                                                  :what (str "act " event " -j " job)}))
                               (catch Throwable t (.put q t))
                               (finally (.put q end))))
                           "deliberator-ci-local")]
                   (.setDaemon th true)
                   (.start th)
                   (fn [] (when-let [k @killer] (k)) (.interrupt th))))]
    (try
      (let [r (logfetch/follow-run! ws {:target target :session session :emit! emit! :cancelled? (or cancelled? (constantly false))
                                        :start! start! :src-kind :build :env-key (keyword (:name e))
                                        :op-label (str "ci.local " (.getName (io/file wf)) " -j " job)
                                        :kind "ci" :now (store/now) :sub "ci"
                                        :hint (str "act could not run (is it on the daemon's PATH? mise installs it); " docker-host)})
            exit (:exit @result)]
        (assoc r :exit exit :conclusion (cond (nil? exit) "cancelled" (zero? exit) "success" :else "failure")
               :dir dir :workflow wf :job job :event event :image image :docker-host docker-host :endpoint (get-in e [:aws :endpoint])))
      (finally
        (doseq [[id _] (apply dissoc (act-containers pc) (keys before))]
          (try (podman/remove! pc id :force true) (catch Exception _ nil)))))))
