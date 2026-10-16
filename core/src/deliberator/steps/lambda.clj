(ns deliberator.steps.lambda
  "Kind `lambda`: `### lambda invoke function/logger`. The target is an environment key, a function
  name (`name` or `name:alias`) or an ARN. The step body is the payload, sent byte for byte (an empty
  body sends no payload). Synchronous (`RequestResponse`). A function that fails is a successful
  call: fact `error` carries `FunctionError` (`Unhandled`, `Handled`, empty when none) and the
  error document is the payload. Facts: `status error elapsed`; `$` is the response payload when it
  is JSON. `request.json` is the payload as sent (the EDN form of the call stays in the raw store,
  its payload is base64 there); `response.json` is the raw payload. `# @log-tail` asks for
  `LogType Tail`; the decoded log goes into the displayed output after the payload (the runtime may
  not return one: the display says so)."
  (:require [clojure.string :as str]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c])
  (:import (java.util Base64)))

(defn run [ctx step]
  (let [fn-name (sa/required-target ctx step "function")
        body (:body step)
        tail? (sa/flag? step "log-tail")
        r (sa/call! ctx :lambda :Invoke
                    (cond-> {:FunctionName fn-name :InvocationType "RequestResponse"}
                      (and body (pos? (alength ^bytes body))) (assoc :Payload body)
                      tail? (assoc :LogType "Tail")))
        res (sa/result ctx r "Invoke"
                       {:facts {"status" (:StatusCode r) "error" (or (:FunctionError r) "")
                                "elapsed" (sa/elapsed r)}})
        payload (:response-bytes res)
        log (when-let [l (:LogResult r)] (c/text (.decode (Base64/getDecoder) ^String l)))]
    (cond-> (assoc res :body-json (sa/parse-json payload))
      (and body (pos? (alength ^bytes body))) (assoc :request-bytes body :request-ext (if (sa/parse-json body) "json" "txt"))
      tail? (assoc :display (str (some-> payload c/text str/trimr) "\n--- log tail ---\n"
                                 (if log (str/trimr log) "(the runtime returned no log tail)"))))))

(def kind
  {:kind "lambda" :verbs #{"invoke"} :target :required
   :directives [{:name "log-tail" :doc "ask for the last 4 KB of the invocation log (LogType Tail)"}]
   :facts [{:name "status" :type :number} {:name "error" :type :string}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
