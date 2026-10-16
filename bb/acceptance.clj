(ns acceptance
  "`bb acceptance [--ws W] [--down]`: the M1 acceptance run (layer 5 of docs/plans/m1-test.md).

  1. Estate: if service A and B do not answer `/actuator/health`, `estate/up --ws W` (cold ~2 min).
  2. Session `ACCEPT-<yyyymmdd>` in workspace W (`dlb session start`, idempotent).
  3. `dlb run` of `estate/scenarios/order.scn`, then every `estate/scenarios/payloads/*.scn`, in
     name order, against the `local` environment; a run's exit code is dlb's (0 green, 1 failed
     checks, 2 error).
  4. A summary (scenario, result trailer, run directory) and the exit code: non-zero when any run
     did not end green.
  5. The daemon is stopped by SIGTERM to the exact pid in `daemon/lock` when this task started it
     (a daemon that was already running is left alone); the estate stays up unless `--down`.

  Environment: `DELIBERATOR_HOME` (default: dlb's own default), `DELIBERATOR_JAR` (default
  core/target/deliberatord.jar, built when missing), `DLB_DAEMON_ARGS` (default `--idle-minutes 5`,
  the safety net should the stop fail). Run from the repository root, as `bb` does."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- home []
  (or (System/getenv "DELIBERATOR_HOME")
      (str (or (System/getenv "XDG_DATA_HOME") (str (System/getProperty "user.home") "/.local/share")) "/deliberator")))

(defn- healthy? [port]
  (-> (p/shell {:out :string :err :string :continue true}
               "curl" "-s" "-m" "3" "-o" "/dev/null" "-w" "%{http_code}" (str "http://localhost:" port "/actuator/health"))
      :out str/trim (= "200")))

(defn- today [] (.format (java.time.LocalDate/now java.time.ZoneOffset/UTC) (java.time.format.DateTimeFormatter/BASIC_ISO_DATE)))

(defn- lock-pid [home]
  (let [f (str home "/daemon/lock")]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- alive? [pid] (zero? (:exit (p/shell {:out :string :err :string :continue true} "kill" "-0" (str pid)))))

(defn- stop-daemon!
  "SIGTERM to the daemon's pid (its shutdown hook finalises runs), wait up to 10 s, `kill -9` that pid last."
  [home]
  (when-let [pid (lock-pid home)]
    (when (alive? pid)
      (p/shell {:continue true} "kill" (str pid))
      (loop [n 0] (when (and (alive? pid) (< n 100)) (Thread/sleep 100) (recur (inc n))))
      (when (alive? pid) (p/shell {:continue true} "kill" "-9" (str pid)))
      (println "daemon" pid (if (alive? pid) "STILL RUNNING" "stopped")))))

(defn- scenarios []
  (concat ["estate/scenarios/order.scn"]
          (sort (map str (fs/glob "estate/scenarios/payloads" "*.scn")))))

(defn- run-scenario [dlb env ws scn]
  (let [r (p/shell {:out :string :err :string :continue true :extra-env env} dlb "run" scn "--ws" ws)
        lines (str/split-lines (:out r))
        field (fn [pfx] (some #(when (str/starts-with? % pfx) (subs % (count pfx))) (reverse lines)))]
    (print (:out r)) (print (:err r)) (flush)
    {:scenario scn :exit (:exit r)
     :trailer (or (some #(when (re-find #"^(OK|FAILED) " %) %) (reverse lines)) (str "exit " (:exit r)))
     :run (field "run: ")}))

(defn -main [& args]
  (let [ws (or (arg args "--ws") "default")
        home (home)
        dlb (str (fs/absolutize "cli/dlb"))
        pre-existing? (boolean (some-> (lock-pid home) alive?))
        t0 (System/nanoTime)]
    (when-not (fs/exists? "core/target/deliberatord.jar")
      (println "core/target/deliberatord.jar missing: building it")
      (p/shell "bb" "core:build"))
    (if (and (healthy? 8081) (healthy? 8082))
      (println "estate already up (service A and B healthy)")
      (estate/up ["--ws" ws]))
    (let [env {"DELIBERATOR_HOME" home
               "DELIBERATOR_JAR" (or (System/getenv "DELIBERATOR_JAR") (str (fs/absolutize "core/target/deliberatord.jar")))
               "DLB_DAEMON_ARGS" (or (System/getenv "DLB_DAEMON_ARGS") "--idle-minutes 5")}
          session (str "ACCEPT-" (today))
          results (try
                    (p/shell {:extra-env env} dlb "session" "start" session "--ws" ws)
                    (doall (for [s (scenarios)] (run-scenario dlb env ws s)))
                    (finally (when-not pre-existing? (stop-daemon! home))))
          bad (remove #(zero? (:exit %)) results)]
      (println "\n== acceptance" session "ws" ws)
      (doseq [{:keys [scenario trailer run exit]} results]
        (println (format "%-4s %-45s %s\n       %s" (if (zero? exit) "ok" "FAIL") scenario trailer run)))
      (println (format "%d/%d runs green, %d s" (- (count results) (count bad)) (count results)
                       (quot (- (System/nanoTime) t0) 1000000000)))
      (when (some #{"--down"} args) (estate/down ["--ws" ws]))
      (System/exit (if (seq bad) 1 0)))))
