(ns deliberator.steps.dynamodb
  "Kind `dynamodb`: `### dynamodb get|query|scan table/messages`. The target is an environment key,
  a table name or an ARN. The step body is the DynamoDB JSON request without `TableName` (a
  `TableName` in the body is ignored: the target decides), e.g. `{\"Key\": {\"id\": {\"S\": \"1\"}}}`
  for `get` (GetItem), `{\"KeyConditionExpression\": ..., \"ExpressionAttributeValues\": ...}` for
  `query`, `{\"Limit\": 10}` or nothing for `scan`. `B` / `BS` attribute values are base64 in the
  body, as on the wire. No pagination: `LastEvaluatedKey` is in the response file.

  Facts: `count scanned elapsed` (`get`: `count` is 1 when the item exists, 0 otherwise, no
  `scanned`). `$` is the typed DynamoDB JSON of the raw response: `Item` for `get`, `Items` for
  `query` and `scan`, so a string attribute reads `$.id.S` (get) or `$[0].id.S` (query)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c])
  (:import (java.util Base64)))

(defn- b64 [s] (.decode (Base64/getDecoder) ^String s))

(defn request-from-body
  "Parsed body (keyword keys) with `B`/`BS` values decoded to bytes."
  [^bytes body]
  (let [t (some-> body c/text str str/trim)
        m (if (str/blank? t) {}
              (try (json/read-str t :key-fn keyword)
                   (catch Exception e (sa/fail (str "the body is not JSON: " (ex-message e))
                                               "the body of a dynamodb step is the request JSON without TableName"))))]
    (when-not (map? m) (sa/fail "the body must be a JSON object" "e.g. {\"Key\": {\"id\": {\"S\": \"1\"}}}"))
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (cond-> x
                         (string? (:B x)) (update :B b64)
                         (sequential? (:BS x)) (update :BS #(mapv b64 %)))
                       x))
                   (dissoc m :TableName))))

(defn run [ctx step]
  (let [table (sa/required-target ctx step "table")
        verb (:verb step)
        op (case verb "get" :GetItem "query" :Query "scan" :Scan)
        r (sa/call! ctx :dynamodb op (assoc (request-from-body (:body step)) :TableName table))
        res (sa/result ctx r (name op) {})
        parsed (sa/parse-json (:response-bytes res))
        item (get parsed "Item") items (get parsed "Items")]
    (assoc res
           :facts (cond-> {"elapsed" (sa/elapsed r)}
                    (= "get" verb) (assoc "count" (if item 1 0))
                    (not= "get" verb) (assoc "count" (:Count r) "scanned" (:ScannedCount r)))
           :body-json (if (= "get" verb) item items)
           :lines (if (= "get" verb) (if item 1 0) (count items)))))

(def kind
  {:kind "dynamodb" :verbs #{"get" "query" "scan"} :target :required
   :directives []
   :facts [{:name "count" :type :number} {:name "scanned" :type :number}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
