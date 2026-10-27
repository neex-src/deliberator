(ns deliberator.env
  "Environments: where the core talks to. `(load ws name) -> env` reads
  `<ws>/environments/<name>.edn` (the default `local.edn` is written on first use of \"local\") and
  validates it against the two shapes of `docs/architecture.md` section 4; invalid or mixed shapes
  throw `{:type :env/invalid :problems [...]}`, an unknown name `{:type :env/not-found :known [...]}`.

    :local   {:name :kind :aws {:endpoint :region :account :credentials {:access-key-id
             :secret-access-key}} :targets}   no `:profile`, no `:read-only`
    :remote  {:name :kind :read-only (default true) :aws {:profile :region :account [:endpoint]}
             :targets}                        no `:credentials`

  `(with-client ws env service f)` calls `f` with an aws-api client memoised per workspace, environment name and service
  (`aws-client` is the uncounted accessor for tests); a changed record retires the old client, stopped when its last call
  returns (B17). `(save! ws env)` writes a validated record, `(remove! ws name)` deletes it. A `:local` client
  is built from the record's `:aws` only (endpoint override, basic credentials, explicit region):
  it never consults `~/.aws`, `AWS_PROFILE`, `AWS_*` keys or `AWS_ENDPOINT_URL`. A `:remote` client
  takes its credentials from `:profile`: first the static keys of the profile in the shared
  credentials file (aws-api's own provider), then, for SSO, assume-role and credential_process
  profiles that provider cannot read, `aws configure export-credentials --profile P` (refreshed
  before expiry by the wall clock, a failure keeps the still valid ones and is recorded for the hint; decisions 09, 56). `:aws :endpoint` is allowed on a remote record so tests can point a
  remote-shaped environment at the local emulator; a real remote record leaves it out.

  `(target env key) -> value` resolves `service/a`, `queue/inbox`, ... (keyword or string), throwing
  `{:type :env/unknown-target :known [...]}`. `(op-class service op)` is the class table of
  `deliberator.env.ops`, re-exported here. `(read-only? env)` is the policy flag of a remote record:
  callers (daemon, kinds, agents) may refuse mutation on it, and the confirm summary says so; inside
  `deliberator.aws/invoke!` it never replaces or relaxes the confirmation, which gates every remote
  `:mutate` whatever the flag says."
  (:refer-clojure :exclude [load])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [deliberator.env.ops :as ops]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.net URI)
           (java.time Instant OffsetDateTime)))

;; schema -------------------------------------------------------------------------------------

(def fake-account
  "The account id of Floci when nothing sets one (`FLOCI_DEFAULT_ACCOUNT_ID`): the account of a `local` environment that was
  never given another (M6.2: `workspace.open --account`, `env.save` of `local`; the runtime passes it to Floci)."
  "000000000000")

(defn local-for
  "The environment written on first use of \"local\" in workspace `ws`: the runtime of the local Floci,
  the fake account (`fake-account`, until the workspace names the real one), and the names of the estate (`estate/`): services on 8081/8082, the container logs of
  the workspace's `deliberator-<ws>-service-*`, Postgres `deliberator`. A workspace whose runtime.edn
  moved the host ports gets its copy fixed by `runtime/sync-local-env!` (endpoint, db/main, log targets)."
  [ws]
  {:name "local" :kind :local
   :description "Local runtime and the estate."
   :aws {:endpoint (or (System/getenv "DELIBERATOR_LOCAL_ENDPOINT") (System/getProperty "deliberator.local.endpoint")
                       "http://localhost:4566")
         :region "eu-central-1" :account fake-account
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
(def ^:private topology-keys #{:mode :url :container-url :log :fake})
(def ^:private local-aws-keys #{:endpoint :region :account :credentials})
(def ^:private remote-aws-keys #{:profile :region :account :endpoint})

(defn- nonblank? [x] (and (string? x) (not (str/blank? x))))

(defn- unknown-keys [m allowed where]
  (when-let [ks (seq (remove allowed (keys m)))]
    [(str where " has unknown keys " (pr-str (vec (sort-by str ks))) ", allowed " (pr-str (vec (sort allowed))))]))

(defn- topology-problems [t]
  (if-not (map? t)
    [":topology must be a map of service name -> {:mode :external|:off|:fake [:url] [:log {:container}]}"]
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
                  :fake (concat
                         (when-not (nonblank? (:fake e)) [(str ":topology " n " :fake needs :fake \"<name of the fake>\"")])
                         (when-not (and (string? (:url e)) (re-matches #"https?://[^/\s]+(:\d+)?(/\S*)?" (:url e)))
                           [(str ":topology " n " :fake needs :url like http://localhost:port")])
                         (when-not (and (string? (:container-url e)) (re-matches #"https?://[^/\s]+(:\d+)?(/\S*)?" (:container-url e)))
                           [(str ":topology " n " :fake needs :container-url like http://container:port")]))
                  [(str ":topology " n " :mode must be :external, :off or :fake (absent means local)")])
                (when (and (contains? e :log) (not (and (map? (:log e)) (nonblank? (get-in e [:log :container])))))
                  [(str ":topology " n " :log must be {:container \"name\"}")])))]
       p))))

(def ^:private aws-host-suffixes
  "Where real AWS answers; a `:local` endpoint may never name one of these."
  [".amazonaws.com" ".amazonaws.com.cn" ".api.aws" ".aws.dev" ".amazonwebservices.com"])

(defn- ip-literal? [^String h] (boolean (re-matches #"[0-9.]+|[0-9a-fA-F:]*:[0-9a-fA-F:.]*" h)))

(defn- private-ip?
  "Loopback, link-local, RFC 1918, CGNAT (100.64/10, Tailscale), site-local v6, and IPv4-mapped forms of those."
  [^String h]
  (try (let [a (java.net.InetAddress/getByName h)]            ; a literal: no DNS lookup
         (or (.isLoopbackAddress a) (.isLinkLocalAddress a) (.isSiteLocalAddress a) (.isAnyLocalAddress a)
             (let [bs (.getAddress a)]
               (and (= 4 (alength bs)) (= 100 (bit-and 0xff (aget bs 0))) (<= 64 (bit-and 0xff (aget bs 1)) 127)))))
       (catch Exception _ false)))

(defn local-host-problem
  "Why `host` is not an acceptable endpoint host of a `:local` environment, or nil. Accepted: loopback, a private or
  link-local address (RFC 1918, 169.254/16, 100.64/10, fc00::/7, fe80::/10), a bare name (a container, `floci`), and
  the local suffixes `.internal .local .localhost .lan .home.arpa .test`. Refused: AWS's own domains, and any other
  public name: a `:local` environment must be structurally unable to reach AWS."
  [^String host]
  (let [h (str/lower-case (str/replace (or host "") #"^\[|\]$" ""))]
    (cond
      (str/blank? h) "endpoint has no host"
      (some #(or (= h (subs % 1)) (str/ends-with? h %)) aws-host-suffixes)
      (str "endpoint host " host " names AWS: a :local environment must never reach AWS (make it :remote with a :profile)")
      (= h "localhost") nil
      (ip-literal? h) (when-not (private-ip? h)
                        (str "endpoint host " host " is a public address: a :local endpoint must be on this machine or a private network"))
      (not (str/includes? h ".")) nil
      (some #(str/ends-with? h %) [".internal" ".local" ".localhost" ".lan" ".home.arpa" ".test"]) nil
      :else (str "endpoint host " host " is a public name: a :local endpoint must be loopback, a private address, "
                 "a container name or *.internal/*.local/*.lan"))))

(defn- endpoint-host [^String url]
  (try (.getHost (URI. url)) (catch Exception _ nil)))

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
        (when (and (= "local" (:name m)) (not= :local kind))
          [":name local is reserved for the :local environment (it must never reach AWS)"])
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
           (when (and (nonblank? (:endpoint aws)) (re-matches #"https?://[^/\s]+(:\d+)?/?" (:endpoint aws)))
             (when-let [p (local-host-problem (endpoint-host (:endpoint aws)))] [(str ":local :aws " p)]))
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

(defn read-only?
  "The policy flag of a remote record (default true): `deliberator.aws/invoke!` refuses every `:mutate` on it flat,
  no token; `:read-only false` makes each mutation a confirmation instead. Local records have no flag."
  [env] (boolean (:read-only env)))

(defn child-env
  "The process environment of a child a LOCAL run starts (`shell` steps): `base` (the daemon's environment) without
  any `AWS_*`, the AWS config and credentials files pointed at /dev/null, IMDS and the pager off, and the record's
  own endpoint, fake keys and region exported (`AWS_ENDPOINT_URL AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
  AWS_DEFAULT_REGION AWS_REGION`), so `aws` in a step talks to the local emulator and nothing else. `env` nil: the
  scrub only."
  [base env]
  (let [aws (:aws env)]
    (cond-> (into {} (remove (fn [[k _]] (str/starts-with? (str k) "AWS_"))) base)
      true (assoc "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null"
                  "AWS_EC2_METADATA_DISABLED" "true" "AWS_PAGER" "")
      (and aws (= :local (:kind env)))
      (assoc "AWS_ENDPOINT_URL" (str (:endpoint aws))
             "AWS_ACCESS_KEY_ID" (str (get-in aws [:credentials :access-key-id]))
             "AWS_SECRET_ACCESS_KEY" (str (get-in aws [:credentials :secret-access-key]))
             "AWS_DEFAULT_REGION" (str (:region aws)) "AWS_REGION" (str (:region aws))))))

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

(declare retire-changed! forget!)

(defn save!
  "Validates and writes the environment record; returns it. The clients of the record it replaces are retired (B17)."
  [ws env]
  (let [env (validate env)]
    (store/write-edn! (env-file ws (:name env)) env)
    (retire-changed! ws env)
    env))

(defn remove!
  "Deletes environments/<name>.edn and retires its clients; returns the record it held. `local` cannot be removed (it is
  written again from runtime.edn on first use): `:env/protected`; an unknown name is `:env/not-found`. What was fetched
  through the environment stays (principle 1): config/<name>.edn and the raw store are not touched."
  [ws name]
  (let [n (store/safe-name name)]
    (when (= "local" n)
      (throw (ex-info "the local environment cannot be removed: it follows runtime.edn and is written again on first use"
                      {:type :env/protected :name n
                       :hint "change the local runtime with `dlb runtime set` / `dlb runtime sync-env` instead"})))
    (let [e (load ws n)]
      (java.nio.file.Files/delete (env-file ws n))
      (forget! ws n)
      e)))

;; targets ------------------------------------------------------------------------------------

(defn service-entry
  "The topology entry `{:mode :external|:off|:fake …}` of service `n` in `env`, nil when it is local."
  [env n] (get-in env [:topology (str n)]))

(defn service-mode
  "`:local` (no entry), `:external`, `:off` or `:fake`."
  [env n] (or (:mode (service-entry env n)) :local))

(defn read-topology
  "The `:topology` of environment `name` read raw from its file ({} when the file or the key is absent);
  never creates the default environment and never validates (the runtime asks on every status)."
  [ws name]
  (or (:topology (store/read-edn (env-file ws name))) {}))

(defn read-targets
  "The `:targets` of environment `name` read raw ({} when the file or the key is absent); never creates the default environment."
  [ws name]
  (or (:targets (store/read-edn (env-file ws name))) {}))

(defn- topology-target
  "The topology's answer for `k` (`service/<n>` url or off error; `log/service-<n>` of an external instance
  that names its log container), or nil when the topology has nothing to say (local)."
  [env k]
  (let [n (name k)]
    (case (namespace k)
      "service" (let [e (service-entry env n)]
                  (case (:mode e)
                    :external (:url e)
                    :fake (:url e)
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
  "Command used for the SSO fallback: `DELIBERATOR_AWS_CLI` (a path, for a daemon started with a short PATH, e.g. from a
  desktop launcher), else `aws` on the daemon's PATH. Tests bind it to a stub script."
  (or (not-empty (System/getenv "DELIBERATOR_AWS_CLI")) "aws"))

(defn- endpoint-override [^String url]
  (let [u (URI. url)
        proto (keyword (.getScheme u))]
    {:protocol proto :hostname (.getHost u)
     :port (if (pos? (.getPort u)) (.getPort u) (if (= proto :https) 443 80))}))

;; The bridge to the AWS CLI. aws-api's own provider reads static keys only; SSO, assume-role and credential_process
;; profiles go through `aws configure export-credentials --profile P`, whose output carries an `Expiration`. The expiry
;; is checked against the wall clock on every fetch: a timer (aws-api's auto-refresh) counts monotonic time, which stands
;; still while a laptop sleeps, so after a resume it would serve expired credentials until the timer fired.
(def ^:private cli-timeout-ms 30000)
(def ^:private refresh-margin-s 300)
(def ^:private retry-s 30)

(defonce ^:private credential-failures (atom {}))

(defn credential-failure
  "The last failure of the CLI bridge for `profile` (`{:at inst :message str}`), nil once it worked again."
  [profile] (get @credential-failures profile))

(defn- first-line [s]
  (->> (str/split-lines (str s)) (map str/trim) (remove str/blank?) first))

(defn- cli-env
  "The daemon's environment for the CLI child: the AWS config and credentials files stay (they say where the profile
  is), a profile selected by the environment or an endpoint override (the emulator's) goes: the profile is passed
  explicitly and the call must reach the real STS/SSO."
  []
  (-> (into {} (remove (fn [[k _]] (or (#{"AWS_PROFILE" "AWS_DEFAULT_PROFILE"} k) (str/starts-with? k "AWS_ENDPOINT_URL"))))
            (System/getenv))
      (assoc "AWS_PAGER" "")))

(defn- run-cli
  "`{:exit :out :err}` of the command, killed after `timeout-ms` (:exit :timeout); an unstartable command is
  `{:exit :missing}`."
  [cmd timeout-ms]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (vec cmd))
          _ (doto (.environment pb) (.clear) (.putAll (cli-env)))
          p (.start pb)
          _ (.close (.getOutputStream p))
          out (future (slurp (.getInputStream p)))
          err (future (slurp (.getErrorStream p)))]
      (if (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        {:exit (.exitValue p) :out @out :err @err}
        (do (.destroyForcibly p) {:exit :timeout :out "" :err ""})))
    (catch java.io.IOException e {:exit :missing :out "" :err (ex-message e)})))

(defn- cli-credentials
  "`{:creds {...} :refresh-at inst :expires-at inst}` from `aws configure export-credentials --profile P --format
  process`, refreshed five minutes before the expiry the CLI reports (never when it reports none); nil when the CLI is
  missing or refuses, the reason recorded for `credential-failure`."
  [cli profile]
  (let [{:keys [exit out err]} (run-cli [cli "configure" "export-credentials" "--profile" profile "--format" "process"]
                                        cli-timeout-ms)
        fail (fn [msg] (swap! credential-failures assoc profile {:at (Instant/now) :message msg}) nil)]
    (cond
      (= :missing exit) (fail (str "the AWS CLI (" cli ") cannot be started by the daemon: " (first-line err)
                                   " (is it on the daemon's PATH?)"))
      (= :timeout exit) (fail (str cli " configure export-credentials timed out after " (quot cli-timeout-ms 1000) " s"))
      (not (zero? exit)) (fail (str cli " configure export-credentials --profile " profile " exited " exit
                                    (when-let [l (first-line err)] (str ": " l))))
      :else
      (try
        (let [m (json/read-str out)
              exp (some-> (get m "Expiration") (OffsetDateTime/parse) (.toInstant))
              creds {:aws/access-key-id (get m "AccessKeyId")
                     :aws/secret-access-key (get m "SecretAccessKey")
                     :aws/session-token (get m "SessionToken")}]
          (if-not (and (:aws/access-key-id creds) (:aws/secret-access-key creds))
            (fail (str cli " configure export-credentials printed no AccessKeyId/SecretAccessKey for profile " profile))
            (do (swap! credential-failures dissoc profile)
                {:creds creds :expires-at (or exp Instant/MAX)
                 :refresh-at (if exp (.minusSeconds ^Instant exp refresh-margin-s) Instant/MAX)})))
        (catch Exception e (fail (str cli " configure export-credentials printed something else than JSON credentials: "
                                      (ex-message e))))))))

(defn- before? [c k now] (boolean (and c (.isBefore ^Instant now ^Instant (get c k)))))

(defn- profile-provider
  "Static keys of the profile in the shared credentials file, else the CLI bridge. One cache per client, checked against
  the wall clock on every fetch under a lock (a burst of calls runs the CLI once). A refresh that fails keeps the
  cached credentials while they are still valid and tries again in 30 s; once expired they are dropped (nil)."
  [profile file cli]
  (let [static (if file
                 (creds/profile-credentials-provider profile (File. (str file)))
                 (creds/profile-credentials-provider profile))
        cache (atom nil)
        obtain (fn []
                 (or (when-let [k (creds/fetch static)]
                       {:creds k :expires-at Instant/MAX :refresh-at Instant/MAX})
                     (cli-credentials cli profile)))
        refresh! (fn []
                   (let [now (Instant/now) c @cache fresh (obtain)]
                     (cond
                       fresh (do (reset! cache fresh) (:creds fresh))
                       (before? c :expires-at now) (do (swap! cache assoc :refresh-at (let [retry (.plusSeconds now retry-s)]
                                                                                        (if (.isBefore retry ^Instant (:expires-at c)) retry (:expires-at c))))
                                                       (:creds c))
                       :else (do (reset! cache nil) nil))))]
    (reify creds/CredentialsProvider
      (fetch [_]
        (if (before? @cache :refresh-at (Instant/now))
          (:creds @cache)
          (locking cache
            (if (before? @cache :refresh-at (Instant/now))
              (:creds @cache)
              (refresh!))))))))

;; Clients are memoised per slot [workspace, environment name, api, credentials file, cli] and carry the record they were
;; built from. A slot whose record changed (env.save, an edited environments/x.edn) retires its client: stopped at once
;; when no call uses it, else when the last call that does returns (`with-client` counts the users). B17.

(defonce ^:private lock (Object.))
(defonce ^:private clients (atom {}))   ; slot -> {:record :client :users (atom n)}
(defonce ^:private retired (atom []))   ; entries still used by a call, stopped when it returns

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

(defn- stop-client! [{:keys [client]}]
  (try (aws/stop client) (catch Exception _ nil)))

(defn- retire! [entry]
  (if (pos? @(:users entry))
    (swap! retired conj entry)
    (stop-client! entry)))

(defn- record-of [env] (select-keys env [:name :kind :aws]))

(defn- slot-of [ws env service]
  [(some-> ws store/ws-name) (:name env) (ops/api-name service) *credentials-file* *aws-cli*])

(defn- entry!
  "The live entry of the slot for `env`, built (and the replaced one retired) when the record differs. Under `lock`."
  [ws env service]
  (let [slot (slot-of ws env service) record (record-of env) e (get @clients slot)]
    (if (and e (= record (:record e)))
      e
      (do (when e (retire! e))
          (let [n {:record record :client (build-client env service) :users (atom 0)}]
            (swap! clients assoc slot n)
            n)))))

(defn with-client
  "Calls `(f client)` with the aws-api client of `service` for `env` (workspace `ws`), memoised per slot. The client is
  counted as in use for the duration, so a record change during the call cannot stop it under the call."
  [ws env service f]
  (let [env (validate env)
        e (locking lock (let [e (entry! ws env service)] (swap! (:users e) inc) e))]
    (try (f (:client e))
         (finally
           (locking lock
             (when (and (zero? (swap! (:users e) dec)) (some #(identical? e %) @retired))
               (swap! retired (fn [v] (vec (remove #(identical? e %) v))))
               (stop-client! e)))))))

(defn credentials
  "B39: the current credentials of a `:remote` environment's profile as Terraform's AWS provider reads them from the process
  environment: `{\"AWS_ACCESS_KEY_ID\" .. \"AWS_SECRET_ACCESS_KEY\" .. [\"AWS_SESSION_TOKEN\" ..] \"AWS_REGION\" ..}`, from the same
  provider the clients use (static keys of the profile, else the CLI bridge). Never persisted or logged by the caller. Throws
  `{:type :env/credentials}` with the bridge's reason when there are none."
  [env]
  (let [{:keys [profile region]} (:aws (validate env))
        c (creds/fetch (profile-provider profile *credentials-file* *aws-cli*))]
    (when-not c
      (throw (ex-info (str "no credentials for profile " profile ": "
                           (or (:message (credential-failure profile)) "the profile has none (not static keys, and the AWS CLI gave none)"))
                      {:type :env/credentials :profile profile})))
    (cond-> {"AWS_ACCESS_KEY_ID" (:aws/access-key-id c) "AWS_SECRET_ACCESS_KEY" (:aws/secret-access-key c)
             "AWS_REGION" region "AWS_DEFAULT_REGION" region}
      (:aws/session-token c) (assoc "AWS_SESSION_TOKEN" (:aws/session-token c)))))

(defn aws-client
  "The aws-api client of `service` for `env`, memoised per record and service (slot without a workspace). Not counted:
  for tests and one-off calls; the core calls through `with-client`."
  [env service]
  (let [env (validate env)]
    (locking lock (:client (entry! nil env service)))))

(defn retire-changed!
  "Retires the clients of environment `name` in workspace `ws` whose record is not `env` (the record just saved)."
  [ws env]
  (let [w (some-> ws store/ws-name) record (record-of env)]
    (locking lock
      (doseq [[slot e] @clients
              :when (and (= w (first slot)) (= (:name env) (second slot)) (not= record (:record e)))]
        (swap! clients dissoc slot)
        (retire! e)))))

(defn forget!
  "Retires every client of environment `name` in workspace `ws` (the record was removed)."
  [ws name]
  (let [w (some-> ws store/ws-name)]
    (locking lock
      (doseq [[slot e] @clients :when (and (= w (first slot)) (= name (second slot)))]
        (swap! clients dissoc slot)
        (retire! e)))))

(defn client-stats
  "`{:live n :retired n}`: the memoised clients and the replaced ones still serving a call."
  [] {:live (count @clients) :retired (count @retired)})

(defn close-clients!
  "Stops and forgets every memoised client."
  []
  (locking lock
    (doseq [e (concat (vals @clients) @retired)] (stop-client! e))
    (reset! clients {})
    (reset! retired [])))
