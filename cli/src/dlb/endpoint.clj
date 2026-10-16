(ns dlb.endpoint
  "Finding, starting and handshaking with `deliberatord`. The home is `--home`, else
  `$DELIBERATOR_HOME`, else `~/.local/share/deliberator`; the endpoint is the one line of
  `<home>/daemon/endpoint`. When it is missing or refuses, the daemon is started detached
  (`setsid -f java -jar <jar> --home H $DLB_DAEMON_ARGS`, output to `daemon/daemon.out`) and the
  file is polled for up to 10 s. The jar is `$DELIBERATOR_JAR`, else `core/target/deliberatord.jar`
  next to this script's real location (`cli/../core/…`), else the same path under the cwd. The
  client never waits for the daemon process, so it cannot leave a zombie behind."
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
                          (str (System/getProperty "user.home") "/.local/share/deliberator")))))

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
  (let [out (fs/file home "daemon" "daemon.out")
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

(defn- try-connect [home]
  (when-let [ep (read-endpoint home)]
    (try {:channel (client/connect ep) :endpoint ep}
         (catch Exception _ nil))))

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
