(ns deliberator.steps.eventbridge
  "Kind `eventbridge`: `### eventbridge put bus/main`. The target is an environment key, an event bus
  name or an ARN. The step body is the event's detail (a JSON object; empty means `{}`); the step's
  correlation id is added as `detail.correlation` unless the detail has one, by inserting the member
  before the closing brace so the author's text is otherwise untouched. `# @source` (default
  `deliberator`) and `# @detail-type` (default `scenario`). One `PutEvents` entry. Facts: `eventId
  failed elapsed`; `failed` is `FailedEntryCount` (a rejected entry is a result, not an ERROR:
  assert `failed == 0`). `$` is the PutEvents response."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c]))

(defn with-correlation
  "The detail text with a `correlation` member added (see the namespace docstring)."
  [text corr]
  (let [t (str/trim (or text ""))
        parsed (if (str/blank? t) {} (try (json/read-str t) (catch Exception e
                                                               (sa/fail (str "the detail is not JSON: " (ex-message e))
                                                                        "the step body of eventbridge put is the event's detail, a JSON object"))))]
    (when-not (map? parsed)
      (sa/fail "the detail must be a JSON object" "write the body as {\"key\": ...}"))
    (cond
      (contains? parsed "correlation") t
      (empty? parsed) (str "{\"correlation\":" (json/write-str corr) "}")
      :else (let [i (str/last-index-of t "}")]
              (str (str/trimr (subs t 0 i)) ",\"correlation\":" (json/write-str corr) "}")))))

(defn run [ctx step]
  (let [bus (sa/required-target ctx step "event bus")
        r (sa/call! ctx :eventbridge :PutEvents
                    {:Entries [{:EventBusName bus
                                :Source (or (c/dir step "source") "deliberator")
                                :DetailType (or (c/dir step "detail-type") "scenario")
                                :Detail (with-correlation (some-> (:body step) c/text) (:step-correlation ctx))}]})
        res (sa/result ctx r "PutEvents"
                       {:facts {"eventId" (some-> r :Entries first :EventId)
                                "failed" (:FailedEntryCount r)
                                "elapsed" (sa/elapsed r)}})]
    (assoc res :body-json (sa/parse-json (:response-bytes res)))))

(def kind
  {:kind "eventbridge" :verbs #{"put"} :target :required
   :directives [{:name "source" :hint "<source>" :values ["deliberator"] :doc "event source (default deliberator)"}
                {:name "detail-type" :hint "<type>" :doc "event detail-type (default scenario)"}]
   :facts [{:name "eventId" :type :id} {:name "failed" :type :number}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
