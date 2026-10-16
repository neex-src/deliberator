(ns dlb-test
  "Layer 4: `dlb` as a user runs it, against a real daemon started from the built jar by `dlb`
  itself on a short home under `cli/target/th/` (socket paths are limited to ~104 bytes). Every
  daemon started here is stopped by SIGTERM to the pid in its lock file;
  `DLB_DAEMON_ARGS=--idle-minutes 0.2` is the safety net if the tests die first."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dlb.args :as args]))

(def root (str (fs/absolutize ".")))
(def dlb-bin (str root "/cli/dlb"))
(def wait-scn (str root "/cli/test/fixtures/wait.scn"))
(def fail-scn (str root "/cli/test/fixtures/fail.scn"))

(defn- new-home []
  (let [h (fs/path root "cli" "target" "th" (Long/toString (mod (System/nanoTime) 1679616) 36))]
    (fs/create-dirs h)
    (str h)))

(defn dlb
  "Runs `dlb` with `args` in `home`; -> {:exit :out :err}."
  [home & args]
  (let [r (apply p/shell {:out :string :err :string :continue true
                          :extra-env {"DELIBERATOR_HOME" home "DLB_DAEMON_ARGS" "--idle-minutes 0.2"}}
                 dlb-bin args)]
    (select-keys r [:exit :out :err])))

(defn- lock-pid [home]
  (let [f (fs/file home "daemon" "lock")]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- alive? [pid] (zero? (:exit (p/shell {:out :string :err :string :continue true} "kill" "-0" (str pid)))))

(defn stop-daemon!
  "SIGTERM to the pid of the daemon of `home` (its shutdown hook stops it cleanly), then waits."
  [home]
  (when-let [pid (lock-pid home)]
    (when (alive? pid)
      (p/shell {:continue true} "kill" (str pid))
      (loop [n 0]
        (when (and (alive? pid) (< n 100)) (Thread/sleep 100) (recur (inc n))))
      (when (alive? pid) (p/shell {:continue true} "kill" "-9" (str pid))))))

(def ^:dynamic *home* nil)

(use-fixtures :once
  (fn [t]
    (let [h (new-home)]
      (binding [*home* h]
        (try (t) (finally (stop-daemon! h) (fs/delete-tree h)))))))

(defn- json-line [out]
  (let [ls (remove str/blank? (str/split-lines out))]
    (is (= 1 (count ls)) "--json prints exactly one line")
    (json/parse-string (first ls) true)))

;; arguments (in process) -------------------------------------------------------------------------

(def fake {:op "x.y" :args {:key {:type "string" :required true :doc "k"}
                            :other {:type "string" :required true}
                            :n {:type "int"} :f {:type "bool"} :l {:type "string[]"}
                            :o {:type "object"} :a {:type "any"}}})

(deftest arg-mapping
  (is (= {:key "A" :other "B"} (args/parse fake ["A" "B"])) "positionals fill required args sorted by name: key, other")
  (is (= {:key "A" :other "B" :n 5 :f true :l ["a" "b"] :o {"x" 1} :a 3}
         (args/parse fake ["A" "--n" "5" "--f" "--l" "a,b" "--o" "{\"x\":1}" "B" "--a=3"])))
  (is (= {:f false} (args/parse fake ["--f" "false"])))
  (is (thrown? clojure.lang.ExceptionInfo (args/parse fake ["--nope" "1"])))
  (is (thrown? clojure.lang.ExceptionInfo (args/parse fake ["--n" "x"])))
  (is (thrown? clojure.lang.ExceptionInfo (args/parse fake ["A" "B" "C"]))))

;; help --------------------------------------------------------------------------------------------

(deftest help-names-every-op
  (let [table (json/parse-string (:out (dlb *home* "help" "--json")) true)
        txt (:out (dlb *home* "help"))]
    (is (= 22 (count table)))
    (doseq [{:keys [op]} table]
      (is (str/includes? txt (str/replace op "." " ")) (str op " is in the help")))
    (is (= txt (:out (dlb *home*))) "no args = help")
    (let [r (dlb *home* "help" "session" "start")]
      (is (zero? (:exit r)))
      (is (str/includes? (:out r) "<key>")))
    (is (str/includes? (:out (dlb *home* "help" "runs")) "runs diff <a> <b>"))
    (is (str/includes? (:out (dlb *home* "run" "--help")) "<path>"))))

;; autostart, reuse, stop ---------------------------------------------------------------------------

(deftest autostart-then-reuse
  (let [h (new-home)]
    (try
      (is (nil? (lock-pid h)) "no daemon yet")
      (let [a (json-line (:out (dlb h "hello" "--json")))
            b (json-line (:out (dlb h "hello" "--json")))
            pid (get-in a [:result :pid])]
        (is (true? (:ok a)))
        (is (= pid (get-in b [:result :pid])) "the second call found the daemon of the first")
        (is (= pid (lock-pid h)))
        (is (re-find #"^(unix|tcp)://" (str/trim (slurp (fs/file h "daemon" "endpoint")))))
        (is (= 1 (count (re-seq #"listening on" (slurp (fs/file h "daemon" "daemon.out"))))) "one daemon was started")
        (let [r (dlb h "hello" "--home" h)] (is (zero? (:exit r)))))
      (finally
        (let [pid (lock-pid h)]
          (stop-daemon! h)
          (is (or (nil? pid) (not (alive? pid))) "stopped by SIGTERM")
          (is (not (fs/exists? (fs/file h "daemon" "endpoint"))) "the endpoint file is removed at stop"))
        (fs/delete-tree h)))))

(deftest unreachable-daemon-is-exit-2
  (let [h (new-home)]
    (try
      (let [r (p/shell {:out :string :err :string :continue true
                        :extra-env {"DELIBERATOR_HOME" h "DELIBERATOR_JAR" wait-scn "DLB_START_TIMEOUT_MS" "1500"}}
                       dlb-bin "hello")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "daemon/unreachable")))
      (let [r (p/shell {:out :string :err :string :continue true
                        :extra-env {"DELIBERATOR_HOME" h "DELIBERATOR_JAR" "/nonexistent.jar"}}
                       dlb-bin "hello" "--json")]
        (is (= 2 (:exit r)))
        (is (false? (:ok (json-line (:out r))))))
      (finally (stop-daemon! h) (fs/delete-tree h)))))

;; the daemon in use ----------------------------------------------------------------------------------

(deftest session-run-runs
  (testing "session start"
    (let [r (dlb *home* "session" "start" "DEMO")]
      (is (zero? (:exit r)) (:err r))
      (is (str/includes? (:out r) "path: "))))
  (testing "run streams step lines and prints the summary and paths"
    (let [r (dlb *home* "run" wait-scn)]
      (is (zero? (:exit r)) (:err r))
      (is (re-find #"▶ 001 wait first" (:out r)))
      (is (re-find #"✓ 001 wait first  \d+ ms" (:out r)))
      (is (re-find #"✓ 002 wait second" (:out r)))
      (is (re-find #"OK  2 steps, 0 failed" (:out r)))
      (is (re-find #"(?m)^result: .*/result\.txt$" (:out r)))
      (is (re-find #"(?m)^run: " (:out r)))))
  (testing "--json prints only the terminal line"
    (let [r (dlb *home* "run" wait-scn "--json")
          m (json-line (:out r))]
      (is (zero? (:exit r)))
      (is (true? (:ok m)))
      (is (= "ok" (get-in m [:result :status])))
      (is (get-in m [:result :paths :result]))))
  (testing "a failed check is exit 1"
    (let [r (dlb *home* "run" fail-scn)]
      (is (= 1 (:exit r)))
      (is (re-find #"FAILED  1 steps, 1 failed" (:out r)))
      (is (= 1 (:exit (dlb *home* "run" fail-scn "--json"))))))
  (testing "runs list / show / diff"
    (let [l (json-line (:out (dlb *home* "runs" "list" "--json")))
          ids (map :run-id (get-in l [:result :runs]))]
      (is (>= (count ids) 4))
      (is (re-find #"runs: \d+" (:out (dlb *home* "runs" "list"))))
      (let [ok-ids (map :run-id (filter #(= "ok" (:status %)) (get-in l [:result :runs])))
            bad (:run-id (first (filter #(= "failed" (:status %)) (get-in l [:result :runs]))))
            show (json-line (:out (dlb *home* "runs" "show" (first ids) "--json")))]
        (is (get-in show [:result :paths :result]))
        (is (str/includes? (:out (dlb *home* "runs" "show" (first ids))) "result: "))
        (let [same (dlb *home* "runs" "diff" (first ok-ids) (second ok-ids))]
          (is (zero? (:exit same)) "two runs of one scenario are equal after normalisation")
          (is (str/includes? (:out same) "runs are equal")))
        (is (true? (get-in (json-line (:out (dlb *home* "runs" "diff" (first ok-ids) (second ok-ids) "--json"))) [:result :equal])))
        (let [diff (dlb *home* "runs" "diff" (first ok-ids) bad)]
          (is (= 1 (:exit diff)))
          (is (str/includes? (:out diff) "@@"))))))
  (testing "options reach the op: --limit"
    (is (= 1 (count (get-in (json-line (:out (dlb *home* "runs" "list" "--limit" "1" "--json"))) [:result :runs]))))))

(deftest exit-code-2
  (is (= 2 (:exit (dlb *home* "nothing" "here"))))
  (is (= 2 (:exit (dlb *home* "session" "start"))) "missing required arg")
  (is (= 2 (:exit (dlb *home* "session" "start" "X" "--bogus" "1"))))
  (is (= 2 (:exit (dlb *home* "runs" "list" "--limit" "many"))))
  (is (= 2 (:exit (dlb *home* "run" "/no/such/file.scn"))))
  (let [r (dlb *home* "run" "/no/such/file.scn" "--json")
        m (json-line (:out r))]
    (is (= 2 (:exit r)))
    (is (false? (:ok m)))
    (is (= "scenario/not-found" (get-in m [:error :type])))))

(deftest plain-scenario-when-the-runtime-is-up
  (let [st (json-line (:out (dlb *home* "runtime" "status" "--json")))
        cs (get-in st [:result :containers])
        up? (and (seq cs) (some #(= "floci" (str (:role %))) cs) (every? #(= "running" (str (:state %))) cs))]
    (if-not up?
      (println "SKIPPED (loudly): plain.scn needs the local runtime (bb runtime:up); not running, so not run")
      (do (dlb *home* "session" "start" "PLAIN")
          (let [r (dlb *home* "run" (str root "/core/test/fixtures/plain.scn"))]
            (is (#{0 1} (:exit r)) (str (:out r) (:err r)))
            (is (re-find #"▶ 001" (:out r))))))))
