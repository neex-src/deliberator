(ns deliberator.aws
  "Every AWS call goes through `(invoke! ctx env service op request)`.

  `ctx` keys: `:ws` (workspace name or map, required), `:session` (session key; default the
  workspace's current session; with none, the audit goes to the workspace-level `audit.log`),
  `:confirm` (a confirmation token), `:persist-secrets` (true keeps secret values in the persisted
  blobs of a remote call), `:redact-secrets` (true redacts the persisted blobs of secret-bearing calls
  on ANY environment, local included: how `secrets-from` fetches real values). `service` is aws-api's keyword (`:eventbridge` accepted for `:events`),
  `op` a keyword or string, `request` the aws-api request map.

  Order of events, always: the serialised request is persisted through `store/raw-put!`
  (`:source :aws`, EDN, byte arrays as `#bytes \"base64\"`), then for a `:remote` environment the
  class gate runs, then the call is made, then the raw response bytes (or, when no response
  arrived, the anomaly as EDN) are persisted, and only then is anything decoded. Meta of both blobs:
  `:source :aws :op :env :at :content-type :service :role (:request|:response|:anomaly) :class`.

  Result: the decoded aws-api response map, with metadata `{:deliberator.aws/raw {:request
  {:sha :path} :response {:sha :path :bytes} :status n} :deliberator.aws/elapsed-ms n}` so a kind can
  `store/link!` the blobs into its step directory. Failures are returned, not thrown (`error?`):
    {:type :aws/error :category <anomaly category, e.g. :not-found> :code \"AwsErrorCode\" :message
     :hint <derived from the environment: endpoint, profile, account> :service :op :env :raw {...}}
    {:type :confirm-required :token \"…\" :summary \"PutParameter /x on dev (account …)\" :expires inst}
  A refused confirmation (unknown, expired, already used, or for another request) is an
  `:aws/error` with `:category :confirmation`. Programmer errors (no `:ws`, invalid env) throw.

  Remote environments only. Every `:mutate` op (class table `deliberator.env.ops`; unknown ops are
  mutate) needs a confirmation whatever the record's `:read-only` says; that flag only changes the
  wording of the summary. A token is issued by the first call, is bound to the exact environment,
  service, op and request, is single use and valid for ten minutes (in memory, in this process: a
  daemon restart voids them). Every attempt appends one EDN line to `audit.log`: `:at :env :account
  :service :op :class :outcome (:ok|:error|:confirm-required|:refused) :confirmation (:used|nil)
  :bytes :status :request-sha :response-sha`. A response from `GetSecretValue`, or
  `GetParameter(s)`/`GetParametersByPath` made with decryption, is persisted with its value fields
  replaced by \"<redacted>\" unless `:persist-secrets`; so are the secret values in the requests of
  `PutParameter`, `CreateSecret`, `PutSecretValue`, `UpdateSecret`. The decoded map returned to the
  caller always keeps the real values. Local calls are neither gated, audited nor redacted."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.aws.client.api :as aws]
            [deliberator.env :as env]
            [deliberator.env.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.io InputStream)
           (java.nio.file Files OpenOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.security SecureRandom)
           (java.time Duration Instant)
           (java.util Base64)))

(def confirm-ttl (Duration/ofMinutes 10))

(def redacted "<redacted>")

;; serialisation ------------------------------------------------------------------------------

(defn- b64 ^String [^bytes b] (.encodeToString (Base64/getEncoder) b))

(defn- edn-ready [x]
  (walk/postwalk (fn [v]
                   (cond (bytes? v) (tagged-literal 'bytes (b64 v))
                         (instance? Instant v) (tagged-literal 'inst (str v))
                         (instance? InputStream v) (tagged-literal 'stream "<InputStream>")
                         :else v))
                 x))

(defn- pr-edn ^String [x]
  (binding [*print-namespace-maps* false *print-length* nil *print-level* nil]
    (pr-str (edn-ready x))))

(defn- canonical
  "Order-independent form of a request, for fingerprints."
  [x]
  (walk/postwalk (fn [v] (if (map? v) (vec (sort-by (comp pr-str key) v)) v)) (edn-ready x)))

(defn- without-run-id
  "The request minus the run id: an SQS send carries it as the `correlation` message attribute (also
  per entry of a batch), and it differs on every run. Everything else stays."
  [request]
  (let [drop-attr (fn [m] (if (map? (:MessageAttributes m)) (update m :MessageAttributes dissoc "correlation") m))]
    (cond-> (drop-attr request)
      (sequential? (:Entries request)) (update :Entries #(mapv drop-attr %)))))

(defn- fingerprint-request
  "What a confirmation token binds: the canonical request without the run id."
  [request]
  (canonical (without-run-id request)))

(defn- ->bytes ^bytes [^String s] (.getBytes s "UTF-8"))

;; redaction ----------------------------------------------------------------------------------

(def ^:private response-redactions
  "[service op] -> {:when-decrypt? bool :paths [[json-key ... :*]]}"
  {[:secretsmanager "GetSecretValue"] {:paths [["SecretString"] ["SecretBinary"]]}
   [:ssm "GetParameter"] {:decrypt? true :paths [["Parameter" "Value"]]}
   [:ssm "GetParameters"] {:decrypt? true :paths [["Parameters" :* "Value"]]}
   [:ssm "GetParametersByPath"] {:decrypt? true :paths [["Parameters" :* "Value"]]}})

(def ^:private request-redactions
  {[:ssm "PutParameter"] [[:Value]]
   [:secretsmanager "CreateSecret"] [[:SecretString] [:SecretBinary]]
   [:secretsmanager "PutSecretValue"] [[:SecretString] [:SecretBinary]]
   [:secretsmanager "UpdateSecret"] [[:SecretString] [:SecretBinary]]})

(defn- redact-path
  "Replaces the value at `path` (steps: key or :* for every element of a vector) when present."
  [x [step & more :as path]]
  (cond
    (empty? path) redacted
    (= step :*) (if (sequential? x) (mapv #(redact-path % more) x) x)
    (and (map? x) (contains? x step)) (update x step #(redact-path % more))
    :else x))

(defn- redact-response
  "Bytes to persist for a response of `[service op]`: the body with the secret fields replaced, or
  the original bytes when nothing secret applies."
  ^bytes [service op-name request ^bytes body]
  (let [{:keys [decrypt? paths]} (get response-redactions [service op-name])]
    (if (or (nil? paths) (and decrypt? (not (true? (:WithDecryption request)))))
      body
      (try
        (let [parsed (json/read-str (String. body "UTF-8"))
              out (reduce redact-path parsed paths)]
          (if (= out parsed) body (->bytes (json/write-str out))))
        (catch Exception _ (->bytes (json/write-str {"redacted" "response could not be parsed"})))))))

(defn- redact-request [service op-name request]
  (reduce (fn [r path] (if (get-in r path) (assoc-in r path redacted) r))
          request (get request-redactions [service op-name])))

;; confirmation tokens ------------------------------------------------------------------------

(defonce ^:private tokens (atom {}))

(defn- new-token []
  (let [b (byte-array 18)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- issue-token! [fp now]
  (let [t (new-token)
        exp (.plus ^Instant now confirm-ttl)]
    (swap! tokens (fn [m] (assoc (into {} (remove (fn [[_ v]] (.isAfter ^Instant now (:expires v))) m))
                                 t {:fp fp :expires exp})))
    {:token t :expires exp}))

(defn- consume-token!
  "True (and the token is gone) when `token` is live and was issued for `fp`."
  [token fp now]
  (let [[old _] (swap-vals! tokens
                            (fn [m] (let [v (get m token)]
                                      (cond (nil? v) m
                                            (.isAfter ^Instant now (:expires v)) (dissoc m token)
                                            (= fp (:fp v)) (dissoc m token)
                                            :else m))))
        v (get old token)]
    (boolean (and v (= fp (:fp v)) (not (.isAfter ^Instant now (:expires v)))))))

;; audit --------------------------------------------------------------------------------------

(def ^:private audit-lock (Object.))

(defn audit-path
  "Where the audit lines of `ctx` go."
  [ctx]
  (let [ws (:ws ctx)
        key (or (:session ctx) (:key (session/current ws)))]
    (if key
      (.resolve (session/dir ws key) "audit.log")
      (store/path ws "audit.log"))))

(defn- audit! [ctx line]
  (let [p (audit-path ctx)
        bs (->bytes (str (pr-edn line) "\n"))]
    (locking audit-lock
      (Files/createDirectories (.getParent p) (make-array FileAttribute 0))
      (Files/write p bs (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND])))))

(defn audit-lines
  "The audit lines of `ctx` as maps (`#inst` read as Instant)."
  [ctx]
  (let [p (audit-path ctx)]
    (if (Files/exists p (make-array java.nio.file.LinkOption 0))
      (mapv #(edn/read-string {:readers {'inst (fn [s] (Instant/parse s))}} %)
            (str/split-lines (slurp (.toFile p))))
      [])))

;; errors -------------------------------------------------------------------------------------

(defn error?
  "True for a map returned by `invoke!` that is a failure or a confirmation request."
  [r]
  (boolean (some-> r meta ::error)))

(defn- hint
  "A pointer derived from the environment record only: endpoint or profile, region, account."
  [env category]
  (let [{:keys [endpoint profile region account]} (:aws env)
        local? (= :local (:kind env))
        where (if local? (str "endpoint " endpoint) (str "profile " profile " (" region ")"
                                                         (when endpoint (str ", endpoint " endpoint))))]
    (case category
      (:unavailable :interrupted)
      (if local?
        (str "cannot reach " endpoint "; is the local runtime up? (bb runtime:up)")
        (str "cannot reach AWS through " where "; check the network"))
      :forbidden
      (if local?
        (str "the runtime at " endpoint " refused the request of environment " (:name env))
        (str "credentials of " where " were refused or have expired (account " account
             "); log in again: aws sso login --profile " profile))
      :not-found
      (str "not found via " where ", account " account "; check the targets of environment "
           (:name env))
      :incorrect
      (str "rejected by " where ", account " account)
      (if local?
        (str "failed talking to " endpoint " (environment " (:name env) ")")
        (str "failed talking to " where ", account " account
             "; if credentials are the cause: aws sso login --profile " profile)))))

(defn- throwable [r]
  (or (:cognitect.aws.client.impl/throwable r) (:cognitect.aws/throwable r)))

(defn- connection-failure? [^Throwable t]
  (boolean (some #(or (instance? java.net.ConnectException %)
                      (instance? java.net.UnknownHostException %)
                      (instance? java.net.SocketException %)
                      (instance? java.net.http.HttpConnectTimeoutException %)
                      (instance? java.nio.channels.UnresolvedAddressException %))
                 (take-while some? (iterate #(.getCause ^Throwable %) t)))))

(defn- ->error [env service op-name r raw]
  (let [t (throwable r)
        cat (if (and t (connection-failure? t))
              :unavailable
              (keyword (name (:cognitect.anomalies/category r))))]
    (with-meta
      (cond-> {:type :aws/error :category cat
               :code (or (:cognitect.aws.error/code r) (some-> (:__type r) str))
               ;; query-protocol services (iam, sts, sns...) nest it: <ErrorResponse><Error><Message>
               :message (or (:message r) (:Message r) (get-in r [:ErrorResponse :Error :Message]) (get-in r [:Error :Message])
                            (:cognitect.anomalies/message r)
                            (some-> t ex-message) (str cat))
               :hint (hint env cat)
               :service service :op op-name :env (:name env) :raw raw}
        (:cognitect.aws.error/code r) (assoc :code (:cognitect.aws.error/code r)))
      {::error true})))

;; invoke! ------------------------------------------------------------------------------------

(defn- put! [ws env bytes meta*]
  (store/raw-put! ws bytes (merge {:source :aws :env (:name env) :at (store/now)} meta*)))

(defn- item-ref [r] (select-keys r [:sha :path :bytes]))

(defn- summary-target [request]
  (some (fn [[k v]] (when (and (string? v) (re-find #"(Name|Url|Arn|Id)$" (name k))) v))
        request))

(defn- summary [env op-name request]
  (str op-name (when-let [t (summary-target request)] (str " " t))
       " on " (:name env) " (account " (get-in env [:aws :account]) ")"
       (when (env/read-only? env) " [environment is read-only]")))

(defn- body-bytes ^bytes [r]
  (let [b (some-> r meta :http-response :body)]
    (if (instance? InputStream b) (.readAllBytes ^InputStream b) (byte-array 0))))

(defn invoke!
  "See the namespace docstring."
  [ctx env service op request]
  (let [ws (or (:ws ctx) (throw (ex-info "ctx needs :ws" {:type :aws/bad-ctx})))
        env (env/validate env)
        api (ops/api-name service)
        op-name (name op)
        request (or request {})
        class (ops/op-class api op-name)
        remote? (= :remote (:kind env))
        redact? (or (:redact-secrets ctx) (and remote? (not (:persist-secrets ctx))))
        req-ref (put! ws env
                      (->bytes (pr-edn {:service api :op op-name :env (:name env)
                                        :request (if redact? (redact-request api op-name request) request)}))
                      {:op op-name :service api :role :request :class class
                       :content-type "application/edn"})
        fp (store/sha256 (->bytes (pr-str [(store/ws-name ws) (:name env) (get-in env [:aws :account])
                                           api op-name (fingerprint-request request)])))
        line (fn [m] (when remote?
                       (audit! ctx (merge {:at (store/now) :env (:name env) :account (get-in env [:aws :account])
                                           :service api :op op-name :class class
                                           :request-sha (:sha req-ref)}
                                          m))))
        run (fn [confirmation]
              (let [t0 (System/nanoTime)
                    r (aws/invoke (env/aws-client env api) {:op (keyword op-name) :request request})
                    elapsed (quot (- (System/nanoTime) t0) 1000000)
                    anomaly? (some? (:cognitect.anomalies/category r))
                    http (some-> r meta :http-response)
                    body (body-bytes r)
                    resp-ref (if (and (some? http) (:status http))
                               (put! ws env (if redact? (redact-response api op-name request body) body)
                                     {:op op-name :service api :role :response :class class
                                      :status (:status http)
                                      :content-type (or (get-in http [:headers "content-type"])
                                                        "application/octet-stream")})
                               (put! ws env (->bytes (pr-edn (dissoc r :cognitect.aws.client.impl/throwable :cognitect.aws/throwable)))
                                     {:op op-name :service api :role :anomaly :class class
                                      :content-type "application/edn"}))
                    raw {:request (item-ref req-ref) :response (item-ref resp-ref)
                         :status (:status http)}]
                (line (cond-> {:outcome (if anomaly? :error :ok) :confirmation confirmation
                               :bytes (alength body) :status (:status http)
                               :response-sha (:sha resp-ref)}
                        anomaly? (assoc :category (keyword (name (:cognitect.anomalies/category r))))))
                (if anomaly?
                  (assoc (->error env api op-name r raw) :elapsed-ms elapsed)
                  (with-meta r {::raw raw ::elapsed-ms elapsed}))))]
    (if (and remote? (= class :mutate))
      (if-let [token (:confirm ctx)]
        (if (consume-token! token fp (store/now))
          (run :used)
          (do (line {:outcome :refused :confirmation nil})
              (with-meta {:type :aws/error :category :confirmation :service api :op op-name :env (:name env)
                          :message "confirmation token is unknown, expired, already used, or was issued for a different request"
                          :hint (str "repeat the call without a token to get a new one for " (summary env op-name request))}
                {::error true})))
        (let [{:keys [token expires]} (issue-token! fp (store/now))]
          (line {:outcome :confirm-required :confirmation nil})
          (with-meta {:type :confirm-required :token token :expires expires
                      :summary (summary env op-name request)}
            {::error true})))
      (run nil))))

(defn raw-refs
  "The persisted request and response blobs of a result of `invoke!` (success or error)."
  [r]
  (if (error? r) (:raw r) (some-> r meta ::raw)))

(defn check
  "Asks the environment who we are (`sts:GetCallerIdentity`) and compares the account with the
  record's. `{:ok? bool :account \"…\" :expected \"…\"}` or `{:ok? false :error <error map>}`."
  [ctx env]
  (let [r (invoke! ctx env :sts :GetCallerIdentity {})
        expected (get-in env [:aws :account])]
    (if (error? r)
      {:ok? false :expected expected :error r}
      {:ok? (= (:Account r) expected) :account (:Account r) :expected expected})))
