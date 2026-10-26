(ns check-all
  "`bb check:all [--ws W]`: the whole integration test as one command (review 2026-10-25, roadmap S1), each stage a `bb`
  task run as a subprocess with its output streamed and kept, then a summary table and the estate down.

  1. `check:arch`
  2. `core:test:all --ws W --keep`: pass A (estate down), pass B (estate up, red on any SKIP); the estate stays up
  3. `cli:test` with `ESTATE_WS=W`, so its estate-gated test runs instead of skipping
  4. `tour --ws W` (chapter 01 finds the estate up)
  5. `acceptance --ws W`
  6. `estate:down --ws W`, always, in a `finally`; the daemon this task started is stopped too.

  Workspace `--ws W` (default `check`); home `$DELIBERATOR_HOME`, else `<repo>/.scratch/check-home`. The table has one row per
  stage (two for the core passes) with the result, the seconds and the stage's own counts; the exit code is 0 only when every
  row is green. Run under `.scratch/runtime.lock`: it owns Floci, Postgres and the estate ports while it runs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dlb.client]
            [dlb.endpoint :as endpoint]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- home [] (str (fs/absolutize (or (System/getenv "DELIBERATOR_HOME") ".scratch/check-home"))))

(defn- secs [t0] (quot (- (System/nanoTime) t0) 1000000000))

(defn- stage!
  "`bb args...` with `env` added, output streamed as it comes and kept; -> {:name :exit :secs :lines}."
  [name env & args]
  (println (str "\n==== check:all " name ": bb " (str/join " " args)))
  (flush)
  (let [t0 (System/nanoTime)
        proc (apply p/process {:err :out :extra-env env} "bb" args)
        lines (with-open [r (io/reader (:out proc))]
                (doall (for [l (line-seq r)] (do (println l) (flush) l))))]
    {:name name :exit (:exit @proc) :secs (secs t0) :lines lines}))

(defn- find-line [lines re] (some #(re-find re %) lines))

(defn rows
  "The summary rows of a stage: `{:stage :ok? :secs :detail}`; `core:test:all` gives one row per pass, read back from the
  lines `core-test-all` prints (`== pass A: exit 0, N tests, ...`), so a skipped test in pass B shows as red here too."
  [{:keys [name exit secs lines]}]
  (let [row (fn [stage ok? detail] {:stage stage :ok? ok? :secs secs :detail (or detail "")})]
    (case name
      "core:test:all"
      (let [pass (fn [p] (second (find-line lines (re-pattern (str "^== pass " p ": (exit .*)$")))))
            a (pass "A") b (pass "B")
            skipped (fn [s] (some-> (re-find #"(\d+) skipped" (str s)) second parse-long))]
        [(row "core:test pass A" (boolean (some-> a (str/starts-with? "exit 0"))) a)
         (row "core:test pass B" (boolean (and b (str/starts-with? b "exit 0") (= 0 (skipped b)))) b)])
      "check:arch" [(row name (zero? exit) (find-line lines #"^arch (ok|violations):.*"))]
      "cli:test" [(row name (zero? exit)
                       (let [ran (find-line lines #"^Ran (\d+) tests containing (\d+) assertions")
                             fe (find-line lines #"^(\d+) failures, (\d+) errors, (\d+) skipped")]
                         (when (and ran fe)
                           (format "%s tests, %s assertions, %s failures, %s errors, %s skipped" (ran 1) (ran 2) (fe 1) (fe 2) (fe 3)))))]
      "tour" [(row name (zero? exit) (find-line lines #"^\d+/\d+ chapters green.*"))]
      "acceptance" [(row name (zero? exit) (find-line lines #"^\d+/\d+ runs green.*"))]
      [(row name (zero? exit) nil)])))

(defn- print-table [all t0 ws home]
  (println (str "\n== check:all ws " ws " home " home))
  (println (format "%-20s %-6s %6s  %s" "stage" "result" "secs" "detail"))
  (doseq [{:keys [stage ok? secs detail]} all]
    (println (format "%-20s %-6s %6d  %s" stage (if ok? "ok" "FAIL") secs detail)))
  (println (format "check:all: %d/%d green, %d s" (count (filter :ok? all)) (count all) (secs t0))))

(defn -main [& args]
  (let [ws (or (arg args "--ws") "check")
        home (home)
        env {"DELIBERATOR_HOME" home}
        pre-existing? (boolean (when-let [c (endpoint/connect-existing home)] (dlb.client/close! (:channel c)) true))
        t0 (System/nanoTime)
        results (atom [])]
    (fs/create-dirs home)
    (try
      (swap! results conj (stage! "check:arch" env "check:arch"))
      (swap! results conj (stage! "core:test:all" env "core:test:all" "--ws" ws "--keep"))
      (swap! results conj (stage! "cli:test" (assoc env "ESTATE_WS" ws) "cli:test"))
      (swap! results conj (stage! "tour" env "tour" "--ws" ws))
      (swap! results conj (stage! "acceptance" env "acceptance" "--ws" ws))
      (finally
        (swap! results conj (stage! "estate:down" env "estate:down" "--ws" ws))
        (when-not pre-existing?
          (let [r (endpoint/stop-daemon! home)]
            (when (:running? r) (println "daemon" (:pid r) (if (:gone? r) "stopped" "STILL RUNNING")))))
        (let [all (mapcat rows @results)]
          (print-table all t0 ws home)
          (System/exit (if (every? :ok? all) 0 1)))))))
