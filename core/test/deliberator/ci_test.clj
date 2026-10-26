(ns deliberator.ci-test
  "M5.2 and M5.3 against a local stand-in for the GitHub API (`github-fake`): the cases a public repository cannot stage on
  demand (CRLF and BOM logs, a job that is running and then completes, a missing blob, a rate limit, a green run on another
  branch). The real GitHub is in `ci-github-test`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ci :as ci]
            [deliberator.github-fake :as fake]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- qmap [q] (into {} (for [kv (str/split (or q "") #"&") :when (seq kv) :let [[k v] (str/split kv #"=" 2)]] [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(def runs
  [{:id 200 :number 20 :branch "feat" :conclusion "failure" :created "2026-10-10T10:00:00Z" :title "wip"}
   {:id 195 :number 19 :branch "feat" :conclusion "success" :created "2026-10-10T09:00:00Z" :title "wip 2"}
   {:id 190 :number 18 :branch "main" :conclusion "success" :created "2026-10-09T09:00:00Z" :title "release"}])

(def jobs
  {200 [{:id 2001 :name "test (ubuntu)" :conclusion "failure"} {:id 2002 :name "lint" :conclusion "success"}]
   195 [{:id 1951 :name "test (ubuntu)" :conclusion "success"} {:id 1952 :name "lint" :conclusion "success"}]
   190 [{:id 1901 :name "test (ubuntu)" :conclusion "success"}]})

(defn- ts [h m s ms] (format "2026-10-10T%02d:%02d:%02d.%07dZ" h m s ms))

(defn- log-lines [{:keys [h dur fail?]}]
  (concat
   [(str (ts h 0 1 4793500) " Current runner version: '2.337.0'")
    (str (ts h 0 1 4809906) " ##[group]Runner Image Provisioner")
    (str (ts h 0 1 4810502) " Version: 2026100" h ".596")
    (str (ts h 0 2 1) " Runner name: 'GitHub Actions 10000" h "'")
    (str (ts h 0 3 2) " ##[group]Run make test")
    (str (ts h 0 4 3) " Downloaded 5." h " MBs/sec in " dur "s")
    (str (ts h 0 5 4) " temp file ~/work/_temp/8ac63fb9-1234-4bcd-9abc-0123456789" (format "%02d" h) ".sh")
    (str (ts h 0 6 5) " running 12 tests")]
   (if fail?
     [(str (ts h 0 7 6) " FAIL in (match-test) (fs_test.clj:98)")
      (str (ts h 0 7 7) " ##[error]Process completed with exit code 1.")]
     [(str (ts h 0 7 6) " 12 tests, 0 failures")])
   [(str (ts h 0 9 8) " Cleaning up orphan processes")]))

(defn- crlf-bom [lines] (str "﻿" (str/join "\r\n" lines) "\r\n"))
(defn- lf [lines] (str (str/join "\n" lines) "\n"))

(def logs
  {2001 (crlf-bom (log-lines {:h 10 :dur "12.5" :fail? true}))
   1951 (lf (log-lines {:h 9 :dur "11.9"}))
   1901 (lf (log-lines {:h 8 :dur "13.1"}))})

(defn- job-json [j status]
  {"id" (:id j) "name" (:name j) "status" status "conclusion" (when (= "completed" status) (:conclusion j))
   "started_at" "2026-10-10T10:00:00Z" "completed_at" (when (= "completed" status) "2026-10-10T10:09:00Z") "run_id" 1 "run_attempt" 1
   "steps" [{"number" 1 "name" "Set up job" "status" "completed" "conclusion" "success"}
            {"number" 2 "name" "make test" "status" status "conclusion" (when (= "completed" status) (:conclusion j))}]})

(defn- run-json [r]
  {"id" (:id r) "run_number" (:number r) "run_attempt" 1 "name" "ci" "workflow_id" 7 "display_title" (:title r)
   "head_branch" (:branch r) "head_sha" (apply str (repeat 40 "a")) "event" "push" "status" "completed" "conclusion" (:conclusion r)
   "created_at" (:created r) "html_url" (str "https://github.com/acme/widgets/actions/runs/" (:id r))})

(defn handler
  "The stand-in. `state` is an atom: `:token-needed?`, `:blob-missing` (job ids whose blob is 404), `:running` (job id ->
  polls left before it completes), `:rate-limited?`."
  [srv state]
  (fn [{:keys [path query headers]}]
    (let [q (qmap query)
          json* (fn [x] {:status 200 :headers {"Content-Type" "application/json" "X-RateLimit-Remaining" "4990"} :body (json/write-str x)})
          m (re-matches #"/repos/acme/widgets/actions/(.*)" path)
          rest* (some-> m second)]
      (cond
        (:rate-limited? @state) {:status 403 :headers {"X-RateLimit-Remaining" "0" "X-RateLimit-Reset" "1790000000"} :body "{\"message\":\"API rate limit exceeded\"}"}
        (and (:token-needed? @state) (not (get headers "authorization")) (str/ends-with? path "/logs"))
        {:status 403 :body "{\"message\":\"Must have admin rights to Repository.\"}"}
        (nil? rest*) (if-let [[_ id] (re-matches #"/blob/(\d+)" path)]
                       (if (contains? (:blob-missing @state) (parse-long id))
                         {:status 404 :body "<Error><Code>BlobNotFound</Code></Error>"}
                         {:status 200 :headers {"Content-Type" "text/plain"} :body (get logs (parse-long id))})
                       {:status 404 :body "{\"message\":\"Not Found\"}"})
        (= "workflows" rest*) (json* {"workflows" [{"id" 7 "name" "ci" "path" ".github/workflows/ci.yml"}]})
        (re-matches #"workflows/[^/]+/runs" rest*)
        (let [rs (cond->> runs
                   (get q "status") (filter #(= (get q "status") (:conclusion %)))
                   (get q "branch") (filter #(= (get q "branch") (:branch %)))
                   (get q "created") (filter #(neg? (compare (:created %) (subs (get q "created") 1)))))]
          (json* {"total_count" (count rs) "workflow_runs" (mapv run-json rs)}))
        (= "runs" rest*) (json* {"total_count" (count runs) "workflow_runs" (mapv run-json runs)})
        (re-matches #"runs/\d+" rest*) (json* (run-json (first (filter #(= (str (:id %)) (last (str/split rest* #"/"))) runs))))
        (re-matches #"runs/\d+/jobs" rest*)
        (let [id (parse-long (second (str/split rest* #"/")))]
          (json* {"total_count" (count (jobs id)) "jobs" (mapv #(job-json % "completed") (jobs id))}))
        (re-matches #"jobs/\d+" rest*)
        (let [id (parse-long (second (str/split rest* #"/")))
              j (first (filter #(= id (:id %)) (mapcat val jobs)))
              left (get-in @state [:running id])]
          (if (and left (pos? left))
            (do (swap! state update-in [:running id] dec) (json* (job-json j "in_progress")))
            (json* (job-json j "completed"))))
        (re-matches #"jobs/\d+/logs" rest*)
        (let [id (parse-long (second (str/split rest* #"/")))
              left (get-in @state [:running id])]
          (if (and left (pos? left))
            {:status 302 :headers {"Location" (str (fake/blob-base @srv) "/blob/0?sig=SECRET")} :body ""}
            {:status 302 :headers {"Location" (str (fake/blob-base @srv) "/blob/" id "?sig=SECRET")} :body ""}))
        :else {:status 404 :body "{\"message\":\"Not Found\"}"}))))

(defmacro with-github
  "Runs body with a temp home, session S1, a stand-in server and the workspace's github.edn pointing at it (no token)."
  [[srv state] & body]
  `(tu/with-temp-home
     (let [state# (atom {})
           srv# (atom nil)]
       (reset! srv# (fake/start! (handler srv# state#)))
       (try
         (store/write-edn! (store/path "w" "github.edn") {:token-source :none :api (fake/base @srv#)})
         (session/start! "w" "S1")
         (let [~srv @srv# ~state state#] ~@body)
         (finally (fake/stop! @srv#))))))

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))

(deftest runs-as-text
  (with-github [srv st]
    (let [r (call "ci.runs" {:repo "acme/widgets" :workflow "ci" :status "failure"})]
      (is (= 1 (:count r)))
      (is (= "ci" (:workflow r)))
      (is (= 200 (:id (first (:rows r)))))
      (is (= "feat" (:branch (first (:rows r)))))
      (is (= "failure" (:conclusion (first (:rows r)))))
      (is (= "none" (:auth r)))
      (is (str/starts-with? (:path r) (str (store/path "w" "sessions" "S1" "ci" "runs-acme-widgets-ci-"))))
      (let [txt (slurp (:path r))]
        (is (str/includes? txt "# runs of acme/widgets, workflow ci, status failure: 1 of 1"))
        (is (re-find #"200\s+#20\s+failure\s+feat\s+aaaaaaa\s+2026-10-10T10:00:00Z\s+wip" txt)))
      (testing "the workflow was found by name through the workflows list; the filter reached the API"
        (let [qs (map (juxt :path :query) @(:requests srv))]
          (is (some #(= "/repos/acme/widgets/actions/workflows" (first %)) qs))
          (is (some #(and (= "/repos/acme/widgets/actions/workflows/7/runs" (first %)) (str/includes? (str (second %)) "status=failure")) qs))))
      (testing "the raw response is a blob"
        (is (str/includes? (String. ^bytes (store/raw-get "w" (:raw r)) "UTF-8") "\"workflow_runs\""))))
    (testing "an unknown workflow lists the known ones"
      (let [d (thrown #(call "ci.runs" {:repo "acme/widgets" :workflow "nope"}))]
        (is (= :ci/no-workflow (:type d)))
        (is (= ["ci"] (:known d)))))
    (testing "a recorded repository's name works as the repo"
      (is (= :ci/bad-repo (:type (thrown #(call "ci.runs" {:repo "not a repo"}))))))))

(deftest log-of-the-failed-job-as-text
  (with-github [srv st]
    (let [r (call "ci.log" {:repo "acme/widgets" :run "200"})]
      (is (= "test (ubuntu)" (get-in r [:job :name])) "no job given: the first failed one")
      (is (= "failure" (:conclusion r)))
      (is (= [] (:other-failed r)))
      (is (= 11 (:lines r)))
      (testing "the raw blob is the CRLF+BOM bytes as received; the file is the text view of it"
        (let [raw (String. ^bytes (store/raw-get "w" (:sha r)) "UTF-8")]
          (is (str/starts-with? raw "﻿"))
          (is (str/includes? raw "\r\n")))
        (let [txt (slurp (:path r))]
          (is (not (str/includes? txt "\r")))
          (is (not (str/starts-with? txt "﻿")))
          (is (str/includes? txt "##[error]Process completed with exit code 1."))))
      (is (str/ends-with? (:path r) "acme-widgets-200-test-ubuntu--2001.log")))
    (testing "a log that is already plain text is the raw blob itself (a hard link)"
      (let [r (call "ci.log" {:repo "acme/widgets" :run "195" :job "test"})]
        (is (java.nio.file.Files/isSameFile (.toPath (io/file (:path r))) (store/raw-path "w" (:sha r))))))
    (testing "a job by id, by a unique part of its name"
      (is (= 1952 (get-in (call "ci.log" {:repo "acme/widgets" :run "195" :job "1952"}) [:job :id])))
      (is (= 2002 (get-in (call "ci.log" {:repo "acme/widgets" :run "200" :job "lint"}) [:job :id]))))
    (testing "a run url carries the repository and the job"
      (let [r (call "ci.log" {:run "https://github.com/acme/widgets/actions/runs/200/job/2002"})]
        (is (= 2002 (get-in r [:job :id])))))
    (testing "a run without a failed job, an unknown job"
      (is (= :ci/no-failed-job (:type (thrown #(call "ci.log" {:repo "acme/widgets" :run "195"})))))
      (is (= :ci/no-job (:type (thrown #(call "ci.log" {:repo "acme/widgets" :run "200" :job "zzz"}))))))
    (testing "a bare run id needs a repository"
      (is (= :ci/bad-repo (:type (thrown #(call "ci.log" {:run "200"}))))))))

(deftest log-errors-say-what-to-do
  (with-github [srv st]
    (testing "no token and the endpoint wants one"
      (swap! st assoc :token-needed? true)
      (let [d (thrown #(call "ci.log" {:repo "acme/widgets" :run "200"}))]
        (is (= :ci/auth-required (:type d)))
        (is (str/includes? (:hint d) "GITHUB_TOKEN"))))
    (swap! st dissoc :token-needed?)
    (testing "a blob that is not there (the log of a job that has not completed)"
      (swap! st assoc :blob-missing #{2001})
      (is (= :ci/log-not-available (:type (thrown #(call "ci.log" {:repo "acme/widgets" :run "200"}))))))
    (testing "the rate limit"
      (swap! st assoc :rate-limited? true)
      (let [d (thrown #(call "ci.runs" {:repo "acme/widgets"}))]
        (is (= :ci/rate-limited (:type d)))
        (is (str/includes? (:hint d) "60 requests"))))))

(defn call-tail [events]
  (ci/tail! "w" {:repo "acme/widgets" :run "200" :session "S1" :interval-ms 20
                 :emit! #(swap! events conj %) :cancelled? (constantly false)}))

(deftest tail-follows-a-running-job-then-its-log
  (with-github [srv st]
    (swap! st assoc :running {2001 2})            ; two polls in progress, the third sees it completed
    (let [events (atom [])
          r (call-tail events)]
      (is (true? (:follow r)))
      (is (= (:lines r) (count @events)))
      (let [texts (mapv :text @events)]
        (is (= (range 1 (inc (count texts))) (map :n @events)))
        (is (= ["[deliberator] step 1 Set up job: completed success" "[deliberator] step 2 make test: in_progress" "[deliberator] step 2 make test: completed failure"] (take 3 texts)) "step lines while it runs")
        (is (some #(= "[deliberator] job test (ubuntu) completed: failure" %) texts))
        (is (some #(str/ends-with? % "##[error]Process completed with exit code 1.") texts) "then the whole log"))
      (testing "one file for the whole follow, under ci/, adopted as the raw blob"
        (is (str/starts-with? (:path r) (str (store/path "w" "sessions" "S1" "ci" "ci-acme-widgets-200-test-ubuntu-"))))
        (is (= (slurp (:path r)) (String. ^bytes (store/raw-get "w" (:sha r)) "UTF-8")))
        (is (= 4 (count (re-seq #"\[deliberator\]" (slurp (:path r))))))))))

(deftest tail-stops-when-cancelled
  (with-github [srv st]
    (swap! st assoc :running {2001 1000000})
    (let [events (atom []) stop (atom false)
          f (future (ci/tail! "w" {:repo "acme/widgets" :run "200" :session "S1" :interval-ms 20
                                   :emit! #(do (swap! events conj %) (reset! stop true)) :cancelled? #(deref stop)}))
          r (deref f 10000 ::hung)]
      (is (not= ::hung r) "a cancelled follow returns")
      (is (= "[deliberator] step 1 Set up job: completed success" (:text (first @events)))))))

(defmacro are-normalised [& pairs]
  `(do ~@(for [[in out] pairs] `(is (= ~out (ci/normalise-line ~in)) ~in))))

(deftest normalising-what-changes-between-runs
  (are-normalised
   ["2026-10-10T10:00:04.0000003Z Downloaded 5.1 MBs/sec in 12.5s" "Downloaded <rate> in <dur>"]
   ["﻿2026-10-10T10:00:04.0000003Z started" "started"]
   ["Took 1m 23s" "Took <dur>"]
   ["finished in 350 ms (2 seconds total)" "finished in <dur> (<dur> total)"]
   ["Runner name: 'GitHub Actions 1000045678'" "Runner name: '<runner>'"]
   ["  Version: 20261002.596" "  Version: <varies>"]
   ["~/work/_temp/8ac63fb9-1234-4bcd-9abc-0123456789ab.sh" "~/work/_temp/<uuid>.sh"]
   ["checkout 0123456789abcdef0123456789abcdef01234567" "checkout <sha>"]
   ["at 2026-10-10 10:00:04,123 and 10:00:05" "at <ts> and <time>"]
   ["the answer is 42 and 7 tests" "the answer is 42 and 7 tests"]))

(deftest diff-against-the-last-green-run
  (with-github [srv st]
    (let [r (call "ci.diff" {:repo "acme/widgets" :run "200"})]
      (is (false? (:equal r)))
      (is (= "195" (:run (:green r))) "the green run on the same branch comes before the newer-than-main one on another")
      (is (= "200" (:run (:red r))))
      (testing "after normalisation only the failure differs"
        (is (= 2 (:added r)) (:diff r))
        (is (= 1 (:removed r)))
        (is (str/includes? (:diff r) "-12 tests, 0 failures"))
        (is (str/includes? (:diff r) "+FAIL in (match-test) (fs_test.clj:98)"))
        (is (str/includes? (:diff r) "+##[error]Process completed with exit code 1."))
        (is (str/starts-with? (:diff r) "--- green acme/widgets run 195 test (ubuntu)\n+++ red acme/widgets run 200 test (ubuntu)\n")))
      (is (= (:diff r) (slurp (:path r))))
      (is (str/ends-with? (:path r) "diff-acme-widgets-200-vs-195-test-ubuntu-.diff")))
    (testing "without normalisation everything that carries a time differs"
      (let [r (call "ci.diff" {:repo "acme/widgets" :run "200" :normalise false})]
        (is (> (:added r) 8))))
    (testing "against names the run; a run whose job did not succeed is refused"
      (is (= "190" (:run (:green (call "ci.diff" {:repo "acme/widgets" :run "200" :against "190"})))))
      (is (= :ci/no-green-run (:type (thrown #(call "ci.diff" {:repo "acme/widgets" :run "200" :against "200"}))))))
    (testing "a green job is not diffed"
      (is (= :ci/not-failed (:type (thrown #(call "ci.diff" {:repo "acme/widgets" :run "195" :job "lint"}))))))
))
