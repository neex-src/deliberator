(ns deliberator.env
  "Environments: where the core talks to. `(load ws name) -> env` reads
  `<ws>/environments/<name>.edn` (the default `local.edn` is written on first use of \"local\") and
  validates it against the two shapes of `docs/architecture.md` section 4; invalid or mixed shapes
  throw `{:type :env/invalid :problems [...]}`, an unknown name `{:type :env/not-found :known [...]}`.

    :local   {:name :kind :aws {:endpoint :region :account :credentials {:access-key-id
             :secret-access-key}} :targets}   no `:profile`, no `:read-only`
    :remote  {:name :kind :read-only (default true) :aws {:profile :region :account [:endpoint]}
             :targets}                        no `:credentials`

  `(aws-client env service)` is an aws-api client memoised per `[record service]`. A `:local` client
  is built from the record's `:aws` only (endpoint override, basic credentials, explicit region):
  it never consults `~/.aws`, `AWS_PROFILE`, `AWS_*` keys or `AWS_ENDPOINT_URL`. A `:remote` client
  takes its credentials from `:profile`: first the static keys of the profile in the shared
  credentials file (aws-api's own provider), then, for SSO, assume-role and credential_process
  profiles that provider cannot read, `aws configure export-credentials --profile P` (refreshed
  before expiry;). `:aws :endpoint` is allowed on a remote record so tests can point a
  remote-shaped environment at the local emulator; a real remote record leaves it out.

  `(target env key) -> value` resolves `service/a`, `queue/inbox`, ... (keyword or string), throwing
  `{:type :env/unknown-target :known [...]}`. `(op-class service op)` is the class table of
  `deliberator.env.ops`, re-exported here. `(read-only? env)` is the policy flag of a remote record:
  callers (daemon, kinds, agents) may refuse mutation on it, and the confirm summary says so; inside
  `deliberator.aws/invoke!` it never replaces or relaxes the confirmation, which gates every remote
  `:mutate` whatever the flag says."
  (:refer-clojure :exclude [load])
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [deliberator.env.ops :as ops]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.net URI)
           (java.time Duration Instant)))

;; schema -------------------------------------------------------------------------------------

(defn local-for
  "The environment written on first use of \"local\" in workspace `ws`: the runtime of the local Floci,
  fake account, and the names of the estate (`estate/`): services on 8081/8082, the container logs of
  the workspace's `deliberator-<ws>-service-*`, Postgres `deliberator`. A workspace whose runtime.edn
  moved the host ports gets its copy fixed by `runtime/sync-local-env!` (endpoint, db/main, log targets)."
  [ws]
  {:name "local" :kind :local
   :description "Local runtime and the estate."
   :aws {:endpoint "http://localhost:4566" :region "eu-central-1" :account "000000000000"
         :credentials {:access-key-id "test" :secret-access-key "test"}}
   :targets {:service/a "http://localhost:8081"
             :service/b "http://localhost:8082"
             :queue/inbox "estate-inbox"
             :queue/b-events "estate-b-events"
             :table/messages "estate-messages"
             :bus/main "estate-bus"
             :log/service-a {:container (store/container-name ws "service-a")}
             :log/service-b {:container (store/container-name ws "service-b")}
             :log/logger {:cloudwatch "/aws/lambda/estate-logger"}
             :db/main "jdbc:postgresql://localhost:5432/deliberator"
             :function/logger "estate-logger"}})

(def default-local
  "`(local-for \"default\")`: the record of the default workspace; tests build on it."
  (local-for "default"))

(def ^:private top-keys #{:name :kind :description :read-only :aws :targets :topology})
(def ^:private topology-keys #{:mode :url :container-url :log})
(def ^:private local-aws-keys #{:endpoint :region :account :credentials})
(def ^:private remote-aws-keys #{:profile :region :account :endpoint})

(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))

(defn- unknown-keys [m allowed where]
  (when-let [ks (seq (remove allowed (keys m)))]
    [(str where " has unknown keys " (pr-str (vec (sort-by str ks))) ", allowed " (pr-str (vec (sort allowed))))]))

(defn- topology-problems [t]
  (if-not (map? t)
    [":topology must be a map of service name -> {:mode :external|:off [:url] [:log {:container}]}"]
    (vec
     (for [[n e] t
           p (cond
               (not (nonblank? n)) [(str ":topology key " (pr-str n) " must be a service name (string)")]
               (not (map? e)) [(str ":topology " n " must be a map")]
               :else
               (concat
                (unknown-keys e topology-keys (str ":topology " n))
                (case (:mode e)
                  :external (concat
                             (when-not (and (string? (:url e)) (re-matches #"https?://[^/\s]+(:\d+)?(/\S*)?" (:url e)))
                               [(str ":topology " n " :external needs :url like http://host:port")])
                             (when (and (contains? e :container-url)
                                        (not (and (string? (:container-url e)) (re-matches #"https?://[^/\s]+(:\d+)?(/\S*)?" (:container-url e)))))
                               [(str ":topology " n " :container-url must look like http://host:port")]))
                  :off (when (or (contains? e :url) (contains? e :container-url)) [(str ":topology " n " :off takes no :url")])
                  [(str ":topology " n " :mode must be :external or :off (absent means local)")])
                (when (and (contains? e :log) (not (and (map? (:log e)) (nonblank? (get-in e [:log :container])))))
                  [(str ":topology " n " :log must be {:container \"name\"}")])))]
       p))))

(defn problems
  "The list of problems of the raw environment record `m` (empty when valid)."
  [m]
  (if-not (map? m)
    ["an environment must be a map"]
    (let [{:keys [kind aws targets]} m
          aws? (map? aws)]
      (vec
       (concat
        (unknown-keys m top-keys "environment")
        (when-not (nonblank? (:name m)) [":name must be a non-blank string"])
        (when-not (contains? #{:local :remote} kind) [":kind must be :local or :remote"])
        (when-not aws? [":aws must be a map"])
        (when (and aws? (not (nonblank? (:region aws)))) [":aws :region must be a non-blank string"])
        (when (and aws? (not (and (string? (:account aws)) (re-matches #"\d{12}" (:account aws)))))
          [":aws :account must be a 12-digit string"])
        (when (contains? m :topology) (topology-problems (:topology m)))
        (when-not (map? targets) [":targets must be a map"])
        (when (map? targets)
          (for [[k v] targets
                :when (or (not (and (keyword? k) (namespace k))) (nil? v))]
            (str ":targets key " (pr-str k) " must be a namespaced keyword (service/a) with a value")))
        (when (and aws? (= kind :local))
          (concat
           (unknown-keys aws local-aws-keys ":local :aws")
           (when (contains? aws :profile)
             [":local environments cannot name a :profile (their clients are built from the record only)"])
           (when (contains? m :read-only) [":read-only belongs to :remote environments"])
           (when-not (nonblank? (:endpoint aws)) [":local :aws :endpoint is required"])
           (when (and (nonblank? (:endpoint aws))
                      (not (re-matches #"https?://[^/\s]+(:\d+)?/?" (:endpoint aws))))
             [":aws :endpoint must look like http://host:port"])
           (let [c (:credentials aws)]
             (when-not (and (map? c) (nonblank? (:access-key-id c)) (nonblank? (:secret-access-key c)))
               [":local :aws :credentials needs non-blank :access-key-id and :secret-access-key"]))))
        (when (and aws? (= kind :remote))
          (concat
           (unknown-keys aws remote-aws-keys ":remote :aws")
           (when (contains? aws :credentials)
             [":remote environments take credentials from :profile, not :credentials"])
           (when-not (nonblank? (:profile aws)) [":remote :aws :profile is required"])
           (when (and (contains? m :read-only) (not (boolean? (:read-only m))))
             [":read-only must be a boolean"]))))))))

(defn validate
  "Returns the normalised env (remote `:read-only` defaults to true) or throws `:env/invalid`."
  [m]
  (let [ps (problems m)]
    (when (seq ps)
      (throw (ex-info (str "invalid environment: " (str/join "; " ps))
                      {:type :env/invalid :problems ps})))
    (cond-> m (= :remote (:kind m)) (update :read-only #(if (nil? %) true %)))))

(defn read-only? [env] (boolean (:read-only env)))

;; loading ------------------------------------------------------------------------------------

(defn- env-file [ws name] (store/path ws "environments" (str (store/safe-name name) ".edn")))

(defn names
  "Names of the environment files of the workspace, sorted."
  [ws]
  (let [d (.toFile (store/path ws "environments"))]
    (vec (sort (for [^File f (or (.listFiles d) [])
                     :let [n (.getName f)]
                     :when (str/ends-with? n ".edn")]
                 (subs n 0 (- (count n) 4)))))))

(defn load
  "The validated environment `name` of workspace `ws`; writes the default `local` on first use."
  [ws name]
  (let [n (store/safe-name name)
        f (env-file ws n)
        raw (or (store/read-edn f)
                (when (= n "local") (let [e (local-for ws)] (store/write-edn! f e) e))
                (throw (ex-info (str "no such environment " n ", known: " (pr-str (names ws)))
                                {:type :env/not-found :name n :known (names ws)})))
        env (validate raw)]
    (when (not= n (:name env))
      (throw (ex-info (str "environment file " n ".edn names itself " (pr-str (:name env)))
                      {:type :env/invalid :problems [(str ":name " (pr-str (:name env)) " differs from the file name " n)]})))
    env))

(defn save!
  "Validates and writes the environment record; returns it."
  [ws env]
  (let [env (validate env)]
    (store/write-edn! (env-file ws (:name env)) env)
    env))

;; targets ------------------------------------------------------------------------------------

(defn service-entry
  "The topology entry `{:mode :external|:off …}` of service `n` in `env`, nil when it is local."
  [env n] (get-in env [:topology (str n)]))

(defn service-mode
  "`:local` (no entry), `:external` or `:off`."
  [env n] (or (:mode (service-entry env n)) :local))

(defn read-topology
  "The `:topology` of environment `name` read raw from its file ({} when the file or the key is absent);
  never creates the default environment and never validates (the runtime asks on every status)."
  [ws name]
  (or (:topology (store/read-edn (env-file ws name))) {}))

(defn- topology-target
  "The topology's answer for `k` (`service/<n>` url or off error; `log/service-<n>` of an external instance
  that names its log container), or nil when the topology has nothing to say (local)."
  [env k]
  (let [n (name k)]
    (case (namespace k)
      "service" (let [e (service-entry env n)]
                  (case (:mode e)
                    :external (:url e)
                    :off (throw (ex-info (str "service " n " is off in environment " (:name env))
                                         {:type :topology/service-off :service n :env (:name env)
                                          :hint (str "dlb topology set " (:name env) " " n " local   (or external URL)")}))
                    nil))
      "log" (when (str/starts-with? n "service-")
              (let [e (service-entry env (subs n 8))]
                (when (and (= :external (:mode e)) (:log e)) (:log e))))
      nil)))

(defn target
  "Resolves a target key (`:queue/inbox` or \"queue/inbox\") in the environment. `service/<n>` follows the
  environment's topology: external -> its url, off -> throws `{:type :topology/service-off}`, local -> `:targets`."
  [env key]
  (let [k (if (keyword? key) key (keyword (str key)))
        ts (:targets env)
        t (topology-target env k)]
    (if (some? t)
      t
      (if (contains? ts k)
        (get ts k)
        (let [known (vec (sort-by str (keys ts)))]
          (throw (ex-info (str "unknown target " (pr-str k) " in environment " (:name env)
                               ", known: " (str/join ", " (map #(subs (str %) 1) known)))
                          {:type :env/unknown-target :target k :env (:name env) :known known})))))))

(defn op-class
  "`:read` or `:mutate`; see `deliberator.env.ops`."
  [service op]
  (ops/op-class service op))

;; clients ------------------------------------------------------------------------------------

(def ^:dynamic *credentials-file*
  "Shared credentials file for `:remote` profiles; nil means aws-api's resolution
  (`AWS_SHARED_CREDENTIALS_FILE`, then `~/.aws/credentials`). Tests bind it."
  nil)

(def ^:dynamic *aws-cli*
  "Command used for the SSO fallback; tests bind it to a stub script."
  "aws")

(defn- endpoint-override [^String url]
  (let [u (URI. url)
        proto (keyword (.getScheme u))]
    {:protocol proto :hostname (.getHost u)
     :port (if (pos? (.getPort u)) (.getPort u) (if (= proto :https) 443 80))}))

(defn- cli-credentials
  "Credentials from `aws configure export-credentials --profile P --format process`, with a ttl
  that refreshes five minutes before expiry; nil when the CLI is missing or refuses."
  [cli profile]
  (try
    (let [{:keys [exit out]} (shell/sh cli "configure" "export-credentials" "--profile" profile
                                       "--format" "process")]
      (when (zero? exit)
        (let [m (json/read-str out)
              exp (some-> (get m "Expiration") Instant/parse)
              ttl (some-> exp (as-> e (- (.getSeconds (Duration/between (Instant/now) e)) 300)) (max 30))]
          (cond-> {:aws/access-key-id (get m "AccessKeyId")
                   :aws/secret-access-key (get m "SecretAccessKey")
                   :aws/session-token (get m "SessionToken")}
            ttl (assoc ::creds/ttl ttl)))))
    (catch Exception _ nil)))

(defn- profile-provider [profile file cli]
  (let [static (if file
                 (creds/profile-credentials-provider profile (File. (str file)))
                 (creds/profile-credentials-provider profile))]
    (creds/cached-credentials-with-auto-refresh
     (reify creds/CredentialsProvider
       (fetch [_]
         (or (creds/fetch static)
             (cli-credentials cli profile)))))))

(defonce ^:private clients (atom {}))

(defn- build-client [{:keys [kind aws]} service]
  (let [base {:api (ops/api-name service) :region (:region aws)}]
    (aws/client
     (case kind
       :local (assoc base
                     :endpoint-override (endpoint-override (:endpoint aws))
                     :credentials-provider
                     (creds/basic-credentials-provider
                      {:access-key-id (get-in aws [:credentials :access-key-id])
                       :secret-access-key (get-in aws [:credentials :secret-access-key])}))
       :remote (cond-> (assoc base :credentials-provider
                              (profile-provider (:profile aws) *credentials-file* *aws-cli*))
                 (:endpoint aws) (assoc :endpoint-override (endpoint-override (:endpoint aws))))))))

(defn aws-client
  "The aws-api client of `service` for `env`, memoised per record and service."
  [env service]
  (let [env (validate env)
        k [(select-keys env [:name :kind :aws]) (ops/api-name service) *credentials-file* *aws-cli*]]
    (or (get @clients k)
        (let [c (build-client env service)]
          (get (swap! clients (fn [m] (if (contains? m k) m (assoc m k c)))) k)))))

(defn close-clients!
  "Stops and forgets every memoised client."
  []
  (doseq [c (vals @clients)] (try (aws/stop c) (catch Exception _ nil)))
  (reset! clients {}))
