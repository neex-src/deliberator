(ns deliberator.steps.sqs
  "Kind `sqs`: `### sqs send queue/inbox` and `### sqs receive queue/inbox`. The target is an
  environment key, a queue name, a queue URL or an ARN (a name is turned into a URL with
  `GetQueueUrl` on every run; the repeated, identical blobs deduplicate in the raw store).

  `send`: the step body is the message body byte for byte (UTF-8). `# @attr k=v` (repeatable) adds a
  String message attribute; the `correlation` attribute carries the step's correlation id unless the
  step sets its own. Facts: `messageId md5 elapsed`; `$` is the SendMessage response.

  `receive`: one `ReceiveMessage` per attempt (`@until` repeats it): `# @max n` (default 1, up to 10),
  `# @wait <duration>` the long-poll window (default: what is left of the attempt's deadline, at most
  20 s, rounded down to whole seconds). Received messages are deleted afterwards (one
  `DeleteMessageBatch`), unless `# @keep`, which also receives with `VisibilityTimeout 0` so that a
  kept message is a peek and stays visible to the next attempt. A failed delete is a step ERROR (the
  receive response is kept). Facts: `count elapsed`; `$` is the JSON array of the received bodies,
  each parsed when it is JSON and the string otherwise; the whole `ReceiveMessage` response
  (attributes included) is `response.json`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.scenario.expr :as expr]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c]))

(defn queue-url [ctx step]
  (let [t (sa/required-target ctx step "queue")]
    (if (re-find #"^https?://" t)
      t
      (let [name (if (str/starts-with? t "arn:") (last (str/split t #":")) t)]
        (:QueueUrl (sa/call! ctx :sqs :GetQueueUrl {:QueueName name}))))))

(defn parse-attr
  "`k=v` -> [k v]."
  [s]
  (let [i (str/index-of s "=")]
    (if (and i (pos? i))
      [(str/trim (subs s 0 i)) (subs s (inc i))]
      (sa/fail (str "bad @attr " (pr-str s)) "write # @attr name=value"))))

(defn- int-dir [step name default lo hi]
  (if-let [v (c/dir step name)]
    (let [n (try (Long/parseLong (str/trim v)) (catch Exception _ nil))]
      (if (and n (<= lo n hi)) n
          (sa/fail (str "bad @" name " " (pr-str v)) (str "a whole number from " lo " to " hi))))
    default))

(defn- send! [ctx step]
  (let [url (queue-url ctx step)
        attrs (into {"correlation" (:step-correlation ctx)} (map parse-attr (c/dirs step "attr")))
        r (sa/call! ctx :sqs :SendMessage
                    {:QueueUrl url
                     :MessageBody (c/text (or (:body step) (byte-array 0)))
                     :MessageAttributes (into {} (for [[k v] attrs] [k {:DataType "String" :StringValue v}]))})]
    (let [res (sa/result ctx r "SendMessage"
                         {:facts {"messageId" (:MessageId r) "md5" (:MD5OfMessageBody r) "elapsed" (sa/elapsed r)}})]
      (assoc res :body-json (sa/parse-json (:response-bytes res))))))

(defn wait-seconds
  "Long-poll seconds: `@wait` or the default, never beyond what the deadline leaves (one second
  of margin) nor 20."
  [ctx step]
  (let [room (max 0 (quot (- (kinds/remaining-ms ctx) 1000) 1000))
        asked (if-let [w (c/dir step "wait")]
                (long (Math/ceil (/ (expr/parse-duration w) 1000.0)))
                20)]
    (min 20 room asked)))

(defn- body-value [s]
  (try (json/read-str s) (catch Exception _ s)))

(defn- receive! [ctx step]
  (let [url (queue-url ctx step)
        keep? (sa/flag? step "keep")
        r (sa/call! ctx :sqs :ReceiveMessage
                    (cond-> {:QueueUrl url
                             :MaxNumberOfMessages (int-dir step "max" 1 1 10)
                             :WaitTimeSeconds (wait-seconds ctx step)
                             :AttributeNames ["All"] :MessageAttributeNames ["All"]}
                      keep? (assoc :VisibilityTimeout 0)))
        msgs (vec (:Messages r))
        res (sa/result ctx r "ReceiveMessage"
                       {:facts {"count" (count msgs) "elapsed" (sa/elapsed r)}
                        :body-json (mapv #(body-value (:Body %)) msgs)
                        :lines (count msgs)})]
    (when (and (seq msgs) (not keep?))
      (let [d (try (sa/call! ctx :sqs :DeleteMessageBatch
                             {:QueueUrl url
                              :Entries (vec (map-indexed (fn [i m] {:Id (str i) :ReceiptHandle (:ReceiptHandle m)}) msgs))})
                   (catch clojure.lang.ExceptionInfo e
                     (throw (ex-info (str "received " (count msgs) " message(s) but could not delete them: " (ex-message e))
                                     (merge (select-keys res [:request-bytes :request-ext :response-bytes :response-ext])
                                            {:type :aws/error :hint (:hint (ex-data e))})
                                     e))))]
        (when (seq (:Failed d))
          (throw (ex-info (str "received " (count msgs) " message(s) but DeleteMessageBatch failed for "
                               (count (:Failed d)) ": " (pr-str (:Failed d)))
                          (merge (select-keys res [:request-bytes :request-ext :response-bytes :response-ext])
                                 {:type :aws/error :hint "the messages become visible again after the queue's visibility timeout"}))))))
    res))

(defn run [ctx step]
  (case (:verb step)
    "send" (send! ctx step)
    "receive" (receive! ctx step)))

(def kind
  {:kind "sqs" :verbs #{"send" "receive"} :target :required
   :directives [{:name "attr" :hint "k=v" :repeatable? true :doc "send: a String message attribute"}
                {:name "max" :hint "<1-10>" :values ["1" "10"] :doc "receive: messages per call (default 1)"}
                {:name "wait" :hint "<duration>" :values ["1s" "5s" "20s"] :doc "receive: long-poll window (default: the deadline, at most 20s)"}
                {:name "keep" :doc "receive: do not delete; peek (VisibilityTimeout 0)"}]
   :facts [{:name "messageId" :type :id} {:name "md5" :type :string} {:name "count" :type :number}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
