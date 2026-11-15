(ns deliberator.ci-local-test
  "M5.4 `ci.local` without the estate: the pure pieces (directory, workflow and environment resolution, the act command line) and
  REAL `act` runs on the sandbox's rootless Podman of a fixture repository whose jobs only print (image debian:stable-slim, which the sandbox
  has): a green job, a red one (a result, not an error), the injected variables, the `logs.line` events against the session file, the
  raw blob, and a cancelled run that leaves no `act-*` container. The estate's own pipeline is `ci-local-estate-test`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.cilocal :as cl]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- git [dir & args]
  (let [r (apply sh/sh "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c" "commit.gpgsign=false" (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {:r r})))
    (str/trim (:out r))))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private plain-image "docker.io/library/debian:stable-slim")

(def ^:private workflow "name: fixture
on:
  workflow_dispatch:
    inputs:
      code: {description: exit code of the flaky job, default: \"0\"}
jobs:
  flaky:
    runs-on: ubuntu-latest
    steps:
      - name: Work
        run: echo working; exit ${{ inputs.code }}

  say:
    runs-on: ubuntu-latest
    steps:
      - run: echo \"endpoint=$AWS_ENDPOINT_URL region=$AWS_REGION key=$AWS_ACCESS_KEY_ID\"
      - run: echo \"pager=[$AWS_PAGER] a-real-secret=[${AWS_SESSION_TOKEN:-unset}] home-profile=[${AWS_PROFILE:-unset}]\"
  boom:
    needs: say
    runs-on: ubuntu-latest
    steps:
      - run: echo about to fail
      - run: exit 3
  slow:
    runs-on: ubuntu-latest
    steps:
      - run: echo sleeping now; sleep 300
")

(defn- repo-dir
  "A git repository with a commit and the fixture workflow under `<root>/proj`."
  [root]
  (let [d (io/file root "proj")]
    (.mkdirs (io/file d ".github" "workflows"))
    (spit (io/file d ".github" "workflows" "fixture.yml") workflow)
    (git d "init" "-q" "-b" "main") (git d "add" ".") (git d "commit" "-q" "-m" "fixture")
    (str d)))

(defn- podman-up? []
  (try (podman/available? (podman/client (podman/default-endpoint))) (catch Exception _ false)))

(defn- act-containers
  "The job containers of local runs (they carry the run label); somebody else's act on the same Podman is not ours."
  []
  (let [c (podman/client (podman/default-endpoint))]
    (vec (for [x (podman/list-containers c :all true) :let [n (first (:Names x)) ls (:Labels x)]
               :when (and n (or (get ls cl/run-label) (get ls (keyword cl/run-label))))] n))))

(defn- ctx [events cancelled]
  {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :cancelled? #(boolean @cancelled) :daemon {:follows (atom 0)}})

(deftest resolution-and-the-command-line
  (tu/with-temp-home
    (let [dir (repo-dir (tu/temp-home))]
      (testing "an absolute directory, a recorded checkout by name, a bare clone needs a worktree"
        (is (= dir (cl/resolve-dir "w" dir nil)))
        (ops/call! "repo.attach" {} {:ws "w" :path dir})
        (is (= dir (cl/resolve-dir "w" "proj" nil)))
        (git dir "remote" "add" "github" "https://github.com/acme/proj.git")
        (is (= dir (cl/resolve-dir "w" "acme/proj" nil)) "a tree row that is a GitHub repository finds the attached checkout of that remote")
        (is (= "acme/proj" (deliberator.cilocalrun/repo-key-of-dir "w" "proj" dir)) "local runs of this checkout sit with the GitHub ones")
        (is (= :ci/no-dir (:type (thrown #(cl/resolve-dir "w" "/nowhere/at/all" nil)))))
        (is (= :repo/not-found (:type (thrown #(cl/resolve-dir "w" "nope" nil)))))
        (let [origin (io/file (tu/temp-home) "o.git")]
          (.mkdirs origin) (git origin "init" "-q" "--bare" "-b" "main" ".")
          (git dir "push" (str origin) "main")
          (ops/call! "repo.clone" {} {:ws "w" :url (str origin) :name "bare"})
          (is (= :ci/bare-repo (:type (thrown #(cl/resolve-dir "w" "bare" nil)))))
          (ops/call! "worktree.start" {} {:ws "w" :repo "bare" :branch "feat"})
          (is (str/ends-with? (cl/resolve-dir "w" "bare" "feat") "/worktrees/bare/feat"))
          (is (= :ci/no-dir (:type (thrown #(cl/resolve-dir "w" "bare" "zzz")))))))
      (testing "the workflow: a path, a name under .github/workflows, else the known ones"
        (let [wf (str dir "/.github/workflows/fixture.yml")]
          (is (= wf (cl/resolve-workflow dir ".github/workflows/fixture.yml")))
          (is (= wf (cl/resolve-workflow dir "fixture.yml")))
          (is (= wf (cl/resolve-workflow dir "fixture")))
          (is (= wf (cl/resolve-workflow dir wf)))
          (let [e (thrown #(cl/resolve-workflow dir "deploy"))]
            (is (= :ci/no-workflow (:type e)))
            (is (= ["fixture.yml"] (:known e))))))
      (testing "act's command line"
        (let [argv (cl/act-argv {:event "workflow_dispatch" :workflow "/w.yml" :job "say" :image "img:1" :pull? false
                                 :env {"B" "2" "A" "1"} :inputs {"tag" "t1"} :secrets {"S" "local-S"} :vars {"V" "local-V"} :eventpath "/e.json"})]
          (is (= ["act" "workflow_dispatch" "-W" "/w.yml" "-j" "say" "-P" "ubuntu-latest=img:1" "-P" "ubuntu-22.04=img:1" "--rm" "--json" "--pull=false"
                  "--eventpath" "/e.json" "--env" "A=1" "--env" "B=2" "--input" "tag=t1" "--secret" "S=local-S" "--var" "V=local-V"] argv))
          (is (not (some #{"--pull=false"} (cl/act-argv {:event "e" :workflow "w" :job "j" :image "i" :pull? true :env {}}))))
          (is (not (some #{"-j"} (cl/act-argv {:event "e" :workflow "w" :image "i" :pull? true :env {}}))) "no job: the whole workflow")))
      (testing "act never sees the daemon's tokens or AWS variables"
        (let [env (cl/act-env "unix:///s")]
          (is (= "unix:///s" (get env "DOCKER_HOST")))
          (is (not-any? #(re-matches #"(?i)GITHUB_.*|GH_.*|AWS_.*|.*TOKEN.*" %) (keys env)))))
      (testing "event payloads come from the checkout"
        (let [facts {:branch "feat" :sha (apply str (repeat 40 "a")) :subject "s" :message "s\n" :author "me" :repo "o/r" :name "r" :default-branch "main"}
              push (cl/event-payload "push" facts nil) pr (cl/event-payload "pull_request" facts nil) wd (cl/event-payload "workflow_dispatch" facts {"tag" "x"})]
          (is (= "refs/heads/feat" (:ref push)))
          (is (= (:sha facts) (:after push) (get-in push [:head_commit :id])))
          (is (= ["feat" "main"] [(get-in pr [:pull_request :head :ref]) (get-in pr [:pull_request :base :ref])]))
          (is (= {"tag" "x"} (:inputs wd)))))
      (testing "the local environment gives the endpoint, fake keys and region, and nothing else"
        (let [e {:kind :local :aws {:endpoint "http://localhost:4566" :region "eu-central-1"
                                    :credentials {:access-key-id "test" :secret-access-key "test"}}}]
          (is (= {"AWS_ENDPOINT_URL" "http://localhost:4566" "AWS_DEFAULT_REGION" "eu-central-1" "AWS_REGION" "eu-central-1"
                  "AWS_ACCESS_KEY_ID" "test" "AWS_SECRET_ACCESS_KEY" "test" "AWS_EC2_METADATA_DISABLED" "true" "AWS_PAGER" ""}
                 (cl/injected-env e)))
          (is (= "test" (get (cl/injected-env (assoc e :aws {:endpoint "x" :region "r"})) "AWS_ACCESS_KEY_ID")) "no credentials in the record: the fake ones")))
      (testing "refusals before anything runs"
        (is (= :ci/no-job (:type (thrown #(cl/run! "w" {:repo dir :workflow "fixture" :job "nope"})))))
        (is (= :ci/event-not-listened (:type (thrown #(cl/run! "w" {:repo dir :workflow "fixture" :event "push"})))))
        (is (= :ci/unknown-input (:type (thrown #(cl/run! "w" {:repo dir :workflow "fixture" :inputs {"zz" "1"}})))))
        (let [plain (io/file (tu/temp-home) "plain")]
          (.mkdirs (io/file plain ".github" "workflows")) (spit (io/file plain ".github" "workflows" "x.yml") workflow)
          (git plain "init" "-q" "-b" "main")      ; a repository with no commit: act cannot read its ref
          (is (= :ci/not-a-git-repo (:type (thrown #(cl/run! "w" {:repo (str plain) :workflow "x" :job "say"}))))))
        (env/save! "w" {:name "dev" :kind :remote :aws {:profile "dlbtest-dev" :region "eu-central-1" :account "210987654321"}
                        :targets {:service/a "https://a.example.test"}})
        (is (= :ci/not-local (:type (thrown #(cl/run! "w" {:repo dir :workflow "fixture" :job "say" :env "dev"})))))))))

(deftest a-green-and-a-red-job-on-the-real-act
  (if-not (podman-up?)
    (fu/skip! "ci-local" "Podman's socket does not answer")
    (tu/with-temp-home
      (let [dir (repo-dir (tu/temp-home))
            events (atom []) none (atom false)
            before (set (act-containers))
            run (fn [job] (ops/call! "ci.local" (ctx events none) {:ws "w" :repo dir :workflow "fixture" :job job :image plain-image :session "S"}))
            run-flaky (fn [code] (ops/call! "ci.local" (ctx events none) {:ws "w" :repo dir :workflow "fixture" :job "flaky" :input [(str "code=" code)] :image plain-image :session "S"}))
            green (run "say")]
        (testing "green: a result with the exit and the file, lines streamed as logs.line"
          (is (= "success" (:conclusion green)) (pr-str (dissoc green :argv)))
          (is (zero? (:exit green)))
          (is (.isFile (io/file (:path green))))
          (is (str/includes? (:path green) "/sessions/S/ci/ci-local-proj-fixture.yml-say-"))
          (is (= "local-1" (:run green)))
          (let [text (slurp (:path green))
                lines (filter #(= "logs.line" (:event %)) @events)]
            (is (= (count (str/split-lines text)) (count lines)) "every line of the file was an event")
            (is (str/includes? text "[deliberator] local run 1 of fixture (workflow_dispatch)") "provenance first")
            (is (not (str/includes? text "AWS_SECRET_ACCESS_KEY")) "the keys are in the environment, not in the printed command line")
            (is (str/includes? text "endpoint=http://localhost:19 region=eu-central-1 key=test") "the local environment's endpoint, region and fake key reached the job")
            (is (str/includes? text "pager=[] a-real-secret=[unset] home-profile=[unset]") "nothing else of AWS"))
          (testing "it is a run: recorded at workspace level with act's own structure, listed, opened"
            (let [jobs (ops/call! "ci.jobs" {} {:ws "w" :session "S" :repo "proj" :run "local-1"})]
              (is (true? (:final jobs)))
              (is (= "local" (:origin jobs)))
              (is (str/starts-with? (:dir jobs) (str (store/path "w" "ci" "local"))))
              (is (= ["say"] (map :name (:jobs jobs))))
              (is (= ["Set up job" "Run echo \"endpoint=$AWS_ENDPOINT_URL region=$AWS_REGION key=$AWS_ACCESS_KEY_ID\"" "Run echo \"pager=[$AWS_PAGER] a-real-secret=[${AWS_SESSION_TOKEN:-unset}] home-profile=[${AWS_PROFILE:-unset}]\"" "Complete job"]
                     (mapv :name (:steps (first (:jobs jobs))))))
              (is (every? #(= "success" (:conclusion %)) (:steps (first (:jobs jobs)))))
              (is (nil? (:url (:run-row jobs)))))
            (let [step (ops/call! "ci.log" {} {:ws "w" :session "S" :repo "proj" :run "local-1" :job "say" :step "2"})]
              (is (str/includes? (slurp (:path step)) "endpoint=http://localhost:19"))
              (is (not (str/includes? (slurp (:path step)) "pager=")) "only that step's lines"))
            (let [runs (ops/call! "ci.runs" {} {:ws "w" :session "S" :repo "proj"})]
              (is (= ["local-1"] (map :id (:rows runs))))
              (is (= "local" (:origin (first (:rows runs)))))
              (is (= :local (:source runs))) (is (str/includes? (slurp (:path runs)) "local-1")))
            (is (.isFile (io/file (:record green) "act.jsonl")) "act's raw JSON is kept"))
          (is (.isFile (io/file (str (store/raw-path "w" (:sha green))))) "the raw blob was adopted"))
        (testing "red: a failing job is a result, with its output; needs ran first"
          (reset! events [])
          (let [red (run "boom")
                text (slurp (:path red))]
            (is (= "failure" (:conclusion red)))
            (is (= 1 (:exit red)))
            (is (= "local-2" (:run red)) "the next run, the next number")
            (is (str/includes? text "endpoint=http://localhost:19") "the job it needs ran first")
            (is (str/includes? text "about to fail"))
            (let [jobs (ops/call! "ci.jobs" {} {:ws "w" :session "S" :repo "proj" :run "local-2"})]
              (is (= [["say" "success"] ["boom" "failure"]] (mapv (juxt :name :conclusion) (:jobs jobs))))
              (is (some #(and (= "failure" (:conclusion %)) (str/includes? (:name %) "exit 3")) (:steps (second (:jobs jobs))))))
            (testing "a red local run is compared with the last green LOCAL run, step by step"
              (is (= :ci/no-green-run (:type (thrown #(ops/call! "ci.diff" {} {:ws "w" :session "S" :repo "proj" :run "local-2" :job "boom" :step "3"})))))
              (let [g (run-flaky "0") r (run-flaky "1")
                    d (ops/call! "ci.diff" {} {:ws "w" :session "S" :repo "proj" :run (:run r) :job "flaky" :step "Work"})]
                (is (= "success" (:conclusion g)))
                (is (= "failure" (:conclusion r)))
                (is (= (:run g) (:run (:green d))) "the last green local run")
                (is (re-find #"(?m)^\+##\[error\]exitcode '1'" (slurp (:path d))))
                (is (= (:run g) (:run (:green (ops/call! "ci.diff" {} {:ws "w" :session "S" :repo "proj" :run (:run r) :job "flaky" :step "Work" :against (:run g)})))))))))
        (is (= before (set (act-containers))) "act removed its containers")))))

(deftest cancelling-stops-act-and-leaves-no-container
  (if-not (podman-up?)
    (fu/skip! "ci-local" "Podman's socket does not answer")
    (tu/with-temp-home
      (let [dir (repo-dir (tu/temp-home))
            events (atom []) cancelled (atom false)
            before (set (act-containers))
            f (future (ops/call! "ci.local" (ctx events cancelled) {:ws "w" :repo dir :workflow "fixture" :job "slow" :image plain-image :session "S"}))]
        (let [deadline (+ (System/currentTimeMillis) 90000)]
          (while (and (< (System/currentTimeMillis) deadline)
                      (not (some #(str/includes? (str (:text %)) "sleeping now") @events)))
            (Thread/sleep 300)))
        (is (some #(str/includes? (str (:text %)) "sleeping now") @events) "the job started")
        (reset! cancelled true)
        (let [r (deref f 60000 ::hung)]
          (is (not= ::hung r) "the request returned after the cancel")
          (is (= "cancelled" (:conclusion r)) (pr-str (select-keys r [:conclusion :exit]))))
        (is (= before (set (act-containers))) "no act container is left behind")))))

(deftest workflows-are-read-with-a-yaml-parser-and-agree-with-act
  (tu/with-temp-home
    (let [dir (repo-dir (tu/temp-home))
          ws (first (cl/workflows dir))]
      (is (= "fixture" (:name ws)))
      (is (= ".github/workflows/fixture.yml" (:file ws)))
      (is (= ["workflow_dispatch"] (:events ws)))
      (is (= [{:name "code" :type nil :default "0" :description "exit code of the flaky job" :options nil :required false}] (:inputs ws)))
      (is (= #{["flaky" 0 []] ["say" 0 []] ["slow" 0 []] ["boom" 1 ["say"]]} (set (map (juxt :id :stage :needs) (:jobs ws)))))
      (is (= "boom" (:id (last (:jobs ws)))) "needs order: a job after the one it needs")
      (testing "act -l is only a cross-check: the same jobs, the same stages"
        (let [r (sh/sh "act" "-l" "-W" (:path ws) :dir dir)]
          (if-not (zero? (:exit r))
            (fu/skip! "ci-local" "act -l is not runnable here")
            (let [rows (for [l (rest (str/split-lines (:out r))) :let [[stage id] (str/split (str/trim l) #"\s+")] :when (re-matches #"\d+" (str stage))] [id (parse-long stage)])]
              (is (= (set rows) (set (map (juxt :id :stage) (:jobs ws))))))))))))
