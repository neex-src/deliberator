(ns tour
  "`bb tour [--ws W] [--only N] [--from N] [--down]`: the tour as an integration test. Every `tour/NN-*.scn`, in name
  order, runs with `dlb run` in workspace W (default `tour`) and one session `TOUR-<yyyymmdd>` against `local`; chapter 01 brings
  the estate up itself. `--only 05` runs chapter 05 only, `--from 05` chapters 05 onwards. dlb's output streams as it comes;
  then one line per chapter (ok/FAIL, the result trailer, the run directory) and the exit code: 1 when any chapter was not green.
  The daemon is stopped at the end when this task started it; the estate stays up unless `--down`.

  Chapter 11 runs against `tour-remote`, a remote-shaped record at the tour's Floci: its profile `tour-remote` (fake keys, the
  emulator's) is written to `<home>/tour-aws-credentials`, which the daemon this task starts gets as `AWS_SHARED_CREDENTIALS_FILE`
  (a daemon that is already running keeps its own environment: when chapter 11 is in the run and the running daemon of this home does
  not have the file, it is stopped and replaced; `/proc/<pid>/environ` says).

  Environment, all optional: `DELIBERATOR_HOME` (default `<repo>/.scratch/tour-home`, the home of `bb tour:ide`), `DELIBERATOR_JAR` (default
  core/target/deliberatord.jar, built when missing), `DLB_DAEMON_ARGS` (default `--idle-minutes 15`). Run from the repository
  root, as `bb` does."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [core-build]
            [clojure.string :as str]
            [dlb.client]
            [dlb.endpoint :as endpoint]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- home
  "$DELIBERATOR_HOME, else `<repo>/.scratch/tour-home`: the same home `bb tour:ide` opens, so the IDE sees what the test ran."
  []
  (str (fs/absolutize (or (System/getenv "DELIBERATOR_HOME") ".scratch/tour-home"))))

(defn- credentials-file!
  "`<home>/tour-aws-credentials` with the profile chapter 11's record uses (Floci takes any keys); -> its path."
  [home]
  (let [f (fs/path home "tour-aws-credentials")]
    (fs/create-dirs home)
    (spit (str f) "[tour-remote]\naws_access_key_id = test\naws_secret_access_key = test\n")
    (str f)))

(defn- daemon-has-credentials?
  "True when the daemon of `home` (the pid in its lock file) was started with `AWS_SHARED_CREDENTIALS_FILE=<creds>`."
  [home creds]
  (try (let [pid (str/trim (slurp (str (fs/path home "daemon" "lock"))))
             environ (slurp (str "/proc/" pid "/environ"))]
         (boolean (some #(= (str "AWS_SHARED_CREDENTIALS_FILE=" creds) %) (str/split environ #"\u0000"))))
       (catch Exception _ false)))

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
        creds (credentials-file! home)
        running? (boolean (when-let [c (endpoint/connect-existing home)] (dlb.client/close! (:channel c)) true))
        ;; chapter 11 needs the daemon to see the profile `tour-remote`: a running daemon without it is replaced
        replace? (and running? (some #(re-find #"/11-" %) files) (not (daemon-has-credentials? home creds)))
        _ (when replace?
            (println "the daemon of" home "was not started with the tour's credentials file (chapter 11 needs the profile tour-remote): stopping it, `bb tour` starts its own")
            (stop-daemon! home))
        pre-existing? (and running? (not replace?))
        t0 (System/nanoTime)]
    (when (empty? files) (println "no chapter matches" (str/join " " args)) (System/exit 2))
    (core-build/ensure-jar!)
    (try
      (let [env {"DELIBERATOR_HOME" home
                 "DELIBERATOR_JAR" (or (System/getenv "DELIBERATOR_JAR") (str (fs/absolutize "core/target/deliberatord.jar")))
                 "DLB_DAEMON_ARGS" (or (System/getenv "DLB_DAEMON_ARGS") "--idle-minutes 15")
                 "AWS_SHARED_CREDENTIALS_FILE" creds}
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
