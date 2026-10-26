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
  mutate) is gated. On a record with `:read-only true` (the default) it is refused flat: `{:type
  :aws/error :category :read-only}`, no token, nothing runs. With `:read-only false` it needs a
  confirmation: a token is issued by the first call, is bound to the exact environment, service, op
  and request, is single use and valid for ten minutes (in memory, in this process: a daemon restart
  voids them); `gate!` is the same gate for a step whose one mutation has no request yet, and hands
  back a 60 s single-use pass instead. Every attempt appends one EDN line to `audit.log`: `:at :env
  :account :service :op :class :outcome (:ok|:error|:confirm-required|:refused|:confirmed) :confirmation
  (:used|:pass|nil) [:reason (:read-only|:pass)] :bytes :status :request-sha :response-sha`.
  Redaction (remote unless `:persist-secrets`; any environment with `:redact-secrets`): the persisted
  request and response have their secret members replaced by \"<redacted>\" (`&lt;redacted&gt;` in an XML
  body): the table rows of `response-redactions`/`request-redactions` (Secrets Manager and SSM values,
  KMS plaintext, Cognito tokens) and, on every member of every redacted blob, the name rule
  `ops/secret-member?` (`SecretAccessKey`, `SessionToken`, `Plaintext`, `Password`, `*Secret`, ...: what
  STS, IAM and anything not yet named return). The decoded map returned to the caller always keeps
  the real values. Local calls are neither gated, audited nor redacted."
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

(defn- without-detail-correlation
  "An event's `Detail` as parsed JSON without its `correlation` member (the step adds the run's one, see
  `steps/eventbridge`); text that is not a JSON object stays as it is."
  [detail]
  (let [m (try (json/read-str detail) (catch Exception _ nil))]
    (if (map? m) (dissoc m "correlation") detail)))

(defn- without-run-id
  "The request minus the run id: an SQS send carries it as the `correlation` message attribute (also
  per entry of a batch) and an EventBridge entry as `detail.correlation`; it differs on every run.
  Everything else stays."
  [request]
  (let [drop-id (fn [m] (cond-> m
                          (map? (:MessageAttributes m)) (update :MessageAttributes dissoc "correlation")
                          (string? (:Detail m)) (update :Detail without-detail-correlation)))]
    (cond-> (drop-id request)
      (sequential? (:Entries request)) (update :Entries #(mapv drop-id %)))))

(defn- fingerprint-request
  "What a confirmation token binds: the canonical request without the run id."
  [request]
  (canonical (without-run-id request)))

(defn- ->bytes ^bytes [^String s] (.getBytes s "UTF-8"))

;; redaction ----------------------------------------------------------------------------------

(def ^:private response-redactions
  "[service op] -> {:decrypt? bool :paths [[json-key ... :*]]}: the precise rows, for members whose name does not give
  them away (`Value` of a decrypted parameter) or that the row makes explicit. The sts and iam rows of
  `ops/secret-bearing` need no path: they answer in XML and their `SecretAccessKey`/`SessionToken`/`ServicePassword`
  fall to the name rule (`ops/secret-member?`), which runs on every redacted blob, JSON or XML."
  {[:secretsmanager "GetSecretValue"] {:paths [["SecretString"] ["SecretBinary"]]}
   [:ssm "GetParameter"] {:decrypt? true :paths [["Parameter" "Value"]]}
   [:ssm "GetParameters"] {:decrypt? true :paths [["Parameters" :* "Value"]]}
   [:ssm "GetParametersByPath"] {:decrypt? true :paths [["Parameters" :* "Value"]]}
   [:kms "Decrypt"] {:paths [["Plaintext"]]}
   [:kms "GenerateDataKey"] {:paths [["Plaintext"]]}
   [:kms "GenerateDataKeyPair"] {:paths [["PrivateKeyPlaintext"]]}
   [:kms "GenerateRandom"] {:paths [["Plaintext"]]}
   [:kms "DeriveSharedSecret"] {:paths [["SharedSecret"]]}
   [:cognito-idp "InitiateAuth"] {:paths [["AuthenticationResult" "AccessToken"] ["AuthenticationResult" "IdToken"]
                                          ["AuthenticationResult" "RefreshToken"]]}
   [:cognito-idp "AdminInitiateAuth"] {:paths [["AuthenticationResult" "AccessToken"] ["AuthenticationResult" "IdToken"]
                                               ["AuthenticationResult" "RefreshToken"]]}
   [:cognito-idp "RespondToAuthChallenge"] {:paths [["AuthenticationResult" "AccessToken"] ["AuthenticationResult" "IdToken"]
                                                    ["AuthenticationResult" "RefreshToken"]]}
   [:cognito-idp "AdminRespondToAuthChallenge"] {:paths [["AuthenticationResult" "AccessToken"]
                                                         ["AuthenticationResult" "IdToken"]
                                                         ["AuthenticationResult" "RefreshToken"]]}
   [:cognito-idp "GetTokensFromRefreshToken"] {:paths [["AuthenticationResult" "AccessToken"]
                                                       ["AuthenticationResult" "IdToken"]
                                                       ["AuthenticationResult" "RefreshToken"]]}})

(def ^:private request-redactions
  "[service op] -> paths of the request map (keywords) that carry a secret; the name rule covers the rest
  (`Plaintext` of kms Encrypt, `Password` of iam CreateLoginProfile and cognito AdminSetUserPassword, ...)."
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

(defn- redact-members
  "Every value of `x` (nested maps with string or keyword keys) whose member name is a secret by the name rule."
  [x]
  (walk/postwalk (fn [v]
                   (if (map? v)
                     (reduce-kv (fn [m k val] (assoc m k (if (and (or (string? k) (keyword? k)) (ops/secret-member? k)) redacted val)))
                                v v)
                     v))
                 x))

(def ^:private xml-element #"(?s)<([A-Za-z_][\w.:-]*)>([^<]*)</\1>")

(def xml-redacted
  "What a redacted XML text node holds: `<redacted>` written as XML text."
  "&lt;redacted&gt;")

(defn- redact-xml
  "A query-protocol body (sts, iam, sns, ...) with the text of every element named by the name rule replaced."
  ^String [^String s]
  (str/replace s xml-element (fn [[whole tag _]] (if (ops/secret-member? tag) (str "<" tag ">" xml-redacted "</" tag ">") whole))))

(defn redact-body
  "The bytes to persist for a response `body` of `[service op]` when the call is redacted: a JSON body is parsed, the
  table row's paths (when the row applies: a decrypting read only with `WithDecryption`) and the name rule are applied
  and it is re-serialised when something changed; an XML body gets its secret elements replaced; any other body is
  kept, unless a table row applies and it cannot be parsed: then a note replaces it."
  ^bytes [service op-name request ^bytes body]
  (let [{:keys [decrypt? paths]} (get response-redactions [(ops/api-name service) (name op-name)])
        paths (when-not (and decrypt? (not (true? (:WithDecryption request)))) paths)
        s (String. body "UTF-8")
        t (str/triml s)
        note #(->bytes (json/write-str {"redacted" "response could not be parsed"}))]
    (cond
      (or (str/starts-with? t "{") (str/starts-with? t "["))
      (try (let [parsed (json/read-str t)
                 out (redact-members (reduce redact-path parsed paths))]
             (if (= out parsed) body (->bytes (json/write-str out))))
           (catch Exception _ (if paths (note) body)))
      (str/starts-with? t "<")
      (let [out (redact-xml s)] (if (= out s) body (->bytes out)))
      :else (if paths (note) body))))

(defn redact-request
  "The request map with its secret members replaced: the table row's paths, then the name rule."
  [service op-name request]
  (redact-members
   (reduce (fn [r path] (if (get-in r path) (assoc-in r path redacted) r))
           request (get request-redactions [(ops/api-name service) (name op-name)]))))

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

;; passes: what `gate!` hands a step whose one mutation was confirmed before its request existed ----------------

(def pass-ttl (Duration/ofSeconds 60))

(defonce ^:private passes (atom {}))

(defn- issue-pass! [key now]
  (let [p (new-token)]
    (swap! passes (fn [m] (assoc (into {} (remove (fn [[_ v]] (.isAfter ^Instant now (:expires v))) m))
                                 p {:key key :expires (.plus ^Instant now pass-ttl)})))
    p))

(defn- consume-pass!
  "True (and the pass is gone) when `pass` is live and was issued for `key` ([ws env account service op]). Like a
  token, a pass presented for another operation is refused and stays: it dies by its use or its expiry only."
  [pass key now]
  (let [[old _] (swap-vals! passes (fn [m] (let [v (get m pass)]
                                             (cond (nil? v) m
                                                   (.isAfter ^Instant now (:expires v)) (dissoc m pass)
                                                   (= key (:key v)) (dissoc m pass)
                                                   :else m))))
        v (get old pass)]
    (boolean (and v (= key (:key v)) (not (.isAfter ^Instant now (:expires v)))))))

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

(def ^:private credential-codes
  "Error codes that mean the credentials themselves are bad or gone: log in again or fix the profile's keys."
  #{"ExpiredToken" "ExpiredTokenException" "InvalidClientTokenId" "UnrecognizedClientException" "InvalidAccessKeyId"
    "SignatureDoesNotMatch" "TokenRefreshRequired" "MissingAuthenticationToken" "InvalidIdentityToken"
    "AuthFailure" "InvalidSignatureException" "IncompleteSignature"})

(def ^:private policy-codes
  "Error codes that mean the caller is authenticated and an IAM policy says no."
  #{"AccessDenied" "AccessDeniedException" "UnauthorizedOperation" "AuthorizationError" "NotAuthorized"
    "NotAuthorizedException" "UnauthorizedException" "AuthorizationErrorException"})

(defn hint
  "A pointer derived from the environment record (endpoint or profile, region, account) and the failure: the error
  code first (expired or wrong keys: log in again; an IAM policy denial: the policy, not the login), then the anomaly
  category."
  [env category code service op]
  (let [{:keys [endpoint profile region account]} (:aws env)
        local? (= :local (:kind env))
        code (some-> code str)
        where (if local? (str "endpoint " endpoint) (str "profile " profile " (" region ")"
                                                         (when endpoint (str ", endpoint " endpoint))))]
    (cond
      (and (not local?) (contains? credential-codes code))
      (str "credentials of " where " were refused or have expired (" code ", account " account
           "); log in again (aws sso login --profile " profile ") or fix the profile's keys")
      (and (not local?) (contains? policy-codes code))
      (str "the IAM policy of " where " (account " account ") denies " (name service) ":" (name op)
           " (" code "): a permission, not an expired login")
      :else
      (case category
        (:unavailable :interrupted)
        (if local?
          (str "cannot reach " endpoint "; is the local runtime up? (bb runtime:up)")
          (str "cannot reach AWS through " where "; check the network"))
        :forbidden
        (if local?
          (str "the runtime at " endpoint " refused the request of environment " (:name env))
          (str (name service) ":" (name op) " was refused by " where " (account " account
               (when code (str ", " code)) "); an IAM policy or expired credentials: aws sso login --profile " profile
               " if the latter"))
        :not-found
        (str "not found via " where ", account " account "; check the targets of environment "
             (:name env))
        :incorrect
        (str "rejected by " where ", account " account)
        (if local?
          (str "failed talking to " endpoint " (environment " (:name env) ")")
          (str "failed talking to " where ", account " account
               "; if credentials are the cause: aws sso login --profile " profile))))))

(defn- throwable [r]
  (or (:cognitect.aws.client.impl/throwable r) (:cognitect.aws/throwable r)))

(defn- connection-failure? [^Throwable t]
  (boolean (some #(or (instance? java.net.ConnectException %)
                      (instance? java.net.UnknownHostException %)
                      (instance? java.net.SocketException %)
                      (instance? java.net.http.HttpConnectTimeoutException %)
                      (instance? java.nio.channels.UnresolvedAddressException %))
                 (take-while some? (iterate #(.getCause ^Throwable %) t)))))

(defn- credential-bridge-error
  "When the call failed because the credentials could not be fetched (`Unable to fetch credentials`) and the CLI bridge
  recorded why: `{:message :hint}` naming the CLI's own words. The hint always says `aws sso login` (R18): an expired or
  never-made SSO login is the usual cause, and a profile that names an sso_session the config lacks needs its config fixed
  first."
  [env r]
  (when (and (= :remote (:kind env))
             (str/starts-with? (str (:cognitect.anomalies/message r)) "Unable to fetch credentials"))
    (when-let [{:keys [message]} (env/credential-failure (get-in env [:aws :profile]))]
      {:message (str "credentials of profile " (get-in env [:aws :profile]) " could not be fetched: " message)
       :hint (str "the AWS CLI could not export credentials for profile " (get-in env [:aws :profile])
                  "; if its SSO login is gone or expired, log in again: aws sso login --profile " (get-in env [:aws :profile])
                  ". An sso_session or role_arn profile must also exist in the AWS config the daemon sees (AWS_CONFIG_FILE, "
                  "~/.aws/config); a profile of static keys needs its keys in the shared credentials file")})))

(defn- ->error [env service op-name r raw]
  (let [t (throwable r)
        cat (if (and t (connection-failure? t))
              :unavailable
              (keyword (name (:cognitect.anomalies/category r))))
        code (or (:cognitect.aws.error/code r) (some-> (:__type r) str)
                 ;; query-protocol services (iam, sts, sns...) nest it: <ErrorResponse><Error><Code>
                 (get-in r [:ErrorResponse :Error :Code]) (get-in r [:Error :Code]))
        code (some-> code str (str/replace #"^.*#" ""))]
    (with-meta
      {:type :aws/error :category cat
       :code code
       :message (or (:message (credential-bridge-error env r))
                    (:message r) (:Message r) (get-in r [:ErrorResponse :Error :Message]) (get-in r [:Error :Message])
                    (:cognitect.anomalies/message r)
                    (some-> t ex-message) (str cat))
       :hint (or (:hint (credential-bridge-error env r)) (hint env cat code service op-name))
       :message (or (:message r) (:Message r) (get-in r [:ErrorResponse :Error :Message]) (get-in r [:Error :Message])
                    (:cognitect.anomalies/message r)
                    (some-> t ex-message) (str cat))
       :hint (hint env cat code service op-name)
       :service service :op op-name :env (:name env) :raw raw}
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
       " on " (:name env) " (account " (get-in env [:aws :account]) ")"))

(defn- body-bytes ^bytes [r]
  (let [b (some-> r meta :http-response :body)]
    (if (instance? InputStream b) (.readAllBytes ^InputStream b) (byte-array 0))))

(defn- read-only-refusal
  "The error of a mutation on a read-only remote environment: no token, nothing runs."
  [env api op-name]
  (with-meta {:type :aws/error :category :read-only :service api :op op-name :env (:name env)
              :message (str op-name " refused: environment " (:name env) " is read-only")
              :hint (str "environments/" (:name env) ".edn has :read-only true (the default of a remote record); "
                         "set :read-only false to allow mutations, each then needs a confirmation")}
    {::error true}))

(defn- token-refusal [env api op-name request]
  (with-meta {:type :aws/error :category :confirmation :service api :op op-name :env (:name env)
              :message "confirmation token is unknown, expired, already used, or was issued for a different request"
              :hint (str "repeat the call without a token to get a new one for " (summary env op-name request))}
    {::error true}))

(defn- fingerprint [ws env api op-name request]
  (store/sha256 (->bytes (pr-str [(store/ws-name ws) (:name env) (get-in env [:aws :account])
                                  api op-name (fingerprint-request request)]))))

(defn- pass-key [ws env api op-name]
  [(store/ws-name ws) (:name env) (get-in env [:aws :account]) api op-name])

(defn- gate
  "The remote `:mutate` gate for one call: `[:refused error]`, `[:confirm-required error]` or `[:run confirmation]`
  (the confirmation to audit: `:used` for a token, `:pass` for a pass of `gate!`). `line` audits."
  [ctx env api op-name request fp line]
  (cond
    (env/read-only? env)
    (do (line {:outcome :refused :confirmation nil :reason :read-only})
        [:refused (read-only-refusal env api op-name)])
    (:pass ctx)
    (if (consume-pass! (:pass ctx) (pass-key (:ws ctx) env api op-name) (store/now))
      [:run :pass]
      (do (line {:outcome :refused :confirmation nil :reason :pass})
          [:refused (token-refusal env api op-name request)]))
    (:confirm ctx)
    (if (consume-token! (:confirm ctx) fp (store/now))
      [:run :used]
      (do (line {:outcome :refused :confirmation nil})
          [:refused (token-refusal env api op-name request)]))
    :else
    (let [{:keys [token expires]} (issue-token! fp (store/now))]
      (line {:outcome :confirm-required :confirmation nil})
      [:confirm-required (with-meta {:type :confirm-required :token token :expires expires
                                     :summary (summary env op-name request)}
                           {::error true})])))

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
        fp (fingerprint ws env api op-name request)
        line (fn [m] (when remote?
                       (audit! ctx (merge {:at (store/now) :env (:name env) :account (get-in env [:aws :account])
                                           :service api :op op-name :class class
                                           :request-sha (:sha req-ref)}
                                          m))))
        run (fn [confirmation]
              (let [t0 (System/nanoTime)
                    r (env/with-client ws env api #(aws/invoke % {:op (keyword op-name) :request request}))
                    elapsed (quot (- (System/nanoTime) t0) 1000000)
                    anomaly? (some? (:cognitect.anomalies/category r))
                    http (some-> r meta :http-response)
                    body (body-bytes r)
                    resp-ref (if (and (some? http) (:status http))
                               (put! ws env (if redact? (redact-body api op-name request body) body)
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
      (let [[verdict x] (gate ctx env api op-name request fp line)]
        (if (= :run verdict) (run x) x))
      (run nil))))

(defn gate!
  "The confirmation gate alone, for a step that is one mutation whose request does not exist before it runs (`sqs
  receive @delete`: the receipt handles come from the receive, so a token bound to the delete request could never be
  re-presented). `summary-request` stands for the mutation (`{:QueueUrl url}`); it is persisted and audited like a
  request. Local environment: nil, nothing is gated. Remote: read-only -> the `:aws/error` of a refused mutation; no
  token -> `{:type :confirm-required ...}` (the step shows it as any confirmation); the matching token -> consumed, and
  the result is `{:pass p}`: a single-use pass, valid for `pass-ttl` (60 s), bound to [workspace env account service
  op], that `invoke!` accepts as ctx `:pass` in place of a token for that one call (audited `:confirmation :pass`)."
  [ctx env service op summary-request]
  (let [ws (or (:ws ctx) (throw (ex-info "ctx needs :ws" {:type :aws/bad-ctx})))
        env (env/validate env)
        api (ops/api-name service)
        op-name (name op)
        request (or summary-request {})]
    (when (= :remote (:kind env))
      (let [class (ops/op-class api op-name)
            req-ref (put! ws env
                          (->bytes (pr-edn {:service api :op op-name :env (:name env) :request request :gate true}))
                          {:op op-name :service api :role :request :class class :gate true
                           :content-type "application/edn"})
            fp (fingerprint ws env api op-name request)
            line (fn [m] (audit! ctx (merge {:at (store/now) :env (:name env) :account (get-in env [:aws :account])
                                             :service api :op op-name :class class :gate true
                                             :request-sha (:sha req-ref)}
                                            m)))
            [verdict x] (gate (dissoc ctx :pass) env api op-name request fp line)]
        (if (= :run verdict)
          (do (line {:outcome :confirmed :confirmation x})
              {:pass (issue-pass! (pass-key ws env api op-name) (store/now))})
          x)))))

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
