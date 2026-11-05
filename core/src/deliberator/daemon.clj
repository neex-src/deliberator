(ns deliberator.daemon
  "`deliberatord`: the long-lived core process, one instance per `$DELIBERATOR_HOME`, speaking the
  protocol of `deliberator.protocol` with the operations of `deliberator.ops`.

  Files under `<home>/daemon/`: `lock` (held with `FileChannel.tryLock` for the life of the process,
  holds the pid; a second instance refuses with exit code 3), `deliberatord.sock` (unix domain socket,
  mode 600), `endpoint` (one line, `unix:///abs/path/deliberatord.sock` or `tcp://127.0.0.1:<port>`,
  written once the listener is up and removed on shutdown), `daemon.log` (one line per start, stop and
  request: id, op, outcome, ms; never arguments). The socket is replaced by loopback TCP when `--tcp`
  is given, or when a unix socket cannot be bound (path over 100 bytes, no support): the endpoint file
  always says which. The TCP listener is bound to 127.0.0.1 only and has no authentication
 .

  `-main` options: `--home DIR` (default `$DELIBERATOR_HOME`, else the store's default),
  `--idle-minutes N` (default 240; shuts down when no request has been served for that long and none
  is running; 0 disables), `--tcp [PORT]` (0 or no port: any free port), `--version`. It prints
  `deliberatord <version> listening on <endpoint>` and blocks until stopped (idle, SIGTERM, `stop!`, the `daemon.stop` op).
  Exit codes: 0 stopped, 2 usage, 3 another daemon owns the home, 1 anything else.

  `(start! opts)` is the same without the process: `{:home :idle-minutes :tcp}` -> a handle
  `{:endpoint :home :stop! (fn [])  :stopped promise-of-reason}`, used by tests and `core/dev/user.clj`
  (`cljdev` runs the daemon from the REPL on the same socket). Throws `{:type :daemon/already-running
  :pid :endpoint}` when the home is taken. Stopping cancels the runs that are still going (their
  `run.edn` is finalised as cancelled) and removes socket and endpoint; the runtime's containers stay.
  The daemon requires every `deliberator.steps.*` namespace so all kinds are registered."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.ops :as ops]
            [deliberator.protocol :as protocol]
            [deliberator.provider :as provider]
            [deliberator.scenario.engine :as engine]
            [deliberator.steps.dynamodb]
            [deliberator.steps.eventbridge]
            [deliberator.steps.http]
            [deliberator.steps.lambda]
            [deliberator.steps.logs]
            [deliberator.steps.scheduler]
            [deliberator.steps.awscall]
            [deliberator.steps.sql]
            [deliberator.steps.sqs]
            [deliberator.steps.wait]
            [deliberator.steps.ai]
            [deliberator.steps.shell]
            [deliberator.steps.op]
            [deliberator.logfetch :as logfetch]
            [deliberator.store :as store])
  (:import (java.lang ProcessHandle)
           (java.net InetAddress InetSocketAddress StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels FileChannel FileLock OverlappingFileLockException ServerSocketChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.time Instant))
  (:gen-class))

(defn version
  "The version string from the classpath resource `deliberator/version.edn`."
  []
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource "deliberator/version.edn")))]
    (:version (edn/read r))))

(def default-idle-minutes 240.0)

(def ^:private max-unix-path-bytes 100)

(defonce ^:private held-locks (atom #{}))

(defn- opts-of [& xs] (into-array OpenOption xs))

(defn- no-attrs [] (make-array FileAttribute 0))

(defn- chmod! [^Path p mode]
  (try (Files/setPosixFilePermissions p (PosixFilePermissions/fromString mode)) (catch Exception _ nil)))

(defn- logger [^Path file]
  (let [lock (Object.)]
    (fn [& parts]
      (let [line (str (Instant/now) " " (str/join " " (map str parts)) "\n")]
        (locking lock
          (try (Files/write file (.getBytes line StandardCharsets/UTF_8)
                            (opts-of StandardOpenOption/CREATE StandardOpenOption/APPEND))
               (catch Exception _ nil)))))))

(defn- read-pid [^Path lock-file]
  (try (str/trim (slurp (.toFile lock-file))) (catch Exception _ nil)))

(defn- acquire-lock!
  "Takes `daemon/lock`; throws `:daemon/already-running` when another instance (this JVM or another
  process) holds it. -> {:channel :lock :key}."
  [^Path dir]
  (let [file (.resolve dir "lock")
        k (str (.toAbsolutePath file))
        refuse (fn [pid]
                 (throw (ex-info (str "deliberatord is already running for " (.getParent dir)
                                      (when pid (str " (pid " pid ")")))
                                 {:type :daemon/already-running :pid (some-> pid parse-long) :home (str (.getParent dir))
                                  :endpoint (try (str/trim (slurp (.toFile (.resolve dir "endpoint")))) (catch Exception _ nil))})))]
    (let [mine? (atom false)]
      (swap! held-locks (fn [s] (if (contains? s k) (do (reset! mine? false) s) (do (reset! mine? true) (conj s k)))))
      (when-not @mine? (refuse (read-pid file))))
    (let [ch (FileChannel/open file (opts-of StandardOpenOption/CREATE StandardOpenOption/WRITE StandardOpenOption/READ))
          lk (try (.tryLock ch) (catch OverlappingFileLockException _ nil))]
      (if-not lk
        (let [pid (read-pid file)]
          (try (.close ch) (catch Exception _ nil))
          (swap! held-locks disj k)
          (refuse pid))
        (do (.truncate ch 0)
            (.write ch (java.nio.ByteBuffer/wrap (.getBytes (str (.pid (ProcessHandle/current)) "\n") StandardCharsets/UTF_8)))
            (.force ch true)
            {:channel ch :lock lk :key k})))))

(defn- release-lock! [{:keys [^FileChannel channel ^FileLock lock key]}]
  (try (.release lock) (catch Exception _ nil))
  (try (.close channel) (catch Exception _ nil))
  (swap! held-locks disj key))

(defn- bind-unix [^Path sock]
  (Files/deleteIfExists sock)
  (let [ssc (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try (.bind ssc (UnixDomainSocketAddress/of sock))
         (chmod! sock "rw-------")
         ssc
         (catch Throwable t (.close ssc) (throw t)))))

(defn- bind-tcp [port]
  (doto (ServerSocketChannel/open)
    (.bind (InetSocketAddress. (InetAddress/getLoopbackAddress) (int (or port 0))))))

(defn- listener
  "-> {:ssc :endpoint}; unix socket unless `tcp` is set or the socket cannot be bound."
  [^Path dir tcp log]
  (let [sock (.resolve dir "deliberatord.sock")
        tcp-listener (fn []
                       (let [ssc (bind-tcp (when (integer? tcp) tcp))]
                         {:ssc ssc :endpoint (str "tcp://127.0.0.1:" (.getPort ^InetSocketAddress (.getLocalAddress ssc)))}))]
    (if tcp
      (tcp-listener)
      (try
        (when (> (count (.getBytes (str sock) StandardCharsets/UTF_8)) max-unix-path-bytes)
          (throw (ex-info "socket path too long" {})))
        {:ssc (bind-unix sock) :endpoint (str "unix://" sock)}
        (catch Throwable t
          (log "unix socket unusable, falling back to tcp:" (ex-message t))
          (tcp-listener))))))

(defn start!
  "Starts a daemon in this JVM; see the namespace docstring."
  [{:keys [home idle-minutes tcp]}]
  (let [home (.toAbsolutePath (.normalize (.toPath (io/file (str (or home (store/home)))))))
        dir (.resolve home "daemon")
        idle-ms (long (* 60000 (double (or idle-minutes default-idle-minutes))))
        stopped (promise)]
    (Files/createDirectories dir (no-attrs))
    (chmod! dir "rwx------")
    (let [lock (acquire-lock! dir)
          log (logger (.resolve dir "daemon.log"))]
      (try
        (let [orphans (try (binding [store/*home* home] (engine/mark-orphans! home))
                           (catch Throwable e (log "orphan scan failed" (ex-message e)) []))
              adopted (try (binding [store/*home* home] (logfetch/adopt-orphans! home))
                           (catch Throwable e (log "follow adoption failed" (ex-message e)) []))
              _ (when (seq adopted) (log "adopted unfinished log follows:" (str/join "," adopted)))
              _ (when (seq orphans) (log "marked interrupted:" (str/join "," (map #(.getFileName ^Path %) orphans))))
              {:keys [ssc endpoint]} (listener dir tcp log)
              last-activity (atom (System/currentTimeMillis))
              inflight (atom 0)
              runs (atom {})
              providers (provider/registry home log)
              follows (atom 0)                 ; active logs.fetch follows: work in flight like a run (hello counts them, R32)
              stop-ref (atom nil)
              dstate {:version (version) :pid (.pid (ProcessHandle/current)) :home home :providers providers
                      :endpoint endpoint :runs runs :follows follows :checked (atom #{})
                      ;; `daemon.stop`: the same `stop!`, started after the answer has been written
                      :stop-later! (fn [] (protocol/spawn! (fn [] (Thread/sleep 150) (@stop-ref :requested))))}
              stopping (atom false)
              server (atom nil)
              stop! (fn stop!
                      ([] (stop! :stopped))
                      ([reason]
                       (when (compare-and-set! stopping false true)
                         (log "stopping:" (name reason))
                         (try
                           (let [rs (vals @runs)]
                             (doseq [r rs] (try (engine/cancel! r) (catch Throwable _ nil)))
                             (doseq [r rs]
                               (when (= ::timeout (deref (:done r) 3000 ::timeout))
                                 (log "run did not finish in 3s, marking it interrupted:" (:run-id r))
                                 (try (engine/interrupt-run! (:run-dir r) "the daemon stopped before the run finished")
                                      (catch Throwable e (log "could not mark" (:run-id r) (ex-message e)))))))
                           (catch Throwable e (log "stop: finalising runs failed" (ex-message e)))
                           (finally
                             (try (when-let [s @server] ((:stop! s))) (catch Throwable _ nil))
                             (try (provider/stop-all! providers) (catch Throwable _ nil))
                             (try (Files/deleteIfExists (.resolve dir "endpoint")) (catch Exception _ nil))
                             (try (Files/deleteIfExists (.resolve dir "deliberatord.sock")) (catch Exception _ nil))
                             (release-lock! lock)
                             (deliver stopped reason))))))
              dispatch (fn [{:keys [id op args]} ctx]
                         (let [t0 (System/nanoTime)
                               done (fn [outcome] (reset! last-activity (System/currentTimeMillis))
                                      (swap! inflight dec)
                                      (log "req" id op outcome (str (quot (- (System/nanoTime) t0) 1000000) "ms")))]
                           (reset! last-activity (System/currentTimeMillis))
                           (swap! inflight inc)
                           (try (let [r (ops/call! op (assoc ctx :daemon dstate) args)] (done "ok") r)
                                (catch Throwable e
                                  (done (str "error " (:type (ops/error-map e))))
                                  (when-not (ex-data e) (log "internal error in" op ":" e))
                                  (throw e)))))]
          (reset! stop-ref stop!)
          (reset! server (binding [store/*home* home]
                           (protocol/listen! ssc {:dispatch dispatch :error-fn ops/error-map :log log})))
          (store/write-bytes-atomic! (.resolve dir "endpoint") (.getBytes (str endpoint "\n") StandardCharsets/UTF_8))
          (log "started" (version) "pid" (:pid dstate) "home" home "endpoint" endpoint
               "idle-minutes" (/ idle-ms 60000.0) "threads" (name protocol/thread-model))
          (when (pos? idle-ms)
            (protocol/spawn!
             (fn []
               (loop []
                 (Thread/sleep 250)
                 (cond (realized? stopped) nil
                       (and (zero? @inflight) (> (- (System/currentTimeMillis) @last-activity) idle-ms))
                       (stop! :idle)
                       :else (recur))))))
          {:endpoint endpoint :home home :stop! stop! :stopped stopped :pid (:pid dstate)})
        (catch Throwable t
          (release-lock! lock)
          (throw t))))))

;; main ----------------------------------------------------------------------------------------

(def ^:private usage
  "usage: deliberatord [--home DIR] [--idle-minutes N] [--tcp [PORT]] | --version")

(defn- parse-args [args]
  (loop [[a & more :as xs] args opts {}]
    (cond
      (empty? xs) opts
      (= a "--version") (recur more (assoc opts :version? true))
      (= a "--help") (recur more (assoc opts :help? true))
      (= a "--home") (if (seq more) (recur (rest more) (assoc opts :home (first more))) (throw (ex-info "--home needs a directory" {:usage true})))
      (= a "--idle-minutes") (if (seq more)
                               (recur (rest more) (assoc opts :idle-minutes
                                                         (try (Double/parseDouble (first more))
                                                              (catch Exception _ (throw (ex-info "--idle-minutes needs a number" {:usage true}))))))
                               (throw (ex-info "--idle-minutes needs a number" {:usage true})))
      (= a "--tcp") (if (and (seq more) (re-matches #"\d+" (first more)))
                      (recur (rest more) (assoc opts :tcp (Long/parseLong (first more))))
                      (recur more (assoc opts :tcp true)))
      :else (throw (ex-info (str "unknown argument " a) {:usage true})))))

(defn -main
  "Runs the daemon until it stops; see the namespace docstring."
  [& args]
  (let [opts (try (parse-args args)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println (ex-message e)) (println usage))
                    (System/exit 2)))]
    (when (and (not (:version? opts)) (not (:help? opts)) (not (:home opts))
               (System/getenv "DELIBERATOR_NO_DEFAULT_HOME") (not (System/getenv "DELIBERATOR_HOME")))
      (binding [*out* *err*] (println store/no-default-home-message))
      (System/exit 2))
    (cond
      (:version? opts) (println (str "deliberatord " (version)))
      (:help? opts) (println usage)
      :else
      (do
        (when (:home opts)
          (let [h (.toAbsolutePath (.toPath (io/file (:home opts))))]
            (alter-var-root #'store/*home* (constantly h))))
        (let [h (try (start! (select-keys opts [:home :idle-minutes :tcp]))
                     (catch clojure.lang.ExceptionInfo e
                       (binding [*out* *err*] (println (ex-message e)))
                       (System/exit (if (= :daemon/already-running (:type (ex-data e))) 3 1)))
                     (catch Throwable t
                       (binding [*out* *err*] (println "deliberatord failed to start:" (ex-message t)))
                       (System/exit 1)))]
          (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] ((:stop! h) :signal))))
          (println (str "deliberatord " (version) " listening on " (:endpoint h)))
          (flush)
          @(:stopped h)
          (shutdown-agents)
          (System/exit 0))))))
