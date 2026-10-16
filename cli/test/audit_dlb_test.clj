(ns audit-dlb-test
  "Audit of M1, layer 4 (`dlb`), odd inputs, against a real daemon on a short home (same harness as
  `dlb-test`, whose helpers it reuses). Ctrl-C (SIGINT) mid-run cancels the run, two `dlb run` in
  parallel on one session, `runs diff` of a run against itself, a scenario file outside the session
  and the repo, `raw get` of a 5 MB blob, `--ws ../x`, `dlb run | head`. Tests tagged `:known-bug`
  document behaviour the product does not have; `bb cli:test` reports them but they are not run
 ."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dlb-test :as t :refer [dlb]]))

(def ^:dynamic *home* nil)

(use-fixtures :once
  (fn [f]
    (let [h (t/new-home)]
      (binding [*home* h]
        (try (f) (finally (t/stop-daemon! h) (fs/delete-tree h)))))))

(defn- scn! [name text]
  (let [f (fs/path t/root "cli" "target" "audit" name)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)
    (str f)))

(defn- json-out [{:keys [out]}] (json/parse-string (first (remove str/blank? (str/split-lines out))) true))

(defn- run-rows [home]
  (get-in (json-out (dlb home "runs" "list" "--limit" "50" "--json")) [:result :runs]))

(defn- until [ms f]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 100) (recur))))))

(defn- spawn
  "Starts `dlb args` without waiting; -> the babashka.process."
  [home & args]
  (apply p/process {:out :string :err :string :extra-env {"DELIBERATOR_HOME" home "DLB_DAEMON_ARGS" "--idle-minutes 0.5"}}
         t/dlb-bin args))

(deftest sigint-mid-run-cancels-the-run
  (dlb *home* "session" "start" "SIG")
  (let [path (scn! "long.scn" "# @env local\n\n### wait long\n# @for 60s\n")
        proc (spawn *home* "run" path)
        pid (.pid ^Process (:proc proc))]
    (try
      (is (some? (until 20000 (fn [] (some (fn [r] (= "running" (:status r))) (run-rows *home*))))) "the run started")
      (p/shell {:continue true} "kill" "-INT" (str pid))
      (let [exited (.waitFor ^Process (:proc proc) 15 java.util.concurrent.TimeUnit/SECONDS)]
        (is (true? exited) "dlb ended after SIGINT")
        (is (some? (until 15000 (fn [] (some (fn [r] (= "cancelled" (:status r))) (run-rows *home*)))))
            (str "the run is recorded as cancelled: " (pr-str (map :status (run-rows *home*))))))
      (finally (when (.isAlive ^Process (:proc proc)) (.destroy ^Process (:proc proc)))))))

(deftest dlb-run-into-a-closed-pipe-does-not-cancel-the-run
  ;; `dlb run x.scn | head -1`: head exits after the first line, the next println throws, dlb exits, the
  ;; socket closes and cancel-on-close ends the run: the user asked for fewer lines, not for no run.
  (dlb *home* "session" "start" "PIPE")
  (let [path (scn! "pipe.scn" "# @env local\n\n### wait one\n# @for 1500ms\n\n### wait two\n# @for 1500ms\n")
        r (p/shell {:out :string :err :string :continue true
                    :extra-env {"DELIBERATOR_HOME" *home* "DLB_DAEMON_ARGS" "--idle-minutes 0.5"}}
                   "sh" "-c" (str t/dlb-bin " run " path " | head -n 1"))
        before (set (map :run-id (run-rows *home*)))]
    (is (= "run" (first (str/split (str/trim (:out r)) #"\s"))))
    (Thread/sleep 4000)
    (let [row (first (filter #(str/ends-with? (:run-id %) "pipe") (run-rows *home*)))]
      (is (some? row) (pr-str before))
      (is (= "ok" (:status row)) (str "run ended " (:status row))))))

(deftest two-dlb-runs-in-parallel-on-one-session
  (dlb *home* "session" "start" "PAR")
  (let [path (scn! "par.scn" "# @env local\n\n### wait one\n# @for 800ms\n# @assert elapsed >= 700\n")
        procs (doall (repeat 4 nil))
        ps (doall (for [_ procs] (spawn *home* "run" path "--json")))
        results (doall (map (fn [pr] (deref pr 60000 nil)) ps))
        outs (map #(json-out %) results)
        dirs (map #(get-in % [:result :paths :run]) outs)]
    (is (every? some? results))
    (is (every? zero? (map :exit results)) (pr-str (map (juxt :exit :err) results)))
    (is (= 4 (count (set dirs))) "four run directories")
    (is (= 4 (count (filter #(str/includes? (:run-id %) "par") (run-rows *home*)))))))

(deftest runs-diff-of-a-run-against-itself-is-equal
  (dlb *home* "session" "start" "SELF")
  (let [r (json-out (dlb *home* "run" t/fail-scn "--json"))
        id (get-in r [:result :run-id])
        d (dlb *home* "runs" "diff" id id)]
    (is (zero? (:exit d)) (pr-str d))
    (is (str/includes? (:out d) "runs are equal"))
    (is (true? (get-in (json-out (dlb *home* "runs" "diff" id id "--json")) [:result :equal])))))

(deftest a-scenario-outside-the-session-and-the-repo-runs-by-absolute-and-relative-path
  (dlb *home* "session" "start" "OUT")
  (let [path (scn! "elsewhere/far.scn" "# @env local\n\n### wait far\n# @for 50ms\n")
        abs (dlb *home* "run" path)
        rel (p/shell {:out :string :err :string :continue true :dir (str (fs/parent path))
                      :extra-env {"DELIBERATOR_HOME" *home* "DLB_DAEMON_ARGS" "--idle-minutes 0.5"}}
                     t/dlb-bin "run" "far.scn")
        _ (fs/create-dirs (fs/path (fs/parent path) "x"))
        dotdot (p/shell {:out :string :err :string :continue true :dir (str (fs/path (fs/parent path) "x"))
                         :extra-env {"DELIBERATOR_HOME" *home* "DLB_DAEMON_ARGS" "--idle-minutes 0.5"}}
                        t/dlb-bin "run" "../far.scn")]
    (is (zero? (:exit abs)) (:err abs))
    (is (zero? (:exit rel)) (:err rel))
    (testing "a ../ path is resolved by the shell's cwd, before the daemon sees it"
      (is (zero? (:exit dotdot)) (:err dotdot)))))

(deftest raw-get-of-a-5mb-blob-prints-a-summary
  (dlb *home* "session" "start" "RAW")
  (let [;; the CLI has no `raw put`; the blob goes in through a scenario's own scenario-file blob (the run
        ;; persists the scenario text), so a 5 MB scenario body makes a 5 MB blob in the raw store
        scn (scn! "five.scn" (str "# @env local\n\n### wait big\n# @for 10ms\n" (apply str (repeat (* 5 1024 1024) "A")) "\n"))
        r (json-out (dlb *home* "run" scn "--json"))
        shas (->> (fs/glob (fs/path *home* "workspaces") "**/raw/sha256/*/*")
                  (remove #(str/ends-with? (str %) ".meta.edn"))
                  (filter #(> (fs/size %) (* 4 1024 1024)))
                  (map #(str (fs/file-name (fs/parent %)) (fs/file-name %))))]
    (is (map? r))
    (is (seq shas) "the 5 MB scenario text is in the raw store (persist first)")
    (when (seq shas)
      (let [g (dlb *home* "raw" "get" (first shas))]
        (is (zero? (:exit g)) (:err g))
        (is (< (count (:out g)) 4000) "a summary, not the blob")
        (is (re-find #"(?m)^bytes: 52\d{5}$" (:out g)) (:out g))))))

(deftest odd-workspace-and-session-names-are-exit-2
  (doseq [args [["session" "start" "../x"] ["session" "start" "a/b"] ["runs" "list" "--ws" "../x"]
                ["workspace" "show" "--ws" "a b"]]]
    (let [r (apply dlb *home* args)]
      (is (= 2 (:exit r)) (pr-str args r))
      (is (not (str/includes? (:err r) "Exception")) (:err r)))))
