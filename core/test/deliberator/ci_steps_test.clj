(ns deliberator.ci-steps-test
  "Piece 01: `ci.jobs`, `ci.log` with a step, `ci.diff` with a step and `ci.archive` against a local stand-in for the
  GitHub API that serves REAL job logs and steps (test/fixtures/ci): what is kept, what a second open fetches (nothing), what a run in
  progress does (never kept), a step cut by time."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.github-fake :as fake]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.nio.file Files)))

(defn- fx [n ext] (slurp (io/file "test/fixtures/ci" (str n ext)) :encoding "UTF-8"))

(defn- job-json [id fixture status conclusion & {:keys [steps]}]
  (let [j (json/read-str (fx fixture ".job.json"))]
    {"id" id "name" "verify" "status" status "conclusion" conclusion "run_id" 1 "run_attempt" 1
     "started_at" "2026-11-05T12:15:51Z" "completed_at" (when (= "completed" status) "2026-11-05T12:16:18Z")
     "html_url" (str "https://github.com/acme/widgets/actions/runs/1/job/" id) "runner_name" "GitHub Actions 1"
     "steps" (or steps (get j "steps"))}))

(def runs
  {300 {:branch "feat" :conclusion "failure" :created "2026-11-05T12:15:48Z" :job 3001 :fixture "billing-red"}
   295 {:branch "main" :conclusion "success" :created "2026-11-04T12:15:48Z" :job 2951 :fixture "billing-green"}})

(defn- run-json [id status conclusion]
  (let [r (runs id)]
    {"id" id "run_number" (- id 280) "run_attempt" 1 "name" "build" "workflow_id" 7 "display_title" (str "title " id)
     "head_branch" (or (:branch r) "x") "head_sha" (apply str (repeat 40 "b")) "event" "push" "status" status "conclusion" conclusion
     "created_at" (or (:created r) "2026-11-05T13:00:00Z") "run_started_at" "2026-11-05T13:00:01Z" "updated_at" "2026-11-05T13:01:00Z"
     "html_url" (str "https://github.com/acme/widgets/actions/runs/" id)}))

(defn- composite
  "The red log with a group marker a composite action would print: the markers no longer match the steps."
  []
  (let [ls (str/split-lines (fx "billing-red" ".log"))]
    (str (str/join "\n" (concat (take 150 ls) ["2026-11-05T12:15:57.0000000Z ##[group]Run inner step of a composite"] (drop 150 ls))) "\n")))

(defn handler [srv state]
  (fn [{:keys [path query]}]
    (let [json* (fn [x] {:status 200 :headers {"Content-Type" "application/json"} :body (json/write-str x)})
          m (re-matches #"/repos/acme/widgets/actions/(.*)" path)
          r (some-> m second)]
      (cond
        (nil? r) (if-let [[_ id] (re-matches #"/blob/(\d+)" path)]
                   {:status 200 :headers {"Content-Type" "text/plain"}
                    :body (case (parse-long id) 3001 (fx "billing-red" ".log") 2951 (fx "billing-green" ".log") 4001 (composite) "")}
                   {:status 404 :body "{}"})
        (= "runs" r) (json* {"total_count" 2 "workflow_runs" (mapv #(run-json % "completed" (:conclusion (runs %))) [300 295])})
        (re-matches #"workflows/7/runs" r) (json* {"total_count" 1 "workflow_runs" [(run-json 295 "completed" "success")]})
        (= "runs/300" r) (json* (run-json 300 "completed" "failure"))
        (= "runs/295" r) (json* (run-json 295 "completed" "success"))
        (= "runs/400" r) (json* (run-json 400 "completed" "failure"))
        (= "runs/500" r) (json* (run-json 500 (if (:done @state) "completed" "in_progress") (when (:done @state) "success")))
        (= "runs/300/jobs" r) (json* {"jobs" [(job-json 3001 "billing-red" "completed" "failure")]})
        (= "runs/295/jobs" r) (json* {"jobs" [(job-json 2951 "billing-green" "completed" "success")]})
        (= "runs/400/jobs" r) (json* {"jobs" [(job-json 4001 "billing-red" "completed" "failure")]})
        (= "runs/500/jobs" r)
        (json* {"jobs" [(if (:done @state)
                          (job-json 5001 "billing-green" "completed" "success")
                          (job-json 5001 "billing-green" "in_progress" nil
                                    :steps [{"number" 1 "name" "Set up job" "status" "completed" "conclusion" "success" "started_at" "2026-11-05T12:15:52Z" "completed_at" "2026-11-05T12:15:53Z"}
                                            {"number" 2 "name" "Build" "status" "in_progress" "conclusion" nil "started_at" "2026-11-05T12:15:53Z" "completed_at" nil}]))]})
        (re-matches #"jobs/(\d+)/logs" r)
        (let [id (second (re-matches #"jobs/(\d+)/logs" r))]
          {:status 302 :headers {"Location" (str (fake/blob-base @srv) "/blob/" id "?sig=SECRET")} :body ""})
        :else {:status 404 :body "{\"message\":\"Not Found\"}"}))))

(defmacro with-github [[srv state] & body]
  `(tu/with-temp-home
     (let [state# (atom {}) srv# (atom nil)]
       (reset! srv# (fake/start! (handler srv# state#)))
       (try
         (store/write-edn! (store/path "w" "github.edn") {:token-source :none :api (fake/base @srv#)})
         (session/start! "w" "S1")
         (let [~srv @srv# ~state state#] ~@body)
         (finally (fake/stop! @srv#))))))

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))
(defn- n-requests [srv] (count @(:requests srv)))
(defn- files-under [p] (->> (file-seq (.toFile p)) (filter #(.isFile ^java.io.File %)) (map #(str (.relativize p (.toPath ^java.io.File %))))))

(deftest jobs-of-a-finished-run-are-kept-at-workspace-level-and-opened-again-without-the-network
  (with-github [srv state]
    (let [r1 (call "ci.jobs" {:repo "acme/widgets" :run "300"})
          job (first (:jobs r1))
          after-first (n-requests srv)]
      (is (= :fetched (:source r1)))
      (is (true? (:final r1)))
      (is (= "verify" (:name job)))
      (is (= ["Set up job" "Run actions/checkout@v4" "Run actions/setup-java@v4" "Run mvn -B verify" "Post Run actions/setup-java@v4" "Post Run actions/checkout@v4" "Complete job"]
             (mapv :name (:steps job))))
      (is (= [1 2 3 4 7 8 9] (mapv :number (:steps job))))
      (is (= 20 (:duration-s (nth (:steps job) 3))))
      (is (= "failure" (:conclusion (nth (:steps job) 3))))
      (is (= 27 (:duration-s job)))
      (is (= "push" (:event (:run-row r1))))
      (is (= 2 after-first) "one GET of the run, one of its jobs")
      (testing "kept under the workspace, not under a session"
        (is (str/starts-with? (:dir r1) (str (store/path "w" "ci" "github" "acme-widgets"))))
        (is (= ["jobs.json" "jobs.txt" "run.edn"] (sort (map #(last (str/split % #"/")) (files-under (store/path "w" "ci"))))) "jobs only: no log was asked for")
        (is (not-any? #(str/includes? % "run.edn") (files-under (store/path "w" "sessions")))))
      (testing "the text view: one line per job and per step"
        (let [t (slurp (:path r1))]
          (is (re-find #"(?m)^job 3001 verify failure 27s$" t))
          (is (re-find #"(?m)^  step 4 Run mvn -B verify failure 20s$" t))))
      (testing "opened again: the kept copy, zero requests, with its age; and with no token and a closed port"
        (store/write-edn! (store/path "w" "github.edn") {:token-source :none :api "http://127.0.0.1:1"})
        (let [r2 (call "ci.jobs" {:repo "acme/widgets" :run "300"})]
          (is (= :kept (:source r2)))
          (is (some? (:kept-at r2)))
          (is (number? (:age-s r2)))
          (is (= (:jobs r1) (:jobs r2)) "the same jobs")
          (is (= after-first (n-requests srv))))))))

(deftest a-step-log-is-that-steps-lines-kept-and-served-without-the-network
  (with-github [srv state]
    (let [r (call "ci.log" {:repo "acme/widgets" :run "300" :step "4"})
          txt (slurp (:path r))]
      (is (= "Run mvn -B verify" (:name (:step r))))
      (is (true? (:exact (:step r))))
      (is (str/includes? txt "expected: <26> but was: <25>"))
      (is (str/includes? txt "PricingTest.java:48"))
      (is (not (str/includes? txt "Post job cleanup")))
      (is (not (str/includes? txt "Run actions/setup-java")))
      (is (= (:lines (:step r)) (count (str/split-lines txt))))
      (is (str/includes? (:path r) "/sessions/") "the view is the session's")
      (is (str/starts-with? (:kept r) (str (store/path "w" "ci"))) "the copy that stays is the workspace's")
      (let [before (n-requests srv)
            _ (store/write-edn! (store/path "w" "github.edn") {:token-source :none :api "http://127.0.0.1:1"})
            r2 (call "ci.log" {:repo "acme/widgets" :run "300" :step "Run mvn -B verify"})
            r3 (call "ci.log" {:repo "acme/widgets" :run "300"})
            j (call "ci.jobs" {:repo "acme/widgets" :run "300"})]
        (testing "the same step by name, the whole job log as it was, all from the kept copy"
          (is (= :kept (:source r2)))
          (is (= (slurp (:path r) ) (slurp (:path r2))))
          (is (> (:lines r3) (:lines r2)))
          (is (str/includes? (slurp (:path r3)) "Post job cleanup"))
          (is (= before (n-requests srv))))
        (testing "ci.jobs now says where each step's log is"
          (is (every? :log (:steps (first (:jobs j)))))
          (is (= (:kept r) (:log (nth (:steps (first (:jobs j))) 3)))))))
    (testing "a step that is not there is a typed error with the known steps"
      (let [e (try (call "ci.log" {:repo "acme/widgets" :run "300" :step "42"}) (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :ci/no-step (:type e)))
        (is (some #(str/includes? % "Run mvn") (:known e)))))))

(deftest a-step-cut-by-time-says-so-in-its-first-line
  (with-github [srv state]
    (let [r (call "ci.log" {:repo "acme/widgets" :run "400" :step "4"})
          txt (slurp (:path r))]
      (is (false? (:exact (:step r))))
      (is (= :time (:cut (:step r))))
      (is (str/starts-with? txt "# deliberator: this step's log was cut by time"))
      (is (str/includes? txt "expected: <26> but was: <25>"))
      (let [j (call "ci.jobs" {:repo "acme/widgets" :run "400"})]
        (is (false? (:exact (nth (:steps (first (:jobs j))) 3))))))))

(deftest a-run-in-progress-is-never-kept-and-moves-on
  (with-github [srv state]
    (let [r1 (call "ci.jobs" {:repo "acme/widgets" :run "500"})]
      (is (false? (:final r1)))
      (is (= "in_progress" (:status (:run-row r1))))
      (is (= ["completed" "in_progress"] (mapv :status (:steps (first (:jobs r1))))))
      (is (nil? (:duration-s (second (:steps (first (:jobs r1)))))))
      (is (= [] (files-under (store/path "w" "ci"))) "nothing kept")
      (is (str/includes? (slurp (:path r1)) "not final"))
      (let [again (call "ci.jobs" {:repo "acme/widgets" :run "500"})]
        (is (= :fetched (:source again)) "asks again: a run in progress is never answered from a copy")
        (is (= [] (files-under (store/path "w" "ci")))))
      (swap! state assoc :done true)
      (let [r3 (call "ci.jobs" {:repo "acme/widgets" :run "500"})]
        (is (true? (:final r3)))
        (is (= "success" (:conclusion (:run-row r3))))
        (is (some? (:kept-at r3)))
        (is (seq (files-under (store/path "w" "ci"))))))))

(deftest archive-fetches-only-what-is-missing
  (with-github [srv state]
    (call "ci.jobs" {:repo "acme/widgets" :run "300"})
    (let [before (n-requests srv)
          a (call "ci.archive" {:repo "acme/widgets" :limit 10})]
      (is (= 2 (:checked a)))
      (is (= 1 (:new-runs a)) "run 300 was kept already")
      (is (= 2 (:new-logs a)))
      (is (empty? (:failed a)))
      (is (str/includes? (slurp (:path a)) "295"))
      (let [mid (n-requests srv)
            a2 (call "ci.archive" {:repo "acme/widgets" :limit 10})]
        (is (= 1 (- (n-requests srv) mid)) "the listing, and nothing else")
        (is (= [0 0] [(:new-runs a2) (:new-logs a2)])))
      (testing "a kept step log opens with no request"
        (let [mid (n-requests srv)
              r (call "ci.log" {:repo "acme/widgets" :run "295" :job "verify" :step "4"})]
          (is (= :kept (:source r)))
          (is (= mid (n-requests srv))))))))

(deftest diff-of-a-step-against-the-last-green-run
  (with-github [srv state]
    (let [d (call "ci.diff" {:repo "acme/widgets" :run "300" :step "Run mvn -B verify"})
          txt (slurp (:path d))]
      (is (= 4 (:number (:step d))))
      (is (false? (:equal d)))
      (is (str/includes? (:path d) "-step4.diff"))
      (is (re-find #"(?m)^\+.*expected: <26> but was: <25>" txt) "the failing assertion is in the red run only")
      (is (not (str/includes? txt "Post job cleanup")) "only that step")
      (testing "both runs' logs are kept now"
        (is (= 2 (count (filter #(str/ends-with? % "/run.edn") (files-under (store/path "w" "ci"))))))))))

(deftest the-daemon-says-which-files-name-code
  (with-github [srv state]
    (let [r (call "ci.log" {:repo "acme/widgets" :run "300" :step "4"})
          d (call "ci.diff" {:repo "acme/widgets" :run "300" :step "4"})
          k (fn [p] (call "logs.linkable" {:path p}))]
      (is (= :ci (:kind (k (:path r)))) "a session's CI log")
      (is (= :ci (:kind (k (:path d)))) "a session's CI diff")
      (is (= :ci (:kind (k (:kept r)))) "a kept log")
      (is (false? (:linkable (k "/etc/hostname"))))
      (is (false? (:linkable (k (str (store/path "w" "sessions" "S1" "notes.txt")))))))))
