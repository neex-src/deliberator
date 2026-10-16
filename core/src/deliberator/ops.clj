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

    hello client version                           -> {version pid home endpoint protocol runs compatible}
    op.describe                                    -> [{op class doc args order events}]   (order = argument names as declared)
    workspace.open path ws                         -> {ws dir project created}   (the one op that creates a workspace on purpose)
    workspace.show ws                              -> {ws path workspace environments}   (reading ops: unknown ws is workspace/not-found)
    session.note name* text* session ws            -> {path}
    session.start key* title ws                    -> {session path}   (reopens an existing key)
    session.end key ws                             -> {session path}   (default: the current session)
    session.list ws                                -> {sessions current}
    session.current ws                             -> {session path}
    env.list ws                                    -> {environments [{name kind account ...}]}
    env.describe name* ws                          -> {env path}
    env.check name* ws                             -> {ok account expected targets [error]}
    daemon.stop                                    -> {stopping pid runs}   (the daemon exits a moment later)
    runtime.sync-env ws                            -> {ws path}
    runtime.adopt file* ws                         -> {ws path roles}
    runtime.ensure ws infra-only timeout-ms        -> status   [runtime.pulling runtime.starting runtime.ready]
    runtime.status ws                              -> {ws podman containers}
    runtime.down ws                                -> {removed}  [runtime.removed]
    runtime.logs role* ws since follow tail        -> {lines paths}   [line {stream at text}]
    scenario.run path* env session steps confirm ws -> {status steps failed checks failed-checks run-id paths}
                                                      [run.started step.started line confirm.required step.finished run.finished]
    scenario.cancel run-id*                        -> {run-id cancelled}
    scenario.kinds                                 -> [{kind verbs verb-optional target directives facts capture-roots concurrent default-timeout-ms}]
    scenario.parse path text ws                    -> {header steps errors}   (a syntax error is data in errors, not an op error)
    runs.list ws session scenario limit            -> {session runs}
    repo.attach path* kind ws                      -> {name path kind attached-at}
    repo.detach name* ws                           -> {removed}
    repo.list ws                                   -> [{name path kind attached-at}]
    logs.fetch target* env since for filter session ws -> {path lines bytes sha target source}
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
            [deliberator.env :as env]
            [deliberator.logfetch :as logfetch]
            [deliberator.markers :as markers]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.parse :as parse]
            [deliberator.scenario.render :as render]
            [deliberator.session :as session]
            [deliberator.store :as store]
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
   :role :key :sha :schema :account :expected :found])

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

(defn- parse-instant [s what]
  (try (Instant/parse s)
       (catch DateTimeParseException _
         (fail :bad-args (str what " must be an ISO-8601 instant like 2026-10-16T10:15:00Z") :problems [what]))))

;; handlers: hello, workspace, sessions --------------------------------------------------------

(defn- hello [ctx {:keys [version]}]
  (let [d (:daemon ctx)]
    (cond-> {:version (:version d) :pid (:pid d) :home (str (:home d)) :endpoint (:endpoint d) :protocol 1
             :runs (count @(:runs d))}
      version (assoc :compatible (= version (:version d))))))

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

(defn- env-describe [_ {:keys [name] :as args}]
  (let [ws (ws-of args)]
    {:env (env/load ws name) :path (str (store/path ws "environments" (str (store/safe-name name) ".edn")))}))

(defn- env-check [_ {:keys [name] :as args}]
  (let [ws (ws-of args)
        e (env/load ws name)
        r (aws/check {:ws ws} e)]
    (cond-> {:env name :kind (:kind e) :ok (boolean (:ok? r)) :account (:account r) :expected (:expected r)
             :targets (name-list (:targets e))}
      (:error r) (assoc :error (aws-error (:error r))))))

;; handlers: runtime ---------------------------------------------------------------------------

(defn- runtime-ensure [ctx {:keys [infra-only timeout-ms] :as args}]
  (runtime/ensure! (ws-of args)
                   (cond-> {:sink (:emit! ctx)}
                     infra-only (assoc :infra-only true)
                     timeout-ms (assoc :timeout-ms timeout-ms))))

(defn- runtime-status [_ args] (runtime/status (ws-of args)))

(defn- runtime-down [ctx args] (runtime/down! (ws-of args) {:sink (:emit! ctx)}))

(defn- runtime-sync-env [_ args] (runtime/sync-env! (ws-of args)))

(defn- runtime-adopt [_ {:keys [file] :as args}]
  (let [cfg (runtime/adopt-taskdefs! (ws-of args) (clojure.edn/read-string (slurp (str file))))]
    {:ws (store/ws-name (ws-of args)) :path (str (runtime/config-path (ws-of args)))
     :roles (mapv #(subs (str %) 1) (sort (keys (:containers cfg))))}))

(defn- daemon-stop [ctx _]
  (let [d (:daemon ctx)
        ids (vec (sort (keys @(:runs d))))]
    ((:stop-later! d))
    {:stopping true :pid (:pid d) :runs ids}))

(defn- runtime-logs [ctx {:keys [role since follow tail] :as args}]
  (let [ws (ws-of args)
        s (runtime/logs ws role (cond-> {}
                                  since (assoc :since (parse-instant since "since"))
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

(defn- resolve-scenario [ws sess path]
  (let [p (io/file path)
        f (if (.isAbsolute p) p (.toFile (.resolve (.resolve (session/dir ws sess) "scenarios") ^String path)))]
    (when-not (.isFile f)
      (fail :scenario/not-found (str "no scenario file " f)
            :hint (if (.isAbsolute p) "pass an existing file" "a relative path is looked up in the session's scenarios/ directory; pass an absolute path otherwise")))
    (str f)))

(defn- header-env [file]
  (try (:env (:header (parse/parse (Files/readAllBytes (.toPath (io/file file))) :path (.toPath (io/file file)))))
       (catch Exception _ nil)))

(defn- verify-account!
  "Once per [session, environment, account] in the daemon's life: the remote profile must be in the
  account the record says (`aws/check`)."
  [d ws sess e]
  (when (= :remote (:kind e))
    (let [k [(store/ws-name ws) sess (:name e) (get-in e [:aws :account])]]
      (when-not (contains? @(:checked d) k)
        (let [r (aws/check {:ws ws :session sess} e)]
          (cond
            (:ok? r) (swap! (:checked d) conj k)
            (:error r) (fail :check-failed (str "cannot verify the account of environment " (:name e) ": "
                                                (get-in r [:error :message]))
                             :hint (get-in r [:error :hint]) :env (:name e))
            :else (fail :account-mismatch
                        (str "environment " (:name e) " expects account " (:expected r)
                             " but the profile is in account " (:account r))
                        :expected (:expected r) :found (:account r) :env (:name e)
                        :hint "log in to the right account (aws sso login) or fix the record")))))))


(defn- scenario-run [ctx {:keys [path env session steps confirm] :as args}]
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
                                    :resolve-target (fn [k] (env/target e (keyword k)))}
                             (seq steps) (assoc :steps (set steps))
                             confirm (assoc :confirm confirm))))]
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

(defn- logs-fetch [_ {:keys [target env since for filter] :as args}]
  (let [ws (ws-of args)]
    (logfetch/fetch! ws (or env "local") target :session (session-key ws args) :since since :for for :filter filter)))

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
            :doc "Handshake: the client names itself and the core version it expects; the daemon answers its version, pid, home and endpoint."
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
                :doc "Asks the environment who we are (STS) and compares the account with the record; lists the target names."
                :args (ws-schema :name {:type :string :required true :doc "environment name"})}
   "runtime.ensure" {:fn runtime-ensure :class :mutate
                     :doc "Reconciles the local runtime (network, images, containers) with runtime.edn and waits until healthy."
                     :events ["runtime.pulling" "runtime.starting" "runtime.ready"]
                     :args (ws-schema :infra-only {:type :bool :doc "skip the service containers"}
                                      :timeout-ms {:type :int :doc "health wait per container"})}
   "runtime.sync-env" {:fn runtime-sync-env :class :mutate :args (ws-schema)
                       :doc "Writes the default runtime.edn when absent and points the workspace's local environment at its host ports and container names."}
   "runtime.adopt" {:fn runtime-adopt :class :mutate
                    :doc "Writes the service roles of an ECS task-definition descriptor (EDN file, see runtime/adopt-taskdefs!) into the workspace's runtime.edn."
                    :args (ws-schema :file {:type :string :required true :doc "absolute path of the descriptor.edn"})}
   "runtime.status" {:fn runtime-status :class :read :args (ws-schema)
                     :doc "Containers of the workspace's runtime with state and health; changes nothing."}
   "runtime.down" {:fn runtime-down :class :mutate :args (ws-schema)
                   :doc "Removes the workspace's runtime containers (and only those)."
                   :events ["runtime.removed"]}
   "runtime.logs" {:fn runtime-logs :class :read
                   :doc "Streams a container's log lines as `line` events (mirrored to logs/services/); with follow, until cancelled or the client leaves."
                   :events ["line"]
                   :args (ws-schema :role {:type :string :required true :doc "runtime role, e.g. floci, postgres, service/a"}
                                    :since {:type :string :doc "ISO-8601 instant; older lines are dropped"}
                                    :follow {:type :bool :doc "keep streaming new lines"}
                                    :tail {:type :int :doc "only the last n lines"})}
   "scenario.run" {:fn scenario-run :class :mutate
                   :doc "Runs a scenario file in a session against an environment; streams the engine's events; the result is the run summary with paths."
                   :events ["run.started" "step.started" "line" "confirm.required" "step.finished" "run.finished"]
                   :args (ws-schema :path {:type :string :required true :doc "scenario file; relative paths are looked up in the session's scenarios/"}
                                    :env {:type :string :doc "environment name; default the scenario's @env, else local"}
                                    :session {:type :string :doc "session key; default the current session"}
                                    :steps {:type :ints :doc "run only these step numbers"}
                                    :confirm {:type :string :doc "confirmation token from a confirm-required error"})}
   "scenario.cancel" {:fn scenario-cancel :class :mutate
                      :doc "Cancels a running scenario by run id; the scenario.run request ends with status cancelled."
                      :args {:run-id {:type :string :required true :doc "run id from the run.started event"}}}
   "scenario.kinds" {:fn scenario-kinds :class :read :args {}
                     :doc "The registered step kinds as data: verbs, target rule, directives (engine-owned ones marked), facts, capture roots, concurrency. Completion and documentation are generated from it."}
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
                 :doc "Fetches a log target (container, file or CloudWatch group of the environment) into the session as logs/<target>-<ts>.log, hard-linked from the raw blobs. Without for: one shot; with for: also listens that long. filter keeps whole matching entries."
                 :args (ws-schema :target {:type :string :required true :doc "environment target such as log/service-a, or an absolute file path"}
                                  :env {:type :string :doc "environment name; default local"}
                                  :since {:type :string :doc "ISO-8601 instant or a duration ago like 10m; default all (CloudWatch: 15m)"}
                                  :for {:type :string :doc "keep listening this long, e.g. 5s"}
                                  :filter {:type :string :doc "text or /regex/ an entry must match"}
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
                :doc "The runs of a session, newest first; scenario keeps the runs of one scenario."
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
      (when (and (= :read (:class entry)) (contains? (:args entry) :ws) (not= "scenario.parse" op))
        (require-workspace! (:ws args)))
      ((:fn entry) ctx args))))
