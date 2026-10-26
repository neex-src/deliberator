(ns dlb.endpoint
  "Finding, starting and handshaking with `deliberatord`. The home is `--home`, else
  `$DELIBERATOR_HOME`, else `~/.local/share/deliberator`; the endpoint is the one line of
  `<home>/daemon/endpoint`. When it is missing or refuses, the daemon is started detached
  (`setsid -f java -jar <jar> --home H $DLB_DAEMON_ARGS`, output to `daemon/daemon.out`) and the
  file is polled for up to 10 s. The jar is `$DELIBERATOR_JAR`, else `core/target/deliberatord.jar`
  next to this script's real location (`cli/../core/…`), else the same path under the cwd. The
  client never waits for the daemon process, so it cannot leave a zombie behind.

  `connect-compatible!` is the one way in for `dlb` and the bb tasks: connect (starting the daemon),
  `hello` with the version of the jar, and when the daemon answers `compatible:false` and the jar's
  version is newer (dotted numeric compare), no run is in flight: stop it (`daemon.stop`, or SIGTERM
  to the pid it announced when it predates that op), wait until it is gone, start the jar's daemon
  and say hello again. A same-version rebuilt jar is NOT noticed: `dlb daemon stop`.
  `stop-daemon!` never starts anything."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [dlb.client :as client])
  (:import (java.lang ProcessBuilder$Redirect)))

(defn start-timeout-ms
  "How long to poll for the endpoint after starting the daemon: 10 s, or `$DLB_START_TIMEOUT_MS`."
  []
  (or (some-> (System/getenv "DLB_START_TIMEOUT_MS") parse-long) 10000))

(defn home
  "The home directory as a string."
  [home-flag]
  (str (fs/absolutize (or home-flag
                          (System/getenv "DELIBERATOR_HOME")
                          (if (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
                            (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME"
                                            {:type "no-default-home" :cli true}))
                            (str (System/getProperty "user.home") "/.local/share/deliberator"))))))

(defn endpoint-file [home] (fs/file home "daemon" "endpoint"))

(defn read-endpoint [home]
  (let [f (endpoint-file home)]
    (when (fs/exists? f) (not-empty (str/trim (slurp f))))))

(defn jar
  "The daemon jar path or nil. `script-dir` is the directory of the resolved `cli/dlb`."
  [script-dir]
  (if-let [explicit (System/getenv "DELIBERATOR_JAR")]
    (when (fs/exists? explicit) (str (fs/absolutize explicit)))
    (some (fn [p] (when (and p (fs/exists? p)) (str (fs/absolutize p))))
          [(when script-dir (fs/path script-dir ".." "core" "target" "deliberatord.jar"))
           (fs/path "core" "target" "deliberatord.jar")])))

(defn jar-version
  "The version recorded in the jar's `deliberator/version.edn`, or nil."
  [jar-path]
  (try
    (with-open [z (java.util.zip.ZipFile. ^String jar-path)]
      (when-let [e (.getEntry z "deliberator/version.edn")]
        (:version (read-string (slurp (.getInputStream z e))))))
    (catch Exception _ nil)))

(defn- java-bin []
  (if-let [jh (System/getenv "JAVA_HOME")]
    (let [p (fs/path jh "bin" "java")] (if (fs/exists? p) (str p) "java"))
    "java"))

(defn start-daemon!
  "Starts the daemon detached; returns nil. Throws when there is no jar."
  [home jar-path]
  (when-not jar-path
    (throw (ex-info "no daemon jar: set DELIBERATOR_JAR or run `bb core:build`"
                    {:type "daemon/no-jar"})))
  (fs/create-dirs (fs/path home "daemon"))
  (binding [*out* *err*] (println (str "home: " home " (starting a daemon)")))
  (let [out (fs/file home "daemon" "daemon.out")
        _ (spit out (str "home: " home "\n") :append true)
        extra (some-> (System/getenv "DLB_DAEMON_ARGS") str/trim not-empty (str/split #"\s+"))
        detach? (some? (fs/which "setsid"))
        cmd (vec (concat (when detach? ["setsid" "-f"])
                         [(java-bin) "-jar" jar-path "--home" home]
                         extra))
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectInput (ProcessBuilder$Redirect/from (fs/file "/dev/null")))
             (.redirectOutput (ProcessBuilder$Redirect/appendTo out))
             (.redirectError (ProcessBuilder$Redirect/appendTo out)))
        p (.start pb)]
    ;; `setsid -f` forks and exits at once: reap it. The daemon itself is not our child.
    (when detach? (.waitFor p))
    nil))

(defn- version-parts [v]
  (map #(if (re-matches #"\d+" %) (parse-long %) %) (str/split (str v) #"[.\-]")))

(defn version-newer?
  "True when version string `a` is newer than `b`: dotted/dashed parts compared in order, numbers
  numerically, a longer version newer than its own prefix."
  [a b]
  (loop [[x & xs :as as] (version-parts a), [y & ys :as bs] (version-parts b)]
    (cond
      (and (empty? as) (empty? bs)) false
      (empty? bs) true
      (empty? as) false
      :else (let [c (if (and (number? x) (number? y)) (compare x y) (compare (str x) (str y)))]
              (if (zero? c) (recur xs ys) (pos? c))))))

(defn- try-connect [home]
  (when-let [ep (read-endpoint home)]
    (try {:channel (client/connect ep) :endpoint ep}
         (catch Exception _ nil))))

(defn connect-existing
  "A connection to the running daemon of `home` or nil; never starts one. -> {:channel :endpoint}."
  [home]
  (try-connect home))

(defn connect!
  "A connection to the daemon of `home`, starting it when needed. -> {:channel :endpoint :started?}."
  [home jar-path]
  (or (some-> (try-connect home) (assoc :started? false))
      (do (start-daemon! home jar-path)
          (let [deadline (+ (System/currentTimeMillis) (start-timeout-ms))]
            (loop []
              (if-let [c (try-connect home)]
                (assoc c :started? true)
                (if (< (System/currentTimeMillis) deadline)
                  (do (Thread/sleep 50) (recur))
                  (throw (ex-info (str "the daemon did not come up in time; see "
                                       (fs/path home "daemon" "daemon.out"))
                                  {:type "daemon/unreachable"})))))))))

(defn hello!
  "`hello` on `channel`; -> the result map (`:compatible` only when `want` is given)."
  [channel want]
  (get-in (client/request! channel "hello" (cond-> {:client "dlb"} want (assoc :version want)) (fn [_]))
          [:terminal :result]))

(defn- pid-alive? [pid]
  (boolean (some-> (.orElse (java.lang.ProcessHandle/of (long pid)) nil) .isAlive)))

(defn- wait-gone!
  "Waits until the process `pid` is gone and `home` has no endpoint file; false after `timeout-ms`."
  [home pid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (and (not (pid-alive? pid)) (nil? (read-endpoint home))) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn stop-daemon!
  "Stops the daemon of `home` without starting one. -> `{:running? false}` when none answers, else
  `{:running? true :pid :runs [ids] :via \"daemon.stop\"|\"SIGTERM\" :gone? bool}`; `:gone?` is true
  once the process has exited and removed its endpoint (waits up to 20 s)."
  [home]
  (if-let [{:keys [channel]} (try-connect home)]
    (try
      (let [{:keys [pid]} (hello! channel nil)
            r (client/request! channel "daemon.stop" {} (fn [_]))
            ok? (get-in r [:terminal :ok])
            unknown? (= "unknown-op" (get-in r [:terminal :error :type]))]
        (when-not (or ok? unknown?)
          (throw (ex-info (str "daemon.stop failed: " (get-in r [:terminal :error :message]))
                          {:type "daemon/stop-failed"})))
        (when unknown? (some-> (.orElse (java.lang.ProcessHandle/of (long pid)) nil) .destroy))
        {:running? true :pid pid :runs (get-in r [:terminal :result :runs] [])
         :via (if ok? "daemon.stop" "SIGTERM") :gone? (wait-gone! home pid 20000)})
      (finally (client/close! channel)))
    {:running? false}))

(defn connect-compatible!
  "A connection to the daemon of `home` that speaks the version of the jar, starting or replacing it
  (see the namespace docstring). -> `{:channel :hello :restarted? bool}`; throws `daemon/incompatible`
  with a hint when it cannot be made compatible."
  [home jar-path]
  (let [want (some-> jar-path jar-version)
        {:keys [channel]} (connect! home jar-path)
        hello (hello! channel want)]
    (if-not (false? (:compatible hello))
      {:channel channel :hello hello :restarted? false}
      (let [refuse (fn [msg hint] (client/close! channel)
                     (throw (ex-info msg {:type "daemon/incompatible" :hint hint})))
            running (str "daemon pid " (:pid hello) " runs " (:version hello) " but this dlb ships " want)]
        (cond
          (pos? (or (:runs hello) 0))
          (refuse (str running " and has " (:runs hello) " request(s) in flight"
                       (when (pos? (or (:follows hello) 0)) (str " (" (:follows hello) " log follow(s))")))
                  "wait for them or cancel them, then `dlb daemon stop`; dlb restarts the daemon on its next call")
          (not (version-newer? want (:version hello)))
          (refuse (str running " (the daemon is newer than this dlb)")
                  "update dlb's jar (bb core:build) or `dlb daemon stop` to start the one this dlb ships")
          :else
          (do (client/close! channel)
              (let [r (stop-daemon! home)]
                (when-not (:gone? r)
                  (throw (ex-info (str "the old daemon (pid " (:pid hello) ") did not exit"
                                       "; see " (fs/path home "daemon" "daemon.log"))
                                  {:type "daemon/incompatible" :hint "kill that pid"}))))
              (let [{:keys [channel]} (connect! home jar-path)
                    hello (hello! channel want)]
                (when (false? (:compatible hello))
                  (client/close! channel)
                  (throw (ex-info (str "the restarted daemon still runs " (:version hello) ", this dlb ships " want)
                                  {:type "daemon/incompatible" :hint "check DELIBERATOR_JAR"})))
                {:channel channel :hello hello :restarted? true})))))))
