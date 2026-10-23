(ns daemon-client
  "The runtime half of `bb runtime:*` and `bb estate:*` through the daemon instead of a JVM per call
 . `(runtime & args)` takes the arguments of `clojure -M -m deliberator.runtime` (`up
  [--infra] | status | down | sync-env`, `--ws W`) and sends the matching op
  (`runtime.ensure|status|down|sync-env`) to the daemon of $DELIBERATOR_HOME, started by
  `dlb.endpoint/connect-compatible!` when it is not running; events are printed as `dlb` prints them.
  `--no-daemon`, or no daemon jar (core/target/deliberatord.jar, $DELIBERATOR_JAR), runs the one-shot
  JVM exactly as before. -> exit code 0 / 1.

  `-main` is the `bb runtime:up|status|down` entry point: `bb runtime:up [--infra] [--ws W]
  [--no-daemon]`. `(stop-if-started! f)` runs `f` and then stops the daemon unless one was already
  running before (for tasks that start the estate on their own account)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [core-build]
            [dlb.client :as client]
            [dlb.endpoint :as endpoint]
            [dlb.render :as render]))

(defn- flag [args k] (second (drop-while #(not= k %) args)))

(defn- print-status [{:keys [ws podman containers]}]
  (println (str "workspace " ws " podman " (:endpoint podman)
                (if (:reachable podman) (str " " (:version podman)) " UNREACHABLE")))
  (doseq [{:keys [role name state health detail ports drift?]} containers]
    (println (format "  %-10s %-26s %-8s %-10s %s%s%s"
                     role name (clojure.core/name state) (if health (clojure.core/name health) "-")
                     (str/join "," (for [[cp hp] ports] (str hp "->" (clojure.core/name cp))))
                     (if drift? "  DRIFT (runtime.edn changed)" "")
                     (if detail (str "  " detail) "")))))

(defn- one-shot [args]
  (:exit (apply p/shell {:dir "core" :continue true} "clojure" "-M" "-m" "deliberator.runtime" args)))

(defn- request
  "Sends `op` with `args` to the (started, compatible) daemon; -> the terminal map."
  [op args]
  (core-build/ensure-jar!)
  (let [home (endpoint/home nil)
        {:keys [channel]} (endpoint/connect-compatible! home (endpoint/jar nil))
        steps (atom {})]
    (try (:terminal (client/request! channel op args
                                     (fn [ev] (when-let [l (render/event-line steps ev)] (println l) (flush)))))
         (finally (client/close! channel)))))

(defn call!
  "Sends `op` with `args` to the daemon of $DELIBERATOR_HOME (started when needed), printing its events;
  -> the result map, or throws `ex-info` with the op's error (`:babashka/exit 1`) for the task to stop on."
  [op args]
  (let [t (request op args)]
    (if (:ok t)
      (:result t)
      (throw (ex-info (str op " failed: " (get-in t [:error :type]) ": " (get-in t [:error :message])
                           (when-let [h (get-in t [:error :hint])] (str "\n  hint: " h)))
                      {:babashka/exit 1 :error (:error t)})))))

(defn- via-daemon [[cmd & more]]
  (let [ws (or (flag more "--ws") "default")
        [op args show] (case cmd
                         "up" ["runtime.ensure" (cond-> {:ws ws} (some #{"--infra"} more) (assoc :infra-only true)) print-status]
                         "status" ["runtime.status" {:ws ws} print-status]
                         "down" ["runtime.down" {:ws ws} (fn [r] (doseq [x (:removed r)] (println "removed" (:name x))))]
                         "sync-env" ["runtime.sync-env" {:ws ws} (fn [_] (println "local environment follows runtime.edn"))])
        ;; commands that bring a runtime into being create the workspace on purpose (reading ops never do)
        _ (when (#{"up" "sync-env"} cmd) (request "workspace.open" {:ws ws}))
        t (request op args)]
    (if (:ok t)
      (do (show (:result t)) 0)
      (do (binding [*out* *err*]
            (println "runtime" cmd "failed:" (get-in t [:error :message]))
            (when-let [h (get-in t [:error :hint])] (println "  hint:" h)))
          1))))

(defn runtime
  "See the namespace docstring."
  [& args]
  (let [no-daemon? (some #{"--no-daemon"} args)
        args (vec (remove #{"--no-daemon"} args))]
    (cond
      no-daemon? (one-shot args)
      (nil? (endpoint/jar nil)) (do (println "no daemon jar (bb core:build builds it): using a one-shot JVM")
                                    (one-shot args))
      :else (via-daemon args))))

(defn -main [cmd & args]
  (System/exit (apply runtime cmd args)))

(defn stop-if-started!
  "Runs `f` and returns its value; afterwards stops the daemon of $DELIBERATOR_HOME unless it was running before."
  [f]
  (let [home (endpoint/home nil)
        before? (when-let [c (endpoint/connect-existing home)] (client/close! (:channel c)) true)]
    (try (f)
         (finally
           (when-not before?
             (let [r (endpoint/stop-daemon! home)]
               (when (:running? r) (println "daemon" (:pid r) (if (:gone? r) "stopped" "STILL RUNNING")))))))))
