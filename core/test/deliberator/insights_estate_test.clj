(ns deliberator.insights-estate-test
  "`logs.insights` against Floci (M4.3). Floci's Logs Insights is a stub (M1 spike: it answers `Failed`), so this checks what
  does not depend on the service's answer: the query text is saved first, StartQuery and every GetQueryResults are persisted
  raw before anything is read, the result text exists with its `# bytes scanned:` line, the audit has the calls (StartQuery is
  a read), the errors name the service's words, and the arguments are refused without a call. The real statistics shape
  and the polling are checked on a sandbox AWS account (docs/walkthrough.md, a sandbox AWS account chapter;). Needs a running Floci."
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
          _ (aws/invoke! {:ws "default"} local :logs :CreateLogGroup {:logGroupName group})
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
        (is (= (:status r) (if (:result outcome) "Complete" (name (last (str/split (name (get-in outcome [:error :type])) #"/"))) ))
            "an unfinished query is an error whose type names the status")
        (is (integer? (:bytes-scanned r)) "bytes scanned is always a whole number, 0 when the service sent none")
        (is (map? (:statistics r)))
        (is (string? (:bytes-scanned-text r))))
      (testing "the result text exists in the session with the bytes scanned line, whatever the status"
        (let [f (io/file (:path r))]
          (is (.isFile f))
          (is (str/starts-with? (str (.getParent f)) (str (.resolve sess "logs"))))
          (is (re-find #"(?m)^# bytes scanned: \d+ B \(\d+\)" (slurp f)))
          (is (re-find #"(?m)^# status: " (slurp f)))
          (is (= (:sha r) (store/sha256 (.getBytes (slurp f) "UTF-8"))) "the file is the raw blob, hard-linked")))
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
      (testing "nothing was sent to the service"
        (is (not (.exists (io/file (str (aws/audit-path {:ws "default" :session "I-2"}))))))))))

(deftest-floci a-logs-insights-step-runs-and-fails-with-the-service-s-words
  (ru/with-remote-at-floci [remote local]
    (ops/call! "session.start" {} {:key "I-3" :ws "default"})
    (let [dir (.resolve (session/dir "default" "I-3") "scenarios")
          _ (io/make-parents (.toFile (.resolve dir "q.scn")))
          _ (spit (.toFile (.resolve dir "q.scn"))
                  (str "# @env dlbremote\n\n### logs insights floci-shape\n# @group /m4/insights-step\n# @since 15m\n"
                       "# @assert status == Complete\nfields @timestamp | limit 3\n"))
          _ (aws/invoke! {:ws "default"} (fu/local-env) :logs :CreateLogGroup {:logGroupName "/m4/insights-step"})
          r (call "scenario.run" {:ws "default" :path "q.scn" :session "I-3" :env "dlbremote"} (atom []))
          step-dir (first (filter #(str/includes? (str %) "logs-insights") (file-seq (io/file (get-in r [:paths :run] "")))))]
      (is (contains? #{:ok :error :failed} (:status r)) (pr-str (dissoc r :paths)))
      (testing "the step left its query as request.txt and its answer as response.json whichever way it ended"
        (is (some? step-dir))
        (when step-dir
          (is (= "fields @timestamp | limit 3\n" (slurp (io/file step-dir "request.txt"))))
          (is (.isFile (io/file step-dir "response.json")))
          (is (str/includes? (slurp (io/file step-dir "response.json")) "\"statistics\"")))))))
