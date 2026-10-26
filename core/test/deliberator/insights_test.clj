(ns deliberator.insights-test
  "Logs Insights (M4.3), the parts that need no service: the derived text with the bytes scanned line, the statistics
  shape measured on a sandbox AWS account, group and window resolution, the `logs insights` step line and its directives. The
  persistence and the real answer are `insights-estate-test` (Floci) and a sandbox AWS account chapter of the walkthrough."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.insights :as ins]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.scenario.parse :as parse]
            [deliberator.steps.logs]))

;; the shape of `statistics` as AWS sent it for a query on a sandbox AWS account-logger, 2026-10-26 (JSON numbers are doubles)
(def real-answer
  {:status "Complete"
   :statistics {:recordsMatched 5.0 :recordsScanned 5.0 :estimatedRecordsSkipped 0.0 :bytesScanned 737.0
                :estimatedBytesSkipped 0.0 :logGroupsScanned 1.0 :resultCount 5.0}
   :results [[{:field "@timestamp" :value "2026-10-26 19:15:03.365"}
              {:field "@message" :value "END RequestId: 704804d7\n"}
              {:field "@ptr" :value "hotstore:ODUx"}]
             [{:field "@timestamp" :value "2026-10-26 19:15:03.364"}
              {:field "@message" :value "a\tb"}
              {:field "@ptr" :value "hotstore:ODUy"}]]})

(deftest statistics-keep-the-service-names-and-whole-numbers-are-longs
  (is (= {"bytesScanned" 737 "recordsMatched" 5 "recordsScanned" 5 "estimatedRecordsSkipped" 0 "estimatedBytesSkipped" 0
          "logGroupsScanned" 1 "resultCount" 5}
         (into {} (ins/statistics real-answer))))
  (is (every? integer? (vals (ins/statistics real-answer))))
  (is (= {"bytesScanned" 1.5} (into {} (ins/statistics {:statistics {:bytesScanned 1.5}}))))
  (is (= {} (into {} (ins/statistics {})))))

(deftest sizes-read-like-people-say-them
  (is (= "0 B" (ins/human-bytes nil)))
  (is (= "737 B" (ins/human-bytes 737)))
  (is (= "1.5 KB" (ins/human-bytes 1536)))
  (is (= "2.0 MB" (ins/human-bytes (* 2 1024 1024))))
  (is (= "3.00 GB" (ins/human-bytes (* 3 1024 1024 1024)))))

(deftest rows-drop-the-ptr-handle-and-the-text-carries-the-bytes-scanned-line
  (let [rows (ins/rows (:results real-answer))
        text (ins/result-text {:env "dev" :account "000000000000" :groups ["/aws/lambda/a sandbox AWS account-logger"]
                               :since "2026-10-26T18:00:00Z" :until "2026-10-26T19:00:00Z"
                               :query "fields @timestamp, @message\n| limit 5" :status "Complete"
                               :statistics (ins/statistics real-answer) :rows rows :elapsed-ms 2100 :name "last-calls"
                               :query-id "q-1"})
        lines (str/split-lines text)]
    (is (= [["@timestamp" "@message"]] [(ins/fields rows)]))
    (is (not (str/includes? text "hotstore")) "the @ptr handle is raw-only")
    (is (str/starts-with? (first lines) "# logs insights on dev (account 000000000000), query last-calls"))
    (is (some #(str/starts-with? % "# bytes scanned: 737 B (737)") lines) "the money line is in the text")
    (is (some #(str/includes? % "# query: fields @timestamp, @message | limit 5") lines) "the query on one line")
    (testing "a tab or a newline inside a value cannot break the table"
      (is (some #(= "2026-10-26 19:15:03.364\ta\\tb" %) lines))
      (is (some #(= "2026-10-26 19:15:03.365\tEND RequestId: 704804d7\\n" %) lines)))))

(deftest a-query-with-no_rows_still_says_what_it_scanned
  (let [text (ins/result-text {:env "dev" :groups ["g"] :since "a" :until "b" :query "fields x" :status "Complete"
                               :statistics {"bytesScanned" 123456 "recordsScanned" 9} :rows []})]
    (is (str/includes? text "# bytes scanned: 120.6 KB (123456)"))
    (is (str/includes? text "rows: 0"))))

(deftest groups-are-names-or-cloudwatch-targets-of-the-environment
  (let [e {:targets {:log/service-a {:cloudwatch "/ecs/service-a"} :log/b {:container "x"}}}]
    (is (= "/ecs/service-a" (ins/resolve-group e "log/service-a")))
    (is (= "/aws/lambda/f" (ins/resolve-group e "/aws/lambda/f")))
    (let [ex (try (ins/resolve-group e "log/b") nil (catch clojure.lang.ExceptionInfo x (ex-data x)))]
      (is (= :insights/bad-arg (:type ex)) "a container log is not a CloudWatch group")
      (is (str/includes? (:hint ex) "{:cloudwatch")))))

(deftest the-window-is-an-instant-or-a-duration-ago
  (let [now (java.time.Instant/parse "2026-10-26T12:00:00Z")]
    (is (= (java.time.Instant/parse "2026-10-26T11:30:00Z") (ins/parse-instant "30m" now)))
    (is (= (java.time.Instant/parse "2026-10-26T10:00:00Z") (ins/parse-instant "2h" now)))
    (is (= (java.time.Instant/parse "2026-10-01T00:00:00Z") (ins/parse-instant "2026-10-01T00:00:00Z" now)))
    (is (thrown? clojure.lang.ExceptionInfo (ins/parse-instant "yesterday" now)))))

(deftest the-arguments-are-checked-before-any-call
  (let [boom (fn [opts] (try (ins/query! {:ws "w" :session "s"} {:name "dev" :kind :local :aws {}} opts) nil
                             (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (is (= :insights/bad-arg (:type (boom {:groups [] :query "fields x"}))))
    (is (= :insights/bad-arg (:type (boom {:groups ["g"] :query " "}))))
    (is (= :insights/bad-arg (:type (boom {:groups ["g"] :query "fields x" :since "1h" :until "2h"}))))
    (is (= :insights/bad-arg (:type (boom {:groups (vec (repeat 51 "g")) :query "fields x"}))))
    (testing "no session, no place for the answer: refused before any call"
      (is (= :session/none (try (ins/query! {:ws "w"} {:name "dev" :kind :local :aws {}} {:groups ["g"] :query "fields x"}) nil
                                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

;; --- the step line ------------------------------------------------------------------------------------------

(defn- parsed [text] (parse/parse text :kinds (kinds/registry)))

(deftest logs-insights-is-a-verb-of-logs-with-its-own-shape
  (let [step (first (:steps (parsed (str "### logs insights recent\n# @group /aws/lambda/f\n# @group log/service-a\n"
                                         "# @since 30m\n# @to 5m\n# @assert bytes-scanned < 1000000\n"
                                         "fields @timestamp, @message | limit 5\n"))))]
    (is (= "logs" (:kind step)))
    (is (= "insights" (:verb step)))
    (is (nil? (:target step)))
    (is (= "recent" (:name step)))
    (is (= ["group" "group" "since" "to" "assert"] (map :name (:directives step))))
    (is (= "fields @timestamp, @message | limit 5" (str/trim (String. ^bytes (:body step) "UTF-8"))))
    (let [kmap (kinds/for-verb (kinds/get-kind "logs") "insights")]
      (is (false? (boolean (:concurrent? kmap))) "the tail of logs is armed before step 1, a query is not")
      (is (= :number (kinds/fact-type kmap "bytes-scanned")))
      (is (nil? (kinds/fact-type kmap "gaps")) "the tail's facts do not leak into the query"))))

(deftest the-tail-of-logs-is-unchanged-and-the-two-do-not-share-directives
  (let [tail (first (:steps (parsed "### logs log/service-a\n# @filter x\n# @for 2s\n")))]
    (is (= "log/service-a" (:target tail)))
    (is (nil? (:verb tail))))
  (is (seq (:errors (parse/describe "### logs insights\n# @filter x\nfields a\n" :kinds (kinds/registry))))
      "@filter is a tail directive")
  (is (seq (:errors (parse/describe "### logs log/a\n# @group g\n" :kinds (kinds/registry))))
      "@group belongs to the query")
  (is (some #(re-find #"known:.*@to" (str (:message %)))
            (:errors (parse/describe "### logs insights\n# @bogus 1\nfields a\n" :kinds (kinds/registry))))
      "the unknown-directive error lists the query's directives")
  (testing "@until stays the engine's retry"
    (is (empty? (:errors (parse/describe "### logs insights\n# @group g\n# @until rows > 0 within 30s\nfields a\n" :kinds (kinds/registry)))))))

(deftest scenario-kinds-lists-the-verb-kind
  (let [logs (first (filter #(= "logs" (:kind %)) (kinds/describe-kinds)))
        vk (first (:verb-kinds logs))]
    (is (= ["insights"] (:verbs logs)))
    (is (true? (:verb-optional logs)) "the plain tail has no verb")
    (is (= "insights" (:verb vk)))
    (is (= "none" (:target vk)))
    (is (= #{"group" "since" "to"} (set (map :name (remove :engine (:directives vk))))))
    (is (some #{"bytes-scanned"} (map :name (:facts vk))))
    (is (every? nil? (map :verb-kinds (remove #(= "logs" (:kind %)) (kinds/describe-kinds)))))))
