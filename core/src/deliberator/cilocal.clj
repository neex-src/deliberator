(ns deliberator.cilocal
  "The local pipeline (M5.4, redone in piece 03): `(run! ws {:repo :workflow ...})` runs a GitHub Actions workflow (all its jobs in
  `needs` order, or ONE job and what it needs) with `act` on the runtime's Podman, for an event (`workflow_dispatch` with inputs, `push`, `pull_request`),
  with the `local` environment's Floci endpoint, fake keys and region injected, so a pipeline that builds an image, registers a task definition revision
  and updates the ECS service (or updates a Lambda's code) runs end to end on this box and reaches nothing else: the environment must be of kind `local`
  (`:ci/not-local` otherwise), the job gets no other AWS variable, `GITHUB_TOKEN` is a placeholder, a secret or variable the workflow names is answered
  from the workspace's `ci-local.edn` (`{:secrets {NAME value} :vars {NAME value}}`) or is `local-<NAME>`; the real ones are never read. The daemon's own
  GitHub and AWS variables are not passed to act.

    `repo`      a directory (absolute path) or the name of a recorded repository (an attached checkout; a bare clone needs `worktree`).
    `workflow`  a file path (absolute, or relative to the directory), else a name looked up in `.github/workflows/`.
    `job`       one job (act runs the jobs it `needs` first); without it the whole workflow runs.
    `event`     default `workflow_dispatch` when the workflow listens to it, else its first event. `inputs` a map of dispatch inputs.
    `image`     the runner image of `ubuntu-latest` (default `catthehacker/ubuntu:act-22.04`).

  A local run is a RUN (`deliberator.cilocalrun`): recorded at workspace level while it goes, with jobs and steps from act's own JSON (`act --json`, kept raw
  as `act.jsonl`), listed by `ci.runs` beside the GitHub ones, opened by `ci.jobs`, `ci.log`, `ci.diff` as `local-<n>`. Events: `logs.line` (the
  console), `ci.run {run number status conclusion}` at start and end, `ci.step {run job name status conclusion}` as steps move.

  The image of a deploy: act mounts the runtime's Podman socket into the job, so a `docker build -t NAME` lands in the runtime's own image store; no push
  is needed or done (Floci's ECR cannot take one). When the run is over, every ECS-bound workspace service whose revision changed is reported
  in the run's log with the image it now runs and whether that image is in the store, and the way back to the dev container (`dlb service dev NAME`).

  Cancelling kills act's process tree and removes the `act-*` containers it left; the run ends `cancelled`."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.cilocalrun :as lr]
            [deliberator.ciworkflow :as ciwf]
            [deliberator.ecsrecord :as ecsrecord]
            [deliberator.env :as env]
            [deliberator.git :as git]
            [deliberator.logfetch :as logfetch]
            [deliberator.podman :as podman]
            [deliberator.process :as process]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.worktree :as worktree])
  (:import (java.nio.file Files OpenOption StandardOpenOption)
           (java.time.temporal ChronoUnit)
           (java.util.concurrent LinkedBlockingQueue)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def default-image "docker.io/catthehacker/ubuntu:act-22.04")

(def run-label "The label every job container of a local run carries: act's containers of two runs (or of someone else's act) are told apart by it." "deliberator.ci-run")

(def default-budget-ms "Thirty minutes: a first run pulls images and downloads Maven dependencies." 1800000)

;; arguments -------------------------------------------------------------------------------------------

(defn- by-slug
  "The attached checkout whose GitHub remote is `owner/name` (so a tree row that is a GitHub repository can run its workflows locally), nil otherwise."
  [ws target]
  (when (re-matches #"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+" (str target))
    (first (filter #(and (not (:bare %)) (:path %) (= (str target) (lr/remote-slug (:path %)))) (repo/list ws)))))

(defn resolve-dir
  "The directory to run in: an absolute path; else the recorded repository `target` (its checkout, or the worktree `worktree` of it), or `owner/name` of an attached checkout's GitHub remote."
  [ws target worktree]
  (let [f (io/file (str target))]
    (cond
      (and (not worktree) (.isAbsolute f))
      (if (.isDirectory f)
        (.getCanonicalPath f)
        (fail :ci/no-dir (str target " is not a directory") :hint "an absolute directory, or the name of a repository from repo.list"))
      (str/blank? (str target))
      (fail :ci/no-dir "which repository or directory?" :hint "dlb ci local <repo-or-dir> <workflow> [job]")
      :else
      (let [e (or (by-slug ws target) (repo/find ws target))]
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

(defn workflows
  "The workflow files of the checkout `dir`: `[{:file :path :name :events :inputs :jobs}]` (every `.github/workflows/*.yml|yaml`, plus the estate's
  nested ones), read with the YAML parser; a file that does not parse is `{:file :error}`."
  [dir]
  (let [roots (distinct (filter #(.isDirectory ^java.io.File %)
                                [(io/file dir ".github" "workflows") (io/file dir "estate" ".github" "workflows")]))]
    (vec (for [^java.io.File r roots, ^java.io.File f (sort-by #(.getName ^java.io.File %) (.listFiles r))
               :when (re-find #"\.ya?ml$" (.getName f))
               :let [rel (str (.relativize (.toPath (io/file dir)) (.toPath f)))]]
           (try (let [w (ciwf/parse-file f)]
                  {:file rel :path (.getCanonicalPath f) :name (or (:name w) (str/replace (.getName f) #"\.ya?ml$" ""))
                   :events (:events w) :inputs (:inputs w) :jobs (mapv #(select-keys % [:id :name :needs :stage]) (:jobs w))})
                (catch Exception e {:file rel :path (.getCanonicalPath f) :name (.getName f) :error (ex-message e)}))))))

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

(defn local-config
  "`<ws>/ci-local.edn`: the secrets and variables a local run answers with (never the real ones), nil when absent."
  [ws] (store/read-edn (store/path ws "ci-local.edn")))

(defn- answers
  "`{:secrets {NAME value} :vars {NAME value}}` for the names the workflow refers to: the workspace's value, else `local-<NAME>`. GITHUB_TOKEN is a placeholder."
  [ws w]
  (let [cfg (local-config ws)
        pick (fn [k names] (into {} (for [n names] [n (str (or (get-in cfg [k (keyword n)]) (get-in cfg [k n]) (str "local-" n)))])))]
    {:secrets (assoc (pick :secrets (:secrets (:refs w))) "GITHUB_TOKEN" "local-github-token")
     :vars (pick :vars (:vars (:refs w)))}))

(def ^:private daemon-secret-env
  "What of the daemon's environment never reaches act: tokens and every AWS variable (act reads GITHUB_TOKEN from the environment)."
  #"(?i)^(GITHUB_.*|GH_.*|ACTIONS_.*|AWS_.*|.*TOKEN.*|.*SECRET.*|.*PASSWORD.*|.*API_KEY.*)$")

(defn act-env
  "The environment of the act process: the daemon's, without tokens and AWS variables, plus the Podman socket as DOCKER_HOST."
  [docker-host]
  (assoc (into {} (remove (fn [[k _]] (re-matches daemon-secret-env k))) (System/getenv)) "DOCKER_HOST" docker-host))

(defn act-argv
  "The `act` command line as data: `{:event :workflow :job :image :env :pull? :inputs :secrets :vars :eventpath :offline?}` -> argv. `pull?` false adds
  `--pull=false`; `--rm` removes the job containers; `--json` is the structure the run record is made from."
  [{:keys [event workflow job image env pull? inputs secrets vars eventpath offline? label]}]
  (vec (concat ["act" event "-W" workflow]
               (when job ["-j" job])
               ["-P" (str "ubuntu-latest=" image) "-P" (str "ubuntu-22.04=" image) "--rm" "--json"]
               (when-not pull? ["--pull=false"])
               (when offline? ["--action-offline-mode"])
               (when label ["--container-options" (str "--label=" run-label "=" label)])
               (when eventpath ["--eventpath" eventpath])
               (mapcat (fn [[k v]] ["--env" (str k "=" v)]) (sort-by key env))
               (mapcat (fn [[k v]] ["--input" (str k "=" v)]) (sort-by key inputs))
               (mapcat (fn [[k v]] ["--secret" (str k "=" v)]) (sort-by key secrets))
               (mapcat (fn [[k v]] ["--var" (str k "=" v)]) (sort-by key vars)))))

;; the event payloads ---------------------------------------------------------------------------------------------

(defn- git1 [dir & args] (let [r (git/run dir args)] (when (zero? (:exit r)) (str/trim (:out r)))))

(defn checkout-facts
  "What a payload needs from the checkout: branch, head sha, subject, author, the repository's name and its default branch."
  [dir key]
  {:branch (or (git1 dir "symbolic-ref" "--short" "-q" "HEAD") "main") :sha (git1 dir "rev-parse" "HEAD")
   :subject (or (git1 dir "log" "-1" "--format=%s") "") :message (or (git1 dir "log" "-1" "--format=%B") "")
   :author (or (git1 dir "log" "-1" "--format=%an") "local") :repo key :name (last (str/split (str key) #"[/]")) :default-branch "main"})

(defn event-payload
  "The JSON object of `--eventpath` for `event` built from the checkout's `facts` (branch and head) and the dispatch `inputs`. Nothing in it is a real GitHub
  fact beyond what the checkout says."
  [event facts inputs]
  (let [repo {:name (:name facts) :full_name (:repo facts) :default_branch (:default-branch facts) :private true}
        head {:ref (:branch facts) :sha (:sha facts) :label (str (:name facts) ":" (:branch facts))}
        sender {:login "local"}]
    (case event
      "push" {:ref (str "refs/heads/" (:branch facts)) :before (apply str (repeat 40 "0")) :after (:sha facts) :created false :deleted false :forced false
              :repository repo :pusher {:name (:author facts)} :sender sender
              :head_commit {:id (:sha facts) :message (:message facts) :author {:name (:author facts)}}
              :commits [{:id (:sha facts) :message (:subject facts)}]}
      "pull_request" {:action "opened" :number 0 :sender sender :repository repo
                      :pull_request {:number 0 :title (:subject facts) :state "open" :draft false :head head
                                     :base {:ref (:default-branch facts) :sha (:sha facts) :label (str (:name facts) ":" (:default-branch facts))}
                                     :user sender}}
      {:ref (str "refs/heads/" (:branch facts)) :inputs (or inputs {}) :repository repo :sender sender})))

;; run --------------------------------------------------------------------------------------------------------

(defn- act-containers
  "Ids and names of the containers act made (`act-*`), or, with `label`, only those of that run."
  ([pc] (act-containers pc nil))
  ([pc label]
   (into {} (for [c (podman/list-containers pc :all true)
                  :let [n (some-> (first (:Names c)) (str/replace #"^/" ""))
                        ls (:Labels c)]
                  :when (and n (str/starts-with? n "act-")
                             (or (nil? label) (= label (or (get ls run-label) (get ls (keyword run-label)))))) ]
              [(:Id c) n]))))

(defn selected-jobs
  "`job` and the jobs it needs, transitively (act runs those first); every job when `job` is nil."
  [w job]
  (if (str/blank? (str job))
    (:jobs w)
    (let [by (into {} (map (juxt :id identity)) (:jobs w))
          _ (when-not (by job) (fail :ci/no-job (str "no job " (pr-str job) " in " (:name w)) :known (mapv :id (:jobs w)) :hint "a job id of the workflow"))
          need (loop [todo [job] seen #{}]
                 (if-let [x (first todo)]
                   (if (seen x) (recur (rest todo) seen) (recur (concat (rest todo) (:needs (by x))) (conj seen x)))
                   seen))]
      (filterv #(need (:id %)) (:jobs w)))))

(defn- console-line
  "The console's line for an act record (the readable side of the JSON): step starts and ends, output, errors."
  [rec]
  (cond
    (:jobResult rec) (str "== job " (:jobID rec) ": " (:jobResult rec))
    (str/starts-with? (str (:msg rec)) "⭐ Run ") (str "-- " (:jobID rec) " / " (str/replace (:msg rec) #"^⭐ Run " ""))
    (:stepResult rec) (str "   " (:stepResult rec) (some->> (:executionTime rec) (#(str " in " (Math/round (/ (double %) 1e6)) " ms"))))
    (:raw_output rec) (str/replace (str (:msg rec)) #"\n$" "")
    (and (= "error" (:level rec)) (:step rec)) (str "ERROR " (:msg rec))
    (:raw rec) (str/replace (str (:raw rec)) #"\n$" "")
    :else nil))

(defn- snapshot-revisions
  "Service name -> `{:revision :image}` of every ECS record of the workspace."
  [ws]
  (let [d (store/path ws "ecs")]
    (into {} (when (Files/isDirectory d (make-array java.nio.file.LinkOption 0))
               (for [^java.io.File f (.listFiles (.toFile d)) :when (str/ends-with? (.getName f) ".edn")
                     :let [r (store/read-edn (.toPath f))] :when r]
                 [(or (:service r) (str/replace (.getName f) #"\.edn$" "")) (select-keys r [:revision :image :cluster :service :deployed :held :image-id])])))))

(defn- deploy-report
  "Lines for the end of the log: every ECS-bound service whose revision changed during the run, the image it now runs and whether it IS the revision's image
  (the watch's record: piece 02b), and the way back to the dev container (`dlb service dev NAME`). The watch looks at ECS every second: it gets two to see the
  revision."
  [ws before]
  (when (seq (snapshot-revisions ws)) (Thread/sleep 2500))
  (let [after (snapshot-revisions ws)]
    (vec (mapcat (fn [[svc a]]
                   (when (and (:revision a) (not= (:revision a) (:revision (get before svc))))
                     [(str "[deliberator] " svc " is now on revision " (:revision a) (when (:image a) (str ", image " (:image a)) )
                           (cond (nil? (:image a)) ""
                                 (ecsrecord/deployed? a) ": the service runs that image"
                                 (:held a) " (in the runtime's store, held on the dev container until the next revision)"
                                 :else " (not in the runtime's store: the service keeps its dev container)"))
                      (str "[deliberator] `dlb service dev " svc "` goes back to the dev container (it stays until the next revision).")]))
                 (sort-by key after)))))

(defn run!
  [ws {:keys [repo worktree workflow job event inputs env image session emit! cancelled? budget-ms]}]
  (let [dir (resolve-dir ws repo worktree)
        wf-path (resolve-workflow dir workflow)
        w (ciwf/parse-file wf-path)
        jobs (selected-jobs w job)
        _ (when-not (git/ok? dir "rev-parse" "--verify" "-q" "HEAD")
            (fail :ci/not-a-git-repo (str dir " has no git commit") :hint "act reads the ref of the repository: git init and commit, or use a worktree"))
        event (or (not-empty event) (if (some #{"workflow_dispatch"} (:events w)) "workflow_dispatch" (first (:events w))) "workflow_dispatch")
        _ (when (and (seq (:events w)) (not (some #{event} (:events w))))
            (fail :ci/event-not-listened (str (:name w) " does not listen to " event) :known (:events w) :hint "an event of the workflow's `on:`"))
        declared (set (map :name (:inputs w)))
        inputs (into {} (for [[k v] inputs] [(name k) (str v)]))
        _ (when-let [bad (seq (remove declared (keys inputs)))]
            (fail :ci/unknown-input (str "the workflow has no input " (str/join ", " bad)) :known (vec declared) :hint "the inputs of on.workflow_dispatch"))
        _ (when (= "workflow_dispatch" event)
            (doseq [i (:inputs w) :when (and (:required i) (nil? (:default i)) (not (contains? inputs (:name i))))]
              (fail :ci/missing-input (str "input " (:name i) " is required") :hint "pass it as --input NAME=value")))
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
        key (lr/repo-key-of-dir ws repo dir)
        facts (checkout-facts dir key)
        claim (lr/claim! ws key)
        n (:n claim)
        run-dir (:dir claim)
        event-file (.resolve run-dir "event.json")
        _ (store/write-bytes-atomic! event-file (.getBytes ^String (json/write-str (event-payload event facts inputs)) "UTF-8"))
        {:keys [secrets vars]} (answers ws w)
        job* (when-not (str/blank? (str job)) job)
        argv (act-argv {:event event :workflow wf-path :job job* :image image :env (injected-env e)
                        :pull? (not (podman/image-exists? pc image)) :inputs inputs :secrets secrets :vars vars
                        :eventpath (str event-file) :label (str (store/ws-name ws) "/" key "/" n) :offline? (.isDirectory (io/file (System/getProperty "user.home") ".cache" "act"))})
        revisions-before (snapshot-revisions ws)
        started (store/now)
        secs (fn [t] (str (.truncatedTo ^java.time.Instant t ChronoUnit/SECONDS)))
        wf-name (or (:name w) (str/replace (.getName (io/file wf-path)) #"\.ya?ml$" ""))
        run-row (fn [status conclusion]
                  {:id (str "local-" n) :number n :attempt 1 :workflow wf-name :workflow-id nil :title (:subject facts) :branch (:branch facts) :sha (:sha facts)
                   :event event :status status :conclusion conclusion :created-at (secs started) :started-at (secs started) :updated-at (secs (store/now))
                   :url nil :origin "local"})
        base {:origin lr/origin :repo key :run n :attempt 1 :dir (str run-dir) :raw {} :workflow wf-path :event event :inputs inputs :image image}
        state (atom {:jobs [] :lines {}})
        last-persist (atom 0)
        persist! (fn [final? conclusion]
                   (lr/persist! (assoc base :run-row (run-row (if final? "completed" "in_progress") conclusion)) @state final?))
        jsonl (Files/newOutputStream (.resolve run-dir "act.jsonl") (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND]))
        runlog (Files/newOutputStream (.resolve run-dir "run.log") (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND]))
        wlines! (fn [out ^String l] (.write out (.getBytes (str l "\n") "UTF-8")) (.flush out))
        _ (persist! false nil)
        _ (when emit! (emit! {:event "ci.run" :run (str "local-" n) :number n :status "in_progress" :workflow wf-name :on event}))
        result (atom nil)
        killer (atom nil)
        end :deliberator.steps.logs/end
        target (str "ci-local-" (str/replace (str (.getName (io/file dir)) "-" (.getName (io/file wf-path)) "-" (or job* "all")) #"[^A-Za-z0-9._-]+" "-"))
        start! (fn [^LinkedBlockingQueue q]
                 (let [say! (fn [l] (wlines! runlog l) (.put q l))
                       on-line! (fn [l]
                                  (wlines! jsonl l)                                    ; raw first
                                  (if-let [rec (lr/read-jsonl-line l)]
                                    (do (swap! state lr/apply-record w rec)
                                        (when-let [c (console-line rec)] (say! c))
                                        (when (or (:stepResult rec) (:jobResult rec) (str/starts-with? (str (:msg rec)) "⭐ Run ")
                                                  (> (- (System/currentTimeMillis) @last-persist) 1500))
                                          (reset! last-persist (System/currentTimeMillis))
                                          (persist! false nil)
                                          (when (and emit! (:step rec) (or (:stepResult rec) (str/starts-with? (str (:msg rec)) "⭐ Run ")))
                                            (emit! {:event "ci.step" :run (str "local-" n) :job (:jobID rec) :name (:step rec)
                                                    :status (if (:stepResult rec) "completed" "in_progress") :conclusion (:stepResult rec)}))))
                                    (when-not (str/blank? l) (say! l))))]
                   (say! (str "[deliberator] local run " n " of " wf-name " (" event ") on " (:branch facts) " " (subs (str (:sha facts)) 0 (min 7 (count (str (:sha facts)))))
                              ": " (count jobs) " job(s): " (str/join ", " (map :id jobs))))
                   (say! (str "[deliberator] DOCKER_HOST=" docker-host " in " dir "; the job sees AWS_ENDPOINT_URL " (get-in e [:aws :endpoint])
                              ", region " (get-in e [:aws :region]) ", fake keys; nothing else of AWS, no GitHub token"))
                   (let [th (Thread.
                             ^Runnable
                             (bound-fn []
                               (try
                                 (reset! result (process/run! argv {:dir dir :env (act-env docker-host) :budget-ms (or budget-ms default-budget-ms)
                                                                    :on-cancel! (fn [k] (reset! killer k)) :line! on-line!
                                                                    :what (str "act " event (when job* (str " -j " job*)))}))
                                 (catch Throwable t (.put q t))
                                 (finally (.put q end))))
                             "deliberator-ci-local")]
                     (.setDaemon th true)
                     (.start th)
                     (fn [] (when-let [k @killer] (k)) (.interrupt th)))))]
    (try
      (let [r (logfetch/follow-run! ws {:target target :session session :emit! emit! :cancelled? (or cancelled? (constantly false))
                                        :start! start! :src-kind :build :env-key (keyword (:name e))
                                        :op-label (str "ci.local " (.getName (io/file wf-path)) (when job* (str " -j " job*)))
                                        :kind "ci" :now (store/now) :sub "ci"
                                        :hint (str "act could not run (is it on the daemon's PATH? mise installs it); " docker-host)})
            exit (:exit @result)
            cancelled? (nil? exit)
            st (swap! state lr/finish-state (assoc w :jobs jobs) cancelled?)
            jobs-conclusion (lr/run-conclusion (:jobs st))
            conclusion (cond cancelled? "cancelled" (and (zero? exit) (= "success" jobs-conclusion)) "success" :else "failure")
            report (when (= "success" conclusion) (deploy-report ws revisions-before))]
        (doseq [l report] (wlines! runlog l))
        (persist! true conclusion)
        (when emit! (emit! {:event "ci.run" :run (str "local-" n) :number n :status "completed" :conclusion conclusion :workflow wf-name :on event}))
        (assoc r :exit exit :conclusion conclusion :run (str "local-" n) :number n :repo key :dir dir :workflow wf-path :job job* :event event :image image
               :docker-host docker-host :endpoint (get-in e [:aws :endpoint]) :record (str run-dir) :summary (vec report)
               :jobs (mapv #(select-keys % [:id :name :status :conclusion]) (:jobs st))))
      (finally
        (.close jsonl)
        (.close runlog)
        (doseq [[id _] (act-containers pc (str (store/ws-name ws) "/" key "/" n))]
          (try (podman/remove! pc id :force true) (catch Exception _ nil)))))))
