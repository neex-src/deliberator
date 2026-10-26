(ns deliberator.provider
  "Providers (architecture section 6, M6.5): operations that live in another process. `<home>/providers/<name>.edn` names the
  command; the daemon starts it on demand, asks it `op.describe`, exposes its operations as `<name>.<op>` in its own op table
  (`op.describe` of the daemon lists them, `dlb <name> <op>` runs them) and streams the provider's events through to the caller.

  The file: `{:command [\"bb\" \"{{dir}}/catalog.bb\"] [:cwd dir] [:env {\"K\" \"v\"}] [:start-timeout-ms 15000] [:doc \"..\"]}`.
  `:command` is a vector of strings (or one string, split on blanks); `{{dir}}` is the directory of the file, `{{home}}` the
  daemon's home. The process runs in `:cwd` (default `{{dir}}`) with `DELIBERATOR_HOME` set; its stderr is appended raw to
  `<home>/logs/providers/<name>.log` (principle 1), which is also where a crash message takes its tail from. `<name>` is
  `[a-z][a-z0-9-]*` and must not be the area of a built-in operation (`service`, `fake`, ...): `provider/name-taken`.

  The protocol is the daemon's own framing on the process's stdio, newline-delimited JSON:

    daemon -> provider  {\"id\":\"p1\",\"op\":\"op.describe\",\"args\":{}}
                        {\"id\":\"p2\",\"op\":\"echo\",\"args\":{\"text\":\"hi\",\"ws\":\"default\"}}
                        {\"id\":\"p2\",\"cancel\":true}                      (the caller went away; answer or ignore)
    provider -> daemon  {\"id\":\"p2\",\"event\":\"tick\",\"n\":1}              (zero or more, relayed as events of the request)
                        {\"id\":\"p2\",\"ok\":true,\"result\":{...}}            (exactly one terminal line per request)
                        {\"id\":\"p2\",\"ok\":false,\"error\":{\"type\":\"x\",\"message\":\"..\",\"hint\":\"..\"}}

  `op.describe` answers `[{\"op\":\"echo\" \"class\":\"read|mutate\" \"doc\":\"..\" \"args\":{\"text\":{\"type\":\"string\",\"required\":true,
  \"doc\":\"..\"}} [\"order\":[\"text\"]] [\"events\":[\"tick\"]]}]` with bare operation names (the daemon prefixes `<name>.`); the
  daemon validates arguments against it before forwarding, exactly as for its own operations. A provider error type is
  prefixed with the provider's name (`sample/not-found`) unless it already has a namespace. Requests are concurrent (ids are
  multiplexed); a provider should exit when its stdin closes (the daemon closes it to stop it, and the OS does when the daemon
  dies).

  Lifecycle: started lazily by the first call (or by the daemon's `op.describe`, which needs the operations), restarted by the
  next call after it died. A call in flight when the process dies fails with `provider/crashed` (exit code, stderr tail, the
  log path, the hint that the next call restarts it); a process that dies or stays silent while starting is
  `provider/start-failed`. `provider.list` shows what runs; `provider.stop` stops one; the daemon stops them all when it stops."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.process :as process]
            [deliberator.protocol :as protocol])
  (:import (java.io BufferedReader File InputStreamReader OutputStreamWriter Writer)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util.concurrent TimeUnit)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def default-start-timeout-ms 15000)

(defn registry
  "A provider registry for the daemon of `home`: `log` is the daemon's logger (a fn of parts)."
  [home log]
  {:home (str home) :log (or log (fn [& _])) :state (atom {}) :locks (java.util.concurrent.ConcurrentHashMap.)})

(defn- dir-of [reg] (io/file (:home reg) "providers"))

(defn log-file
  "The raw stderr log of provider `name`."
  ^File [reg name]
  (io/file (:home reg) "logs" "providers" (str name ".log")))

;; configuration ---------------------------------------------------------------------------------

(def ^:private name-re #"[a-z][a-z0-9-]*")

(defn- template [s {:keys [dir home]}]
  (-> (str s) (str/replace "{{dir}}" (str dir)) (str/replace "{{home}}" (str home))))

(defn- read-config [reg ^File f reserved]
  (let [nm (str/replace (.getName f) #"\.edn$" "")
        base {:name nm :file (str f)}]
    (try
      (let [m (edn/read-string (slurp f))
            ctx {:dir (.getParent f) :home (:home reg)}
            cmd (:command m)
            cmd (cond (string? cmd) (str/split (str/trim cmd) #"\s+")
                      (and (sequential? cmd) (seq cmd) (every? string? cmd)) (vec cmd)
                      :else nil)]
        (cond
          (not (re-matches name-re nm))
          (assoc base :error (str "the provider name " (pr-str nm) " must match [a-z][a-z0-9-]*") :type :provider/bad-config)
          (contains? reserved nm)
          (assoc base :error (str "the name " nm " is the area of built-in operations") :type :provider/name-taken
                 :hint "rename the file: a provider's operations are <name>.<op>, which must not shadow a built-in one")
          (not (map? m)) (assoc base :error "the file must hold one map" :type :provider/bad-config)
          (nil? cmd) (assoc base :error ":command must be a vector of strings (or one string)" :type :provider/bad-config)
          :else (assoc base
                       :command (mapv #(template % ctx) cmd)
                       :cwd (template (or (:cwd m) "{{dir}}") ctx)
                       :env (into {} (map (fn [[k v]] [(clojure.core/name k) (template v ctx)])) (:env m))
                       :start-timeout-ms (or (:start-timeout-ms m) default-start-timeout-ms)
                       :doc (:doc m))))
      (catch Exception e (assoc base :error (str "cannot read the file: " (ex-message e)) :type :provider/bad-config)))))

(defn configs
  "name -> config of every `providers/*.edn`; a broken one carries `:error` and `:type` instead of a command.
  `reserved` is the set of built-in areas a provider name must not take."
  [reg reserved]
  (let [d (dir-of reg)]
    (into (sorted-map)
          (for [^File f (sort-by #(.getName ^File %) (or (seq (.listFiles d)) []))
                :when (and (.isFile f) (str/ends-with? (.getName f) ".edn"))
                :let [c (read-config reg f reserved)]]
            [(:name c) c]))))

;; the process -----------------------------------------------------------------------------------

(defn- stderr-tail
  "The last lines of the provider's log written since `offset` bytes."
  [^File f offset]
  (try (with-open [r (io/reader f)]
         (.skip r (long (or offset 0)))
         (vec (take-last 12 (line-seq r))))
       (catch Exception _ [])))

(defn- fail-pending! [p err-fn]
  (let [ps (first (swap-vals! (:pending p) (constantly {})))]
    (doseq [[_ {:keys [result]}] ps]
      (deliver result {:ok false :exception (err-fn)}))))

(defn- crash-error [reg p]
  (let [code (try (.exitValue ^Process (:proc p)) (catch Exception _ nil))
        tail (stderr-tail (log-file reg (:name p)) (:log-offset p))]
    (ex-info (str "provider " (:name p) " exited" (when code (str " (code " code ")")) " before it answered"
                  (when (seq tail) (str "; stderr: " (str/join " | " (take-last 4 tail)))))
             {:type :provider/crashed :provider (:name p) :exit code :stderr tail :log (str (log-file reg (:name p)))
              :hint "the provider is started again by the next call; its stderr is in the log"})))

(defn- handle-line! [p line]
  (let [m (try (json/read-str line :key-fn keyword) (catch Exception _ nil))]
    (if-not (and (map? m) (some? (:id m)))
      (when-let [w (:raw-log p)] (w (str "stdout: " line)))
      (let [id (str (:id m))
            entry (get @(:pending p) id)]
        (when entry
          (cond
            (:event m) (when-let [emit! (:emit! entry)]
                         (emit! (-> (dissoc m :id) (update :event str))))
            (contains? m :ok) (do (swap! (:pending p) dissoc id)
                                  (deliver (:result entry) m))))))))

(defn- start-process! [reg {:keys [name command cwd env] :as cfg}]
  (let [lf (log-file reg name)
        _ (io/make-parents lf)
        offset (if (.exists lf) (.length lf) 0)
        raw-log (let [lock (Object.)]
                  (fn [s] (locking lock (spit lf (str s "\n") :append true))))
        _ (raw-log (str "# " (Instant/now) " start " (str/join " " command)))
        pb (doto (ProcessBuilder. ^java.util.List command)
             (.directory (io/file cwd))
             (.redirectError (java.lang.ProcessBuilder$Redirect/appendTo lf)))
        _ (doto (.environment pb)
            (.put "DELIBERATOR_HOME" (:home reg))
            (.putAll ^java.util.Map env))
        proc (try (.start pb)
                  (catch java.io.IOException e
                    (fail :provider/start-failed (str "cannot start provider " name ": " (ex-message e))
                          :provider name :command command
                          :hint (str "is " (first command) " on the daemon's PATH? the command is in " (:file cfg)))))
        p {:name name :cfg cfg :proc proc :pending (atom {}) :seq (atom 0) :raw-log raw-log :log-offset offset
           :out (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
           :started-at (Instant/now) :pid (.pid proc) :stopping (atom false)}]
    (protocol/spawn!
     (fn []
       (try
         (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream proc) StandardCharsets/UTF_8))]
           (loop []
             (when-let [l (.readLine r)]
               (try (handle-line! p l) (catch Throwable e ((:log reg) "provider" name "line failed:" (ex-message e))))
               (recur))))
         (catch Throwable _ nil))
       (try (.waitFor proc 5 TimeUnit/SECONDS) (catch InterruptedException _ nil))
       (let [stopping? @(:stopping p)]
         (swap! (:state reg) (fn [s] (let [cur (get s name)]
                                       (if (and cur (identical? (:proc cur) proc) (not= :failed (:status cur)))
                                         (assoc s name (assoc cur :status (if stopping? :stopped :crashed)
                                                              :last-error (when-not stopping? (ex-message (crash-error reg p)))))
                                         s))))
         (when-not stopping? ((:log reg) "provider" name "exited" (try (.exitValue proc) (catch Exception _ "?"))))
         (fail-pending! p #(if stopping?
                             (ex-info (str "provider " name " was stopped") {:type :provider/stopped :provider name})
                             (crash-error reg p))))))
    p))

(defn- send! [p m]
  (let [^Writer w (:out p)]
    (locking w
      (.write w ^String (str (json/write-str m) "\n"))
      (.flush w))))

(defn- request!
  "Sends `op`/`args` and waits for the terminal line -> `{:ok .. :result|:error}` (or throws the crash exception).
  `emit!` receives the events; `on-cancel!` registers the cancellation. A pending-entry that never gets an answer
  is bounded by `timeout-ms` (nil = no bound)."
  [p op args {:keys [emit! on-cancel! timeout-ms]}]
  (let [id (str "p" (swap! (:seq p) inc))
        result (promise)]
    (swap! (:pending p) assoc id {:emit! emit! :result result})
    (when on-cancel!
      (on-cancel! (fn []
                    (when (swap! (:pending p) (fn [m] (if (contains? m id) (dissoc m id) m)))
                      (try (send! p {:id id :cancel true}) (catch Throwable _ nil))
                      (deliver result {:ok false :exception (ex-info (str "cancelled " op) {:type :provider/cancelled :provider (:name p)})}))
                    nil)))
    (try (send! p {:id id :op op :args (or args {})})
         (catch java.io.IOException _
           ;; the process is gone: the reader thread fails the pending entries; wait for it below
           nil))
    (let [r (if timeout-ms (deref result timeout-ms ::timeout) @result)]
      (swap! (:pending p) dissoc id)
      (cond (= r ::timeout) (fail :provider/timeout (str "provider " (:name p) " did not answer " op " in " timeout-ms " ms")
                                  :provider (:name p))
            (:exception r) (throw (:exception r))
            :else r))))

(defn- type-kw [s]
  (get {"string" :string "int" :int "number" :number "bool" :bool "string[]" :strings "int[]" :ints "object" :object "any" :any}
       (str s) :any))

(defn- op-row
  "A provider's `op.describe` row, normalised: bare op name, class keyword, args schema in the daemon's argument form."
  [row]
  (let [args (:args row)
        required (sort (for [[k v] args :when (:required v)] (name k)))
        order (or (some->> (:order row) (mapv str))
                  (vec (concat required (sort (for [[k v] args :when (not (:required v))] (name k))))))]
    {:op (str (:op row))
     :class (if (= "read" (str (:class row))) :read :mutate)
     :doc (or (:doc row) "(a provider operation without documentation)")
     :events (mapv str (:events row))
     :order order
     :args (into (array-map)
                 (for [k order :let [v (get args (keyword k))] :when v]
                   [(keyword k) (cond-> {:type (type-kw (:type v)) :required (boolean (:required v)) :doc (or (:doc v) "")}
                                  (some? (:default v)) (assoc :default (:default v))
                                  (:positional v) (assoc :positional true))]))}))

(defn- start-lock [reg name] (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap (:locks reg) name (reify java.util.function.Function (apply [_ _] (Object.)))))

(defn- running? [p] (and p (= :running (:status p)) (.isAlive ^Process (:proc p))))

(defn- start-provider! [reg cfg]
  (let [name (:name cfg)
        restarts (inc (or (:restarts (get @(:state reg) name)) -1))
        p (start-process! reg cfg)]
    (swap! (:state reg) assoc name (assoc p :status :starting :restarts restarts))
    (try
      (let [r (try (request! p "op.describe" {} {:timeout-ms (:start-timeout-ms cfg)})
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :provider/crashed (:type (ex-data e)))
                       (throw (ex-info (str "provider " name " died while starting"
                                            (when-let [c (:exit (ex-data e))] (str " (code " c ")"))
                                            (when-let [t (seq (:stderr (ex-data e)))] (str ": " (str/join " | " (take-last 3 t)))))
                                       (assoc (ex-data e) :type :provider/start-failed
                                              :hint (str "run the command by hand: " (str/join " " (:command cfg)))) e))
                       (throw e))))]
        (when-not (and (:ok r) (sequential? (:result r)))
          (fail :provider/protocol (str "provider " name " answered op.describe with something else than a list of operations")
                :provider name :hint "see the protocol in the docstring of deliberator.provider"))
        (let [rows (mapv op-row (:result r))
              p (assoc p :ops rows :status :running :last-error nil)]
          (swap! (:state reg) (fn [s] (if (identical? (:proc (get s name)) (:proc p)) (assoc s name (assoc p :restarts restarts)) s)))
          ((:log reg) "provider" name "started pid" (:pid p) "ops" (count rows))
          p))
      (catch Throwable t
        (swap! (:state reg) assoc name (assoc p :status :failed :last-error (ex-message t) :restarts restarts))
        (reset! (:stopping p) true)
        (try (process/kill-tree! (:proc p)) (catch Throwable _ nil))
        (throw (if (:type (ex-data t)) t
                   (ex-info (str "provider " name " could not start: " (ex-message t)) {:type :provider/start-failed :provider name} t)))))))

(defn ensure-started!
  "The running provider `cfg` names, started (and described) when it is not running."
  [reg cfg]
  (when (:error cfg)
    (fail (or (:type cfg) :provider/bad-config) (str "provider " (:name cfg) ": " (:error cfg)) :provider (:name cfg) :hint (:hint cfg)))
  (let [name (:name cfg)]
    (or (let [p (get @(:state reg) name)] (when (and (running? p) (= (:command cfg) (:command (:cfg p)))) p))
        (locking (start-lock reg name)
          (let [p (get @(:state reg) name)]
            (if (and (running? p) (= (:command cfg) (:command (:cfg p))))
              p
              (do (when (and p (:proc p) (.isAlive ^Process (:proc p))) (reset! (:stopping p) true) (process/kill-tree! (:proc p)))
                  (start-provider! reg cfg))))))))

;; stopping --------------------------------------------------------------------------------------

(defn stop!
  "Stops provider `name` (stdin closed, killed after 2 s) -> true when it was running."
  [reg name]
  (let [p (get @(:state reg) name)]
    (if (and p (:proc p) (.isAlive ^Process (:proc p)))
      (do (reset! (:stopping p) true)
          (try (.close ^Writer (:out p)) (catch Exception _ nil))
          (when-not (.waitFor ^Process (:proc p) 2 TimeUnit/SECONDS) (process/kill-tree! (:proc p)))
          (swap! (:state reg) update name assoc :status :stopped)
          true)
      (do (when p (swap! (:state reg) update name assoc :status :stopped :last-error nil))
          false))))

(defn stop-all!
  "Stops every provider of the registry (the daemon is stopping)."
  [reg]
  (doseq [n (keys @(:state reg))] (try (stop! reg n) (catch Throwable _ nil))))

;; the table -------------------------------------------------------------------------------------

(defn- describable [reg reserved]
  (for [[n c] (configs reg reserved)
        :when (not (:error c))
        :when (not= :failed (:status (get @(:state reg) n)))]
    c))

(defn ops
  "`[{:op \"sample.echo\" :provider \"sample\" :local \"echo\" :class :doc :args :order :events}]` of every configured provider,
  starting those not running (a provider that fails to start contributes nothing here; `provider.list` says why)."
  [reg reserved]
  (->> (describable reg reserved)
       (pmap (fn [c] (try (let [p (ensure-started! reg c)]
                            (for [r (:ops p)]
                              (assoc r :op (str (:name c) "." (:op r)) :local (:op r) :provider (:name c))))
                          (catch Throwable e ((:log reg) "provider" (:name c) "not described:" (ex-message e)) nil))))
       (apply concat)
       vec))

(defn lookup
  "The provider operation `op` (\"sample.echo\") -> its normalised row with `:provider` and `:local`, starting the provider.
  nil when no configured provider owns the prefix; throws `unknown-op` (with the provider's operations) when the provider does
  not have the operation."
  [reg op reserved]
  (let [[pn local] (str/split (str op) #"\." 2)
        cfg (get (configs reg reserved) pn)]
    (when (and cfg local)
      (let [p (ensure-started! reg cfg)
            row (some #(when (= local (:op %)) %) (:ops p))]
        (if row
          (assoc row :op op :provider pn :local local)
          (fail :unknown-op (str "provider " pn " has no operation " local)
                  :known (mapv #(str pn "." (:op %)) (:ops p)) :hint (str "dlb help " pn " lists the operations of the provider")))))))

(defn call!
  "Runs `local` of provider `pn` with the validated `args`; the provider's events go to `(:emit! ctx)`. Returns the result;
  a provider error is thrown as ex-info (type prefixed with the provider's name), a crash as `provider/crashed`."
  [reg pn local args ctx reserved]
  (let [cfg (get (configs reg reserved) pn)
        p (ensure-started! reg cfg)
        r (request! p local args {:emit! (:emit! ctx) :on-cancel! (:on-cancel! ctx)})]
    (if (:ok r)
      (:result r)
      (let [e (:error r)
            t (str (or (:type e) "error"))
            t (if (str/includes? t "/") t (str pn "/" t))]
        (throw (ex-info (str (or (:message e) (str "provider " pn " failed")))
                        (merge (dissoc (into {} e) :type :message) {:type (keyword t) :provider pn})))))))

(defn listing
  "`provider.list`: a row per configured provider, never starting one."
  [reg reserved]
  (vec (for [[n c] (configs reg reserved)
             :let [p (get @(:state reg) n)]]
         (cond-> {:name n :file (:file c)
                  :status (cond (:error c) "bad-config"
                                (running? p) "running"
                                p (name (:status p))
                                :else "stopped")
                  :log (str (log-file reg n))}
           (:command c) (assoc :command (:command c))
           (:doc c) (assoc :doc (:doc c))
           (running? p) (assoc :pid (:pid p) :started-at (str (:started-at p)) :ops (mapv #(str n "." (:op %)) (:ops p)))
           (:restarts p) (assoc :restarts (:restarts p))
           (or (:error c) (:last-error p)) (assoc :error (or (:error c) (:last-error p)))))))
