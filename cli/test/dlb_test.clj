(ns dlb-test
  "Layer 4: `dlb` as a user runs it, against a real daemon started from the built jar by `dlb`
  itself on a short home under `cli/target/th/` (socket paths are limited to ~104 bytes). Every
  daemon started here is stopped by `dlb daemon stop` (SIGTERM to the pid in its lock file is the fallback);
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

(defn sigterm-daemon!
  "SIGTERM to the pid of the daemon of `home` (its shutdown hook stops it cleanly), then waits; kill -9 last."
  [home]
  (when-let [pid (lock-pid home)]
    (when (alive? pid)
      (p/shell {:continue true} "kill" (str pid))
      (loop [n 0]
        (when (and (alive? pid) (< n 100)) (Thread/sleep 100) (recur (inc n))))
      (when (alive? pid) (p/shell {:continue true} "kill" "-9" (str pid))))))

(defn stop-daemon!
  "`dlb daemon stop` for the daemon of `home`; SIGTERM to the pid in the lock when that did not do it."
  [home]
  (dlb home "daemon" "stop")
  (sigterm-daemon! home))

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

(def fake {:op "x.y" :order ["key" "other" "n" "f" "l" "o" "a"]
           :args {:key {:type "string" :required true :doc "k"}
                  :other {:type "string" :required true}
                  :n {:type "int"} :f {:type "bool"} :l {:type "string[]"}
                  :o {:type "object"} :a {:type "any"}}})

(def fake-no-order {:op "x.y" :args {:key {:type "string" :required true :doc "k"}
                            :other {:type "string" :required true}
                            :n {:type "int"} :f {:type "bool"} :l {:type "string[]"}
                            :o {:type "object"} :a {:type "any"}}})

(deftest arg-mapping
  (is (= {:key "A" :other "B"} (args/parse fake ["A" "B"])) "positionals fill required args in declared order: key, other")
  (is (= {:other "A" :key "B"} (args/parse (assoc fake :order ["other" "key" "n" "f" "l" "o" "a"]) ["A" "B"]))
      "the declared order decides, not the names")
  (is (= {:key "A" :other "B"} (args/parse fake-no-order ["A" "B"])) "no order: sorted by name")
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
    (is (= 48 (count table)))
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

(deftest positionals-follow-the-declared-order-of-the-real-ops
  (let [table (json/parse-string (:out (dlb *home* "help" "--json")) true)
        by (into {} (map (juxt :op identity)) table)]
    (is (every? :order table))
    (is (= ["a" "b" "session" "ws"] (:order (by "runs.diff"))))
    (is (str/includes? (:out (dlb *home* "help" "runs" "diff")) "dlb runs diff <a> <b>"))
    (is (str/includes? (:out (dlb *home* "help" "runtime" "logs")) "dlb runtime logs <role> [--since string] [--follow] [--tail int] [--ws string]"))
    (is (str/includes? (:out (dlb *home* "help" "scenario" "run")) "dlb scenario run <path> [--env string]"))))

(deftest daemon-status-and-stop
  (let [h (new-home)]
    (try
      (let [r (dlb h "daemon" "status")]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "not running"))
        (is (nil? (lock-pid h)) "status never starts a daemon"))
      (is (zero? (:exit (dlb h "daemon" "stop"))) "stop with no daemon is fine")
      (is (nil? (lock-pid h)) "stop never starts a daemon either")
      (let [pid (get-in (json-line (:out (dlb h "hello" "--json"))) [:result :pid])
            st (dlb h "daemon" "status")
            js (json-line (:out (dlb h "daemon" "status" "--json")))]
        (is (zero? (:exit st)))
        (is (str/includes? (:out st) (str "pid: " pid)))
        (is (true? (:running js)))
        (is (= pid (:pid js)))
        (let [r (dlb h "daemon" "stop")]
          (is (zero? (:exit r)) (:err r))
          (is (str/includes? (:out r) (str "daemon pid " pid " stopped"))))
        (is (not (alive? pid)) "the process exited")
        (is (not (fs/exists? (fs/file h "daemon" "endpoint"))))
        (is (= 1 (:exit (dlb h "daemon" "status")))))
      (finally (stop-daemon! h) (fs/delete-tree h)))))

(deftest sigterm-still-stops-a-daemon
  (let [h (new-home)]
    (try
      (let [pid (get-in (json-line (:out (dlb h "hello" "--json"))) [:result :pid])]
        (sigterm-daemon! h)
        (is (not (alive? pid)))
        (is (not (fs/exists? (fs/file h "daemon" "endpoint")))))
      (finally (stop-daemon! h) (fs/delete-tree h)))))

(defn- jar-with-version
  "A copy of the built jar under `dir` whose version.edn says `version`."
  [dir version]
  (let [src (str root "/core/target/deliberatord.jar")
        dst (str dir "/daemon-" version ".jar")]
    (with-open [zi (java.util.zip.ZipFile. src)
                zo (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. dst))]
      (doseq [e (enumeration-seq (.entries zi))]
        (.putNextEntry zo (java.util.zip.ZipEntry. (.getName e)))
        (if (= "deliberator/version.edn" (.getName e))
          (.write zo (.getBytes (str "{:version \"" version "\"}\n") "UTF-8"))
          (with-open [in (.getInputStream zi e)] (.transferTo in zo)))
        (.closeEntry zo)))
    dst))

(defn- dlb-jar [home jar & args]
  (let [r (apply p/shell {:out :string :err :string :continue true
                          :extra-env {"DELIBERATOR_HOME" home "DELIBERATOR_JAR" jar "DLB_DAEMON_ARGS" "--idle-minutes 0.2"
                                     ;; a re-zipped jar loses the AOT classes (newer sources win): a cold start takes ~13 s
                                     "DLB_START_TIMEOUT_MS" "60000"}}
                 dlb-bin args)]
    (select-keys r [:exit :out :err])))

(deftest an-older-daemon-is-replaced-a-newer-one-is-not
  (let [h (new-home)
        old (jar-with-version h "0.0.1-old")
        new (jar-with-version h "99.0.0")
        real (str root "/core/target/deliberatord.jar")]
    (try
      (let [old-pid (get-in (json-line (:out (dlb-jar h old "hello" "--json"))) [:result :pid])
            st (json-line (:out (dlb-jar h real "daemon" "status" "--json")))]
        (is (= "0.0.1-old" (:version st)))
        (is (false? (:compatible st)) "status reports it and does not restart anything")
        (is (= old-pid (:pid st)))
        (let [r (dlb-jar h real "hello" "--json")
              m (json-line (:out r))]
          (is (zero? (:exit r)) (:err r))
          (is (not= old-pid (get-in m [:result :pid])) "a new daemon answered")
          (is (not (alive? old-pid)) "the old one is gone")
          (is (not= "0.0.1-old" (get-in m [:result :version])))))
      (testing "the daemon is newer than the jar: refused, nothing restarted"
        (stop-daemon! h)
        (let [pid (get-in (json-line (:out (dlb-jar h new "hello" "--json"))) [:result :pid])
              r (dlb-jar h real "hello")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "daemon/incompatible"))
          (is (str/includes? (:err r) "newer"))
          (is (alive? pid))))
      (finally (stop-daemon! h) (fs/delete-tree h)))))

(deftest version-compare
  (let [newer? (requiring-resolve 'dlb.endpoint/version-newer?)]
    (is (newer? "0.1.1" "0.1.0"))
    (is (newer? "0.10.0" "0.9.0"))
    (is (newer? "0.1.0-m2" "0.1.0-m1"))
    (is (newer? "0.1.0-m1" "0.1.0"))
    (is (not (newer? "0.1.0" "0.1.0")))
    (is (not (newer? "0.0.9" "0.1.0")))))

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

(defmethod clojure.test/report :skip [m] (println "SKIP" (:message m)))

(deftest plain-scenario-when-the-runtime-is-up
  ;; The throwaway test home has no runtime and no Postgres target, so this can only run against a
  ;; real estate: ESTATE_WS=<ws> and DELIBERATOR_HOME=<its home> (bb estate:up --ws <ws>).
  (let [ws (System/getenv "ESTATE_WS")
        home (System/getenv "DELIBERATOR_HOME")]
    (if (or (str/blank? ws) (str/blank? home))
      (clojure.test/do-report {:type :skip :message "plain-scenario-when-the-runtime-is-up: set ESTATE_WS=<ws> and DELIBERATOR_HOME=<its home> after bb estate:up"})
      (try
        (dlb home "session" "start" "PLAIN" "--ws" ws)
        (let [r (dlb home "run" (str root "/core/test/fixtures/plain.scn") "--ws" ws)]
          (is (#{0 1} (:exit r)) (str (:out r) (:err r)))
          (is (re-find #"▶ 001" (:out r))))
        (finally (stop-daemon! home))))))
(deftest ide-ops-take-shell-relative-paths
  (testing "scenario parse, repo attach and marker add send existing relative paths absolute"
    (let [rel "cli/test/fixtures/wait.scn"
          r (dlb *home* "scenario" "parse" rel "--json")
          m (json-line (:out r))]
      (is (zero? (:exit r)) (:err r))
      (is (= 2 (count (get-in m [:result :steps]))))
      (is (= [] (get-in m [:result :errors]))))
    (let [r (dlb *home* "repo" "attach" "cli/test/fixtures" "--json")]
      (is (zero? (:exit r)) (:err r))
      (is (= (str (fs/normalize root) "/cli/test/fixtures") (get-in (json-line (:out r)) [:result :path]))))
    (is (zero? (:exit (dlb *home* "marker" "add" "cli/test/fixtures/wait.scn" "3"))))
    (let [m (json-line (:out (dlb *home* "marker" "list" "--json")))]
      (is (= [(str (fs/normalize root) "/cli/test/fixtures/wait.scn")] (map :file (:result m))))))
  (testing "help reads well for the new areas"
    (is (str/includes? (:out (dlb *home* "help" "logs" "fetch")) "dlb logs fetch <target> [--env string] [--since string] [--for string] [--filter string] [--session string] [--ws string]"))
    (is (str/includes? (:out (dlb *home* "help" "marker" "add")) "dlb marker add <file> <line> [--logger string]"))
    (is (str/includes? (:out (dlb *home* "help" "repo")) "dlb repo attach <path>"))
    (is (str/includes? (:out (dlb *home* "help" "scenario")) "dlb scenario kinds"))))

;; --- piece 10: explicit workspaces, typed log errors, ws ignored by ws-less ops ------------------------

(deftest workspace-open-is-the-only-way-in-for-reads
  (let [r (dlb *home* "workspace" "show" "--ws" "typo10")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) "workspace/not-found") (:err r))
    (is (not (fs/exists? (fs/path *home* "workspaces" "typo10"))) "the read created nothing"))
  (let [r (dlb *home* "workspace" "open" "typo10")]
    (is (= 0 (:exit r)) (:err r))
    (is (fs/exists? (fs/path *home* "workspaces" "typo10" "project"))))
  (is (= 0 (:exit (dlb *home* "workspace" "show" "--ws" "typo10")))))

(deftest logs-fetch-of-a-missing-file-is-a-typed-error
  (dlb *home* "workspace" "open" "logs10")
  (dlb *home* "session" "start" "L1" "--ws" "logs10")
  (let [r (dlb *home* "logs" "fetch" "/no/such.log" "--ws" "logs10")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) "logs/not-a-file") (:err r))))

(deftest ops-without-ws-accept-one
  (is (= 0 (:exit (dlb *home* "scenario" "kinds" "--ws" "whatever"))))
  (is (str/includes? (:out (dlb *home* "help" "scenario" "cancel")) "--ws")))

(deftest global-flags-before-the-op-equal-flags-after
  (let [after (dlb *home* "scenario" "kinds" "--ws" "whatever")
        before (dlb *home* "--ws" "whatever" "scenario" "kinds")
        mixed (dlb *home* "--ws" "whatever" "--home" *home* "scenario" "kinds")]
    (is (= 0 (:exit after)))
    (is (= (select-keys after [:exit :out]) (select-keys before [:exit :out])))
    (is (= (select-keys after [:exit :out]) (select-keys mixed [:exit :out]))))
  (is (= 2 (:exit (dlb *home* "--ws" "w" "nonsense" "op")))))
