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

  `secrets-from` on `start!`: without a live token `confirm-required` (token 1: which environment and which
  secret names), with token 1 `confirm-required` again (a different token 2: the values go into the container's
  environment only), with token 2 the secrets are fetched from that environment and the container is created with them.
  A wrong, reused, expired or foreign token is `service/confirmation-refused`; a service with no secret references
  `service/no-secrets`."
  (:refer-clojure :exclude [list])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.process :as process]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.service.staging :as staging]
            [deliberator.store :as store]
            [deliberator.topology :as topology])
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
    (->> (repo/checkouts ws)
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

(defn- topo-mode
  "The mode of service `name` in the `local` environment (the one the runtime serves)."
  [ws name] (topology/container-mode ws name))

(defn- reload-state
  "The reload state of the service when it belongs to the container in `row` (a recreated container starts at 0)."
  [ws desc row]
  (let [st (staging/read-state ws (:name desc))]
    (when (and st row (= (:container st) (:id row))) st)))

(defn- status-row [ws desc rows]
  (let [row (when (map? rows) (get rows (role-label (:name desc))))
        mode (topo-mode ws (:name desc))
        st (if (and (not= :local mode) (= :running (:state row)) (nil? (:health row))) "running" (status-of row))
        ;; not desired by the topology: "off"/"external" once its container is down, still the real status
        ;; (and drift) while the next reconcile has not stopped it
        st (if (and (not= :local mode) (not= rows :unreachable) (not= "running" st) (not= "unhealthy" st) (not= "starting" st))
             (clojure.core/name mode) st)]
    (cond-> {:name (:name desc)
             :status (if (= rows :unreachable) "unreachable" st)}
      (not= :local mode) (assoc :topology (clojure.core/name mode)
                                :detail (str "served " (if (= :off mode) "by nobody" "elsewhere") " by the topology of environment local")
                                :drift (boolean (and row (contains? #{"running" "unhealthy" "starting"} st))))
      (:health row) (assoc :health (clojure.core/name (:health row)))
      (and (:detail row) (= :local mode)) (assoc :detail (:detail row))
      (:name row) (assoc :container (:name row))
      (:since row) (assoc :since (str (:since row)))
      (and (some? (:drift? row)) (= :local mode)) (assoc :drift (boolean (:drift? row)))
      (:count (reload-state ws desc row)) (assoc :reload-count (:count (reload-state ws desc row))))))

(defn status
  "`[{:name :status [:health :detail :container :since :drift]}]` for `name` or every service."
  [ws name]
  (let [descs (if name [(require-description ws name)] (description/list ws))
        rows (rt-rows ws (when name [(role-label name)]))]
    (mapv #(status-row ws % rows) descs)))

(defn- host-port [spec-ports cp] (get spec-ports cp cp))

(defn- host-ports
  "container port -> host port of service `d`: the runtime row's (the container as it is, or as it would be created),
  else the `runtime.edn :services` override, else nil (the description's ports stand)."
  [ws d rows]
  (let [rp (:ports (when (map? rows) (get rows (role-label (:name d)))))]
    (if (seq rp) rp (get-in (runtime/read-config ws) [:services (:name d) :ports]))))

(defn list
  "`[{:name :kind :repo :module :port :jdwp :image :mode [:url] :status :container [:log-target :log]}]`; `:container` is the
  service's runtime container (the runtime's row, else the name it would get), `:log-target` the `log/` target of `env-name`
  for it (`log/service-a`) and `:log` that target's value (`{:container}` or `{:cloudwatch}`; absent when the environment has
  none) (M6.4: the explorer links an ECS service or a log group to both); `:mode` is the service's mode in
  environment `env-name` (default local): local, external (with its `:url`) or off. `:status` is the runtime's.
  `:port` and `:jdwp` are host ports (`runtime.edn :services <name> :ports` applied), the description's when unchanged;
  `:container-port` and `:container-jdwp` are the description's (the IDE joins them with the runtime row's port map)."
  ([ws] (list ws "local"))
  ([ws env-name]
   (let [descs (description/list ws)
         rows (rt-rows ws nil)
         e (if (= "local" env-name) (topology/local-env ws) (env/load ws env-name))
         targets (env/read-targets ws env-name)]
     (mapv (fn [d] (let [ent (env/service-entry e (:name d))]
                     (cond-> (merge (let [hp (host-ports ws d rows)]
                                      (cond-> (select-keys d [:name :kind :repo :module :port :jdwp])
                                        (:port d) (assoc :port (host-port hp (:port d)) :container-port (:port d))
                                        (:jdwp d) (assoc :jdwp (host-port hp (:jdwp d)) :container-jdwp (:jdwp d))))
                                    {:image (or (:image d) description/default-image)
                                     :mode (clojure.core/name (env/service-mode e (:name d)))
                                     :status (:status (status-row ws d rows))
                                     :container (or (:name (when (map? rows) (get rows (role-label (:name d)))))
                                                    (store/container-name ws (role-label (:name d))))})
                       (#{:external :fake} (:mode ent)) (assoc :url (:url ent))
                       (= :fake (:mode ent)) (assoc :fake (:fake ent))
                       ;; the `log/` target of the environment for this service (a container or a CloudWatch group), M6.4
                       (get targets (keyword "log" (role-label (:name d))))
                       (assoc :log-target (str "log/" (role-label (:name d)))
                              :log (get targets (keyword "log" (role-label (:name d))))))))
           descs))))

(defn show
  "The description, its `:taskdef` entries, and `:status` / `:container` (the status row)."
  [ws name]
  (let [d (require-description ws name)
        row (first (status ws name))]
    (assoc d :status (:status row) :container (:container row) :runtime row)))

;; build ----------------------------------------------------------------------------------------

(defn jdk [major]
  (or (System/getenv "ESTATE_JAVA_HOME")
      (when (.isDirectory (io/file "/usr/lib/jvm"))
        (->> (.listFiles (io/file "/usr/lib/jvm")) (map str) sort
             (filter #(and (re-find (re-pattern (str "java-" major "(?!\\d)")) %) (.exists (io/file % "bin" "javac"))))
             first))
      (System/getenv "JAVA_HOME")))

(defn build!
  "Runs the description's build command in the module (JDK of `:build :java` via `ESTATE_JAVA_HOME`,
  `/usr/lib/jvm/java-<n>*`, else `JAVA_HOME`), streaming `service.build.line` events, the output also appended
  as it arrives to `logs/build/<name>-<ts>.log` and, when the build ends, stored raw (source `:build`). `on-cancel!` (the
  protocol's) kills the build's process tree when the request is cancelled; `budget-ms` bounds it (default 10 min,
  `:process/timeout`; the log file holds what was printed).
  -> `{:artifact :bytes :took-ms :log :sha}`; a non-zero exit is `service/build-failed` (data `:log :exit`)."
  [ws name {:keys [sink on-cancel! budget-ms]}]
  (let [d (require-description ws name)
        b (cond-> (:build d)
            (and (= :hotswap (description/image-kind d)) (= "mvn" (first (get-in d [:build :cmd]))))
            (update :cmd into (:deps-args (description/reload-config d))))   ; target/dependency for the exploded classpath
        _ (when-not (seq (:cmd b)) (fail :service/no-build (str "service " name " has no :build :cmd")))
        mod (description/module-dir ws d)
        dir (.getCanonicalFile (io/file mod (or (:dir b) ".")))
        _ (when-not (.isDirectory dir) (fail :service/build-dir (str "build directory " dir " does not exist")))
        t0 (System/nanoTime)
        ts (str/replace (str (store/now)) #"[:]" "")
        logf (.toFile (store/path ws "logs" "build" (str name "-" ts ".log")))
        _ (io/make-parents logf)
        sb (StringBuilder.)
        {:keys [exit]} (with-open [w (io/writer logf :append true)]
                         (try
                           (process/run! (:cmd b)
                                         {:dir dir :on-cancel! on-cancel! :budget-ms budget-ms :what (str "build of " name)
                                          :extra-env (when-let [j (and (:java b) (jdk (:java b)))] {"JAVA_HOME" j})
                                          :line! (fn [l]
                                                   (.append sb l) (.append sb "\n")
                                                   (.write w l) (.write w "\n") (.flush w)
                                                   (when sink (sink {:event "service.build.line" :name name :stream "build" :text l})))})
                           (catch clojure.lang.ExceptionInfo e
                             (if (= :process/cannot-start (:type (ex-data e)))
                               (fail :service/build-failed (ex-message e) :hint "is the build tool on the daemon's PATH?")
                               (throw (ex-info (ex-message e) (assoc (ex-data e) :log (str logf)) e))))))
        raw (store/raw-put! ws (.getBytes (str sb) "UTF-8")
                            {:source :build :op (str/join " " (:cmd b)) :env nil :at (store/now) :content-type "text/plain"})]
    (when-not (zero? exit)
      (fail :service/build-failed (str "build of " name " failed (exit " exit "), log " logf)
            :log (str logf) :exit exit :hint "the log has the build output"))
    (let [a (description/artifact ws d)]
      (cond-> {:took-ms (quot (- (System/nanoTime) t0) 1000000) :log (str logf) :sha (:sha raw)}
        a (assoc :artifact (str a) :bytes (.length a))))))

;; the secrets gate --------------------------------------------------

(defonce ^:private tokens (atom {}))
(def ^:dynamic *token-ttl* (Duration/ofMinutes 10))

(defn- issue! [fp]
  (let [b (byte-array 18) _ (.nextBytes (SecureRandom.) b)
        t (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)
        now (Instant/now)
        exp (.plus now *token-ttl*)]
    (swap! tokens (fn [m] (assoc (into {} (remove (fn [[_ v]] (.isAfter now (:expires v))) m)) t {:fp fp :expires exp})))
    {:token t :expires (str exp)}))

(defn- consume! [token fp]
  (let [now (Instant/now)
        [old _] (swap-vals! tokens (fn [m] (let [v (get m token)] (if (and v (= fp (:fp v))) (dissoc m token) m))))
        v (get old token)]
    (boolean (and v (= fp (:fp v)) (not (.isAfter now (:expires v)))))))

(defn- secret-names
  "env var -> where the value would come from, as the confirmation text shows it: the logical name in the other environment."
  [refs]
  (into (sorted-map)
        (for [[k ref] refs :let [{:keys [service id json-key]} (runtime/logical-ref ref)]]
          [k (str (clojure.core/name service) " " id (when json-key (str " [" json-key "]")))])))

(defn- gate-texts [name from names]
  (let [list-str (str/join ", " (for [[k v] names] (str k " <- " v)))]
    [(str "Start " name " with the REAL secret values of environment " from " instead of the local fakes. "
          "Secrets that would be read from " from " (" (count names) "): " list-str ". Confirmation 1 of 2.")
     (str "The values of those " (count names) " secrets will be fetched from " from " now and written into the environment of the container of "
          name " ONLY (podman create): not into any file under the home, not into the logs, not into the spec hash. "
          "Anyone who can run `podman inspect` on this machine can read them while the container exists; the next plain start of "
          name " replaces the container with the local fakes. Confirmation 2 of 2.")]))

(defn- secrets-gate!
  "The two confirmations of `secrets-from`. Throws `confirm-required` (token 1), `confirm-required` (another token and text 2),
  `service/confirmation-refused` for an unknown, expired, reused or foreign token; returns the secret refs
  (env var -> reference) once token 2 is consumed. Tokens are bound to workspace, service, environment, the secret names and the step."
  [ws name from confirm]
  (env/load ws from)
  (let [refs (runtime/secret-refs ws (role-label name))
        _ (when (empty? refs)
            (fail :service/no-secrets (str "service " name " has no secret references (taskdef :secrets): nothing to take from " from)
                  :hint "service.show shows the description's :taskdef"))
        names (secret-names refs)
        fp [(store/ws-name ws) name from (vec (keys names))]
        texts (gate-texts name from names)
        ask (fn [n]
              (let [{:keys [token expires]} (issue! (conj fp n))
                    summary (nth texts (dec n))]
                (fail :confirm-required summary :token token :summary summary :expires expires
                      :step n :of 2 :env from :names (vec (keys names)) :sources names
                      :confirmations [{:token token :summary summary :expires expires :step n :of 2}]
                      :hint (str "repeat the start with confirm set to the token (" (- 2 n) " more confirmation(s) needed after this one)"))))]
    (cond (nil? confirm) (ask 1)
          (consume! confirm (conj fp 1)) (ask 2)
          (consume! confirm (conj fp 2)) refs
          :else (fail :service/confirmation-refused "the confirmation token is unknown, expired, already used, or was issued for a different request"
                      :hint "start again without confirm to get the first token"))))

;; start / stop / restart -----------------------------------------------------------------------

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
    (when-not (= :local (topo-mode ws name))
      (fail :topology/service-not-local
            (str "service " name " is " (clojure.core/name (topo-mode ws name)) " in the topology of environment local: its container is not desired")
            :service name :env "local"
            :hint (str "dlb topology set local " name " local")))
    (let [refs (when secrets-from (secrets-gate! ws name secrets-from confirm))]
    (when-not (description/runnable? d)
      (fail :service/not-runnable (str "service " name " is a " (clojure.core/name (:kind d)) ": it runs as a function in the environment, not as a container")
            :hint "only spring-boot services start here"))
    (description/module-dir ws d)
    ;; nothing is fetched before token 2 was consumed (the gate returns the refs only then)
    (let [values (when refs (runtime/fetch-secrets ws secrets-from refs))]
      (runtime/ensure! ws (cond-> {:sink sink :only [(role-label name)]}
                            timeout-ms (assoc :timeout-ms timeout-ms)
                            values (assoc :secrets-from {(role-label name) {:env secrets-from :values values}}))))
    (let [r (result ws d)]
      (when sink (sink {:event "service.started" :name name :container (:container r)}))
      r))))

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
  (if (:secrets-from opts)
    (start! ws name opts)   ; a start with secrets always recreates the container; stopping first would strand the service between the two confirmations
    (do (stop! ws name opts)
        (start! ws name opts))))
