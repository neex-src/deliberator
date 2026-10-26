(ns deliberator.ops
  "The operation table: `area.verb` -> `{:fn :args :class :doc :events}`; `op.describe` returns it and
  is what the CLI renders as help, the IDE as actions and an agent as tools. The ops are adapters:
  they validate the arguments against the schema below, call the namespace that owns the behaviour
  and return a small map (summaries plus paths; full data is on disk). Wire format: see
  `deliberator.protocol`. `(call! op ctx args)` is the one entry point; `ctx` is the request context
  of the protocol plus `:daemon` (`{:version :pid :home :endpoint :runs :checked :stop-later!}`).

  Argument schema: `{name {:type :required :positional :doc :default}}` (`:positional`: an optional arg that `dlb` takes as a bare word), types `string int number bool string[]
  int[] object any`. Unknown, missing or ill-typed arguments are a `bad-args` error naming the
  problems and the schema; defaults are applied. Every op that touches a workspace takes `ws`
  (default \"default\"; ops without one ignore a `ws` argument). Class: `read` (changes nothing), `mutate` (changes the store, the runtime or
  the world). Errors on the wire: `{type message hint ...}`; `type` is the ex-data `:type` of the
  throwing namespace (`env/not-found`, `scenario/parse`, `runtime/port-conflict`, ...), plus
  `bad-args`, `unknown-op`, `internal`.

  The M1 operations (events in brackets are streamed before the terminal line):

    hello client version                           -> {version pid home endpoint protocol runs scenario-runs follows compatible}   (runs = work in flight: scenario runs + log follows)
    op.describe                                    -> [{op class doc args order events}]   (order = argument names as declared)
    workspace.open path ws                         -> {ws dir project created}   (the one op that creates a workspace on purpose)
    workspace.show ws                              -> {ws path workspace environments}   (reading ops: unknown ws is workspace/not-found)
    session.note name* text* session ws            -> {path}
    explore.write name* text* if-missing ws        -> {path created written}   (<workspace project>/explore/<name>, for the IDE's explorer)
    session.start key* title ws                    -> {session path}   (reopens an existing key)
    session.end key ws                             -> {session path}   (default: the current session)
    session.list ws                                -> {sessions current}
    session.current ws                             -> {session path}
    env.list ws                                    -> {environments [{name kind account ...}]}
    env.describe name* ws                          -> {env path}
    env.check name* ws                             -> {ok account expected targets [error] [warning endpoint-held-by]}
    env.save name* record* check ws                -> {env kind path [account checked read-only]}   (the one way to write environments/<name>.edn: env/validate; a remote record is written only when its profile is in its account)
    env.create name* profile* account* region* read-only description endpoint targets check ws -> as env.save   (a remote record from flags; refuses an existing name)
    env.remove name* ws                            -> {removed kind path}   (not `local`)
    runtime.set path* value* ws                    -> {ws path keys value}   (one value of runtime.edn by key path, then sync-env)
    daemon.stop                                    -> {stopping pid runs}   (the daemon exits a moment later)
    runtime.sync-env ws                            -> {ws path}
    service.adopt path* taskdef name jdwp ws       -> {name created path repo module from}
    service.list env ws                            -> [{name kind repo module port jdwp image mode [url] status}]
    topology.show env* ws                          -> {env services hash}
    topology.set env* name* mode* url container-url log-container ws -> as show + {dependents}
    service.show name* ws                          -> the description + {status container runtime}
    service.status name ws                         -> [{name status health detail container since drift}]
    service.build name* ws                         -> {artifact bytes took-ms log sha}   [service.build.line]
    service.start name* secrets-from confirm ws    -> {name status container port jdwp}   [runtime.* service.started]
    service.stop name* ws                          -> {name status container}   [service.stopped]
    service.restart name* secrets-from confirm ws  -> as start   [service.stopped runtime.* service.started]
    service.reload name* budget-ms ws              -> {name mechanism changed redefined took-ms log reload-count [fallback]}   [service.reload.step]
    config.chain name* env ws                      -> {service env module snapshot files entries extra broken unresolved hint}   (offline)
    config.refresh env path ws                     -> {env fetched-at parameters secrets path copy raw errors}
    deployed.refresh env ws                        -> {env fetched-at services ecs lambda deployments path text raw errors}   (M4.2: ECS, Lambda, CodeDeploy into cache/<env>/deployed.edn)
    deployed.show env service ws                   -> {env fetched-at age-ms age services count path text}   (from the cache only, never AWS)
    runtime.ensure ws infra-only timeout-ms        -> status   [runtime.pulling runtime.starting runtime.ready]
    runtime.status ws                              -> {ws podman containers}
    runtime.down ws                                -> {removed}  [runtime.removed]
    runtime.logs role* ws since follow tail        -> {lines paths}   [line {stream at text}]
    scenario.run path* env session steps confirm ws -> {status steps failed checks failed-checks run-id paths}
                                                      [run.started step.started line confirm.required step.finished run.finished]
    scenario.cancel run-id*                        -> {run-id cancelled}
    scenario.kinds                                 -> [{kind verbs verb-optional target directives facts capture-roots concurrent default-timeout-ms}]
    aws.discover env ws                            -> {env resources{kind [{name ref}]} errors}
    terraform.drift env file dir plan ws           -> {env source region declared matching missing[] undeclared[] errors}
    terraform.local dir* action env vars ws         -> {action dir env ok exit summary sha path output vars}   [tf.line]   (action vars: {action dir env vars} only)
    aws.describe service* op*                      -> {service op class input}   (input = shape tree: type required members member enum)
    scenario.parse path text ws                    -> {header steps errors}   (a syntax error is data in errors, not an op error)
    runs.list ws session scenario limit            -> {session runs}
    repo.attach path* kind ws                      -> {name path kind attached-at}
    repo.detach name* ws                           -> {removed}
    repo.list ws                                   -> [{name path kind attached-at}]
    logs.fetch target* env since for follow filter session ws -> {path lines bytes sha target source [follow]}   [logs.line]
    logs.insights groups* query* env since until name budget-ms limit session ws -> {query-id status bytes-scanned statistics rows path sha query-file raw}   [line]   (M4.3)
    logs.match path* markers ws                    -> {lines [{n marker-id}] path scanned}
    marker.add file* line* logger ws               -> [{id file line logger}]   (the marker list after the change)
    marker.remove file* line* ws                   -> [{id file line logger}]
    marker.list file ws                            -> [{id file line logger}]
    runs.show run* ws session                      -> {run paths}
    runs.render run* ws session                    -> {paths trailer}
    runs.diff a* b* ws session                     -> {equal diff a b}
    raw.meta sha* ws                               -> {sha path meta}
    raw.get sha* ws                                -> {sha path bytes meta [text]}   (text when <= 64 KB and textual)

  (`*` = required.) A `scenario.run` that hit a remote `:mutate` step ends with the terminal error
  `confirm-required` carrying `token`, `summary`, `step`, `confirmations` and the finished run's
  `result`; re-running with `confirm` set to the token lets that exact request through.
  A run on a `:remote` environment first checks the account once per session (`account-mismatch`)."
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.config :as config]
            [deliberator.deployed :as deployed]
            [deliberator.insights :as insights]
            [deliberator.env :as env]
            [deliberator.logfetch :as logfetch]
            [deliberator.awsdiscover :as awsdiscover]
            [deliberator.tfdrift :as tfdrift]
            [deliberator.tflocal :as tflocal]
            [deliberator.awsmodel :as awsmodel]
            [deliberator.markers :as markers]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.parse :as parse]
            [deliberator.scenario.render :as render]
            [deliberator.service :as service]
            [deliberator.service.reload :as reload]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.topology :as topology]
            [deliberator.workspace :as workspace])
  (:import (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio ByteBuffer)
           (java.nio.file Files LinkOption Path)
           (java.time Instant)
           (java.time.format DateTimeParseException)))

;; errors --------------------------------------------------------------------------------------

(defn- fail
  "Throws an ex-info with `type` (keyword), message and extra data."
  [type msg & {:as data}]
  (throw (ex-info msg (assoc data :type type))))

(def ^:private error-keys
  [:hint :known :problems :line :token :summary :step :confirmations :result :expires :target :env
   :role :key :sha :schema :account :expected :found :log :exit :service :lines :errors
   :of :names :sources :output :endpoint :command :ref])

(defn error-map
  "The wire error of a throwable: `{:type :message [:hint ...]}`."
  [^Throwable e]
  (let [d (ex-data e)
        t (:type d)]
    (merge {:type (cond (keyword? t) (subs (str t) 1)
                        t (str t)
                        :else "internal")
            :message (or (ex-message e) (str (class e)))}
           (select-keys d error-keys))))

;; argument schema -----------------------------------------------------------------------------

(def ^:private types
  {:string [string? "string"]
   :int [integer? "int"]
   :number [number? "number"]
   :bool [boolean? "bool"]
   :strings [#(and (sequential? %) (every? string? %)) "string[]"]
   :ints [#(and (sequential? %) (every? integer? %)) "int[]"]
   :object [map? "object"]
   :any [(constantly true) "any"]})

(def ^:private ws-arg {:type :string :default "default" :doc "workspace name"})

(defn- arg-order
  "Argument names of `schema` in declared order (`ws-schema` keeps it in metadata)."
  [schema]
  (mapv name (or (:order (meta schema)) (keys schema))))

(def ^:private ignored-ws-arg {:doc "accepted and ignored: this operation is not bound to a workspace"})

(defn- describe-args [schema]
  (into (sorted-map)
        (for [[k {:keys [type required doc default positional]}] schema]
          [(name k) (cond-> {:type (second (types type)) :required (boolean required) :doc doc}
                      (some? default) (assoc :default default)
                      positional (assoc :positional true))])))

(defn validate-args
  "Applies defaults and checks `args` against `schema`; throws `bad-args` listing every problem."
  [op schema args]
  (let [unknown (remove #(or (contains? schema %) (= :ws %)) (keys args))   ; ws is accepted by every op (ignored without one)
        problems (vec (concat
                       (for [k (sort unknown)] (str "unknown argument " (name k)))
                       (for [[k {:keys [required]}] schema
                             :when (and required (nil? (get args k)))]
                         (str "missing required argument " (name k)))
                       (for [[k {:keys [type]}] schema
                             :let [v (get args k)]
                             :when (and (some? v) (not ((first (types type)) v)))]
                         (str "argument " (name k) " must be " (second (types type))))))]
    (when (seq problems)
      (fail :bad-args (str op ": " (str/join "; " problems))
            :problems problems :schema (describe-args schema)
            :hint "op.describe lists the arguments of every operation"))
    (reduce-kv (fn [m k {:keys [default]}] (if (and (nil? (get m k)) (some? default)) (assoc m k default) m))
               args schema)))

;; helpers -------------------------------------------------------------------------------------

(defn- ws-of [args] (:ws args))

(defn- exists? [^Path p] (Files/exists p (make-array LinkOption 0)))

(defn- session-key
  "The session of the request: the argument, else the workspace's current session."
  [ws args]
  (or (:session args) (workspace/current-session ws)
      (fail :session/none "no session: pass session or start one (session.start)")))

(defn- name-list [m] (mapv #(subs (str %) 1) (sort-by str (keys m))))

(defn- aws-error [e] (select-keys e [:type :category :code :message :hint]))

;; handlers: hello, workspace, sessions --------------------------------------------------------

(defn compatible?
  "Does a client that ships `want` speak to a daemon of version `have`? Equal versions do. A jar build stamps its version
  (`0.1.0-m1+<yyyyMMddHHmmss>`, core/build.clj), so a daemon of another build is not compatible and the client replaces it when
  its own is newer. An unstamped daemon runs from source (cljdev, `dev/start!`): it serves any build of its base version."
  [want have]
  (or (= want have)
      (and (not (str/includes? (str have) "+")) (= (first (str/split (str want) #"\+")) have))))

(defn- hello [ctx {:keys [version]}]
  (let [d (:daemon ctx)
        runs (count @(:runs d))
        follows (if-let [f (:follows d)] @f 0)]
    ;; `:runs` is the work in flight a replacement must not kill: scenario runs AND active log follows (the IDE's service
    ;; console is a follow, review 2026-10-25 R32); the two parts are given too
    (cond-> {:version (:version d) :pid (:pid d) :home (str (:home d)) :endpoint (:endpoint d) :protocol 1
             :runs (+ runs follows) :scenario-runs runs :follows follows}
      version (assoc :compatible (compatible? version (:version d))))))

(declare table)

(defn describe
  "The table as data: `[{:op :class :doc :args :order :events}]`, sorted by op; `:order` lists the
  argument names in declared order (required ones are the positionals of the CLI, in that order)."
  []
  (vec (for [[op {:keys [class doc args events]}] (sort-by key table)
             :let [ws? (contains? args :ws)]]
         {:op op :class (name class) :doc doc
          :args (cond-> (describe-args args)
                  (not ws?) (assoc "ws" {:type "string" :required false :doc (:doc ignored-ws-arg) :ignored true}))
          :order (cond-> (arg-order args) (not ws?) (conj "ws"))
          :events (vec events)})))

(defn workspace-exists? [ws] (exists? (store/path ws "workspace.edn")))

(defn- require-workspace!
  "Reading ops never create a workspace: an unknown one is `workspace/not-found`."
  [ws]
  (when-not (Files/isDirectory (store/path ws) (make-array LinkOption 0))
    (fail :workspace/not-found (str "no workspace " (store/ws-name ws))
          :hint "workspace.open creates it (dlb workspace open NAME); check the spelling of --ws")))

(defn- workspace-open [_ {:keys [path] :as args}]
  (let [ws (ws-of args)
        _ (store/ws-name ws)
        created (not (workspace-exists? ws))
        _ (when (and path (not (.isAbsolute (io/file (str path)))))
            (fail :bad-args "workspace.open: path must be absolute" :problems ["path"]
                  :hint "the directory of an existing project to bind, or leave it out for <workspace dir>/project"))
        w (workspace/load ws)
        project (if path (str (.normalize (.toPath (io/file (str path))))) (str (store/path ws "project")))
        w (cond-> w path (assoc :project project))]
    (workspace/save! w)
    (env/load ws "local")
    (Files/createDirectories (.toPath (io/file project)) (make-array java.nio.file.attribute.FileAttribute 0))
    {:ws (store/ws-name ws) :dir (str (store/path ws)) :project project :created created}))

(defn- session-note [_ {:keys [name text] :as args}]
  (let [ws (ws-of args)]
    (select-keys (session/note! ws (session-key ws args) name text) [:path])))

(defn- workspace-show [_ args]
  (let [ws (ws-of args)]
    {:ws (store/ws-name ws) :path (str (store/path ws)) :workspace (workspace/load ws)
     :environments (env/names ws)}))

(defn- explore-write
  "Writes `text` as `<workspace project>/explore/<name>` (the daemon names the place, the IDE opens the path it gets);
  `if-missing` leaves an existing file alone (the explorer's `explore-<env>.scn` is created once, then edited in the editor)."
  [_ {:keys [name text if-missing] :as args}]
  (let [ws (ws-of args)
        _ (when-not (workspace-exists? ws) (fail :workspace/not-found (str "workspace " (store/ws-name ws) " does not exist")))
        w (workspace/load ws)
        project (or (:project w) (str (store/path ws "project")))
        safe (-> (str name) (str/replace #"[^A-Za-z0-9._-]+" "-") (str/replace #"^[.-]+" ""))
        _ (when (str/blank? safe) (fail :bad-args "explore.write: name is empty" :problems ["name"]))
        f (.toPath (io/file project "explore" safe))
        existed (exists? f)]
    (if (and existed if-missing)
      {:path (str f) :created false :written false}
      (do (store/write-bytes-atomic! f (.getBytes (str text) "UTF-8"))
          {:path (str f) :created (not existed) :written true}))))

(defn- session-info [ws s] (when s {:session s :path (str (session/dir ws (:key s)))}))

(defn- session-start [_ {:keys [key title] :as args}]
  (let [ws (ws-of args)]
    (session-info ws (session/start! ws key :title title))))

(defn- session-end [_ {:keys [key] :as args}]
  (let [ws (ws-of args)]
    (session-info ws (session/end! ws (or key (workspace/current-session ws)
                                          (fail :session/none "no current session to end; pass key"))))))

(defn- session-list [_ args]
  (let [ws (ws-of args)]
    {:sessions (session/list ws) :current (workspace/current-session ws)}))

(defn- session-current [_ args]
  (let [ws (ws-of args)]
    (or (session-info ws (session/current ws)) {:session nil :path nil})))

;; handlers: environments ----------------------------------------------------------------------

(defn- env-row [e]
  (cond-> {:name (:name e) :kind (:kind e) :account (get-in e [:aws :account])
           :region (get-in e [:aws :region]) :targets (count (:targets e))}
    (:description e) (assoc :description (:description e))
    (= :local (:kind e)) (assoc :endpoint (get-in e [:aws :endpoint]))
    (= :remote (:kind e)) (assoc :profile (get-in e [:aws :profile]) :read-only (boolean (:read-only e)))))

(defn- env-list [_ args]
  (let [ws (ws-of args)]
    {:environments (vec (for [n (env/names ws)]
                          (try (env-row (env/load ws n))
                               (catch Exception e {:name n :error (error-map e)}))))}))

(defn- resolved-targets
  "`:targets` of `e` with `service/<n>` (and the log of an external instance that names one) as the topology
  resolves them; a service that is off shows \"off\"."
  [e]
  (reduce (fn [ts n]
            (let [svc (keyword "service" n) lg (keyword "log" (str "service-" n))]
              (cond-> (assoc ts svc (try (env/target e svc)
                                         (catch clojure.lang.ExceptionInfo x
                                           (if (= :topology/service-off (:type (ex-data x))) "off" (throw x)))))
                (:log (env/service-entry e n)) (assoc lg (env/target e lg)))))
          (or (:targets e) {}) (keys (:topology e))))

(defn- env-describe [_ {:keys [name] :as args}]
  (let [ws (ws-of args)
        e (env/load ws name)]
    (cond-> {:env (cond-> e (:topology e) (assoc :targets (resolved-targets e)))
             :path (str (store/path ws "environments" (str (store/safe-name name) ".edn")))}
      (:topology e) (assoc :topology (:services (topology/show ws name))))))

(defn- env-check [_ {:keys [name] :as args}]
  (let [ws (ws-of args)
        e (env/load ws name)
        r (aws/check {:ws ws} e)
        holder (when (= :local (:kind e)) (runtime/endpoint-holder ws (get-in e [:aws :endpoint])))]
    (cond-> {:env name :kind (:kind e) :ok (boolean (:ok? r)) :account (:account r) :expected (:expected r)
             :targets (name-list (:targets e))}
      (:error r) (assoc :error (aws-error (:error r)))
      (and holder (not (:ours? holder)))
      (assoc :warning (str "port " (:port holder) " of the endpoint " (get-in e [:aws :endpoint]) " is published by container "
                           (:container holder) ", which is not this workspace's runtime: the local environment talks to that emulator,"
                           " not to the Floci of runtime.edn (stop it, or move this workspace's ports)")
             :endpoint-held-by (:container holder)))))

(defn- keywordize
  "JSON's string keys -> keywords at every level (a record arrives as nested objects; the protocol keywordizes only the top)."
  [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize v)])) x)
        (sequential? x) (mapv keywordize x)
        :else x))

(declare check-account!)

(defn- checked-key
  "What `verify-account!` remembers for the daemon's life: the workspace, session, environment, and the account and
  profile of the record (a profile edited in the file is asked again)."
  [ws sess e]
  [(store/ws-name ws) sess (:name e) (get-in e [:aws :account]) (get-in e [:aws :profile])])

(defn- env-save [ctx {:keys [name record check] :as args}]
  (let [ws (ws-of args)
        _ (store/safe-name name)
        r (-> (keywordize record) (assoc :name name))
        r (cond-> r (string? (:kind r)) (update :kind keyword))
        r (env/validate r)
        sess (workspace/current-session ws)
        ;; a remote record is written only for an account it really is in: the profile must exist, log in and say so
        account (when (and (= :remote (:kind r)) (not (false? check)))
                  (try (check-account! ws sess r)
                       (catch Throwable t
                         (env/forget! ws (:name r))      ; the client of the record that was refused is not kept
                         (throw t))))
        e (env/save! ws r)]
    (when account
      (swap! (or (get-in ctx [:daemon :checked]) (atom #{})) conj (checked-key ws sess e)))
    (cond-> {:env (:name e) :kind (:kind e) :path (str (store/path ws "environments" (str (store/safe-name name) ".edn")))}
      (= :remote (:kind e)) (assoc :account (get-in e [:aws :account]) :checked (boolean account)
                                   :read-only (env/read-only? e)))))

(defn- env-create [ctx {:keys [name profile account region read-only description endpoint targets check] :as args}]
  (let [ws (ws-of args)
        rec (cond-> {:kind :remote
                     :read-only (if (nil? read-only) true read-only)
                     :aws (cond-> {:profile profile :region region :account account}
                            endpoint (assoc :endpoint endpoint))
                     :targets (or targets {})}
              description (assoc :description description))]
    (store/safe-name name)
    (env/validate (assoc rec :name name))
    (when (contains? (set (env/names ws)) name)
      (fail :env/exists (str "environment " name " exists already")
            :hint (str "`dlb env remove " name "` first, or `dlb env save` to replace the whole record") :env name))
    (env-save ctx {:ws (:ws args) :name name :check check :record rec})))

(defn- env-remove [ctx {:keys [name] :as args}]
  (let [ws (ws-of args)
        e (env/remove! ws name)]
    (when-let [c (get-in ctx [:daemon :checked])]
      (swap! c (fn [s] (into #{} (remove (fn [k] (and (= (store/ws-name ws) (first k)) (= (:name e) (nth k 2))))) s))))
    {:removed (:name e) :kind (:kind e)
     :path (str (store/path ws "environments" (str (store/safe-name name) ".edn")))}))

(defn- runtime-set [_ {:keys [path value] :as args}]
  (runtime/set-config! (ws-of args)
                       (mapv (fn [k] (if (re-matches #"\d+" k) (parse-long k) (keyword k))) path)
                       (keywordize value)))

;; handlers: runtime ---------------------------------------------------------------------------

(defn- runtime-ensure [ctx {:keys [infra-only timeout-ms] :as args}]
  (runtime/ensure! (ws-of args)
                   (cond-> {:sink (:emit! ctx)}
                     infra-only (assoc :infra-only true)
                     timeout-ms (assoc :timeout-ms timeout-ms))))

(defn- runtime-status [_ args] (runtime/status (ws-of args)))

(defn- runtime-down [ctx args] (runtime/down! (ws-of args) {:sink (:emit! ctx)}))

(defn- runtime-sync-env [_ args] (runtime/sync-env! (ws-of args)))

(defn- service-adopt [_ {:keys [path taskdef name jdwp] :as args}]
  (service/adopt! (ws-of args) {:path path :taskdef taskdef :name name :jdwp jdwp}))
(defn- config-chain [_ {:keys [name env] :as args}] (config/chain (ws-of args) name (or env "local")))
(declare verify-account!)

(defn- verify-remote!
  "`verify-account!` for an operation that takes an environment name: loads the record (a missing one fails as
  before) and checks the account when it is remote. The session is the request's or the current one, nil when none."
  [ctx ws env-name]
  (let [e (env/load ws env-name)]
    (verify-account! (:daemon ctx) ws (or (workspace/current-session ws) nil) e)
    e))

(defn- config-refresh [ctx {:keys [env path] :as args}]
  (let [ws (ws-of args) env-name (or env "local")]
    (verify-remote! ctx ws env-name)
    (config/refresh! ws env-name {:path (or path "/")})))
(defn- service-list [_ {:keys [env] :as args}] (service/list (ws-of args) (or env "local")))
(defn- topology-show [_ {:keys [env] :as args}] (topology/show (ws-of args) env))
(defn- topology-set [_ {:keys [env name mode url log-container container-url] :as args}]
  (topology/set! (ws-of args) env name mode url {:log-container log-container :container-url container-url}))
(defn- service-show [_ {:keys [name] :as args}] (service/show (ws-of args) name))
(defn- service-status [_ {:keys [name] :as args}] (service/status (ws-of args) name))
(defn- service-build [ctx {:keys [name] :as args}] (service/build! (ws-of args) name {:sink (:emit! ctx) :on-cancel! (:on-cancel! ctx)}))
(defn- service-start [ctx {:keys [name secrets-from confirm] :as args}]
  (when secrets-from (verify-remote! ctx (ws-of args) secrets-from))
  (service/start! (ws-of args) name {:sink (:emit! ctx) :secrets-from secrets-from :confirm confirm}))
(defn- service-stop [ctx {:keys [name] :as args}] (service/stop! (ws-of args) name {:sink (:emit! ctx)}))
(defn- service-reload [ctx {:keys [name budget-ms] :as args}]
  (reload/reload! (ws-of args) name (cond-> {:sink (:emit! ctx) :on-cancel! (:on-cancel! ctx)} budget-ms (assoc :budget-ms budget-ms))))
(defn- service-restart [ctx {:keys [name secrets-from confirm] :as args}]
  (when secrets-from (verify-remote! ctx (ws-of args) secrets-from))
  (service/restart! (ws-of args) name {:sink (:emit! ctx) :secrets-from secrets-from :confirm confirm}))

(defn- daemon-stop [ctx _]
  (let [d (:daemon ctx)
        ids (vec (sort (keys @(:runs d))))]
    ((:stop-later! d))
    {:stopping true :pid (:pid d) :runs ids}))

(defn- runtime-logs [ctx {:keys [role since follow tail] :as args}]
  (let [ws (ws-of args)
        s (runtime/logs ws role (cond-> {}
                                  ;; the same forms as logs.fetch: an ISO-8601 instant or a duration ago (10m)
                                  since (assoc :since (logfetch/parse-since since (java.time.Instant/now)))
                                  ;; like logs.fetch (B6): a follow without since or tail does not replay the whole log
                                  (and follow (not since) (not tail)) (assoc :since (java.time.Instant/now))
                                  follow (assoc :follow true)
                                  tail (assoc :tail tail)))
        n (volatile! 0)]
    ((:on-cancel! ctx) (fn [] (when-let [c (:close (meta s))] (c))))
    (try
      (doseq [{:keys [stream at text]} s
              :while (and ((:alive? ctx)) (not ((:cancelled? ctx))))]
        ((:emit! ctx) {:event :line :stream stream :at at :text text})
        (vswap! n inc))
      (catch Exception e (when-not (or ((:cancelled? ctx)) (not ((:alive? ctx)))) (throw e)))
      (finally (when-let [c (:close (meta s))] (try (c) (catch Exception _ nil)))))
    {:lines @n :cancelled (boolean ((:cancelled? ctx)))
     :paths {:log (str (store/path ws "logs" "services" role))}}))

;; handlers: scenarios -------------------------------------------------------------------------

(defn- resolve-scenario
  "A scenario file: an absolute path as it is; a relative one in the session's `scenarios/`, else in the session directory
  itself (`queries/<name>.scn`, the named-query convention, M4.5)."
  [ws sess path]
  (let [p (io/file path)
        sdir (session/dir ws sess)
        in-scenarios (.toFile (.resolve (.resolve sdir "scenarios") ^String path))
        in-session (when-not (.isAbsolute p)
                     (let [f (.normalize (.resolve sdir ^String path))] (when (.startsWith f sdir) (.toFile f))))
        f (cond (.isAbsolute p) p
                (.isFile in-scenarios) in-scenarios
                (and in-session (.isFile in-session)) in-session
                :else in-scenarios)]
    (when-not (.isFile f)
      (fail :scenario/not-found (str "no scenario file " f)
            :hint (if (.isAbsolute p) "pass an existing file" "a relative path is looked up in the session's scenarios/ directory, then in the session directory itself (queries/<name>.scn); pass an absolute path otherwise")))
    (str f)))

(defn- header-env [file]
  (try (:env (:header (parse/parse (Files/readAllBytes (.toPath (io/file file))) :path (.toPath (io/file file)))))
       (catch Exception _ nil)))

(defn- check-account!
  "The remote profile of `e` must be in the account the record says (`aws/check`, sts:GetCallerIdentity, which needs no
  permission): returns the account, or fails `:check-failed` (cannot ask: no credentials, no network) or
  `:account-mismatch`. Always asks; `verify-account!` caches."
  [ws sess e]
  (let [r (aws/check {:ws ws :session sess} e)]
    (cond
      (:ok? r) (:account r)
      (:error r) (fail :check-failed (str "cannot verify the account of environment " (:name e) ": "
                                          (get-in r [:error :message]))
                       :hint (get-in r [:error :hint]) :env (:name e))
      :else (fail :account-mismatch
                  (str "environment " (:name e) " expects account " (:expected r)
                       " but the profile is in account " (:account r))
                  :expected (:expected r) :found (:account r) :env (:name e)
                  :hint "log in to the right account (aws sso login) or fix the record"))))

(defn- verify-account!
  "Once per [session, environment, account, profile] in the daemon's life: the remote profile must be in the
  account the record says (`check-account!`). Every operation that loads a `:remote` environment calls it
  (scenario.run, config.refresh, aws.discover, terraform.drift, logs.fetch, service.start --secrets-from);
  without a daemon map (a direct call) the check runs every time."
  [d ws sess e]
  (when (= :remote (:kind e))
    (let [k (checked-key ws sess e)
          checked (or (:checked d) (atom #{}))]
      (when-not (contains? @checked k)
        (check-account! ws sess e)
        (swap! checked conj k)))))


(declare runs-dir run-edn)

(defn- continue-vars
  "The variables a `continue` run starts from: the latest run of `file` in the session that started at step
  1, then every later `continue` (seeded) run of it, oldest first: their variable snapshots and step captures merged in that
  order. Partial runs that were not continued are ignored (their fresh `{{uuid}}` belongs to another story). nil when no run
  of the file started at step 1."
  [ws sess file]
  (let [want (str (.toAbsolutePath (.toPath (io/file file))))
        dir (runs-dir ws sess)
        runs (sort-by #(str (:run-id %))
                      (when (exists? dir)
                        (for [^java.io.File d (.listFiles (.toFile dir))
                              :when (.isDirectory d)
                              :let [r (try (run-edn (.toPath d)) (catch Exception _ nil))]
                              :when (and r (= want (get-in r [:scenario :path])) (not= :running (:status r)))]
                          r)))
        fresh? #(let [sel (:selected %)] (or (empty? sel) (some #{1} sel)))
        start (last (keep-indexed (fn [i r] (when (and (fresh? r) (not (:seeded r))) i)) runs))]
    (when start
      (let [chain (cons (nth runs start) (filter :seeded (drop (inc start) runs)))]
        (-> (reduce (fn [acc r] (apply merge acc (:vars r) (keep :captures (:steps r)))) {} chain)
            (dissoc "step.correlation"))))))

(defn- scenario-run [ctx {:keys [path env session steps confirm continue] :as args}]
  (let [ws (ws-of args)
        d (:daemon ctx)
        sess (session-key ws args)
        file (resolve-scenario ws sess path)
        env-name (or env (header-env file) "local")
        e (env/load ws env-name)
        _ (verify-account! d ws sess e)
        confirmations (atom [])
        sink (fn [ev]
               (let [ev-name (:event ev)]
               (when (= :confirm.required ev-name)
                 (swap! confirmations conj (select-keys ev [:step :token :summary :expires])))
               ((:emit! ctx) ev)))
        run (apply engine/start! ws e file
                   (mapcat identity
                           (cond-> {:session sess :sink sink
                                    ;; registered before the run can emit: a cancel that arrives first takes
                                    :on-created (fn [run]
                                                  (swap! (:runs d) assoc (:run-id run) run)
                                                  ((:on-cancel! ctx) (fn [] (engine/cancel! run))))
                                    :resolve-target (fn [k] (env/target e (keyword k)))
                                    :topology (topology/snapshot ws e)
                                    :host ctx}
                             (seq steps) (assoc :steps (set steps))
                             confirm (assoc :confirm confirm)
                             ;; a token is bound to the exact request: its run's {{uuid}} vars and captures come back
                             (or continue confirm) (assoc :vars (continue-vars ws sess file)))))]
    (try
      (let [res (assoc @run :session sess :env env-name)
            cs @confirmations]
        (if (seq cs)
          (fail :confirm-required (str "step " (:step (first cs)) " needs confirmation: " (:summary (first cs)))
                :token (:token (first cs)) :summary (:summary (first cs)) :step (:step (first cs))
                :confirmations cs :result res
                :hint "repeat scenario.run with confirm set to the token (the token is bound to the exact request)")
          res))
      (finally (swap! (:runs d) dissoc (:run-id run))))))

(defn- scenario-cancel [ctx {:keys [run-id]}]
  (if-let [run (get @(:runs (:daemon ctx)) run-id)]
    (do (engine/cancel! run) {:run-id run-id :cancelled true})
    {:run-id run-id :cancelled false :reason "no such running run"}))

;; handlers: runs ------------------------------------------------------------------------------

(defn- runs-dir ^Path [ws sess] (.resolve (session/dir ws sess) "runs"))

(defn- run-edn [^Path dir] (store/read-edn (.resolve dir "run.edn")))

(defn- resolve-run
  "Directory of the run named by `ref`: an absolute path, `sessions/K/runs/ID`, or a run id looked
  up in `session` (default: the current one), then in every session."
  ^Path [ws session ref]
  (let [ref (str ref)
        found (fn [^Path p] (when (and p (exists? (.resolve p "run.edn"))) p))
        p (io/file ref)]
    (or (cond
          (.isAbsolute p) (found (.toPath p))
          (str/starts-with? ref "sessions/")
          (let [parts (str/split ref #"/")]
            (when (and (= 4 (count parts)) (= "runs" (nth parts 2)))
              (found (store/path ws "sessions" (store/safe-name (nth parts 1)) "runs" (store/safe-name (nth parts 3))))))
          :else
          (let [id (store/safe-name ref)
                cur (or session (workspace/current-session ws))]
            (or (when cur (found (.resolve (runs-dir ws cur) id)))
                (some #(found (.resolve (runs-dir ws (:key %)) id)) (reverse (session/list ws))))))
        (fail :runs/not-found (str "no run " ref) :hint "give a run id, sessions/<key>/runs/<id> or an absolute run directory"))))

(defn- run-row [^Path dir]
  (let [r (run-edn dir)]
    {:run-id (or (:run-id r) (str (.getFileName dir))) :status (:status r) :started (:started r)
     :finished (:finished r) :scenario (get-in r [:scenario :path]) :env (:env r)
     :steps (get-in r [:summary :steps]) :failed (get-in r [:summary :failed]) :path (str dir)}))

(defn scenario-matches?
  "Does the run's scenario path `p` denote `want`: the same path, a path suffix (`estate/scenarios/order.scn`),
  or a name (`order.scn` or `order`)?"
  [want p]
  (let [want (str/replace (str want) #"/+$" "")
        p (str p)
        base (last (str/split p #"/"))]
    (boolean (and (not (str/blank? want)) p
                  (or (= want p)
                      (str/ends-with? p (str "/" want))
                      (and (not (str/includes? want "/"))
                           (or (= want base) (= want (str/replace base #"\.scn$" "")))))))))

(defn- runs-list [_ {:keys [limit scenario] :as args}]
  (let [ws (ws-of args)
        sess (session-key ws args)
        d (runs-dir ws sess)
        rows (if (exists? d)
               (with-open [st (Files/list d)]
                 (->> (iterator-seq (.iterator st)) (filter #(exists? (.resolve ^Path % "run.edn")))
                      (sort-by #(str (.getFileName ^Path %))) reverse
                      (map run-row)
                      (filter #(or (nil? scenario) (scenario-matches? scenario (:scenario %))))
                      (take limit) vec))
               [])]
    {:session sess :runs rows}))

;; handlers: IDE support (kinds, parse, repositories, logs, markers) ------------------------------

(defn- scenario-kinds [_ _] (kinds/describe-kinds))

(defn- aws-discover [ctx {:keys [env] :as args}]
  (let [ws (ws-of args) env-name (or env "local")]
    (verify-remote! ctx ws env-name)
    (awsdiscover/discover! ws env-name)))

(defn- terraform-drift [ctx {:keys [env file dir plan] :as args}]
  (let [ws (ws-of args) env-name (or env "local")]
    (verify-remote! ctx ws env-name)
    (tfdrift/drift! ws env-name (cond-> {} file (assoc :file file) dir (assoc :dir dir) plan (assoc :plan plan)))))

(defn- terraform-local [ctx {:keys [env dir action vars] :as args}]
  (tflocal/run! (ws-of args) (or env "local") {:dir dir :action (or action "plan") :vars vars :sink (:emit! ctx) :on-cancel! (:on-cancel! ctx)}))

(defn- aws-describe [_ {:keys [service op]}]
  (or (awsmodel/input service op)
      (fail :aws/unknown-operation (str "no operation " service "." op)
            :hint "the aws kind's verbs are service.Operation, listed by scenario.kinds")))

(defn- scenario-parse [_ {:keys [path text] :as args}]
  (when-not (or path text)
    (fail :bad-args "scenario.parse needs path or text" :hint "pass a scenario file path, or text for an unsaved buffer"))
  (let [ws (ws-of args)
        file (when path
               (if (or text (.isAbsolute (io/file path)))
                 path
                 (resolve-scenario ws (session-key ws args) path)))
        content (or text (slurp (io/file file) :encoding "UTF-8"))]
    (parse/describe content :path file :kinds (kinds/registry))))

(defn- repo-attach [_ {:keys [path kind] :as args}] (repo/attach! (ws-of args) path :kind kind))
(defn- repo-detach [_ {:keys [name] :as args}] {:removed (repo/detach! (ws-of args) name)})
(defn- repo-list [_ args] (repo/list (ws-of args)))

(defn- logs-fetch [ctx {:keys [target env since for filter follow] :as args}]
  (let [ws (ws-of args) env-name (or env "local")
        follow? (boolean (or follow (= "until-cancelled" (some-> for str str/trim str/lower-case))))
        counter (when follow? (:follows (:daemon ctx)))]   ; an active follow is work in flight (hello :runs, R32)
    (verify-remote! ctx ws env-name)
    (when counter (swap! counter inc))
    (try
      (logfetch/fetch! ws env-name target :session (session-key ws args) :since since :for for :filter filter
                       :follow follow :emit! (:emit! ctx) :cancelled? (:cancelled? ctx))
      (finally (when counter (swap! counter dec))))))

(defn- logs-insights [ctx {:keys [groups query env since until name budget-ms limit] :as args}]
  (let [ws (ws-of args) env-name (or env "local")
        e (verify-remote! ctx ws env-name)
        emit! (:emit! ctx)]
    (insights/run-query! {:ws ws :session (session-key ws args) :cancelled? (:cancelled? ctx)} e
                         (cond-> {:groups groups :query query :since since :until until :name name
                                  :progress! (fn [t] (when emit! (emit! {:event "line" :text t})))}
                           budget-ms (assoc :budget-ms budget-ms)
                           limit (assoc :limit limit)))))

(defn- deployed-refresh [ctx {:keys [env] :as args}]
  (let [ws (ws-of args) env-name (or env "local")]
    (verify-remote! ctx ws env-name)
    (deployed/refresh! ws env-name)))

(defn- deployed-show [_ {:keys [env service] :as args}]
  (deployed/show (ws-of args) (or env "local") {:service service}))

(defn- marker-add [_ {:keys [file line logger] :as args}] (markers/add! (ws-of args) file line :logger logger))
(defn- marker-remove [_ {:keys [file line] :as args}] (markers/remove! (ws-of args) file line))
(defn- marker-list [_ {:keys [file] :as args}] (markers/list (ws-of args) :file file))

(defn- logs-match [_ {:keys [path markers] :as args}]
  (let [ws (ws-of args)
        p (if (.isAbsolute (io/file path))
            (io/file path)
            (io/file (str (.resolve (session/dir ws (session-key ws args)) "logs")) path))
        _ (when-not (.isFile p)
            (fail :logs/not-found (str "no log file " p) :hint "a fetched log's path from logs.fetch; relative paths are looked up in the session's logs/"))
        ms (cond->> (markers/list ws) (seq markers) (clojure.core/filter #(contains? (set markers) (:id %))))
        lines (str/split (slurp p :encoding "UTF-8") #"\r?\n" -1)]
    {:lines (markers/match-lines ms lines) :path (str p) :scanned (count lines)}))

(defn- run-paths [^Path dir]
  {:run (str dir) :run-edn (str (.resolve dir "run.edn")) :result (str (.resolve dir "result.txt"))})

(defn- runs-show [_ {:keys [run session] :as args}]
  (let [dir (resolve-run (ws-of args) session run)]
    {:run (run-edn dir) :paths (run-paths dir)}))

(defn- runs-render [_ {:keys [run session] :as args}]
  (let [dir (resolve-run (ws-of args) session run)
        p (render/result! dir)
        text (slurp (.toFile ^Path p))]
    {:paths (run-paths dir) :bytes (count (.getBytes ^String text "UTF-8"))
     :trailer (last (str/split-lines (str/trimr text)))}))

(defn- runs-diff [_ {:keys [a b session] :as args}]
  (let [ws (ws-of args)
        da (resolve-run ws session a)
        db (resolve-run ws session b)
        text (render/diff da db)]
    {:equal (str/blank? text) :diff text :a (str da) :b (str db)
     :a-text (render/render-text da :normalise? true) :b-text (render/render-text db :normalise? true)}))

;; handlers: raw store -------------------------------------------------------------------------

(def ^:private text-limit 65536)

(defn- textual? [^bytes bs]
  (try (let [dec (-> StandardCharsets/UTF_8 .newDecoder
                     (.onMalformedInput CodingErrorAction/REPORT)
                     (.onUnmappableCharacter CodingErrorAction/REPORT))]
         (.decode dec (ByteBuffer/wrap bs))
         (not-any? zero? bs))
       (catch Exception _ false)))

(defn- raw-meta [_ {:keys [sha] :as args}]
  (let [ws (ws-of args)]
    {:sha sha :path (str (store/raw-path ws sha))
     :meta (or (store/raw-meta ws sha) (fail :store/not-found (str "no such blob " sha) :sha sha))}))

(defn- raw-get [_ {:keys [sha] :as args}]
  (let [ws (ws-of args)
        p (store/raw-path ws sha)]
    (when-not (exists? p) (fail :store/not-found (str "no such blob " sha) :sha sha))
    (let [n (Files/size p)
          bs (when (<= n text-limit) (Files/readAllBytes p))]
      (cond-> {:sha sha :path (str p) :bytes n :meta (store/raw-meta ws sha)}
        (and bs (textual? bs)) (assoc :text (String. ^bytes bs StandardCharsets/UTF_8))))))

;; the table -----------------------------------------------------------------------------------

(defn- ws-schema
  "An argument schema in declared order: the given `name spec` pairs, then `ws`."
  [& kvs]
  (let [pairs (concat (partition 2 kvs) [[:ws ws-arg]])]
    (with-meta (apply array-map (mapcat identity pairs)) {:order (mapv first pairs)})))

(def table
  "`op` -> `{:fn (fn [ctx args]) :args schema :class :read|:mutate :doc :events}`."
  {"hello" {:fn hello :class :read
            :doc "Handshake: the client names itself and the core version it expects; the daemon answers its version, pid, home, endpoint and the work in flight (runs = scenario runs + active log follows, also given apart)."
            :args {:client {:type :string :doc "client kind: dlb, ide, agent, ..."}
                   :version {:type :string :doc "core version the client expects; the answer says whether it matches"}}}
   "op.describe" {:fn (fn [_ _] (describe)) :class :read :args {}
                  :doc "Every operation with its class, documentation and argument schema."}
   "daemon.stop" {:fn daemon-stop :class :mutate :args {}
                  :doc "Stops the daemon gracefully: runs in flight are cancelled and finalised, endpoint, socket and lock are removed, the process exits. The answer comes first; the stop follows a moment later."}
   "workspace.open" {:fn workspace-open :class :mutate
                     :doc "Creates the workspace when missing (record, default local environment, and the project directory an IDE project binds to) or confirms it; created says which. path: use this existing project directory instead of <workspace dir>/project."
                     :args (with-meta (array-map :ws (assoc ws-arg :positional true :doc "workspace name to create or confirm")
                                                 :path {:type :string :doc "absolute project directory; default <workspace dir>/project"})
                             {:order [:ws :path]})}
   "session.note" {:fn session-note :class :mutate
                   :doc "Persists a text as sessions/<key>/notes/<name>-<ts>.txt (raw store first, then a hard link); answers the path."
                   :args (ws-schema :name {:type :string :required true :doc "note name (letters, digits, . _ - kept)"}
                                    :text {:type :string :required true :doc "the text to keep"}
                                    :session {:type :string :doc "session key; default the current session"})}
   "workspace.show" {:fn workspace-show :class :read :args (ws-schema)
                     :doc "The workspace record, its path and its environments."}
   "explore.write" {:fn explore-write :class :mutate
                    :doc "Writes a text file of the explorer (a Terraform transcript, a drift report, the explore-<env>.scn the AWS tab appends to) as <workspace project>/explore/<name>; the daemon names the place, the answer is the path. if-missing: leave an existing file as it is."
                    :args (ws-schema :name {:type :string :required true :doc "file name under explore/ (letters, digits, . _ - kept)"}
                                     :text {:type :string :required true :doc "the text to write"}
                                     :if-missing {:type :bool :doc "write only when the file does not exist yet"})}
   "session.start" {:fn session-start :class :mutate
                    :doc "Starts (or reopens) a session and makes it current."
                    :args (ws-schema :key {:type :string :required true :doc "session key, e.g. a ticket id"}
                                     :title {:type :string :doc "free-text title"})}
   "session.end" {:fn session-end :class :mutate
                  :doc "Marks a session ended (default: the current one)."
                  :args (ws-schema :key {:type :string :doc "session key; default the current session"})}
   "session.list" {:fn session-list :class :read :args (ws-schema)
                   :doc "All sessions of the workspace, oldest first, and the current key."}
   "session.current" {:fn session-current :class :read :args (ws-schema)
                      :doc "The current session and its directory, or null."}
   "env.list" {:fn env-list :class :read :args (ws-schema)
               :doc "The environments of the workspace (workspace.open writes the default local one)."}
   "env.describe" {:fn env-describe :class :read
                   :doc "One environment record and the file it lives in."
                   :args (ws-schema :name {:type :string :required true :doc "environment name"})}
   "env.check" {:fn env-check :class :read
                :doc "Asks the environment who we are (STS) and compares the account with the record; lists the target names. For a local environment, warns when the endpoint's port is published by a container that is not this workspace's runtime (another emulator on 4566)."
                :args (ws-schema :name {:type :string :required true :doc "environment name"})}
   "env.save" {:fn env-save :class :mutate
               :doc "Writes an environment record of the workspace after validation (env/validate): the one way to create or change environments/<name>.edn, for tasks and clients alike. record is the whole record (kind local|remote, aws, targets, description, topology, read-only), as env.describe returns it under env; name wins over the record's. No account check yet (M4.1)."
               :args (ws-schema :name {:type :string :required true :doc "environment name (the file environments/<name>.edn)"}
                                :record {:type :object :required true :doc "the environment record (kind, aws, targets, ...)"}
                                :check {:type :bool :doc "remote records: verify the account first and refuse a mismatch (default true; false for offline authoring)"})}
   "env.create" {:fn env-create :class :mutate
                 :doc "Creates a remote environment record: the AWS profile, the account it must be in, the region. Asks the profile who it is first (sts:GetCallerIdentity, needs no permission) and refuses, writing nothing, when the account differs or the profile cannot log in (check false: write anyway, for authoring offline). Read-only by default (every mutation refused; --read-only false makes each one a confirmation). Refuses a name that exists."
                 :args (ws-schema :name {:type :string :required true :doc "environment name (the file environments/<name>.edn)"}
                                  :profile {:type :string :required true :doc "AWS profile of the shared config/credentials files (static keys, sso_session, role_arn, credential_process)"}
                                  :account {:type :string :required true :doc "the 12-digit account id the profile must be in"}
                                  :region {:type :string :required true :doc "AWS region"}
                                  :read-only {:type :bool :doc "refuse every mutation (default true; false: each mutation needs a confirmation)"}
                                  :description {:type :string :doc "one line for humans"}
                                  :endpoint {:type :string :doc "endpoint override (the emulator trick of; a real remote record has none)"}
                                  :targets {:type :object :doc "target names -> values, e.g. {\"queue/inbox\": \"https://...\"}"}
                                  :check {:type :bool :doc "verify the account before writing (default true)"})}
   "env.remove" {:fn env-remove :class :mutate
                 :doc "Deletes environments/<name>.edn (not `local`: it follows runtime.edn). What was fetched through it (config/<name>.edn, raw/, session runs) stays."
                 :args (ws-schema :name {:type :string :required true :doc "environment name"})}
   "runtime.set" {:fn runtime-set :class :mutate
                  :doc "Sets one value of the workspace's runtime.edn by key path (the file is created with the defaults when absent) and points the local environment at the result, as runtime.sync-env does; e.g. path containers,postgres,init value /repo/estate/sql. A path element of digits is a number key (ports)."
                  :args (ws-schema :path {:type :strings :required true :doc "key path into runtime.edn, e.g. containers,postgres,init"}
                                   :value {:type :any :required true :doc "the value: a string, number, boolean or object"})}
   "runtime.ensure" {:fn runtime-ensure :class :mutate
                     :doc "Reconciles the local runtime (network, images, containers) with runtime.edn and waits until healthy."
                     :events ["runtime.pulling" "runtime.starting" "runtime.ready"]
                     :args (ws-schema :infra-only {:type :bool :doc "skip the service containers"}
                                      :timeout-ms {:type :int :doc "health wait per container"})}
   "runtime.sync-env" {:fn runtime-sync-env :class :mutate :args (ws-schema)
                       :doc "Writes the default runtime.edn when absent and points the workspace's local environment at its host ports and container names."}
   "service.adopt" {:fn service-adopt :class :mutate
                    :doc "Writes services/<name>.edn from the module at path: its deliberator-service.edn, else derived from a rendered Terraform task definition (taskdef = file with the container definitions JSON). taskdef also supplies the adopted environment and secret references. The workspace must exist."
                    :args (ws-schema :path {:type :string :required true :doc "absolute path of the service's module directory"}
                                     :taskdef {:type :string :doc "absolute path of a file holding the rendered ECS container definitions (JSON)"}
                                     :name {:type :string :doc "service name; default the description's, else the container name without service-"}
                                     :jdwp {:type :int :doc "JDWP container port (overrides the description)"})}
   "config.chain" {:fn config-chain :class :read
                   :doc "The configuration chain of a service, offline: every ${X} / ${X:default} placeholder of its module's application*.properties|yml, the task definition entry that supplies it (environment or secrets; line in taskdef.json.tftpl, declaration in main.tf) and the SSM parameter or secret it points at, from the cached snapshot of env (config.refresh). broken lists placeholders without an entry and entries whose parameter is missing; unresolved the valueFroms that are template expressions."
                   :args (ws-schema :name {:type :string :required true :doc "service name from service.list"}
                                    :env {:type :string :doc "environment whose snapshot to read; default local"})}
   "config.refresh" {:fn config-refresh :class :mutate
                     :doc "Fetches the parameters (GetParametersByPath, no decryption) and the secrets' metadata (ListSecrets, DescribeSecret; never values) of an environment, persists every response raw and writes the snapshot config/<env>.edn (and a copy under the open session)."
                     :args (ws-schema :env {:type :string :doc "environment name; default local"}
                                      :path {:type :string :doc "SSM path to list recursively; default /"})}
   "deployed.refresh" {:fn deployed-refresh :class :mutate
                       :doc "Fetches what is deployed in an environment: ECS clusters, services and their task definitions (image tag, revision), Lambda functions (version, code sha, last modified), CodeDeploy deployments where present. Read calls only; every response is persisted raw first, then cache/<env>/deployed.edn (with fetched-at) and deployed.txt are derived. A section that fails is listed in errors, the others still answer."
                       :args (ws-schema :env {:type :string :doc "environment name; default local"})}
   "deployed.show" {:fn deployed-show :class :read
                    :doc "What was deployed when last fetched, from the cache only (never AWS), with its age; service narrows to one name (or cluster/name). deployed.refresh fetches again."
                    :args (ws-schema :service {:type :string :positional true :doc "service or function name, or cluster/name; default all"}
                                     :env {:type :string :doc "environment name; default local"})}
   "service.list" {:fn service-list :class :read
                   :args (ws-schema :env {:type :string :doc "environment whose topology gives the mode; default local"})
                   :doc "The services of the workspace with kind, repository, module, ports, image, mode (local, external with its url, off) and runtime status."}
   "topology.show" {:fn topology-show :class :read
                    :args (ws-schema :env {:type :string :required true :doc "environment name"})
                    :doc "The mode of every service in an environment: {services {name {mode [url]}} hash}. local is the container (url = its host URL), external is served from a URL, off is not available."}
   "topology.set" {:fn topology-set :class :mutate
                   :args (ws-schema :env {:type :string :required true :doc "environment name"}
                                    :name {:type :string :required true :doc "service name"}
                                    :mode {:type :string :required true :doc "local, external or off"}
                                    :url {:type :string :positional true :doc "base URL, for external"}
                                    :container-url {:type :string :doc "for external: how containers reach it when the url (host view) does not work from a container; default the url with loopback turned into host.containers.internal"}
                                    :log-container {:type :string :doc "for external: the container whose log stands for log/service-<name>"})
                   :doc "Sets a service's mode in an environment. {{service/x}} and env.describe follow it; in the local environment off and external mean the service's container is not desired (the next runtime.ensure or service start/restart stops it) and the services that :depends on it are re-rendered and recreated then. Touches no container itself. Answers the new topology plus the dependents."}
   "service.show" {:fn service-show :class :read
                   :doc "One service: its description (task definition entries included) and its runtime status."
                   :args (ws-schema :name {:type :string :required true :doc "service name from service.list"})}
   "service.status" {:fn service-status :class :read
                     :doc "Runtime status (running, unhealthy, starting, stopped, absent, unreachable) of one service or all."
                     :args (ws-schema :name {:type :string :positional true :doc "service name; default all"})}
   "service.build" {:fn service-build :class :mutate
                    :doc "Runs the service's build command in its module; the output streams as service.build.line events, is appended to logs/build/ as it arrives and is stored raw. Answers the artifact."
                    :events ["service.build.line"]
                    :args (ws-schema :name {:type :string :required true :doc "service name"})}
   "service.start" {:fn service-start :class :mutate
                    :doc "Creates, recreates (spec or jar changed) or starts the service's container and waits until healthy. secrets-from asks for real secret values of an environment: two confirm-required answers (token 1: which environment and which secret names; token 2: the values go into the container's environment only), then the values are fetched (never persisted) and the container is recreated with them. A wrong, reused or expired token is service/confirmation-refused."
                    :events ["runtime.pulling" "runtime.starting" "runtime.ready" "service.started"]
                    :args (ws-schema :name {:type :string :required true :doc "service name"}
                                     :secrets-from {:type :string :doc "environment to take real secret values from (gated)"}
                                     :confirm {:type :string :doc "confirmation token from a confirm-required error"})}
   "service.stop" {:fn service-stop :class :mutate
                   :doc "Stops the service's container and keeps it; start reuses it."
                   :events ["service.stopped"]
                   :args (ws-schema :name {:type :string :required true :doc "service name"})}
   "service.reload" {:fn service-reload :class :mutate
                     :doc "Compiles the service's module (the description's :reload :cmd, derived from its Maven build), brings the changed classes into the RUNNING container and answers what happened: the HotswapAgent image copies only the changed files into the staging dir and waits for the agent's reload line, then health UP and a quiet log (15 s budget); the plain-JRE image pushes them over JDWP (body changes only). A change the JVM cannot take, the budget, an agent error or a changed resource ends in a restart, said in fallback. A compile error is service/compile-failed with the compiler's lines. The container id does not change on an in-place reload."
                     :events ["service.reload.step"]
                     :args (ws-schema :name {:type :string :required true :doc "service name"}
                                      :budget-ms {:type :int :doc "how long to wait for the agent after the copy (default 15000)"})}
   "service.restart" {:fn service-restart :class :mutate
                      :doc "Stop, then start (a rebuilt jar recreates the container)."
                      :events ["service.stopped" "runtime.pulling" "runtime.starting" "runtime.ready" "service.started"]
                      :args (ws-schema :name {:type :string :required true :doc "service name"}
                                       :secrets-from {:type :string :doc "environment to take real secret values from (gated)"}
                                       :confirm {:type :string :doc "confirmation token from a confirm-required error"})}
   "runtime.status" {:fn runtime-status :class :read :args (ws-schema)
                     :doc "Containers of the workspace's runtime with state and health; changes nothing."}
   "runtime.down" {:fn runtime-down :class :mutate :args (ws-schema)
                   :doc "Removes the workspace's runtime containers (and only those)."
                   :events ["runtime.removed"]}
   "runtime.logs" {:fn runtime-logs :class :read
                   :doc "Streams a container's log lines as `line` events (mirrored to logs/services/); with follow, until cancelled or the client leaves, starting at the end of the log unless since or tail says otherwise."
                   :events ["line"]
                   :args (ws-schema :role {:type :string :required true :doc "runtime role, e.g. floci, postgres, service/a"}
                                    :since {:type :string :doc "ISO-8601 instant or a duration ago like 10m (as logs.fetch); older lines are dropped"}
                                    :follow {:type :bool :doc "keep streaming new lines"}
                                    :tail {:type :int :doc "only the last n lines"})}
   "scenario.run" {:fn scenario-run :class :mutate
                   :doc "Runs a scenario file in a session against an environment; streams the engine's events; the result is the run summary with paths."
                   :events ["run.started" "step.started" "line" "confirm.required" "step.finished" "run.finished"]
                   :args (ws-schema :path {:type :string :required true :doc "scenario file; relative paths are looked up in the session's scenarios/, then in the session directory (queries/<name>.scn: a named query)"}
                                    :env {:type :string :doc "environment name; default the scenario's @env, else local"}
                                    :session {:type :string :doc "session key; default the current session"}
                                    :steps {:type :ints :doc "run only these step numbers"}
                                    :confirm {:type :string :doc "confirmation token from a confirm-required error"}
                                    :continue {:type :bool :doc "start from the variables and captures of the latest run of this file in the session (step by step)"})}
   "scenario.cancel" {:fn scenario-cancel :class :mutate
                      :doc "Cancels a running scenario by run id; the scenario.run request ends with status cancelled."
                      :args {:run-id {:type :string :required true :doc "run id from the run.started event"}}}
   "scenario.kinds" {:fn scenario-kinds :class :read :args {}
                     :doc "The registered step kinds as data: verbs, target rule, directives (engine-owned ones marked), facts, capture roots, concurrency. Completion and documentation are generated from it."}
   "aws.discover" {:fn aws-discover :class :read
                   :doc "What exists in an environment (queues, tables, buses, functions, buckets, topics, parameters, secrets, streams, state machines): names for completion. Read calls only; a failing section is listed in errors."
                   :args (ws-schema :env {:type :string :doc "environment name (default local)"})}
   "terraform.drift" {:fn terraform-drift :class :read
                      :doc "Terraform against an environment, reads only: the declared resources of a `terraform show -json` document (file, or run in dir, optionally for a saved plan) compared with what exists in env (aws.discover): missing, undeclared, matching. Of the document only an allowlisted extract (versions; per resource address, type, name, index, provider and the values name/arn/id/bucket/function_name) is persisted, first; the full document holds the state's secret values and is never written (B1)."
                      :args (ws-schema :env {:type :string :doc "environment name (default local)"}
                                       :file {:type :string :doc "terraform show -json output file"}
                                       :dir {:type :string :doc "a Terraform root directory (terraform show -json runs there, no apply)"}
                                       :plan {:type :string :doc "saved plan file in dir"})}
   "terraform.local" {:fn terraform-local :class :mutate
                      :doc "terraform plan or apply of a root directory onto a local environment (refused for a remote one): init, then the action, output streamed as tf.line events and persisted raw. Variables the root declares are filled from the workspace: `endpoint` (the environment's), `container_endpoint`, `service_a_url`, `db_url` (the runtime's container names); `vars` overrides. The result lists the final `vars`."
                      :events ["tf.line"]
                      :args (ws-schema :dir {:type :string :required true :doc "Terraform root directory"}
                                       :action {:type :string :doc "plan (default), apply, or vars (only return the final -var values, run nothing)"}
                                       :env {:type :string :doc "local environment name (default local)"}
                                       :vars {:type :object :doc "explicit -var name value pairs, win over the derived ones"})}
   "aws.describe" {:fn aws-describe :class :read
                   :doc "The request shape of an AWS operation from the bundled service models (members, required, enums, nesting), for completion in step bodies of the aws kind."
                   :args {:service {:type :string :required true :positional true :doc "aws-api service name, e.g. ssm, s3, sns"}
                          :op {:type :string :required true :positional true :doc "operation, e.g. GetParameter"}}}
   "scenario.parse" {:fn scenario-parse :class :read
                     :doc "Parses a scenario with the daemon's parser: header, steps (kind, verb, target, name, lines, directives, body size) and errors. A syntax error is returned in errors (steps empty), not raised. With text, an unsaved buffer is parsed; path then only resolves # @body."
                     :args (ws-schema :path {:type :string :positional true :doc "scenario file (or use text); relative paths are looked up in the session's scenarios/"}
                                      :text {:type :string :doc "scenario text (an unsaved buffer); wins over the file's content"})}
   "repo.attach" {:fn repo-attach :class :mutate
                  :doc "Records an existing checkout in the workspace (workspace.edn repositories): name = directory name, kind detected (gradle, maven, plain). Files are untouched."
                  :args (ws-schema :path {:type :string :required true :doc "absolute path of the repository directory"}
                                   :kind {:type :string :doc "gradle, maven or plain; detected when absent"})}
   "repo.detach" {:fn repo-detach :class :mutate
                  :doc "Removes an attached repository's entry from the workspace; files untouched."
                  :args (ws-schema :name {:type :string :required true :doc "repository name from repo.list"})}
   "repo.list" {:fn repo-list :class :read :args (ws-schema)
                :doc "The repositories attached to the workspace, in attach order."}
   "logs.fetch" {:fn logs-fetch :class :mutate
                 :doc "Fetches a log target (container, file or CloudWatch group of the environment) into the session as logs/<target>-<ts>.log, hard-linked from the raw blobs. Without for: one shot; with for: also listens that long, streaming every line as a logs.line event while it arrives. follow (or for until-cancelled) listens until the request is cancelled or the client leaves, with ONE file for the whole follow, written as lines arrive; a container's follow starts at the current end of its log unless since says otherwise (only the lines written from now on). filter keeps whole matching entries."
                 :events ["logs.line"]
                 :args (ws-schema :target {:type :string :required true :doc "environment target such as log/service-a, or an absolute file path"}
                                  :env {:type :string :doc "environment name; default local"}
                                  :since {:type :string :doc "ISO-8601 instant or a duration ago like 10m; default all (CloudWatch: 15m); a follow of a container without since starts at the end of its log"}
                                  :for {:type :string :doc "keep listening this long, e.g. 5s; until-cancelled = follow"}
                                  :follow {:type :bool :doc "listen until cancelled (Ctrl-C / the client leaving), one file for the whole follow"}
                                  :filter {:type :string :doc "text or /regex/ an entry must match"}
                                  :session {:type :string :doc "session key; default the current session"})}
   "logs.insights" {:fn logs-insights :class :mutate
                    :doc "Runs a CloudWatch Logs Insights query over log groups (names, or log/ targets of the environment) and writes the answer as text under the session's logs/ (insights-<name>-<ts>.txt, bytes scanned in its header); polls with a budget, stops the query when over it or cancelled, persists every request and response raw first. The bytes scanned (what Insights bills) are in the result. A named query's text is kept as queries/<name>.insights; rerun it as a one-step scenario (queries/<name>.scn, see dlb runs list --scenario)."
                    :events ["line"]
                    :args (ws-schema :groups {:type :strings :required true :doc "log group names (comma separated on the command line) or log/ targets of the environment"}
                                     :query {:type :string :required true :doc "the Logs Insights query, e.g. fields @timestamp, @message | sort @timestamp desc | limit 20"}
                                     :env {:type :string :doc "environment name; default local (Floci answers with the rows of the group and zero statistics)"}
                                     :env {:type :string :doc "environment name; default local (Floci's Insights is a stub)"}
                                     :since {:type :string :doc "start of the window: ISO-8601 instant or a duration ago like 30m; default 1h"}
                                     :until {:type :string :doc "end of the window: instant or duration ago; default now"}
                                     :name {:type :string :doc "name the query: its text is saved as sessions/<k>/queries/<name>.insights"}
                                     :budget-ms {:type :int :doc "give up (and stop the query) after this many milliseconds; default 120000"}
                                     :limit {:type :int :doc "StartQuery limit (rows); service default 1000, at most 10000"}
                                     :session {:type :string :doc "session key; default the current session"})}
   "logs.match" {:fn logs-match :class :read
                 :doc "Which lines of a fetched log carry a marked logger and line (logback pattern logger:line - msg, or a stack frame at pkg.Class.m(File.java:N)); pure over the file."
                 :args (ws-schema :path {:type :string :required true :doc "log file, absolute or relative to the session's logs/"}
                                  :markers {:type :strings :doc "marker ids to match; default all"}
                                  :session {:type :string :doc "session key for a relative path"})}
   "marker.add" {:fn marker-add :class :mutate
                 :doc "Marks a source line (idempotent per file and line); answers all markers."
                 :args (ws-schema :file {:type :string :required true :doc "source file path"}
                                  :line {:type :int :required true :doc "1-based line"}
                                  :logger {:type :string :doc "the class the IDE derives, e.g. com.acme.ServiceA"})}
   "marker.remove" {:fn marker-remove :class :mutate
                    :doc "Removes the marker at file and line; answers the remaining markers."
                    :args (ws-schema :file {:type :string :required true :doc "source file path"}
                                     :line {:type :int :required true :doc "1-based line"})}
   "marker.list" {:fn marker-list :class :read
                  :doc "The markers of the workspace, by file and line."
                  :args (ws-schema :file {:type :string :doc "only this file"})}
   "runs.list" {:fn runs-list :class :read
                :doc "The runs of a session, newest first; scenario keeps the runs of one scenario. This is the history of a named query: a query is a one-step scenario kept as queries/<name>.scn in the session (run it with dlb run queries/<name>.scn --env E), so dlb runs list --scenario <name> lists its runs."
                :args (ws-schema :session {:type :string :doc "session key; default the current session"}
                                 :scenario {:type :string :doc "scenario path, path suffix or name (order, order.scn)"}
                                 :limit {:type :int :default 30 :doc "at most this many runs"})}
   "runs.show" {:fn runs-show :class :read
                :doc "run.edn of a run (scenario, vars, per-step summaries) and its paths."
                :args (ws-schema :run {:type :string :required true :doc "run id, sessions/<key>/runs/<id> or an absolute directory"}
                                 :session {:type :string :doc "session to look the run id up in"})}
   "runs.render" {:fn runs-render :class :mutate
                  :doc "Regenerates result.txt of a run from its directory."
                  :args (ws-schema :run {:type :string :required true :doc "run id, sessions/<key>/runs/<id> or an absolute directory"}
                                   :session {:type :string :doc "session to look the run id up in"})}
   "runs.diff" {:fn runs-diff :class :read
                :doc "Unified diff of two runs' result texts with ids, timestamps and elapsed normalised; empty when they agree. Also returns the two normalised texts (a-text, b-text) the diff was made from, for a side-by-side viewer."
                :args (ws-schema :a {:type :string :required true :doc "first run"}
                                 :b {:type :string :required true :doc "second run"}
                                 :session {:type :string :doc "session to look run ids up in"})}
   "raw.meta" {:fn raw-meta :class :read
               :doc "The sidecar metadata of a raw blob."
               :args (ws-schema :sha {:type :string :required true :doc "sha-256 hex of the blob"})}
   "raw.get" {:fn raw-get :class :read
              :doc "A raw blob: its path, size, metadata and, when small and textual, the text."
              :args (ws-schema :sha {:type :string :required true :doc "sha-256 hex of the blob"})}})

(defn call!
  "Validates `args` and runs the op; returns its result or throws (`unknown-op`, `bad-args`, ...)."
  [op ctx args]
  (let [entry (get table op)]
    (when-not entry
      (fail :unknown-op (str "no such operation " op) :known (vec (sort (keys table)))
            :hint "op.describe lists the operations"))
    (let [args (validate-args op (:args entry) args)]
      (when (and (contains? (:args entry) :ws)
                 (or (str/starts-with? op "service.") (str/starts-with? op "config.") (str/starts-with? op "topology.") (str/starts-with? op "deployed.") (= op "logs.insights")   ; new families are strict, mutators too
                     (and (= :read (:class entry)) (not= "scenario.parse" op))))
        (require-workspace! (:ws args)))
      ((:fn entry) ctx args))))
