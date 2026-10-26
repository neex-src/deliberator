(ns deliberator.steps.insights
  "The `insights` verb of kind `logs` (M4.3): `### logs insights [name]`, the body is the Logs Insights query, the directives
  `# @group <log group | log/ target>` (repeatable, at least one), `# @since <instant|duration ago>` (default 1h) and
  `# @to <instant|duration ago>` (default now; the op's `until` argument: `@until` is the engine's retry directive). It is the step form of the `logs.insights` operation
  (`deliberator.insights/query!`): the same StartQuery/GetQueryResults polling, the same raw persistence, the same result text
  under `logs/` of the session, and a named step also saves its query as `queries/<name>.insights`.

  Not a concurrent step (the tail of `logs` is): it runs in order, so a later step can assert on it. Facts: `bytes-scanned`
  (what Insights bills for), `records-matched`, `records-scanned`, `rows`, `status`. `$` is
  `{status, statistics{bytesScanned ...}, results[{field: value}], raw[{op request response}]}`; the persisted
  `response.json` is that document and the step's display is the result text with the bytes scanned line. A query that
  does not end `Complete` (failed, stopped over the step's timeout) is a step ERROR; the partial text is still written.
  The step needs a remote or emulated environment that answers CloudWatch Logs; Floci's Insights is a stub that answers
  `Failed`, so against `local` expect an ERROR with the service's words."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.insights :as ins]
            [deliberator.steps.common :as c]))

(defn- pretty ^bytes [x] (c/utf8 (str (json/write-str x :indent true :escape-slash false) "\n")))

(defn run [ctx step]
  (let [groups (c/dirs step "group")
        query (some-> (:body step) c/text)
        budget (max 1000 (- (.toEpochMilli ^java.time.Instant (or (:attempt-deadline ctx) (:deadline ctx)))
                            (System/currentTimeMillis) 2000))   ; leave the engine's timeout a moment to see the stop
        opts {:groups groups :query query :since (c/dir step "since") :until (c/dir step "to")
              :name (when-not (str/blank? (:name step)) (:name step))
              :budget-ms budget :throw? false
              :progress! (or (:line! ctx) (fn [_]))}
        r (ins/query! (assoc ctx :cancelled? (or (:cancelled? ctx) (constantly false))) (:env ctx) opts)
        stats (:statistics r)
        doc {"status" (:status r) "query-id" (:query-id r) "statistics" stats
             "results" (:result-rows r) "raw" (mapv #(into {} (for [[k v] % :when v] [(name k) v])) (:raw r))}
        resp (pretty doc)
        req (c/utf8 (str (str/trimr (or query "")) "\n"))]
    (if (not= "Complete" (:status r))
      (throw (ex-info (str "Logs Insights query " (:query-id r) " ended " (:status r) "; "
                           (ins/human-bytes (get stats "bytesScanned")) " scanned")
                      {:type :insights/not-complete :request-bytes req :request-ext "txt" :response-bytes resp :response-ext "json"
                       :hint (str "the partial text is in " (:path r) "; "
                                  (case (:status r)
                                    "Failed" "a malformed query fails at GetQueryResults; Floci's Insights is a stub that always answers Failed"
                                    "BudgetExceeded" "raise the step's @timeout or narrow @since and the groups"
                                    "see the raw GetQueryResults response"))}))
      {:facts {"bytes-scanned" (get stats "bytesScanned" 0) "records-matched" (get stats "recordsMatched" 0)
               "records-scanned" (get stats "recordsScanned" 0) "rows" (:rows r) "status" (:status r)}
       :body-json (json/read-str (c/text resp))
       :request-bytes req :request-ext "txt"
       :response-bytes resp :response-ext "json" :display (:text r) :lines (:rows r)
       :source :aws :op "logs.insights" :content-type "application/json"})))

(def verb-kind
  "The `:verb-kinds` entry of kind `logs` for `insights`."
  {:target :none
   :directives [{:name "group" :hint "<log group | log/target>" :repeatable? true
                 :doc "a log group to query (a name, or a log/ target of the environment mapped to a CloudWatch group); at least one"}
                {:name "since" :hint "<instant|duration ago>" :values ["15m" "1h" "6h" "24h"] :doc "start of the window (default 1h)"}
                {:name "to" :hint "<instant|duration ago>" :values ["5m" "1m"] :doc "end of the window (default now); the engine owns @until (retry), so the end of the window is @to"}]
   :facts [{:name "bytes-scanned" :type :number} {:name "records-matched" :type :number}
           {:name "records-scanned" :type :number} {:name "rows" :type :number} {:name "status" :type :string}]
   :capture-roots #{"$"} :concurrent? false
   :default-timeout-ms 600000
   :run run})
