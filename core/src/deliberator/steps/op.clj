(ns deliberator.steps.op
  "Kind `op`: `### op <operation> [name]` calls one daemon operation, the body is its JSON arguments
  (optional). `ws`, `session` and `env` default to the run's when the operation takes them and the body does not give
  them. The operation runs in the daemon that runs the scenario (`ctx :host`, its request context); its events are
  streamed as lines, the arguments are `request.json`, the result (or the error map) is `response.json` and `$`. Facts:
  `ok` (\"true\"/\"false\"), `error.type`, `error.message`. An operation error is a step error unless an
  `@assert`/`@until` names `ok` or `error.type`/`error.message`, then the checks decide (`confirm-required` of
  `service.start --secrets-from` is walked that way). Not offered: `scenario.run`, `scenario.cancel`, `daemon.stop`.
  Local only: refused for a `:remote` environment, and refused when the body names one (`env`, `secrets-from`, or `name`
  of an `env.*` operation): a local run reaches no remote environment, not even for a read."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.common :as c]
            [deliberator.steps.shell :as shell]))

(def excluded #{"scenario.run" "scenario.cancel" "daemon.stop"})

(defn- named-envs
  "The environment names an op body may point an operation at: `env`, `secrets-from`, and `name` of an `env.*` op."
  [op args]
  (keep #(let [v (get args %)] (when (string? v) v))
        (cond-> ["env" "secrets-from"] (str/starts-with? op "env.") (conj "name"))))

(defn- refuse-remote-body!
  "Throws `op/remote-env` when a named environment exists and is `:remote` (an unknown name is left to the operation)."
  [ctx op args]
  (doseq [n (named-envs op args)]
    (let [e (try (env/load (:ws ctx) n) (catch Exception _ nil))]
      (when (= :remote (:kind e))
        (throw (ex-info (str "op steps reach only local environments; the body of " op " names " n ", which is remote")
                        {:type :op/remote-env :env n
                         :hint "run remote operations with dlb or the IDE, where a mutation can be confirmed"}))))))

(defn- scalar? [v] (or (string? v) (number? v) (boolean? v) (keyword? v)))

(defn event-text
  "One line for an operation event, close to what `dlb` prints."
  [{:keys [event] :as ev}]
  (let [event (name event)]
    (cond
      (#{"line" "logs.line" "service.build.line" "tf.line"} event) (str (or (:text ev) (:line ev)))
      (= "service.reload.step" event) (str "reload " (:name ev) ": " (or (:detail ev) (:step ev)))
      :else (str/join " " (cons event (for [[k v] (sort-by key (dissoc ev :event :id)) :when (scalar? v)]
                                        (str (name k) "=" (if (keyword? v) (name v) v))))))))

(defn- ->json-value
  "Clojure data -> JSON-shaped data with string keys (keywords become their name, instants their text)."
  [x]
  (json/read-str (json/write-str x :key-fn #(if (keyword? %) (subs (str %) 1) (str %))
                                   :value-fn (fn [_ v] (cond (keyword? v) (subs (str v) 1)
                                                             (instance? java.time.temporal.TemporalAccessor v) (str v)
                                                             (instance? java.util.Date v) (str (.toInstant ^java.util.Date v))
                                                             (instance? java.nio.file.Path v) (str v)
                                                             :else v)))))

(defn- pretty ^String [x] (str (json/write-str x :indent true :escape-slash false) "\n"))

(defn- args-of [ctx step op]
  (let [given (if-let [b (:body step)]
                (let [t (str/trim (c/text b))]
                  (if (str/blank? t) {} (let [m (json/read-str t)]
                                          (when-not (map? m) (throw (ex-info "the body of an op step is a JSON object of arguments" {:type :op/args})))
                                          m)))
                {})
        schema (:args (get ops/table op))
        defaults (cond-> {}
                   (contains? schema :ws) (assoc "ws" (:ws ctx))
                   (contains? schema :session) (assoc "session" (:session ctx))
                   (and (contains? schema :env) (:env ctx)) (assoc "env" (c/env-name ctx)))]
    (merge defaults given)))

(defn run [ctx step]
  (when (= :remote (some-> ctx :env :kind))
    (throw (ex-info (str "op steps run only against a local environment; " (c/env-name ctx) " is remote")
                    {:type :op/remote-env :hint "run this scenario with --env local"})))
  (let [op (:verb step)
        host (or (:host ctx) (throw (ex-info "op steps need the daemon (run the scenario through it)" {:type :op/no-daemon})))
        args (args-of ctx step op)
        req (c/utf8 (pretty args))
        cancels (atom [])
        line! (or (:line! ctx) (fn [_]))
        cancelled? (fn [] (or ((:cancelled? ctx)) (some-> (:attempt-cancelled? ctx) (apply []))))
        octx {:daemon (:daemon host)
              :emit! (fn [ev] (try (line! (event-text ev)) (catch Exception _ nil)) nil)
              :on-cancel! (fn [f] (swap! cancels conj f))
              :alive? (fn [] (not (cancelled?)))
              :cancelled? cancelled?}
        expect-error? (shell/names-fact? step ["ok" "error.type" "error.message"])
        res (try (refuse-remote-body! ctx op args)   ; an op error like any other: assertable (ok == false), else a step error
                 {:ok true :result (ops/call! op octx (into {} (map (fn [[k v]] [(keyword k) v])) args))}
                 (catch InterruptedException e (doseq [f @cancels] (try (f) (catch Exception _ nil))) (throw e))
                 (catch Exception e
                   (let [d (ex-data e)]
                     (if (and d (:type d) (not (instance? InterruptedException (.getCause e))))
                       {:ok false :error (assoc (ops/error-map e) :message (ex-message e))}
                       (throw e)))))
        body (->json-value (if (:ok res) (:result res) {:error (:error res)}))
        resp (c/utf8 (pretty body))]
    (if (and (not (:ok res)) (not expect-error?))
      (let [{:keys [type message hint]} (:error res)]
        (throw (ex-info (str (name type) ": " message)
                        {:type :op/error :request-bytes req :response-bytes resp
                         :hint (or hint "assert on ok or error.type if this error is expected")})))
      {:facts (cond-> {"ok" (str (:ok res))}
                (:error res) (assoc "error.type" (name (get-in res [:error :type]))
                                    "error.message" (str (get-in res [:error :message]))))
       :body-json body
       :request-bytes req :request-ext "json"
       :response-bytes resp :response-ext "json"})))

(def kind
  {:kind "op" :verbs (into (sorted-set) (remove excluded) (keys ops/table)) :target :none
   :directives []
   :facts [{:name "ok" :type :string} {:name "error.type" :type :string} {:name "error.message" :type :string}]
   :capture-roots #{"$"} :concurrent? false
   :default-timeout-ms 600000
   :run run})

(kinds/register! kind)
