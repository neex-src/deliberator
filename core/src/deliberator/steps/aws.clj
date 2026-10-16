(ns deliberator.steps.aws
  "Helpers the AWS kinds (`sqs eventbridge lambda dynamodb scheduler`) share; not a kind itself.

  Bytes: `deliberator.aws/invoke!` has already persisted the serialised request (EDN) and the raw
  response through `store/raw-put!` before anything was decoded. A kind hands those same bytes back
  (`result`, `failure`) as `:request-bytes` / `:response-bytes` with `:source :aws :op <Op>`; the
  engine's own `raw-put!` then hits the same sha (nothing is written twice, invoke!'s meta -- role,
  class, status -- stays the blob's meta) and links the blob into the step directory as
  `request.edn` and `response.<json|xml|txt|bin>`. Failures: `call!` turns an `invoke!` error map
  into a thrown ERROR (message = AWS code and message, `:hint` from the error, bytes of the failed
  call in the ex-data); a confirmation request (remote mutate) becomes an ERROR too, and the token and summary go out as a `:confirm.required` event on the run's
  sink (never into the ERROR, step.edn or run.edn); `call!` passes the run's `:confirm` token (ctx,
  from the daemon's `scenario.run` `confirm`) on to `invoke!`, which lets exactly that request through.

  Targets: `target-value` takes an `http(s)://` URL, an `arn:` or a bare name (no `/`) as written
  and sends every other word (`queue/inbox`, `table/messages`) through `:resolve-target`; the
  environment's value must be a string."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.scenario.engine :as engine]
            [deliberator.steps.common :as c]
            [deliberator.store :as store]))

(defn fail
  "Throws a step ERROR with a message, a hint and optional extra ex-data."
  [msg hint & [data]]
  (throw (ex-info msg (merge {:type :aws/step :hint hint} data))))

(defn target-value
  "The step's target as a string (see the namespace docstring), nil when the step has none."
  [ctx step]
  (when-let [t (not-empty (:target step))]
    (if (or (re-find #"^(https?://|arn:)" t) (not (str/includes? t "/")))
      t
      (let [v ((:resolve-target ctx) t)]
        (if (string? v)
          v
          (fail (str "target " t " is not a name or URL: " (pr-str v))
                (str "environment " (c/env-name ctx) " must map " t " to a string")))))))

(defn required-target
  "Like `target-value` but a missing target is a step ERROR naming what the kind needs."
  [ctx step what]
  (or (target-value ctx step)
      (fail (str (:kind step) " " (:verb step) " needs a target")
            (str "write the " what " on the step line, e.g. ### " (:kind step) " " (:verb step) " <key>"))))

;; --- bytes ---------------------------------------------------------------------------------------

(defn parse-json
  "Parsed JSON of `bs` (string keys), nil when empty or not JSON."
  [^bytes bs]
  (when (and bs (pos? (alength bs)))
    (try (json/read-str (c/text bs)) (catch Exception _ nil))))

(defn- json? [^bytes bs]
  (and (pos? (alength bs))
       (try (json/read-str (c/text bs)) true (catch Exception _ false))))

(defn- text? [^bytes bs]
  (try (let [dec (-> java.nio.charset.StandardCharsets/UTF_8 .newDecoder
                     (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                     (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
         (.decode dec (java.nio.ByteBuffer/wrap bs)) (not-any? zero? bs))
       (catch Exception _ false)))

(defn ext-of
  "File extension for a persisted response: json (parses), xml, edn (an anomaly), txt or bin."
  [ct ^bytes bs]
  (let [ct (str/lower-case (or ct ""))]
    (cond (json? bs) "json"
          (str/includes? ct "xml") "xml"
          (str/includes? ct "edn") "edn"
          (or (zero? (alength bs)) (text? bs)) "txt"
          :else "bin")))

(defn- blob [ctx ref]
  (when-let [sha (:sha ref)]
    (let [bs (store/raw-get (:ws ctx) sha)]
      {:bytes bs :ext (ext-of (:content-type (store/raw-meta (:ws ctx) sha)) bs)})))

(defn blobs
  "`{:request-bytes :request-ext :response-bytes :response-ext}` of a persisted call (the `raw`
  refs of `aws/raw-refs`); keys with no blob are absent."
  [ctx raw]
  (let [rq (blob ctx (:request raw)) rs (blob ctx (:response raw))]
    (cond-> {}
      rq (assoc :request-bytes (:bytes rq) :request-ext "edn")
      rs (assoc :response-bytes (:bytes rs) :response-ext (:ext rs)))))

(defn failure
  "The ex-info for an error map returned by `invoke!`."
  [ctx r]
  (let [code (:code r) msg (:message r)]
    (if (= :confirm-required (:type r))
      (do
        (try (engine/event! (:sink ctx) {:event :confirm.required :step (:n (:step ctx))
                                         :token (:token r) :summary (:summary r) :expires (:expires r)})
             (catch Throwable _ nil))
        (ex-info (str (:summary r) " needs confirmation")
                 {:type :aws/confirm-required
                  :hint (str "environment " (c/env-name ctx) " is remote: a mutating call must be confirmed; "
                             "re-run with confirm set to the token of the confirm.required event (it is bound to the exact request), or use a read operation")}))
      (ex-info (cond (and code msg (not (str/includes? msg (str code)))) (str code ": " msg)
                     :else (or msg code (str (:category r))))
               (merge {:type :aws/error :hint (:hint r) :aws (dissoc r :raw)}
                      (blobs ctx (aws/raw-refs r)))))))

(defn call!
  "`aws/invoke!` through the step's context; an error map is thrown as a step ERROR."
  [ctx service op request]
  (let [r (aws/invoke! (select-keys ctx [:ws :session :confirm]) (:env ctx) service op request)]
    (if (aws/error? r) (throw (failure ctx r)) r)))

(defn elapsed [r] (:deliberator.aws/elapsed-ms (meta r)))

(defn result
  "The kind result for the successful call `r`: its persisted bytes, `:source :aws :op op`, plus
  `extra` (`:facts :body-json :display :lines`)."
  [ctx r op extra]
  (merge (blobs ctx (aws/raw-refs r))
         {:source :aws :op op}
         extra))

(defn flag? [step name] (boolean (some #(= name (:name %)) (:directives step))))
