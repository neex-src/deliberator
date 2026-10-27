(ns acceptance
  "`bb acceptance [--ws W] [--down]`: the M1 acceptance run (layer 5 of docs/plans/m1-test.md).

  1. Estate: if service A and B do not answer `/actuator/health`, `estate/up --ws W` (cold ~2 min).
  2. Session `ACCEPT-<yyyymmdd>` in workspace W (`dlb session start`, idempotent).
  3. `dlb run` of `estate/scenarios/order.scn`, then every `estate/scenarios/payloads/*.scn`, in
     name order, against the `local` environment; a run's exit code is dlb's (0 green, 1 failed
     checks, 2 error).
  4. The reload case (`reload-case`): the ack log string of service A's `Api.java` is patched, `service.reload a`
     brings it into the running container, `order.scn` runs again and the fetched log of A must carry the new
     string; the file is restored (and reloaded once more) in a `finally`.
  5. A summary (scenario, result trailer, run directory) and the exit code: non-zero when any run
     did not end green.
  6. The daemon is stopped with `daemon.stop` (SIGTERM to the pid it announced for a daemon that has no
     such op) when this task started it (a daemon that was already running is left alone); the estate
     stays up unless `--down`.

  Environment: `DELIBERATOR_HOME` (default: dlb's own default), `DELIBERATOR_JAR` (default
  core/target/deliberatord.jar, built when missing), `DLB_DAEMON_ARGS` (default `--idle-minutes 5`,
  the safety net should the stop fail). Run from the repository root, as `bb` does."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [core-build]
            [daemon-client]
            [dlb.client]
            [dlb.endpoint :as endpoint]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- home []
  (or (System/getenv "DELIBERATOR_HOME")
      (when (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
        (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME" {:type "no-default-home"})))
      (str (or (System/getenv "XDG_DATA_HOME") (str (System/getProperty "user.home") "/.local/share")) "/deliberator")))

(defn- healthy? [port]
  (-> (p/shell {:out :string :err :string :continue true}
               "curl" "-s" "-m" "3" "-o" "/dev/null" "-w" "%{http_code}" (str "http://localhost:" port "/actuator/health"))
      :out str/trim (= "200")))

(defn- today [] (.format (java.time.LocalDate/now java.time.ZoneOffset/UTC) (java.time.format.DateTimeFormatter/BASIC_ISO_DATE)))

(defn- stop-daemon!
  "`daemon.stop` through dlb's own client (SIGTERM to the announced pid when the daemon predates it); waits."
  [home]
  (let [r (endpoint/stop-daemon! home)]
    (when (:running? r)
      (println "daemon" (:pid r) (if (:gone? r) "stopped" "STILL RUNNING") (str "(" (:via r) ")")))))

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

(defn reload-case
  "The M3 reload case: patch one log string of service A's source (the original text kept in memory), `service.reload a`,
  run order.scn, fetch A's log and look for the new string; `finally` restores the file and reloads it back (so the
  container does not stay on the patched class). -> a result map like `run-scenario`'s."
  [dlb env ws session]
  (let [f "estate/services/service-a/src/main/java/estate/a/Api.java"
        from "\"ACK received id={}\""
        marker "ACK received (acceptance reload)"
        to (str "\"" marker " id={}\"")
        orig (slurp f)
        scn "estate/scenarios/order.scn"
        fail (fn [why] {:scenario "reload service/a + order.scn" :exit 1 :trailer (str "FAILED " why) :run nil})]
    (if-not (str/includes? orig from)
      (fail (str "the string " from " is not in " f))
      (try
        (spit f (str/replace orig from to))
        (let [rl (daemon-client/call! "service.reload" {:ws ws :name "a"})
              _ (println "reload a:" (:mechanism rl) (str (:took-ms rl) " ms") "redefined" (:redefined rl) "reload-count" (:reload-count rl))
              run (run-scenario dlb env ws scn)
              fetched (p/shell {:out :string :err :string :continue true :extra-env env}
                               dlb "logs" "fetch" "log/service-a" "--since" "5m" "--ws" ws "--session" session)
              path (some #(when (str/starts-with? % "path: ") (subs % 6)) (str/split-lines (:out fetched)))
              found? (boolean (and path (str/includes? (slurp path) marker)))]
          (println (if found? "ok:" "MISSING:") "fetched log of service A" (if found? "carries" "does not carry") (pr-str marker) path)
          (cond (not (zero? (:exit run))) (assoc run :scenario "reload service/a + order.scn")
                (not found?) (fail "the new string is not in the fetched log of service A")
                :else (assoc run :scenario "reload service/a + order.scn"
                             :trailer (str (:trailer run) "; new string in A's log"))))
        (catch Exception e (fail (ex-message e)))
        (finally
          (spit f orig)
          (try (daemon-client/call! "service.reload" {:ws ws :name "a"})
               (catch Exception e (println "restoring service A's class failed (" (ex-message e) "): reload it by hand"))))))))

(defn -main [& args]
  (let [ws (or (arg args "--ws") "default")
        home (home)
        dlb (str (fs/absolutize "cli/dlb"))
        pre-existing? (boolean (when-let [c (endpoint/connect-existing home)] (dlb.client/close! (:channel c)) true))
        t0 (System/nanoTime)]
    (core-build/ensure-jar!)
    (try
      (if (let [targets (try (:targets (estate/local-env ws)) (catch Exception _ nil))
                port (fn [t] (some-> (get targets t) (str/replace #"^.*:" "") parse-long))]
            (and (port :service/a) (port :service/b) (healthy? (port :service/a)) (healthy? (port :service/b))))
        (println "estate already up (service A and B healthy)")
        (estate/up ["--ws" ws]))
      (let [env {"DELIBERATOR_HOME" home
                 "DELIBERATOR_JAR" (or (System/getenv "DELIBERATOR_JAR") (str (fs/absolutize "core/target/deliberatord.jar")))
                 "DLB_DAEMON_ARGS" (or (System/getenv "DLB_DAEMON_ARGS") "--idle-minutes 5")}
            session (str "ACCEPT-" (today))
            _ (p/shell {:extra-env env} dlb "workspace" "open" ws)
            _ (p/shell {:extra-env env} dlb "session" "start" session "--ws" ws)
            results (conj (vec (doall (for [s (scenarios)] (run-scenario dlb env ws s))))
                          (reload-case dlb env ws session))
            bad (remove #(zero? (:exit %)) results)]
        (println "\n== acceptance" session "ws" ws)
        (doseq [{:keys [scenario trailer run exit]} results]
          (println (format "%-4s %-45s %s\n       %s" (if (zero? exit) "ok" "FAIL") scenario trailer run)))
        (println (format "%d/%d runs green, %d s" (- (count results) (count bad)) (count results)
                         (quot (- (System/nanoTime) t0) 1000000000)))
        (when (some #{"--down"} args) (estate/down ["--ws" ws]))
        (when-not pre-existing? (stop-daemon! home))
        (System/exit (if (seq bad) 1 0)))
      (finally (when-not pre-existing? (stop-daemon! home))))))
