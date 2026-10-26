(ns deliberator.insights-estate-test
  "`logs.insights` against Floci (M4.3). Floci's Logs Insights answers `Complete` with the rows of the group and zero statistics
  (the M1 spike saw `Failed`; a service that does not finish is still accepted here, see the status checks): the query text is
  saved first, StartQuery and every GetQueryResults are persisted raw before anything is read, the result text exists with its
  `# bytes scanned:` line and the rows, `@ptr` is dropped from the text but not from the raw, the audit has the calls (StartQuery
  is a read), a `logs insights` step leaves its query as `request.txt` and its answer as `response.json`, and the arguments are
  refused without a call. The real statistics shape and the polling (Running, budget, StopQuery, cancel) are checked on a sandbox AWS account
  (docs/walkthrough.md, Part 16;). Needs a running Floci."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.insights :as ins]
            [deliberator.ops :as ops]
            [deliberator.remote-util :as ru]
            [deliberator.session :as session]
            [deliberator.store :as store]))

(defn- call [op args events]
  (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :cancelled? (constantly false)
                 :daemon {:runs (atom {}) :checked (atom #{})}}
             args))

(deftest-floci insights-persists-first-and-keeps-the-bytes-scanned-line-whatever-floci-answers
  (ru/with-remote-at-floci [remote local]
    (ops/call! "session.start" {} {:key "I-1" :ws "default"})
    (let [group "/m4/insights-test"
          _ (aws/invoke! {:ws "default"} local :logs :DeleteLogGroup {:logGroupName group})
          _ (aws/invoke! {:ws "default"} local :logs :CreateLogGroup {:logGroupName group})
          _ (aws/invoke! {:ws "default"} local :logs :CreateLogStream {:logGroupName group :logStreamName "s1"})
          now-ms (System/currentTimeMillis)
          _ (aws/invoke! {:ws "default"} local :logs :PutLogEvents
                         {:logGroupName group :logStreamName "s1"
                          :logEvents [{:timestamp (- now-ms 1000) :message "hello insights one"}
                                      {:timestamp (- now-ms 500) :message "hello\tinsights two"}]})
          events (atom [])
          args {:ws "default" :env "dlbremote" :groups [group] :query "fields @timestamp, @message | limit 5" :since "30m"
                :name "m4-shape" :budget-ms 20000 :session "I-1"}
          outcome (try {:result (call "logs.insights" args events)}
                       (catch clojure.lang.ExceptionInfo e {:error (ex-data e) :message (ex-message e)}))
          r (or (:result outcome) (get-in outcome [:error :result]))
          sess (session/dir "default" "I-1")]
      (testing "the named query text is on disk, byte for byte plus a newline"
        (is (= "fields @timestamp, @message | limit 5\n" (slurp (str (ins/query-file "default" "I-1" "m4-shape"))))))
      (testing "the answer is a result with the shape of the real one, or an error that carries it (Floci: Failed)"
        (is (some? r) (pr-str outcome))
        (is (contains? #{"Complete" "Failed"} (:status r)) (pr-str (dissoc r :raw)))
        (is (= (:status r) (if (:result outcome) "Complete" (name (last (str/split (name (get-in outcome [:error :type])) #"/")))))
            "an unfinished query is an error whose type names the status")
        (is (= "Complete" (:status r)) "Floci answers the query")
        (is (= 2 (:rows r)))
        (is (integer? (:bytes-scanned r)) "bytes scanned is always a whole number, 0 when the service sent none")
        (is (map? (:statistics r)))
        (is (string? (:bytes-scanned-text r))))
      (testing "the result text exists in the session with the bytes scanned line, whatever the status"
        (let [f (io/file (:path r))]
          (is (.isFile f))
          (is (str/starts-with? (str (.getParent f)) (str (.resolve sess "logs"))))
          (is (re-find #"(?m)^# bytes scanned: \d+ B \(\d+\)" (slurp f)))
          (is (re-find #"(?m)^# status: " (slurp f)))
          (is (re-find #"(?m)^[0-9-]+ [0-9:.]+\thello insights one$" (slurp f)) "a row, tab separated")
          (is (str/includes? (slurp f) "hello\\tinsights two") "a tab inside a value is escaped, the table stays a table")
          (is (not (str/includes? (slurp f) "@ptr")))
          (is (= (:sha r) (store/sha256 (.getBytes (slurp f) "UTF-8"))) "the file is the raw blob, hard-linked")))
      (testing "@ptr is in the raw response, the text is derived from it"
        (let [get-raw (some #(when (= "GetQueryResults" (:op %)) (:response %)) (reverse (:raw r)))]
          (is (str/includes? (slurp (str (store/raw-path "default" get-raw))) "@ptr"))))
      (testing "StartQuery and GetQueryResults were persisted raw, request before response, and are listed"
        (let [ops* (map :op (:raw r))]
          (is (= "StartQuery" (first ops*)))
          (is (some #{"GetQueryResults"} ops*))
          (doseq [{:keys [request response]} (:raw r)]
            (is (.isFile (io/file (str (store/raw-path "default" request)))))
            (is (.isFile (io/file (str (store/raw-path "default" response))))))))
      (testing "audit: remote calls, all reads (StartQuery and StopQuery are classed read), none refused"
        (let [lines (map read-string (str/split-lines (slurp (str (.resolve sess "audit.log")))))
              q (filter #(= "logs" (name (:service %))) lines)]
          (is (seq q))
          (is (every? #(= :read (:class %)) q))
          (is (not-any? #(= :refused (:outcome %)) q))))
      (testing "progress went out as line events"
        (is (some #(str/includes? (str (:text %)) "started on 1 log group") @events))))))

(deftest-floci arguments-are-refused-before-any-call
  (ru/with-remote-at-floci [remote local]
    (ops/call! "session.start" {} {:key "I-2" :ws "default"})
    (let [bad (fn [args] (try (call "logs.insights" (merge {:ws "default" :env "dlbremote" :session "I-2"} args) (atom [])) nil
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
      (is (= :insights/bad-arg (bad {:groups [] :query "fields x"})))
      (is (= :insights/bad-arg (bad {:groups ["g"] :query "  "})))
      (is (= :insights/bad-arg (bad {:groups ["g"] :query "fields x" :since "1h" :until "2h"})))
      (testing "nothing was sent to CloudWatch Logs (the account check before it is the only call)"
        (let [lines (map read-string (str/split-lines (slurp (str (aws/audit-path {:ws "default" :session "I-2"})))))]
          (is (seq lines))
          (is (not-any? #(= :logs (:service %)) lines)))))))

(deftest-floci a-logs-insights-step-runs-and-fails-with-the-service-s-words
  (ru/with-remote-at-floci [remote local]
    (ops/call! "session.start" {} {:key "I-3" :ws "default"})
    (let [dir (.resolve (session/dir "default" "I-3") "scenarios")
          _ (io/make-parents (.toFile (.resolve dir "q.scn")))
          _ (spit (.toFile (.resolve dir "q.scn"))
                  (str "# @env dlbremote\n\n### logs insights floci-shape\n# @group /m4/insights-step\n# @since 15m\n"
                       "# @assert status == Complete\n# @assert rows == 1\n# @assert bytes-scanned >= 0\n# @assert $.results[0].@message == one step line\n"
                       "fields @timestamp, @message | limit 3\n"))
          loc (fu/local-env)
          _ (aws/invoke! {:ws "default"} loc :logs :DeleteLogGroup {:logGroupName "/m4/insights-step"})
          _ (aws/invoke! {:ws "default"} loc :logs :CreateLogGroup {:logGroupName "/m4/insights-step"})
          _ (aws/invoke! {:ws "default"} loc :logs :CreateLogStream {:logGroupName "/m4/insights-step" :logStreamName "s1"})
          _ (aws/invoke! {:ws "default"} loc :logs :PutLogEvents
                         {:logGroupName "/m4/insights-step" :logStreamName "s1"
                          :logEvents [{:timestamp (- (System/currentTimeMillis) 500) :message "one step line"}]})
          r (call "scenario.run" {:ws "default" :path "q.scn" :session "I-3" :env "dlbremote"} (atom []))
          step-dir (first (filter #(str/includes? (str %) "floci-shape") (file-seq (io/file (get-in r [:paths :run] "")))))]
      (is (= "ok" (name (:status r))) (pr-str (dissoc r :paths)))
      (is (= 0 (:failed-checks r)))
      (testing "the step left its query as request.txt and its answer as response.json whichever way it ended"
        (is (some? step-dir))
        (when step-dir
          (is (= "fields @timestamp, @message | limit 3\n" (slurp (io/file step-dir "request.txt"))))
          (is (.isFile (io/file step-dir "response.json")))
          (is (str/includes? (slurp (io/file step-dir "response.json")) "\"statistics\"")))))))
