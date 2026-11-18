(ns deliberator.runtime
  "The local runtime, reconciled lazily from `runtime.edn` (desired state, architecture section 7)
  at `$DELIBERATOR_HOME/workspaces/<ws>/runtime.edn`; the first `ensure!` writes the default:
  Floci 2.3.0 (ECS mock, advertised as `localhost:<host port>`) and Postgres 17.

  Shape: `{:podman {:endpoint \"unix:///…\"} :network \"deliberator\" :containers {<role> spec}}`.
  A spec: `:image`, `:ports {container-port host-port}` (published on `:host-ip`, default
  127.0.0.1), `:env`, `:command`, `:name` (default `deliberator-<ws>-<role>`), `:mounts [{:source
  :destination :options}]`, `:init <dir>` (mounted at `/docker-entrypoint-initdb.d`, relative to
  the workspace directory), `:podman-socket true|\"/path\"` (mounts the socket of the Podman that
  runs the container at `/var/run/docker.sock`; the path is taken from the endpoint, or from
  `info` for tcp), `:health`. Env values may use `{{port:<container-port>}}` (the host port),
  `{{network}}`, `{{name}}`, `{{role}}`.

  Service containers (role `:service/<name>`, a spec with `:jar`): the estate's Spring services.
  `{:image \"docker.io/library/eclipse-temurin:21-jre\" :jar \"estate/services/service-a/target/x.jar\"
  :ports {8081 8081 5005 5005} :debug 5005 :java-opts [\"-Xmx256m\"] :health {:http \"/actuator/health\"
  :port 8081} :env {…} :secrets {\"API_KEY\" <valueFrom>}}`. The jar (relative to the top-level `:root`
  of `runtime.edn`, or absolute) is mounted read-only at `/app/app.jar` and run as `java
  [-agentlib:jdwp=…,suspend=n,address=*:<debug>] <java-opts> -jar /app/app.jar` unless `:command` is
  given; its size and mtime are part of the spec hash, so a rebuilt jar recreates the container on the
  next `ensure!`. `:secrets` is what an ECS task definition's `secrets` is: env name -> `valueFrom`, an
  SSM parameter ARN or name (`arn:aws:ssm:…:parameter/x/y`, `/x/y`) or a Secrets Manager ARN or name
  (optionally `…:json-key::`); the runtime plays the ECS agent and resolves them against the workspace's
  `local` environment when it CREATES the container (so Floci must already hold them), the values go
  to Podman only, never into `runtime.edn`, the hash or a log. Services start after Floci and Postgres
  are healthy. Service roles are not stored in `runtime.edn`: `effective-containers` derives one per
  service description (`services/<name>.edn`, namespace `deliberator.service.description`) and merges the
  runtime half `:services {\"<name>\" {:ports … :env …}}` over it. Distinct containers on one host port are refused as for every role.
  A `:container` service is the same role with a plain image: its spec has `:service? true` instead of
  `:jar`/`:exploded`, `:aliases` (names the container answers to on the network), `:image-hint` (what
  builds a local image) and `:health {}` for no probe (running counts as healthy).

  Contract: `(ensure! ws [opts])` reconciles network, images and containers labelled
  `deliberator.workspace=<ws>`, `deliberator.role=<role>`, `deliberator.spec=<hash>`: idempotent,
  a changed spec recreates the container, a host port that is taken throws `:runtime/port-conflict`
  naming the holder; waits until every container is healthy and returns `(status ws)`. Events go to
  `(:sink opts)` as maps: `runtime.pulling`, `runtime.starting`, `runtime.ready`. `(status ws)` ->
  `{:ws :podman {:endpoint :reachable :version} :containers [{:role :name :id :image :state :since
  :health :detail :ports :drift?}]}`; `:health` is `:healthy`, `:unhealthy` or nil (not running).
  `(down! ws)` removes only containers labelled with the workspace. `(logs ws role opts)` reads a
  container's logs through `podman/logs` and mirrors them to `logs/services/<role>/<date>.log`.
  Health per role (overridable with `:health {:http path :port cp}` or `{:exec [cmd…]}`): Floci
  `GET /_floci/health` with every service `running`, Postgres `pg_isready -h 127.0.0.1` via exec (TCP: the image's temporary init server has no TCP, B16), `service-*`
  `GET /actuator/health`. `opts`: `:sink`, `:timeout-ms` (health wait per container, default 120 s), `:infra-only` (skip
  the `:jar` service roles).
  Home resolution is the store's (`store/*home*`, `$DELIBERATOR_HOME`). `-main`: `up|status|down
  [--ws W]` (the `bb runtime:*` tasks) plus `up --infra` and `sync-env`."
  (:require [clojure.data.json :as json]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [deliberator.aws :as aws]
            [deliberator.ecsrecord :as ecsrecord]
            [deliberator.env :as env]
            [deliberator.fakes :as fakes]
            [deliberator.podman :as podman]
            [deliberator.service.description :as description]
            [deliberator.service.staging :as staging]
            [deliberator.store :as store]
            [deliberator.topology :as topology])
  (:import (java.net InetSocketAddress Socket URI)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(def default-timeout-ms 120000)

(defn default-config
  "The `runtime.edn` written on first `ensure!`."
  []
  {:podman {:endpoint (podman/default-endpoint)}
   :network "deliberator"
   :containers
   {:floci {:image "docker.io/floci/floci:2.3.0"
            :ports {4566 4566}
            :env {"FLOCI_SERVICES_ECS_MOCK" "true"
                  "FLOCI_HOSTNAME" "localhost"
                  "FLOCI_BASE_URL" "http://localhost:{{port:4566}}"
                  "FLOCI_SERVICES_DOCKER_NETWORK" "{{network}}"
                  "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE" "{{name}}"}
            :podman-socket true}
    :postgres {:image "docker.io/library/postgres:17"
               :ports {5432 5432}
               :env {"POSTGRES_USER" "deliberator"
                     "POSTGRES_PASSWORD" "deliberator"
                     "POSTGRES_DB" "deliberator"}}}})

(defn- emit [sink m] (when sink (sink m)))

;; ---------------------------------------------------------------------------------------------
;; Desired state

(defn config-path [ws] (store/path ws "runtime.edn"))

(defn read-config
  "The workspace's `runtime.edn`, nil when absent."
  [ws]
  (store/read-edn (config-path ws)))

(declare allocate-default-ports sync-local-env!)

(defn- load-config! [ws sink]
  (or (read-config ws)
      (let [cfg (allocate-default-ports ws (default-config))]
        (store/write-edn! (config-path ws) cfg)
        (emit sink {:event "runtime.config-created" :path (str (config-path ws))})
        cfg)))

(defn role-name
  "Role keyword -> label value: `:floci` -> \"floci\", `:service/a` -> \"service-a\"."
  [k]
  (str/replace (subs (str k) 1) "/" "-"))

(defn- template [s {:keys [ports network name role]}]
  (-> (str s)
      (str/replace #"\{\{port:(\d+)\}\}" (fn [[_ cp]] (str (get ports (Long/parseLong cp) cp))))
      (str/replace "{{network}}" network)
      (str/replace "{{name}}" name)
      (str/replace "{{role}}" role)))

(defn- sorted-deep [x]
  (walk/postwalk (fn [v] (if (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2))) v) v)) x))

(defn- jar-file ^java.io.File [cfg _role spec]
  (let [p (str (:jar spec))
        f (if (str/starts-with? p "/") (java.io.File. p) (java.io.File. ^String (or (:root cfg) ".") p))]
    f))

(defn- service-command [{:keys [debug java-opts]}]
  (vec (concat ["java"]
               (when debug [(str "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" debug)])
               java-opts
               ["-jar" "/app/app.jar"])))

(def ^:private agent-opens
  "The `--add-opens` set HotswapAgent needs (measured in piece 01; without `java.base/java.lang` the agent dies)."
  ["java.base/java.lang" "java.base/java.lang.reflect" "java.base/java.io" "java.base/java.util"
   "java.base/jdk.internal.loader" "java.base/sun.nio.ch" "java.desktop/java.beans" "java.desktop/com.sun.beans"
   "java.desktop/com.sun.beans.introspect" "java.desktop/javax.swing.plaf.basic" "java.desktop/sun.awt"])

(defn- exploded-command
  "The hotswap image's command: JBR with enhanced redefinition, the agent, JDWP for the IDE only, the exploded
  classpath (conf dir with hotswap-agent.properties, the staged class dirs, the dependency jars)."
  [{:keys [debug java-opts exploded]}]
  (vec (concat ["java" "-XX:+AllowEnhancedClassRedefinition" "-javaagent:/opt/hotswap/hotswap-agent.jar=autoHotswap=true"
                ;; the Spring plugin waits 1600 ms by default to batch class changes; service.reload stages them all at once (B10)
                "-DSpringReloadDelayMillis=200"]
               (map #(str "--add-opens=" % "=ALL-UNNAMED") agent-opens)
               (when debug [(str "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" debug)])
               java-opts
               ["-cp" (str "/opt/hotswap/conf:" (str/join ":" (map :mount (:dirs exploded))) ":/app/dependency/*")
                (:main exploded)])))

(defn- dep-stamp
  "name+size of every file of the dependency dir (nil when absent): a changed classpath recreates the container."
  [dir]
  (let [f (java.io.File. ^String dir)]
    (when (.isDirectory f)
      (vec (sort (for [^java.io.File x (.listFiles f) :when (.isFile x)] [(.getName x) (.length x)]))))))

(defn stage!
  "Brings the staging dirs of an `:exploded` desired container up to date with the modules' `target/classes` (sha diff).
  Called before a container is created or started, never while it runs (`service.reload` owns that)."
  [d]
  (doseq [{:keys [seed staging]} (:dirs (:exploded d))]
    (staging/sync! (java.io.File. ^String seed) (java.io.File. ^String staging))))

(defn- service-health [{:keys [ports debug health]}]
  {:http (or (:http health) "/actuator/health")
   :port (or (:port health) (first (remove #{debug} (keys (into (sorted-map) ports)))))})

(defn- rehash
  "`d` with `:hash` over everything that would change the container. `:secret-values` is never part of it."
  [d]
  (assoc d :hash (subs (store/sha256 (.getBytes ^String (pr-str (sorted-deep (dissoc d :health :service? :image-hint :secret-values :hash))) StandardCharsets/UTF_8)) 0 16)))

(defn- floci-account-env
  "`[[\"FLOCI_DEFAULT_ACCOUNT_ID\" account]]` for the Floci role when the workspace's `local` environment names another account
  than Floci's own fake one (M6.2: the account the ARNs of the emulator carry, so hardcoded ARNs of a real repository line up);
  an explicit `:env` of the role in `runtime.edn` wins over it."
  [ws role]
  (when (= :floci role)
    (let [a (get-in (env/load ws "local") [:aws :account])]
      (when (and (string? a) (not= a env/fake-account))
        [["FLOCI_DEFAULT_ACCOUNT_ID" a]]))))

(defn desired
  "One container of the config resolved into what will be created:
  `{:role :name :image :ports {cp hp} :host-ip :env :command :mounts :health :podman-socket
  [:secrets] [:jar-stamp] :hash}`. `:hash` covers everything that would change the container (secret
  references, never their values)."
  [ws cfg role spec]
  (when-not (:image spec)
    (throw (ex-info (str "runtime.edn: " role " has no :image") {:type :runtime/invalid-config :role role})))
  (when (:from spec)
    (throw (ex-info (str "runtime.edn: " role " uses :from (not supported; give the service its :jar and :env)")
                    {:type :runtime/invalid-config :role role})))
  (let [rn (role-name role)
        network (:network cfg)
        name (or (:name spec) (store/container-name ws rn))
        ports (into (sorted-map) (:ports spec))
        ctx {:ports ports :network network :name name :role rn}
        jar (when (:jar spec) (jar-file cfg role spec))
        exploded (:exploded spec)
        mounts (vec (concat (:mounts spec)
                            (when exploded
                              (concat (for [{:keys [staging mount]} (:dirs exploded)]
                                        {:source staging :destination mount :options ["rw" "z"]})
                                      [{:source (:dependency exploded) :destination "/app/dependency" :options ["ro" "z"]}]))
                            (when jar [{:source (str jar) :destination "/app/app.jar" :options ["ro" "z"]}])
                            (when-let [init (:init spec)]
                              [{:source (if (str/starts-with? (str init) "/") (str init) (str (store/path ws init)))
                                :destination "/docker-entrypoint-initdb.d" :options ["ro" "z"]}])))
        d {:role rn :name name :image (:image spec) :ports ports
           :host-ip (or (:host-ip spec) "127.0.0.1")
           :env (into (sorted-map) (concat (floci-account-env ws role)
                                           (for [[k v] (:env spec)] [(str (clojure.core/name k)) (template v ctx)])))
           :command (or (:command spec) (cond jar (service-command spec) exploded (exploded-command spec))) :mounts mounts :network network
           :health (if (or jar exploded) (service-health spec) (:health spec))
           :podman-socket (:podman-socket spec)}
        d (cond-> d
            (:entrypoint spec) (assoc :entrypoint (vec (:entrypoint spec)))
            (:image-id spec) (assoc :image-id (:image-id spec))
            jar (assoc :jar-stamp [(.length jar) (.lastModified jar)] :jar-path (str jar))
            exploded (assoc :exploded exploded :dep-stamp (dep-stamp (:dependency exploded)))
            (or jar exploded (:service? spec)) (assoc :service? true)
            (seq (:aliases spec)) (assoc :aliases (vec (:aliases spec)))
            (:image-hint spec) (assoc :image-hint (:image-hint spec))
            (seq (:secrets spec)) (assoc :secrets (into (sorted-map) (for [[k v] (:secrets spec)] [(clojure.core/name k) v]))))]
    (rehash d)))

(defn- merge-spec
  "`base` (derived from a description) with the runtime half `over` (`runtime.edn :services <name>`):
  `:env`, `:ports` and `:secrets` merge, everything else replaces."
  [base over]
  (reduce-kv (fn [m k v] (if (#{:env :ports :secrets} k) (update m k #(into (or % {}) v)) (assoc m k v))) base over))

(defn declared-containers
  "Like `effective-containers` but without the topology's say about which services are desired: every runnable
  description has its role, dependents rendered against the `local` environment's topology."
  [ws cfg]
  (merge (:containers cfg)
         (into {} (for [d (description/list ws) :when (description/runnable? d)]
                    [(description/role (:name d))
                     (merge-spec (topology/render-spec ws (description/->spec ws (ecsrecord/overlay ws d)) (:depends d))
                                 (get-in cfg [:services (:name d)]))]))
         ;; the HTTP fakes (M6.6): one role per declared fake that records or replays
         (into {} (for [f (fakes/list ws) :when (fakes/active? f)]
                    [(fakes/role (:name f)) (fakes/->spec ws f)]))))

(defn ecs-stopped-roles
  "Role labels of the described services bound to an ECS service of the `local` environment whose record says desired 0
  (`deliberator.ecsfollow`): their containers are not desired either."
  [ws]
  (let [zero (ecsrecord/zero-services ws (filter description/runnable? (description/list ws)))]
    (into #{} (map #(role-name (description/role (:name %)))) zero)))

(defn topology-stopped-roles
  "Role labels (\"service-a\") of the described services whose mode in the `local` environment is not local, or whose bound
  ECS service is at desired 0: their containers are not desired, `ensure!` stops (keeps) them."
  [ws]
  (let [off (topology/not-local ws)]
    (into (ecs-stopped-roles ws)
          (comp (filter #(and (off (:name %)) (description/runnable? %)))
                (map #(role-name (description/role (:name %)))))
          (description/list ws))))

(defn effective-containers
  "role -> spec of everything `ensure!` reconciles: the roles of `runtime.edn` (infrastructure, or a hand-written
  `:jar` role) plus one `:service/<name>` role per runnable service description of the workspace
  (`description/->spec`, a dependent's `:depends` entries followed to the topology of the `local` environment,
  merged with `runtime.edn :services <name>`), minus the services that topology serves externally or not at all.
  Service roles are never stored."
  [ws cfg]
  (let [stopped (topology-stopped-roles ws)]
    (into {} (remove (fn [[role _]] (and (= "service" (namespace role)) (stopped (role-name role)))))
          (declared-containers ws cfg))))

(defn desired-all [ws cfg]
  (mapv (fn [[role spec]] (desired ws cfg role spec)) (sort-by (comp str key) (effective-containers ws cfg))))

(defn logical-ref
  "A `valueFrom` reference as `{:service :ssm|:secretsmanager :id name-in-any-environment :json-key}`: a secret ARN
  loses account, region and the random 6-character suffix Secrets Manager appends, an SSM ARN becomes `/path`. Used to
  look the same secret up in ANOTHER environment (`secrets-from`), where the ARN of the local one means nothing."
  [ref]
  (let [ref (str ref)
        arn (re-matches #"arn:[^:]*:(ssm|secretsmanager):[^:]*:[^:]*:(parameter|secret)[:/](.+)" ref)
        ssm? (or (and arn (= "ssm" (second arn))) (str/starts-with? ref "/"))]
    (if ssm?
      {:service :ssm :id (if arn (str "/" (str/replace (nth arn 3) #"^/" "")) ref)}
      (let [tail (if arn (nth arn 3) ref)
            [nm json-key] (let [parts (str/split tail #":" -1)]
                            (if (and (> (count parts) 1) (seq (second parts)))
                              [(first parts) (second parts)]
                              [(first parts) nil]))]
        {:service :secretsmanager
         :id (if arn (str/replace nm #"-[A-Za-z0-9]{6}$" "") nm)
         :json-key json-key}))))

(defn- resolve-one
  "The value of `ref` in environment `e`. `by-name?` looks the logical name up (another environment); otherwise the
  reference is used as written. `redact?` keeps the response bodies out of the raw store."
  [ws e ref {:keys [by-name? redact?]}]
  (let [ref (str ref)
        ctx (cond-> {:ws ws} redact? (assoc :redact-secrets true))
        arn (re-matches #"arn:[^:]*:(ssm|secretsmanager):[^:]*:[^:]*:(parameter|secret)[:/](.+)" ref)
        ssm? (or (and arn (= "ssm" (second arn))) (str/starts-with? ref "/"))
        [secret-id json-key] (if (and arn (not ssm?))
                               (let [parts (str/split (nth arn 3) #":" -1)]
                                 (if (and (> (count parts) 1) (seq (second parts)))
                                   [(str/join ":" (take 7 (str/split ref #":" -1))) (second parts)]
                                   [ref nil]))
                               [ref nil])
        lr (when by-name? (logical-ref ref))
        r (if ssm?
            (aws/invoke! ctx e :ssm :GetParameter {:Name (if by-name? (:id lr) (if arn (str "/" (nth arn 3)) ref)) :WithDecryption true})
            (aws/invoke! ctx e :secretsmanager :GetSecretValue {:SecretId (if by-name? (:id lr) secret-id)}))
        json-key (if by-name? (:json-key lr) json-key)
        v (if ssm? (get-in r [:Parameter :Value]) (:SecretString r))]
    (when (or (aws/error? r) (nil? v))
      (throw (ex-info (str "cannot resolve " ref " against environment " (:name e)
                           (when (aws/error? r) (str ": " (or (:message r) (:category r) (:type r))))
                           (when-not by-name? " (bb estate:deploy seeds it)"))
                      {:type :runtime/secret-unresolved :ref ref :env (:name e) :hint (if by-name? (str "is it defined in environment " (:name e) "?") "bb estate:deploy")})))
    (if json-key (get (json/read-str v) json-key v) v)))

(defn resolve-secrets
  "env name -> value for a `:secrets` map of `valueFrom` references, read from the workspace's
  `local` environment (never persisted by the runtime)."
  [ws secrets]
  (when (seq secrets)
    (let [local (env/load ws "local")]
      (into {} (for [[k ref] secrets] [k (resolve-one ws local ref {})])))))

(defn secret-refs
  "env name -> `valueFrom` of the service role `role` (label, e.g. \"service-a\"): the merged `:secrets` of its spec."
  [ws role]
  (let [d (some #(when (= role (:role %)) %) (desired-all ws (or (read-config ws) {})))]
    (into (sorted-map) (:secrets d))))

(defn fetch-secrets
  "env name -> REAL value of each `refs` entry, read from environment `env-name` by logical name through `aws/invoke!`
  with the persisted bodies redacted (`GetSecretValue`, `GetParameter WithDecryption true`). The caller holds the
  result in memory and hands it to `ensure!` (`:secrets-from`); it is written nowhere."
  [ws env-name refs]
  (let [e (env/load ws env-name)]
    (into {} (for [[k ref] refs] [k (resolve-one ws e ref {:by-name? true :redact? true})]))))

(defn- container-spec
  "The libpod creation body for `d`."
  [ws d socket-path]
  {"name" (:name d)
   "image" (:image d)
   "labels" (cond-> {"deliberator.workspace" (store/ws-name ws) "deliberator.role" (:role d) "deliberator.spec" (:hash d)}
              (:secrets-from d) (assoc "deliberator.secrets-from" (get-in d [:secrets-from :env])))
   "env" (merge (or (:secret-values d) (resolve-secrets ws (:secrets d))) (:env d))
   "command" (:command d)
   "entrypoint" (:entrypoint d)
   "netns" {"nsmode" "bridge"}
   "Networks" {(:network d) (if (seq (:aliases d)) {"aliases" (:aliases d)} {})}
   "log_configuration" {"driver" "k8s-file"}
   "portmappings" (vec (for [[cp hp] (:ports d)]
                         {"container_port" cp "host_port" hp "host_ip" (:host-ip d) "protocol" "tcp"}))
   "mounts" (vec (concat (for [{:keys [source destination options]} (:mounts d)]
                           {"type" "bind" "source" (str source) "destination" destination "options" (vec options)})
                         (when socket-path
                           [{"type" "bind" "source" socket-path "destination" "/var/run/docker.sock" "options" ["z"]}])))})

;; ---------------------------------------------------------------------------------------------
;; Health

(defn- probe-host [c]
  (let [ep (podman/parse-endpoint (:endpoint c))]
    (if (= :tcp (:scheme ep)) (:host ep) "127.0.0.1")))

(defn- default-health [d]
  (let [r (:role d)]
    (cond
      (= r "floci") {:http "/_floci/health" :port 4566}
      ;; over TCP, not the unix socket (B16): the image's entrypoint first runs a TEMPORARY server that listens on the
      ;; socket only (`listen_addresses=''`), stops it and starts the real one; a socket probe answers "accepting" for
      ;; the temporary server, `runtime.ready` fires, and the next probe lands in the gap ("no response", exit 2)
      (= r "postgres") {:exec ["pg_isready" "-h" "127.0.0.1" "-U" (get (:env d) "POSTGRES_USER" "postgres")]}
      (str/starts-with? r "service-") {:http "/actuator/health"}
      :else nil)))

(defonce ^:private http-client (delay (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1)
                                   (.connectTimeout (Duration/ofSeconds 2)) (.build))))

(defonce ^:private probe-persisted (atom {}))   ; [ws url] -> sha of the last health body persisted for it

(defn- persist-probe!
  "A health body goes to the raw store when it differs from the last one persisted for this URL, or when the probe is not
  2xx (review 2026-10-25, R26: the IDE's 15 s status tick wrote a meta sidecar per probe; the bodies are the same)."
  [ws url ^bytes body status content-type]
  (let [sha (store/sha256 body) k [(store/ws-name ws) url]]
    (when (or (not (<= 200 status 299)) (not= sha (get @probe-persisted k)))
      (store/raw-put! ws body {:source :http :op (str "GET " url) :env nil :at (store/now) :content-type content-type})
      (swap! probe-persisted assoc k sha))))

(defn- http-probe [c ws d {:keys [http port]}]
  (let [cp (or port (first (keys (:ports d))))
        hp (get (:ports d) cp)
        url (str "http://" (probe-host c) ":" hp http)]
    (try
      (let [resp (.send ^HttpClient @http-client (-> (HttpRequest/newBuilder (URI. url)) (.timeout (Duration/ofSeconds 3)) .build)
                        (HttpResponse$BodyHandlers/ofByteArray))
            body ^bytes (.body resp)
            status (.statusCode resp)]
        (when (pos? (alength body))
          (persist-probe! ws url body status (or (.orElse (.firstValue (.headers resp) "content-type") nil) "application/octet-stream")))
        (if-not (<= 200 status 299)
          {:health :unhealthy :detail (str "HTTP " status)}
          (let [j (try (json/read-str (String. body StandardCharsets/UTF_8)) (catch Exception _ nil))
                services (when (map? j) (get j "services"))
                down (when (map? services) (sort (for [[k v] services :when (not= "running" v)] (str k ":" v))))]
            (cond
              (seq down) {:health :unhealthy :detail (str "not running: " (str/join " " down))}
              (map? services) {:health :healthy :detail (str (count services) " services running")}
              :else {:health :healthy :detail (str "HTTP " status)}))))
      (catch java.io.IOException e
        {:health :unhealthy :detail (str "no answer on " url ": " (.getMessage e))}))))

(defn- exec-probe [c d {:keys [exec]}]
  (try
    (let [r (podman/exec! c (:name d) exec)]
      (if (zero? (:exit r))
        {:health :healthy :detail (str/trim (str (:stdout r)))}
        {:health :unhealthy :detail (str "exit " (:exit r) " " (str/trim (str (:stdout r) (:stderr r))))}))
    (catch clojure.lang.ExceptionInfo e
      {:health :unhealthy :detail (ex-message e)})))

(defn probe
  "Health of the running container `d`: `{:health :healthy|:unhealthy :detail str}`."
  [c ws d]
  (let [h (or (:health d) (default-health d))]
    (cond
      (:http h) (http-probe c ws d h)
      (:exec h) (exec-probe c d h)
      :else {:health :healthy :detail "running (no probe)"})))

;; ---------------------------------------------------------------------------------------------
;; Reconciling

(defn- label [ctr k] (get-in ctr [:Labels (keyword k)]))

(defn- ws-labels [ws] {"deliberator.workspace" (store/ws-name ws)})

(defn podman-endpoint
  "The endpoint the Podman client speaks for `cfg`: `runtime.edn :podman :endpoint` (`unix://` or `tcp://`), else the
  user's socket."
  [_ws cfg]
  (get-in cfg [:podman :endpoint] (podman/default-endpoint)))

(defn podman-client
  "A Podman client for the workspace's `runtime.edn`. `:persist? false` leaves the raw store out (probes)."
  [ws cfg & {:keys [persist?] :or {persist? true}}]
  (podman/client (podman-endpoint ws cfg) :ws (when persist? (store/ws-name ws))))

(defn- connect-client [ws cfg]
  (let [endpoint (get-in cfg [:podman :endpoint] (podman/default-endpoint))
        c (podman-client ws cfg)]
    (when-not (podman/available? c)
      (throw (ex-info (str "podman is not answering at " endpoint
                           " (unix: systemctl --user enable --now podman.socket)")
                      {:type :runtime/podman-unreachable :endpoint endpoint
                       :hint "systemctl --user enable --now podman.socket"})))
    c))

(defn- port-open? [host port]
  (try (with-open [s (Socket.)] (.connect s (InetSocketAddress. ^String host (int port)) 300) true)
       (catch java.io.IOException _ false)))

(defn- port-holder [c port]
  (some (fn [ctr] (when (some #(= port (:host_port %)) (:Ports ctr)) (first (:Names ctr))))
        (podman/list-containers c :all false)))

(defn- check-ports! [c d]
  (doseq [[cp hp] (:ports d)]
    (when (port-open? (probe-host c) hp)
      (let [holder (port-holder c hp)]
        (throw (ex-info (str "host port " hp " (" (:role d) " :" cp ") is already in use"
                             (if holder (str " by container " holder) " by another process")
                             "; set another host port in runtime.edn, e.g. :ports {" cp " " (+ 10000 hp) "}"
                             " (Floci's advertised URLs follow it)")
                        {:type :runtime/port-conflict :role (:role d) :port hp :container-port cp
                         :held-by holder :config "runtime.edn"}))))))

;; host port allocation (m3 05b) ---------------------------------------------------------------------------
;; Two workspaces of one home can run side by side: a host port that is not pinned in the workspace's runtime.edn is
;; allocated on first use (a new workspace's Floci and Postgres when runtime.edn is created, a service's port and JDWP port at
;; the next `ensure!`) and written there, so it stays. The candidates are the default port, then +100, +200, ...; one is taken
;; when no other workspace of this home has it in its runtime.edn and nothing on the host listens on it (or this workspace's own
;; container does: a running estate keeps its ports). A port the user wrote into runtime.edn is never touched.

(def ^:private port-step 100)

(defn- workspace-names []
  (let [d (.toFile (.resolve (store/home) "workspaces"))]
    (if (.isDirectory d) (vec (sort (map #(.getName ^java.io.File %) (filter #(.isDirectory ^java.io.File %) (.listFiles d))))) [])))

(defn- cfg-host-ports
  "Every host port of a runtime.edn: its containers' and its services' halves."
  [cfg]
  (set (concat (for [[_ spec] (:containers cfg) [_ hp] (:ports spec)] hp)
               (for [[_ spec] (:services cfg) [_ hp] (:ports spec)] hp))))

(defn claimed-ports
  "The host ports the other workspaces of this home have in their runtime.edn (running or not)."
  [ws]
  (let [me (store/ws-name ws)]
    (into #{} (comp (remove #{me}) (mapcat #(cfg-host-ports (store/read-edn (config-path %))))) (workspace-names))))

(defn- own-holder? [c ws port]
  (boolean (some-> (port-holder c port) (str/starts-with? (str "deliberator-" (store/ws-name ws) "-")))))

(defn- pick-port
  "The first of `preferred`, +100, +200 ... that is not in `taken` and is free on the host or held by this workspace's own container."
  [c ws preferred taken]
  (let [host (probe-host c)
        ok? (fn [p] (and (<= p 65535) (not (taken p)) (or (not (port-open? host p)) (own-holder? c ws p))))]
    (or (some #(when (ok? %) %) (map #(+ preferred (* port-step %)) (range 50)))
        (throw (ex-info (str "no free host port for " preferred ", " (+ preferred port-step) " ... in " (store/ws-name ws))
                        {:type :runtime/no-port :port preferred :hint "stop what holds them or pin a port in runtime.edn"})))))

(defn allocate-default-ports
  "`cfg` (the default config of a workspace that has no runtime.edn yet) with its infrastructure containers on free host ports of
  this home: 4566/5432 for the first workspace, +100 for the next."
  [ws cfg]
  (let [c (connect-client ws cfg)]
    (first (reduce (fn [[cfg taken] [role cp hp]]
                     (let [p (pick-port c ws hp taken)]
                       [(assoc-in cfg [:containers role :ports cp] p) (conj taken p)]))
                   [cfg (claimed-ports ws)]
                   (for [[role spec] (:containers cfg) [cp hp] (:ports spec)] [role cp hp])))))

(defn- allocate-service-ports!
  "Pins the host ports of every runnable service description that runtime.edn has none for (`:services <name> :ports {cp hp}`),
  writes runtime.edn and points the `local` environment at them when it changed. -> the config in effect."
  [c ws cfg]
  (let [missing (for [d (description/list ws) :when (and (description/runnable? d) (not (:host-port d)))   ; a pinned host port is the description's word
                      cp (remove nil? [(:port d) (:jdwp d)])
                      :when (nil? (get-in cfg [:services (:name d) :ports cp]))]
                  [(:name d) cp])]
    (if (empty? missing)
      cfg
      (let [[cfg' _] (reduce (fn [[cfg taken] [n cp]]
                               (let [p (pick-port c ws cp taken)]
                                 [(assoc-in cfg [:services n :ports cp] p) (conj taken p)]))
                             [cfg (into (claimed-ports ws) (cfg-host-ports cfg))]
                             missing)]
        (store/write-edn! (config-path ws) cfg')
        (sync-local-env! ws cfg')
        cfg'))))

(defn- start-container! [c d id]
  (try (podman/start! c id)
       (catch clojure.lang.ExceptionInfo e
         (if (re-find #"(?i)address already in use|port is already allocated|bind" (str (ex-message e)))
           (throw (ex-info (str "starting " (:role d) " failed on a host port: " (ex-message e))
                           {:type :runtime/port-conflict :role (:role d) :ports (:ports d) :at-start true} e))
           (throw e)))))

(defn- reconcile! [c ws d socket-path sink]
  (let [mine (podman/list-containers c :all true :labels (assoc (ws-labels ws) "deliberator.role" (:role d)))
        [keep & extra] (sort-by #(not= "running" (:State %)) mine)]
    (doseq [x extra] (podman/remove! c (:Id x)))
    (cond
      (and keep (= (:hash d) (label keep "deliberator.spec")))
      (if (= "running" (:State keep))
        {:action :kept :id (:Id keep)}
        (do (emit sink {:event "runtime.starting" :role (:role d) :name (:name d) :action :restart})
            (check-ports! c d)
            (stage! d)
            (start-container! c d (:Id keep))
            {:action :restarted :id (:Id keep)}))
      :else
      (do
        (when keep
          (emit sink {:event "runtime.recreating" :role (:role d) :reason "spec changed (runtime.edn, description, topology, jar)"})
          (podman/remove! c (:Id keep)))
        (when-let [other (podman/inspect c (:name d))]
          (throw (ex-info (str "a container named " (:name d) " exists but is not this workspace's "
                               (:role d) " (workspace=" (pr-str (get-in other [:Config :Labels :deliberator.workspace]))
                               "); set :name for this role in runtime.edn")
                          {:type :runtime/name-conflict :name (:name d) :role (:role d)})))
        (check-ports! c d)
        (stage! d)
        (emit sink {:event "runtime.starting" :role (:role d) :name (:name d) :action :create})
        (let [{:keys [id]} (podman/create! c (container-spec ws d socket-path))]
          (start-container! c d id)
          {:action (if keep :recreated :created) :id id})))))

(defn- ensure-image! [c d sink]
  (when (and (not (podman/image-exists? c (:image d))) (str/starts-with? (str (:image d)) "localhost/"))
    (throw (ex-info (str (:role d) ": image " (:image d) " is built locally and does not exist (bb estate:build)")
                    {:type :runtime/image-missing :role (:role d) :image (:image d)
                     :hint (or (:image-hint d) "bb estate:build builds it; or give the service description a :image of a registry")})))
  (when-not (podman/image-exists? c (:image d))
    (emit sink {:event "runtime.pulling" :role (:role d) :image (:image d)})
    (podman/pull! c (:image d)
                  :on-event (fn [e] (when-let [s (or (:stream e) (:error e))]
                                      (emit sink {:event "runtime.pulling" :role (:role d) :image (:image d)
                                                  :detail (str/trim s)}))))))

(defn- await-healthy! [c ws d id sink timeout-ms]
  (let [t0 (System/nanoTime) deadline (+ t0 (* 1000000 timeout-ms))]
    (loop [last nil]
      (let [state (some-> (podman/inspect c id) (get-in [:State :Status]))]
        (when-not (= "running" state)
          (let [tail (try (mapv :text (doall (podman/logs c id :tail 20))) (catch Exception _ []))]
            (throw (ex-info (str (:role d) " container is " (or state "gone") " while starting; last log lines:\n"
                                 (str/join "\n" tail))
                            {:type :runtime/container-exited :role (:role d) :state state :tail tail})))))
      (let [r (probe c ws d)]
        (cond
          (= :healthy (:health r))
          (emit sink {:event "runtime.ready" :role (:role d) :name (:name d) :detail (:detail r)
                      :ms (quot (- (System/nanoTime) t0) 1000000)})
          (> (System/nanoTime) deadline)
          (throw (ex-info (str (:role d) " not healthy after " timeout-ms " ms: " (:detail r))
                          {:type :runtime/unhealthy :role (:role d) :detail (:detail r)}))
          :else (do (Thread/sleep 500) (recur r)))))))

;; ---------------------------------------------------------------------------------------------
;; Public API

(declare status)

(defn- ensure-once!
  "Reconciles the runtime of `ws` with its `runtime.edn` and the service descriptions and waits until it is healthy.
  Idempotent. `:only` (role labels such as \"service-a\") reconciles just those. `:skip-unbuilt` leaves a service whose jar or exploded
  classpath is not built alone (a `runtime.skipped` event) instead of failing with `runtime/missing-jar`. `:secrets-from` `{role {:env \"local2\"
  :values {ENV value}}}` creates that role with those real secret values instead of the local fakes.
  -> `(status ws)`."
  ([ws] (ensure-once! ws {}))
  ([ws {:keys [sink timeout-ms infra-only only secrets-from skip-unbuilt] :or {timeout-ms default-timeout-ms}}]
   (let [cfg (load-config! ws sink)
         cfg (if infra-only cfg (allocate-service-ports! (connect-client ws cfg) ws cfg))
         unbuilt? (fn [d] (or (and (:jar-path d) (not (.isFile (java.io.File. ^String (:jar-path d)))))
                              (and (:exploded d)
                                   (some #(not (.isDirectory (java.io.File. ^String %)))
                                         (cons (get-in d [:exploded :dependency]) (map :seed (get-in d [:exploded :dirs])))))))
         ds (cond->> (desired-all ws cfg) infra-only (remove :service?) only (filter (comp (set only) :role)))
         ;; `skip-unbuilt`: what a caller that is not about the services (a fake) must not fail on: the service is left as it is
         ds (if skip-unbuilt
              (let [{skipped true kept false} (group-by (comp boolean unbuilt?) ds)]
                (doseq [d skipped] (emit sink {:event "runtime.skipped" :role (:role d) :reason "not built: bb estate:build"}))
                (vec kept))
              ds)
         ;; `secrets-from {role {:env :values}}`: real secret values for that role, held in memory only. The hash
         ;; carries a marker (env + fresh start id), never a value, so such a start always recreates the container.
         ds (mapv (fn [d] (if-let [{:keys [env values]} (get secrets-from (:role d))]
                            (-> d (assoc :secrets-from {:env env :start (subs (str (java.util.UUID/randomUUID)) 0 8)})
                                rehash (assoc :secret-values values))
                            d))
                  ds)
         c (connect-client ws cfg)
         socket-path (delay (or (podman/socket-path c)
                                (throw (ex-info "cannot tell the Podman socket path for this endpoint; give :podman-socket \"/path/to/podman.sock\" in runtime.edn"
                                                {:type :runtime/no-socket-path}))))]
     (doseq [d ds :when (and (:jar-path d) (not (.isFile (java.io.File. ^String (:jar-path d)))))]
       (throw (ex-info (str "runtime.edn: " (:role d) " jar " (:jar-path d) " does not exist (bb estate:build)")
                       {:type :runtime/missing-jar :role (:role d) :jar (:jar-path d) :hint "bb estate:build"})))
     (doseq [d ds :when (:exploded d)
             :let [missing (first (remove #(.isDirectory (java.io.File. ^String %))
                                          (cons (get-in d [:exploded :dependency]) (map :seed (get-in d [:exploded :dirs]))))) ]
             :when missing]
       (throw (ex-info (str (:role d) ": " missing " does not exist (service.build builds it)")
                       {:type :runtime/missing-jar :role (:role d) :path missing :hint "dlb service build <name> (or bb estate:build)"})))
     ;; a container service mounts directories of its repository: a missing one is said by name, not by Podman's statfs error
     (doseq [d ds :when (and (:service? d) (not (:jar-path d)) (not (:exploded d)))
             m (:mounts d)
             :when (not (.exists (java.io.File. ^String (str (:source m)))))]
       (throw (ex-info (str (:role d) ": mount source " (:source m) " does not exist")
                       {:type :runtime/missing-mount :role (:role d) :path (str (:source m)) :hint "the :mounts :from of the service description are relative to its repository; is the repository attached and the path committed?"})))
     (podman/ensure-network! c (:network cfg))
     ;; a service the topology serves elsewhere (or not at all) is not desired: stop it, keep the container
     (when-not infra-only
       (doseq [role (topology-stopped-roles ws)
               ctr (podman/list-containers c :all true :labels (assoc (ws-labels ws) "deliberator.role" role))
               :when (= "running" (:State ctr))]
         (podman/stop! c (:Id ctr))
         (emit sink {:event "runtime.stopped" :role role :name (first (:Names ctr)) :reason (if ((ecs-stopped-roles ws) role) "ecs desired 0" "topology")})))
     (doseq [d ds] (ensure-image! c d sink))
     ;; infrastructure first, then the services that need it (their secrets live in Floci)
     (doseq [group ((juxt remove filter) :service? ds)
             :when (seq group)]
       (let [started (doall (for [d group]
                              (assoc (reconcile! c ws d (when (:podman-socket d)
                                                          (if (string? (:podman-socket d)) (:podman-socket d) @socket-path))
                                                 sink)
                                     :d d)))]
         (doseq [{:keys [d id]} started]
           (await-healthy! c ws d id sink timeout-ms))))
     (status ws))))

;; a host port that cannot be bound -------------------------------------------------------------------------
;; `check-ports!` and `pick-port` look at the host the daemon can see. With the engine behind something else (a podman machine
;; on a Mac, with the daemon in a container next to it) a port can be free there and taken on the machine in front: the bind
;; fails only when the container starts. Then the port is moved, as allocation would have done had it known: the next free
;; candidate (+100 ...), written to runtime.edn, the `local` environment follows, and the start is tried again.

(defn failed-host-port
  "The host port of `ports` ({container-port host-port}) that the engine's start error `msg` names (`listen tcp 127.0.0.1:8081:
  bind: address already in use`, `Bind for 0.0.0.0:8081 failed: port is already allocated`), or nil."
  [ports msg]
  (some (set (vals ports)) (map #(parse-long (second %)) (re-seq #":(\d{2,5})\b" (str msg)))))

(defn port-path
  "Where runtime.edn `cfg` holds host port `hp`: `[:containers role :ports cp]` or `[:services name :ports cp]`, or nil (a
  port the service description pins is not runtime.edn's to move)."
  [cfg hp]
  (or (some (fn [[role spec]] (some (fn [[cp p]] (when (= p hp) [:containers role :ports cp])) (:ports spec))) (:containers cfg))
      (some (fn [[n spec]] (some (fn [[cp p]] (when (= p hp) [:services n :ports cp])) (:ports spec))) (:services cfg))))

(defn- move-port!
  "After a start that failed on a host port (`e`): that port moved to the next free candidate in runtime.edn. -> the new port,
  nil when the error names no port runtime.edn holds."
  [ws sink e]
  (let [{:keys [ports role]} (ex-data e)
        cfg (read-config ws)
        hp (failed-host-port ports (str (ex-message e) " " (some-> (ex-cause e) ex-message)))
        path (when hp (port-path cfg hp))]
    (when path
      (let [p (pick-port (connect-client ws cfg) ws (+ hp port-step) (conj (into (claimed-ports ws) (cfg-host-ports cfg)) hp))
            cfg' (assoc-in cfg path p)]
        (store/write-edn! (config-path ws) cfg')
        (sync-local-env! ws cfg')
        (emit sink {:event "runtime.port-moved" :role role :from hp :to p
                    :reason "the host port could not be bound when the container started"})
        p))))

(defn ensure!
  "Reconciles the runtime of `ws` with its `runtime.edn` and the service descriptions and waits until it is healthy.
  Idempotent. `:only` (role labels such as \"service-a\") reconciles just those. `:skip-unbuilt` leaves a service whose jar or exploded
  classpath is not built alone (a `runtime.skipped` event) instead of failing with `runtime/missing-jar`. `:secrets-from` `{role {:env \"local2\"
  :values {ENV value}}}` creates that role with those real secret values instead of the local fakes.
  A host port that fails at bind when its container starts is moved (a `runtime.port-moved` event, runtime.edn rewritten) and the
  reconcile runs again, at most 5 times.
  -> `(status ws)`."
  ([ws] (ensure! ws {}))
  ([ws opts]
   (loop [n 0]
     (let [r (try (ensure-once! ws opts)
                  (catch clojure.lang.ExceptionInfo e
                    (if (and (< n 5) (= :runtime/port-conflict (:type (ex-data e))) (:at-start (ex-data e))
                             (move-port! ws (:sink opts) e))
                      ::again
                      (throw e))))]
       (if (= ::again r) (recur (inc n)) r)))))

(defn- since-instant [ctr]
  (let [s (:StartedAt ctr)]
    (when (and (number? s) (pos? s)) (Instant/ofEpochSecond s))))

(defn status
  "State of the workspace's runtime without changing it (see the namespace docstring). `:only` (role labels)
  restricts the rows and the probes."
  ([ws] (status ws {}))
  ([ws {:keys [only]}]
   (let [cfg (read-config ws)
         endpoint (podman-endpoint ws cfg)
         c (podman-client ws cfg)]
     (if-not (podman/available? c)
       {:ws (store/ws-name ws)
        :podman {:endpoint endpoint :reachable false :hint "systemctl --user enable --now podman.socket"}
        :containers []}
       (let [ds (when cfg (into {} (map (juxt :role identity)) (desired-all ws cfg)))
             ctrs (cond->> (podman/list-containers c :all true :labels (ws-labels ws))
                    only (filter #((set only) (label % "deliberator.role"))))
             rows (for [ctr ctrs
                        :let [role (label ctr "deliberator.role")
                              d (get ds role)
                              state (keyword (:State ctr))
                              pr (when (and d (= :running state)) (probe c (store/ws-name ws) d))]]
                    (cond-> {:role role :name (first (:Names ctr)) :id (subs (:Id ctr) 0 12) :image (:Image ctr)
                             :state state :since (since-instant ctr)
                             :health (:health pr) :detail (:detail pr)
                             :ports (into (sorted-map) (for [p (:Ports ctr)] [(:container_port p) (:host_port p)]))}
                      d (assoc :drift? (not= (:hash d) (label ctr "deliberator.spec")))))
             present (set (map :role rows))
             absent (for [[role d] ds :when (and (not (present role)) (or (nil? only) ((set only) role)))]
                      {:role role :name (:name d) :image (:image d) :state :absent :ports (:ports d)})
             v (podman/info c)]
         {:ws (store/ws-name ws)
          :podman {:endpoint endpoint :reachable true :version (get-in v [:version :Version])
                   :rootless (get-in v [:host :security :rootless])}
          :containers (vec (sort-by :role (concat rows absent)))})))))

(defn down!
  "Removes the containers labelled with workspace `ws` (and only those). The network stays.
  -> `{:removed [{:role :name}]}`."
  ([ws] (down! ws {}))
  ([ws {:keys [sink]}]
   (let [cfg (read-config ws)
         c (connect-client ws cfg)
         ctrs (podman/list-containers c :all true :labels (ws-labels ws))]
     {:removed (vec (for [ctr ctrs]
                      (do (podman/remove! c (:Id ctr))
                          (emit sink {:event "runtime.removed" :role (label ctr "deliberator.role") :name (first (:Names ctr))})
                          {:role (label ctr "deliberator.role") :name (first (:Names ctr))})))})))

(defn stop-role!
  "Stops (keeps) the container of `role` (label, e.g. \"service-a\") of `ws`. -> the container name stopped, nil when there is none."
  [ws role]
  (let [c (connect-client ws (read-config ws))
        ctr (first (podman/list-containers c :all true :labels (assoc (ws-labels ws) "deliberator.role" role)))]
    (when ctr
      (podman/stop! c (:Id ctr))
      (first (:Names ctr)))))

(defn image-id
  "The id of `image` in the runtime's Podman store (not persisted: the watch asks at a revision), nil when it is not there or there is no runtime.edn."
  [ws image]
  (when-let [cfg (read-config ws)]
    (podman/image-id (podman-client ws cfg :persist? false) image)))

(defn role-container
  "The container of `role` of `ws` as Podman lists it (`:State`, `:Names`, `:Id`), nil when there is none or no runtime.edn.
  Quiet: the call is not persisted (the ECS watch asks every second)."
  [ws role]
  (when-let [cfg (read-config ws)]
    (first (podman/list-containers (podman-client ws cfg :persist? false) :all true
                                   :labels (assoc (ws-labels ws) "deliberator.role" role)))))

(defn remove-role!
  "Stops and removes the container of `role` (label, e.g. \"fake-a\") of `ws`. -> the container name removed, nil when there is none."
  [ws role]
  (let [c (connect-client ws (read-config ws))]
    (first (doall (for [ctr (podman/list-containers c :all true :labels (assoc (ws-labels ws) "deliberator.role" role))]
                    (do (podman/remove! c (:Id ctr)) (first (:Names ctr))))))))

(defn logs
  "Log events of `role` of `ws` (`{:stream :at :text}`), mirrored to `logs/services/<role>/<date>.log`.
  `opts`: `:since` Instant, `:follow`, `:tail`."
  [ws role opts]
  (let [role (if (keyword? role) (role-name role) (str role))
        c (connect-client ws (read-config ws))
        ctr (first (podman/list-containers c :all true :labels (assoc (ws-labels ws) "deliberator.role" role)))]
    (when-not ctr
      (throw (ex-info (str "no container for role " role " in workspace " (store/ws-name ws))
                      {:type :runtime/no-such-role :role role})))
    (apply podman/logs c (:Id ctr) :mirror [(store/ws-name ws) role]
           (mapcat identity (select-keys opts [:since :follow :tail :until])))))

;; ---------------------------------------------------------------------------------------------
;; local environment follows the runtime

(defn- db-url
  "The JDBC URL `url` on host port `port`, with the Postgres credentials of the runtime's `:postgres`
  env (`POSTGRES_USER`, `POSTGRES_PASSWORD`) appended as `user=`/`password=` when the URL has none."
  [url port pg-env]
  (let [url (str/replace url #"//localhost:\d+/" (str "//localhost:" port "/"))
        user (get pg-env "POSTGRES_USER") pw (get pg-env "POSTGRES_PASSWORD")]
    (if (or (str/includes? url "user=") (not user))
      url
      (str url (if (str/includes? url "?") "&" "?") "user=" user (when pw (str "&password=" pw))))))

(defn sync-local-env!
  "Points the workspace's `local` environment at the host ports of `cfg` (Floci endpoint, Postgres)
  and at the service roles in effect (`effective-containers`: `service/<name>` on the host port of the
  service's port, `log/service-<name>` the container), leaving every other field alone. `db/main` carries
  the runtime's Postgres credentials (`?user=&password=`) so the `sql` kind can connect."
  [ws cfg]
  (let [hp (fn [role cp] (get-in cfg [:containers role :ports cp]))
        e (env/load ws "local")
        e (cond-> e
            (hp :floci 4566) (assoc-in [:aws :endpoint] (str "http://localhost:" (hp :floci 4566)))
            (and (hp :postgres 5432) (string? (get-in e [:targets :db/main])))
            (update-in [:targets :db/main] #(db-url % (hp :postgres 5432) (get-in cfg [:containers :postgres :env]))))
        e (reduce (fn [e [role spec]]
                    (if (and (= "service" (namespace role)) (or (:jar spec) (:exploded spec) (:service? spec)))
                      (let [n (name role)
                            port (:port (service-health spec))
                            host (get (:ports spec) port)]
                        (cond-> (assoc-in e [:targets (keyword "log" (str "service-" n))] {:container (or (:name spec) (store/container-name ws (role-name role)))})
                          host (assoc-in [:targets role] (str "http://localhost:" host))))
                      e))
                  e (sort-by (comp str key) (declared-containers ws cfg)))]
    (env/save! ws e)))

(defn sync-env!
  "Creates `runtime.edn` when absent (the default config) and points the workspace's `local`
  environment at its host ports and container names. -> `{:ws :path}` of the runtime.edn."
  [ws]
  (load-config! ws nil)
  (sync-local-env! ws (read-config ws))
  {:ws (store/ws-name ws) :path (str (config-path ws))})

(defn set-config!
  "Sets the key path `ks` of the workspace's `runtime.edn` (created with the defaults when absent) to `v` and points the
  `local` environment at the result (as `sync-env!`). The one way a task or client changes runtime.edn (review 2026-10-25,
  R33). -> `{:ws :path :keys :value}`."
  [ws ks v]
  (when (empty? ks) (throw (ex-info "runtime.set needs a key path" {:type :runtime/bad-path :hint "e.g. containers postgres init"})))
  (let [cfg (assoc-in (load-config! ws nil) (vec ks) v)]
    (store/write-edn! (config-path ws) cfg)
    (sync-local-env! ws cfg)
    {:ws (store/ws-name ws) :path (str (config-path ws)) :keys (vec ks) :value v}))

(defn endpoint-holder
  "Who publishes the host port of a local `http://host:port` endpoint: `{:port :container :ours?}` when a running container
  does (`:ours?` when it carries this workspace's label), nil when none does, when the host is not local, or when Podman
  does not answer. Review 2026-10-25, R31: a sandbox AWS account's emulator on 4566 is where a default `local` environment talks to."
  [ws url]
  (try
    (let [u (java.net.URI. (str url))
          host (.getHost u)
          port (let [p (.getPort u)] (if (neg? p) (if (= "https" (.getScheme u)) 443 80) p))]
      (when (contains? #{"localhost" "127.0.0.1" "::1" "0.0.0.0" "[::1]"} host)
        (let [c (podman-client ws (read-config ws) :persist? false)]
          (some (fn [ctr]
                  (when (some #(= port (:host_port %)) (:Ports ctr))
                    {:port port :container (first (:Names ctr))
                     :ours? (= (store/ws-name ws) (label ctr "deliberator.workspace"))}))
                (podman/list-containers c :all false)))))
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------------------------
;; Command line

(defn- print-event [{:keys [event role image detail name ms]}]
  (println (str/join " " (remove nil? [event role (or name image) detail (when ms (str ms "ms"))]))))

(defn- print-status [{:keys [ws podman containers]}]
  (println (str "workspace " ws " podman " (:endpoint podman)
                (if (:reachable podman) (str " " (:version podman)) " UNREACHABLE")))
  (doseq [{:keys [role name id state since health detail ports drift?]} containers]
    (println (format "  %-10s %-22s %-8s %-10s %s%s%s"
                     role name (clojure.core/name state) (if health (clojure.core/name health) "-")
                     (str/join "," (for [[cp hp] ports] (str hp "->" cp)))
                     (if drift? "  DRIFT (runtime.edn changed)" "")
                     (if detail (str "  " detail) "")))))

(defn -main [& args]
  (let [[cmd & more] args
        ws (or (second (drop-while #(not= "--ws" %) more)) "default")
        opts {:sink print-event}
        code (try
               (case cmd
                 "up" (print-status (ensure! ws (assoc opts :infra-only (boolean (some #{"--infra"} more)))))
                 "status" (print-status (status ws opts))
                 "down" (doseq [r (:removed (down! ws opts))] (println "removed" (:name r)))
                 "sync-env" (do (sync-env! ws) (println "local environment follows runtime.edn"))
                 (do (println "usage: runtime up|status|down [--ws NAME]") (throw (ex-info "usage" {:type :usage}))))
               0
               (catch clojure.lang.ExceptionInfo e
                 (binding [*out* *err*]
                   (println "runtime" cmd "failed:" (ex-message e))
                   (when-let [h (:hint (ex-data e))] (println "  hint:" h)))
                 1))]
    (shutdown-agents)
    (System/exit code)))
