(ns tour
  "`bb tour [--ws W] [--only N] [--from N] [--down]`: the tour as an integration test. Every `tour/NN-*.scn`, in name
  order, runs with `dlb run` in workspace W (default `tour`) and one session `TOUR-<yyyymmdd>` against `local`; chapter 01 brings
  the estate up itself. `--only 05` runs chapter 05 only, `--from 05` chapters 05 onwards. dlb's output streams as it comes;
  then one line per chapter (ok/FAIL, the result trailer, the run directory) and the exit code: 1 when any chapter was not green.
  The daemon is stopped at the end when this task started it; the estate stays up unless `--down`.

  Environment: `DELIBERATOR_HOME` (refused without one when `DELIBERATOR_NO_DEFAULT_HOME` is set), `DELIBERATOR_JAR` (default
  core/target/deliberatord.jar, built when missing), `DLB_DAEMON_ARGS` (default `--idle-minutes 15`). Run from the repository
  root, as `bb` does."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dlb.client]
            [dlb.endpoint :as endpoint]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- home []
  (or (System/getenv "DELIBERATOR_HOME")
      (when (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
        (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass DELIBERATOR_HOME" {:type "no-default-home"})))
      (str (or (System/getenv "XDG_DATA_HOME") (str (System/getProperty "user.home") "/.local/share")) "/deliberator")))

(defn- today [] (.format (java.time.LocalDate/now java.time.ZoneOffset/UTC) (java.time.format.DateTimeFormatter/BASIC_ISO_DATE)))

(defn chapters
  "The chapter files, name order, narrowed by `--only N` / `--from N` (N compared with the file name's number prefix)."
  [args]
  (let [all (sort (map str (fs/glob "tour" "[0-9]*.scn")))
        num (fn [f] (re-find #"^\d+" (fs/file-name f)))
        only (arg args "--only") from (arg args "--from")]
    (cond->> all
      only (filter #(= (Long/parseLong only) (Long/parseLong (num %))))
      from (filter #(>= (Long/parseLong (num %)) (Long/parseLong from))))))

(defn- run-chapter
  "`dlb run` of one chapter, its output printed line by line as it comes; -> {:chapter :exit :trailer :run}."
  [dlb env ws session f]
  (println (str "\n== " f))
  (let [proc (p/process {:err :out :extra-env env} dlb "run" (str (fs/absolutize f)) "--ws" ws "--session" session)
        lines (with-open [r (io/reader (:out proc))]
                (doall (for [l (line-seq r)] (do (println l) l))))
        exit (:exit @proc)
        field (fn [pfx] (some #(when (str/starts-with? % pfx) (subs % (count pfx))) (reverse lines)))]
    {:chapter f :exit exit
     :trailer (or (some #(when (re-find #"^(OK|FAILED) " %) %) (reverse lines))
                  (some #(when (str/starts-with? % "error:") %) lines)
                  (str "exit " exit))
     :run (field "run: ")}))

(defn- stop-daemon! [home]
  (let [r (endpoint/stop-daemon! home)]
    (when (:running? r)
      (println "daemon" (:pid r) (if (:gone? r) "stopped" "STILL RUNNING") (str "(" (:via r) ")")))))

(defn -main [& args]
  (let [ws (or (arg args "--ws") "tour")
        home (home)
        dlb (str (fs/absolutize "cli/dlb"))
        files (chapters args)
        pre-existing? (boolean (when-let [c (endpoint/connect-existing home)] (dlb.client/close! (:channel c)) true))
        t0 (System/nanoTime)]
    (when (empty? files) (println "no chapter matches" (str/join " " args)) (System/exit 2))
    (when-not (fs/exists? "core/target/deliberatord.jar")
      (println "core/target/deliberatord.jar missing: building it")
      (p/shell "bb" "core:build"))
    (try
      (let [env {"DELIBERATOR_HOME" home
                 "DELIBERATOR_JAR" (or (System/getenv "DELIBERATOR_JAR") (str (fs/absolutize "core/target/deliberatord.jar")))
                 "DLB_DAEMON_ARGS" (or (System/getenv "DLB_DAEMON_ARGS") "--idle-minutes 15")}
            session (str "TOUR-" (today))
            _ (p/shell {:extra-env env :out :string} dlb "workspace" "open" ws)
            _ (p/shell {:extra-env env :out :string} dlb "session" "start" session "--ws" ws)
            results (vec (for [f files] (run-chapter dlb env ws session f)))
            bad (remove #(zero? (:exit %)) results)]
        (println "\n== tour" session "ws" ws "home" home)
        (doseq [{:keys [chapter trailer run exit]} results]
          (println (format "%-4s %-36s %s%s" (if (zero? exit) "ok" "FAIL") chapter trailer (if run (str "\n       " run) ""))))
        (println (format "%d/%d chapters green, %d s" (- (count results) (count bad)) (count results)
                         (quot (- (System/nanoTime) t0) 1000000000)))
        (when (some #{"--down"} args) (estate/down ["--ws" ws]))
        (when-not pre-existing? (stop-daemon! home))
        (System/exit (if (seq bad) 1 0)))
      (finally (when-not pre-existing? (stop-daemon! home))))))
