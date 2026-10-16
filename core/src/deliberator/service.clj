(ns deliberator.service
  "Service lifecycle on the local runtime: adopt a description, list/show/status, build, start/stop/restart.
  The description (`services/<name>.edn`, `deliberator.service.description`) is what a service is; the
  runtime derives the container from it at `ensure!`. Every function takes the workspace and
  an existing one is assumed (the ops check).

  Statuses: `running` (up and healthy), `unhealthy`, `starting` (up, no probe answer yet), `stopped`
  (container kept, not running), `absent` (no container), `unreachable` (Podman does not answer).
  Events (maps with string `:event`, to the `:sink`): `service.build.line {name stream text}`,
  `service.started {name container}`, `service.stopped {name container}`, plus the runtime's
  `runtime.pulling|starting|ready|recreating` while a start reconciles.

  `secrets-from` on `start!` is the gate of piece 08, refused here: without a live token `confirm-required`
  (token 1), with token 1 `confirm-required` again (a different token 2), with token 2
  `service/secrets-not-built`. Nothing is ever injected by this piece."
  (:refer-clojure :exclude [list])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.nio.file Files LinkOption)
           (java.security SecureRandom)
           (java.time Duration Instant)
           (java.util Base64)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn- role-label [name] (runtime/role-name (description/role name)))

(defn- require-description [ws name]
  (or (description/read ws name)
      (fail :service/not-found (str "no service " (pr-str name) " in workspace " (store/ws-name ws))
            :known (description/names ws)
            :hint "service.adopt a module, or dlb service list")))

;; adopt ----------------------------------------------------------------------------------------

(defn- repo-of
  "The attached repository containing `module` (a File): `{:name :path}` with the longest path."
  [ws ^File module]
  (let [real (str (.toRealPath (.toPath module) (make-array LinkOption 0)))]
    (->> (repo/list ws)
         (filter #(or (= real (:path %)) (str/starts-with? real (str (:path %) "/"))))
         (sort-by (comp count :path)) last)))

(defn adopt!
  "Writes `services/<name>.edn` from the module at `path` (absolute directory): its `deliberator-service.edn`,
  else derived from the rendered Terraform task definition in file `taskdef` (JSON container definitions, the
  `task_definitions` entry). `taskdef` also supplies the description's `:taskdef :environment/:secrets`.
  `name` and `jdwp` override. The `local` environment is pointed at the service afterwards.
  -> `{:name :created :path :repo :module :from}`."
  [ws {:keys [path taskdef name jdwp]}]
  (let [module (io/file (str path))
        _ (when-not (.isAbsolute module) (fail :bad-args "service.adopt: path must be absolute" :problems ["path"]))
        _ (when-not (.isDirectory module)
            (fail :service/module-not-found (str "no module directory " path) :hint "the absolute path of the service's module"))
        src (io/file module "deliberator-service.edn")
        td (when taskdef
             (let [f (io/file (str taskdef))]
               (when-not (.isFile f) (fail :service/taskdef-not-found (str "no task definition file " taskdef)))
               (description/parse-taskdef (slurp f))))
        [base from] (cond (.isFile src) [(edn/read-string (slurp src)) :file]
                          td [(description/from-taskdef module td {:name name :jdwp jdwp}) :terraform]
                          :else (fail :service/no-description
                                      (str "no deliberator-service.edn in " module " and no taskdef to derive one from")
                                      :hint "add a deliberator-service.edn to the module, or pass taskdef (a rendered task definition)"))
        r (repo-of ws module)
        base (cond-> base name (assoc :name name) jdwp (assoc :jdwp jdwp))
        desc (-> base
                 (assoc :repo (:name r)
                        :module (if r (let [rel (str (.relativize (.toPath (io/file (:path r))) (.toRealPath (.toPath module) (make-array LinkOption 0))))]
                                        (if (str/blank? rel) "." rel))
                                    (str (.getAbsoluteFile module))))
                 (update :taskdef #(let [old (:taskdef (description/read ws (:name base)))]   ; entries as last adopted survive an adopt without taskdef
                                    (merge (or % {}) (if td (select-keys td [:environment :secrets]) (select-keys old [:environment :secrets])))))
                 (assoc :adopted {:at (store/now) :from from}))
        _ (description/validate desc)
        created (not (.exists (.toFile (description/path ws (:name desc)))))]
    (description/write! ws desc)
    (runtime/sync-env! ws)
    {:name (:name desc) :created created :path (str (description/path ws (:name desc)))
     :repo (:repo desc) :module (:module desc) :from (clojure.core/name from)}))

;; status ---------------------------------------------------------------------------------------

(defn- status-of [rt-row]
  (cond (nil? rt-row) "absent"
        (= :absent (:state rt-row)) "absent"
        (not= :running (:state rt-row)) "stopped"
        (= :healthy (:health rt-row)) "running"
        (= :unhealthy (:health rt-row)) "unhealthy"
        :else "starting"))

(defn- rt-rows
  "role label -> runtime row; `:unreachable` when Podman does not answer."
  [ws only]
  (let [s (runtime/status ws (when only {:only only}))]
    (if (get-in s [:podman :reachable])
      (into {} (map (juxt :role identity)) (:containers s))
      :unreachable)))

(defn- status-row [ws desc rows]
  (let [row (when (map? rows) (get rows (role-label (:name desc))))]
    (cond-> {:name (:name desc)
             :status (if (= rows :unreachable) "unreachable" (status-of row))}
      (:health row) (assoc :health (clojure.core/name (:health row)))
      (:detail row) (assoc :detail (:detail row))
      (:name row) (assoc :container (:name row))
      (:since row) (assoc :since (str (:since row)))
      (some? (:drift? row)) (assoc :drift (boolean (:drift? row))))))

(defn status
  "`[{:name :status [:health :detail :container :since :drift]}]` for `name` or every service."
  [ws name]
  (let [descs (if name [(require-description ws name)] (description/list ws))
        rows (rt-rows ws (when name [(role-label name)]))]
    (mapv #(status-row ws % rows) descs)))

(defn list
  "`[{:name :kind :repo :module :port :jdwp :image :mode :status}]`; `:mode` is always \"local\" until topology (piece 03)."
  [ws]
  (let [descs (description/list ws)
        rows (rt-rows ws nil)]
    (mapv (fn [d] (merge (select-keys d [:name :kind :repo :module :port :jdwp])
                         {:image (or (:image d) description/default-image) :mode "local"
                          :status (:status (status-row ws d rows))}))
          descs)))

(defn show
  "The description, its `:taskdef` entries, and `:status` / `:container` (the status row)."
  [ws name]
  (let [d (require-description ws name)
        row (first (status ws name))]
    (assoc d :status (:status row) :container (:container row) :runtime row)))

;; build ----------------------------------------------------------------------------------------

(defn- jdk [major]
  (or (System/getenv "ESTATE_JAVA_HOME")
      (when (.isDirectory (io/file "/usr/lib/jvm"))
        (->> (.listFiles (io/file "/usr/lib/jvm")) (map str) sort
             (filter #(and (re-find (re-pattern (str "java-" major "(?!\\d)")) %) (.exists (io/file % "bin" "javac"))))
             first))
      (System/getenv "JAVA_HOME")))

(defn build!
  "Runs the description's build command in the module (JDK of `:build :java` via `ESTATE_JAVA_HOME`,
  `/usr/lib/jvm/java-<n>*`, else `JAVA_HOME`), streaming `service.build.line` events, the output also appended
  as it arrives to `logs/build/<name>-<ts>.log` and, when the build ends, stored raw (source `:build`).
  -> `{:artifact :bytes :took-ms :log :sha}`; a non-zero exit is `service/build-failed` (data `:log :exit`)."
  [ws name {:keys [sink]}]
  (let [d (require-description ws name)
        b (:build d)
        _ (when-not (seq (:cmd b)) (fail :service/no-build (str "service " name " has no :build :cmd")))
        mod (description/module-dir ws d)
        dir (.getCanonicalFile (io/file mod (or (:dir b) ".")))
        _ (when-not (.isDirectory dir) (fail :service/build-dir (str "build directory " dir " does not exist")))
        t0 (System/nanoTime)
        ts (str/replace (str (store/now)) #"[:]" "")
        logf (.toFile (store/path ws "logs" "build" (str name "-" ts ".log")))
        _ (io/make-parents logf)
        pb (doto (ProcessBuilder. ^java.util.List (mapv str (:cmd b)))
             (.directory dir) (.redirectErrorStream true))
        _ (when-let [j (and (:java b) (jdk (:java b)))]
            (.put (.environment pb) "JAVA_HOME" j))
        sb (StringBuilder.)
        proc (try (.start pb)
                  (catch java.io.IOException e
                    (fail :service/build-failed (str "cannot run " (str/join " " (:cmd b)) ": " (.getMessage e)) :hint "is the build tool on the daemon's PATH?")))
        exit (with-open [rdr (io/reader (.getInputStream proc)) w (io/writer logf :append true)]
               (doseq [l (line-seq rdr)]
                 (.append sb l) (.append sb "\n")
                 (.write w l) (.write w "\n") (.flush w)
                 (when sink (sink {:event "service.build.line" :name name :stream "build" :text l})))
               (.waitFor proc))
        raw (store/raw-put! ws (.getBytes (str sb) "UTF-8")
                            {:source :build :op (str/join " " (:cmd b)) :env nil :at (store/now) :content-type "text/plain"})]
    (when-not (zero? exit)
      (fail :service/build-failed (str "build of " name " failed (exit " exit "), log " logf)
            :log (str logf) :exit exit :hint "the log has the build output"))
    (let [a (description/artifact ws d)]
      (cond-> {:took-ms (quot (- (System/nanoTime) t0) 1000000) :log (str logf) :sha (:sha raw)}
        a (assoc :artifact (str a) :bytes (.length a))))))

;; the secrets gate (piece 08 builds the rest) --------------------------------------------------

(defonce ^:private tokens (atom {}))
(def ^:private token-ttl (Duration/ofMinutes 10))

(defn- issue! [fp]
  (let [b (byte-array 18) _ (.nextBytes (SecureRandom.) b)
        t (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)
        now (Instant/now)
        exp (.plus now token-ttl)]
    (swap! tokens (fn [m] (assoc (into {} (remove (fn [[_ v]] (.isAfter now (:expires v))) m)) t {:fp fp :expires exp})))
    {:token t :expires (str exp)}))

(defn- consume! [token fp]
  (let [now (Instant/now)
        [old _] (swap-vals! tokens (fn [m] (let [v (get m token)] (if (and v (= fp (:fp v))) (dissoc m token) m))))
        v (get old token)]
    (boolean (and v (= fp (:fp v)) (not (.isAfter now (:expires v)))))))

(defn- secrets-gate!
  "Always throws: the two confirmations, then `service/secrets-not-built`."
  [ws name from confirm]
  (env/load ws from)
  (let [fp [(store/ws-name ws) name from]
        ask (fn [n]
              (let [{:keys [token expires]} (issue! (conj fp n))
                    summary (str "start " name " with real secret values from environment " from " (confirmation " n " of 2)")]
                (fail :confirm-required summary :token token :summary summary :expires expires
                      :confirmations [{:token token :summary summary :expires expires}]
                      :hint (str "repeat the start with confirm set to the token (" (- 3 n) " more confirmation(s) needed after this one)"))))]
    (cond (nil? confirm) (ask 1)
          (consume! confirm (conj fp 1)) (ask 2)
          (consume! confirm (conj fp 2)) (fail :service/secrets-not-built
                                               "both confirmations were given, but injecting secret values is not built yet (m3 piece 08); nothing was started"
                                               :hint "start without secrets-from to run with the local environment's values")
          :else (fail :service/confirmation-refused "the confirmation token is unknown, expired, already used, or was issued for a different request"
                      :hint "start again without confirm to get the first token"))))

;; start / stop / restart -----------------------------------------------------------------------

(defn- host-port [spec-ports cp] (get spec-ports cp cp))

(defn- result [ws d]
  (let [rows (rt-rows ws [(role-label (:name d))])
        row (status-row ws d rows)
        ports (:ports (when (map? rows) (get rows (role-label (:name d)))))]
    (cond-> (select-keys row [:name :status :container])
      (:port d) (assoc :port (host-port ports (:port d)))
      (:jdwp d) (assoc :jdwp (host-port ports (:jdwp d))))))

(defn start!
  "Reconciles the service's container (created, recreated when its spec or jar changed, or started when
  stopped) and waits until it is healthy. Needs the infrastructure up (secrets come from Floci).
  -> `{:name :status :container :port :jdwp}` (host ports)."
  [ws name {:keys [sink secrets-from confirm timeout-ms]}]
  (let [d (require-description ws name)]
    (when secrets-from (secrets-gate! ws name secrets-from confirm))
    (when-not (description/runnable? d)
      (fail :service/not-runnable (str "service " name " is a " (clojure.core/name (:kind d)) ": it runs as a function in the environment, not as a container")
            :hint "only spring-boot services start here"))
    (description/module-dir ws d)
    (runtime/ensure! ws (cond-> {:sink sink :only [(role-label name)]} timeout-ms (assoc :timeout-ms timeout-ms)))
    (let [r (result ws d)]
      (when sink (sink {:event "service.started" :name name :container (:container r)}))
      r)))

(defn stop!
  "Stops the container and keeps it (so `start!` reuses it). -> `{:name :status :container}`; no container is `absent`."
  [ws name {:keys [sink]}]
  (let [d (require-description ws name)
        c (runtime/stop-role! ws (role-label name))]
    (when (and c sink) (sink {:event "service.stopped" :name name :container c}))
    (cond-> {:name name :status (if c "stopped" "absent")} c (assoc :container c))))

(defn restart!
  "Stop, then start (a rebuilt jar or a changed description recreates the container)."
  [ws name opts]
  (stop! ws name opts)
  (start! ws name opts))
