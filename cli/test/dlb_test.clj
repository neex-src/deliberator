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
            [dlb.args :as args]
            [dlb.main]
            [dlb.render :as render]))

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
                          
                          :extra-env {"DELIBERATOR_LOCAL_ENDPOINT" (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:19") "DELIBERATOR_HOME" home "DLB_DAEMON_ARGS" "--idle-minutes 0.2"}}
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

(deftest service-results-are-not-run-summaries
  (let [exit-code #'dlb.main/exit-code]
    (is (= 0 (exit-code {:ok true :result {:name "a" :status "running" :port 8081}})) "a started service is a success")
    (is (= 0 (exit-code {:ok true :result {:name "a" :status "stopped"}})))
    (is (= 0 (exit-code {:ok true :result {:status "ok" :steps 9}})))
    (is (= 1 (exit-code {:ok true :result {:status "failed" :steps 9}})) "a failed run still exits 1")))

(deftest to-approve-names-steps-only-for-scenario-runs
  (let [err {:type "confirm-required" :summary "S" :token "T" :step 1}
        approve (fn [op argv] (->> (with-out-str (render/print-error op err argv))
                                   str/split-lines (filter #(str/starts-with? % "to approve:")) first))]
    (is (= "to approve: dlb service start a --secrets-from local2 --confirm T"
           (approve "service.start" ["service" "start" "a" "--secrets-from" "local2"])))
    (is (= "to approve: dlb run x.scn --confirm T --steps 1" (approve "scenario.run" ["run" "x.scn"])))))

(deftest text-output-shows-nested-results
  (let [out (fn [result] (with-out-str (render/print-result "x" result)))
        discover (out {:environment "local"
                       :resources {:sqs [{:name "q1" :url "http://q/1"} {:name "q2" :url "http://q/2"}]
                                   :s3 [{:name "b1"}]}})
        chain (out {:extra [{:name "a" :source {:file "x.edn" :line 3}} {:name "b" :source {:file "y.edn"}}]
                    :hint {:try ["dlb config show" "dlb env list"] :why {:missing "k"}}})]
    (testing "aws discover: a map of vectors of maps"
      (is (= ["environment: local"
              "resources:"
              "  s3: 1"
              "    name=b1"
              "  sqs: 2"
              "    name=q1 url=http://q/1"
              "    name=q2 url=http://q/2"]
             (str/split-lines discover))))
    (testing "config chain: rows with nested fields and a nested hint"
      (is (= ["extra: 2"
              "  name=a source={\"file\":\"x.edn\",\"line\":3}"
              "  name=b source={\"file\":\"y.edn\"}"
              "hint:"
              "  try: dlb config show, dlb env list"
              "  why:"
              "    missing: k"]
             (str/split-lines chain))))))

;; help --------------------------------------------------------------------------------------------

(deftest help-names-every-op
  (let [table (json/parse-string (:out (dlb *home* "help" "--json")) true)
        txt (:out (dlb *home* "help"))]
    (is (= (count (:result (json-line (:out (dlb *home* "op" "describe" "--json"))))) (count table))
        "help --json is the daemon's whole op table (the count itself is pinned once, in daemon-test/expected-ops)")
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
    ;; the positional head and one flag each, not the whole flag list: a new flag is not a change of order (R29)
    (let [h (:out (dlb *home* "help" "runtime" "logs"))]
      (is (str/includes? h "dlb runtime logs <role> [--"))
      (is (str/includes? h "[--follow]")))
    (is (str/includes? (:out (dlb *home* "help" "scenario" "run")) "dlb scenario run <path> [--"))))

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
                          
                          :extra-env {"DELIBERATOR_LOCAL_ENDPOINT" (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:19") "DELIBERATOR_HOME" home "DELIBERATOR_JAR" jar "DLB_DAEMON_ARGS" "--idle-minutes 0.2"
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

(deftest plain-scenario-when-the-runtime-is-up
  ;; The throwaway test home has no runtime and no Postgres target, so this can only run against a
  ;; real estate: ESTATE_WS=<ws> and DELIBERATOR_HOME=<its home> (bb estate:up --ws <ws>). The scenario is the plain kinds on
  ;; the estate's own targets (plain.scn names the targets of steps_test's private environment: log/pg, service/floci).
  (let [ws (System/getenv "ESTATE_WS")
        home (System/getenv "DELIBERATOR_HOME")]
    (if (or (str/blank? ws) (str/blank? home))
      (clojure.test/do-report {:type :skip :message "plain-scenario-when-the-runtime-is-up: set ESTATE_WS=<ws> and DELIBERATOR_HOME=<its home> after bb estate:up"})
      (try
        (dlb home "session" "start" "PLAIN" "--ws" ws)
        (let [scn (str (fs/create-temp-file {:prefix "estate-plain" :suffix ".scn"}))
              _ (spit scn (str "# the plain kinds in one run, on the targets of the estate's local environment\n"
                               "# @env local\n# @var rowId = row-{{uuid}}\n\n"
                               "### logs log/service-a service-a-log\n# @for 1s\n\n"
                               "### sql db/main make-table\ncreate table if not exists plain_t (id text primary key, note text, n int)\n\n"
                               "### http health\n# @assert status == 200\n# @assert header.content-type contains json\n# @assert bytes > 0\n"
                               "GET {{service/a}}/actuator/health\nAccept: application/json\n\n"
                               "### sql db/main insert\n# @assert updated == 1\ninsert into plain_t values ('{{rowId}}', 'first note', 7) /* {{correlation}} */\n\n"
                               "### sql db/main select\n# @assert rows == 1\n# @assert col.note == first note\n# @assert col.n == 7\n"
                               "select id, note, n from plain_t where id = '{{rowId}}'\n\n"
                               "### wait pause\n# @for 1s\n"))
              r (dlb home "run" scn "--ws" ws)]
          (is (zero? (:exit r)) (str (:out r) (:err r)))
          (is (re-find #"▶ 001" (:out r)))
          (is (some #(str/starts-with? % "OK ") (str/split-lines (:out r))) "the four plain kinds pass against the estate"))
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
    (let [h (:out (dlb *home* "help" "logs" "fetch"))]
      (is (str/includes? h "dlb logs fetch <target> [--"))
      (is (str/includes? h "[--since string]"))
      (is (str/includes? h "[--follow]")))
    (is (str/includes? (:out (dlb *home* "help" "marker" "add")) "dlb marker add <file> <line> [--"))
    (is (str/includes? (:out (dlb *home* "help" "repo")) "dlb repo attach <path>"))
    (is (str/includes? (:out (dlb *home* "help" "scenario")) "dlb scenario kinds"))))

;; --- M6a: Terraform to local --------------------------------------------------------------------------

(deftest terraform-graph-and-locate-from-the-shell
  (let [g (dlb *home* "terraform" "graph" "estate/terraform/aws,estate/terraform/iam" "--json")
        m (json-line (:out g))
        order (get-in m [:result :order])]
    (is (zero? (:exit g)) (:err g))
    (testing "relative roots are sent absolute; the IAM root comes first although it was listed second"
      (is (= [(str (fs/normalize root) "/estate/terraform/iam") (str (fs/normalize root) "/estate/terraform/aws")] order)))
    (is (= [["iam" "aws" "data"]] (map (juxt :from :to :via) (get-in m [:result :edges])))))
  (let [r (dlb *home* "terraform" "graph" "estate/terraform/aws,estate/terraform/iam")]
    (is (zero? (:exit r)))
    (is (str/includes? (:out r) "edges: 1") (:out r))
    (is (re-find #"order: .*estate/terraform/iam, .*estate/terraform/aws" (:out r)) (:out r)))
  (testing "locate: where an address is declared"
    (let [r (dlb *home* "terraform" "locate" "estate/terraform/floci" "module.stack.aws_sqs_queue.inbox" "--json")
          res (:result (json-line (:out r)))]
      (is (zero? (:exit r)) (:err r))
      (is (str/ends-with? (:file res) "estate/terraform/modules/stack/main.tf"))
      (is (pos? (:line res))))
    (let [r (dlb *home* "terraform" "locate" "estate/terraform/floci" "aws_nothing.here")]
      (is (= 2 (:exit r)))
      (is (str/includes? (:err r) "tf/not-found") (:err r)))))

(deftest workspace-open-takes-the-account
  (let [r (dlb *home* "workspace" "open" "acct10" "--account" "000000000000" "--json")]
    (is (zero? (:exit r)) (:err r))
    (is (= "000000000000" (get-in (json-line (:out r)) [:result :account]))))
  (let [r (dlb *home* "workspace" "open" "acct10" "--account" "nope")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) "bad-args") (:err r))))

(deftest terraform-apply-all-help-names-the-roots
  (let [h (:out (dlb *home* "help" "terraform" "apply-all"))]
    (is (str/includes? h "dlb terraform apply-all <roots>") h)
    (is (str/includes? h "[--action string]"))))

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

;; the default-home guard (m2/13) -------------------------------------------------------------------

(def ^:private guard-line "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME")

(defn- guarded
  "Runs argv with DELIBERATOR_NO_DEFAULT_HOME set and no home anywhere. -> {:exit :out :err}"
  [& argv]
  (select-keys (apply p/shell {:out :string :err :string :continue true
                               :env (-> (into {} (System/getenv)) (dissoc "DELIBERATOR_HOME") (assoc "DELIBERATOR_NO_DEFAULT_HOME" "1"))}
                      argv)
               [:exit :out :err]))

(deftest no-default-home-guard-trips-before-anything-is-created
  (let [r (guarded dlb-bin "runtime" "status")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) guard-line) (:err r))
    (is (not (str/includes? (:err r) "starting a daemon")) "no daemon was started"))
  (let [r (guarded "bb" "estate:status")]
    (is (pos? (:exit r)))
    (is (str/includes? (str (:out r) (:err r)) guard-line) (str (:out r) (:err r))))
  (let [jar (str root "/core/target/deliberatord.jar")]
    (when (fs/exists? jar)
      (let [r (guarded "java" "-jar" jar)]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) guard-line) (:err r))))))

(deftest no-default-home-guard-lets-an-explicit-home-through
  (let [r (p/shell {:out :string :err :string :continue true
                    :extra-env {"DELIBERATOR_NO_DEFAULT_HOME" "1" "DLB_DAEMON_ARGS" "--idle-minutes 0.2"}}
                   dlb-bin "--home" *home* "daemon" "status")]
    (is (not (str/includes? (:err r) guard-line)))))

(deftest a-started-daemon-echoes-its-home
  (let [h (new-home)]
    (try
      (let [r (dlb h "hello")]
        (is (= 0 (:exit r)) (:err r))
        (is (str/includes? (:err r) (str "home: " h)) (:err r))
        (is (str/starts-with? (slurp (str h "/daemon/daemon.out")) "home: ")))
      (finally (stop-daemon! h) (fs/delete-tree h)))))

(deftest test-homes-never-point-local-at-4566
  (let [_ (dlb *home* "workspace" "open" "anything")
        r (dlb *home* "env" "describe" "local" "--ws" "anything")]
    (is (= 0 (:exit r)) (:err r))
    (let [f (slurp (str *home* "/workspaces/anything/environments/local.edn"))]
      (is (str/includes? f (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:19")) f)
      (is (not (str/includes? f ":4566"))))))

(deftest env-create-and-env-remove
  (dlb *home* "workspace" "open" "envs")
  (let [create (fn [& more] (apply dlb *home* "env" "create" "dev" "--profile" "dlbtest-none" "--account" "123456789012"
                                   "--region" "eu-central-1" "--ws" "envs" more))]
    (testing "a bad account is refused before anything is asked of AWS"
      (let [r (dlb *home* "env" "create" "dev" "--profile" "p" "--account" "12" "--region" "eu-central-1" "--ws" "envs")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "env/invalid") (:err r))
        (is (str/includes? (:err r) "12-digit") (:err r))))
    (testing "--check false writes a read-only record without asking AWS (offline authoring)"
      (let [r (create "--check" "false" "--description" "dev account")]
        (is (= 0 (:exit r)) (:err r))
        (is (str/includes? (:out r) "env: dev") (:out r))
        (is (str/includes? (:out r) "read-only: true") (:out r))
        (is (str/includes? (:out r) "checked: false") (:out r)))
      (let [f (slurp (str *home* "/workspaces/envs/environments/dev.edn"))]
        (is (str/includes? f ":profile \"dlbtest-none\"") f)
        (is (str/includes? f ":read-only true") f)))
    (testing "the bool flag: --read-only alone is true, --read-only false opens the confirmation flow"
      (dlb *home* "env" "remove" "dev" "--ws" "envs")
      (is (= 0 (:exit (create "--check" "false" "--read-only" "false"))))
      (is (str/includes? (slurp (str *home* "/workspaces/envs/environments/dev.edn")) ":read-only false")))
    (testing "the same name again is refused, with the way out"
      (let [r (create "--check" "false")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "dlb env remove dev") (:err r))))
    (testing "without --check false a profile that cannot log in is refused, naming the profile and `aws sso login`"
      (dlb *home* "env" "remove" "dev" "--ws" "envs")
      (let [r (create)]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "check-failed") (:err r))
        (is (str/includes? (:err r) "aws sso login --profile dlbtest-none") (:err r)))
      (is (not (fs/exists? (str *home* "/workspaces/envs/environments/dev.edn")))))
    (testing "remove: gone, once; local stays"
      (create "--check" "false")
      (let [r (dlb *home* "env" "remove" "dev" "--ws" "envs")]
        (is (= 0 (:exit r)) (:err r))
        (is (str/includes? (:out r) "removed: dev") (:out r)))
      (is (= 2 (:exit (dlb *home* "env" "remove" "dev" "--ws" "envs"))))
      (let [r (dlb *home* "env" "remove" "local" "--ws" "envs")]
        (is (= 2 (:exit r)))
        (is (str/includes? (:err r) "env/protected") (:err r))))))

(deftest text-results-print-nested-rows-and-resources
  ;; B14: the shapes of `aws discover` and `config chain` (rows of maps with nested maps) print every row and every resource
  (let [out (with-out-str
              (render/print-result "aws.discover" {:env "local" :errors []
                                                   :resources {:queue [{:name "q1" :ref "http://q1"} {:name "q2" :ref "http://q2"}]
                                                               :table []}}))]
    (is (str/includes? out "resources:") out)
    (is (str/includes? out "queue: 2") out)
    (is (str/includes? out "name=q1 ref=http://q1") out)
    (is (str/includes? out "name=q2 ref=http://q2") out))
  (let [out (with-out-str
              (render/print-result "config.chain"
                                   {:service "a" :env "local"
                                    :extra [{:taskdef {:name "X" :kind "env"} :parameter {:status "found" :name "/p/x"}}
                                            {:taskdef {:name "Y" :kind "secret"}}]
                                    :entries [{:placeholder "P" :file "/f" :internal false :taskdef {:name "Z"}}]}))
        rows (filter #(str/starts-with? % "  ") (str/split-lines out))]
    (is (str/includes? out "extra: 2") out)
    (is (= 3 (count rows)) out)
    (is (every? #(not (str/blank? %)) rows) "no blank rows")
    (is (some #(str/includes? % "\"name\":\"X\"") rows) out)))

;; M4: deployed, logs insights, named queries ------------------------------------------------------------

(deftest logs-insights-arguments-come-from-the-op-schema
  (let [entry {:op "logs.insights" :order ["groups" "query" "env" "since" "until" "name" "budget-ms" "limit" "session" "ws"]
               :args {:groups {:type "string[]" :required true} :query {:type "string" :required true} :env {:type "string"}
                      :since {:type "string"} :until {:type "string"} :name {:type "string"} :budget-ms {:type "int"}
                      :limit {:type "int"} :session {:type "string"} :ws {:type "string"}}}]
    (is (= {:groups ["/aws/lambda/a" "log/service-b"] :query "fields @message | limit 5" :env "dev" :since "30m" :budget-ms 60000}
           (args/parse entry ["/aws/lambda/a,log/service-b" "fields @message | limit 5" "--env" "dev" "--since" "30m" "--budget-ms" "60000"])))))

(deftest deployed-and-insights-results-print-their-age-and-their-bytes
  (let [shown (with-out-str
                (render/print-result "deployed.show"
                                     {:env "dev" :fetched-at "2026-10-26T12:00:00Z" :age "7 min" :path "/c/deployed.edn" :text "/c/deployed.txt"
                                      :services [{:kind "ecs" :cluster "prod" :name "svc" :revision 7 :desired 2 :running 2
                                                  :containers [{:name "app" :tag "v1"}] :deployments [{:updated-at "2026-10-26T11:00:00Z"}]}
                                                 {:kind "lambda" :name "fn" :version "$LATEST" :code-sha256 "abc=" :last-modified "2026-10-15"}]
                                      :errors {:codedeploy [{:op "ListDeployments" :code "AccessDenied" :message "no"}]}}))
        insights (with-out-str
                   (render/print-result "logs.insights"
                                        {:status "Complete" :rows 5 :bytes-scanned 737 :bytes-scanned-text "737 B" :took-ms 2100
                                         :path "/s/logs/insights-q.txt" :query-id "q-1" :raw [{} {}] :groups ["/g"] :since "a" :until "b"
                                         :statistics {:bytesScanned 737 :resultCount 5}}))]
    (is (= ["dev: 2 services, fetched 2026-10-26T12:00:00Z (7 min ago; dlb deployed refresh fetches again)"
            "  ecs    prod/svc  rev 7  app:v1  2/2 running  updated 2026-10-26T11:00:00Z"
            "  lambda fn  $LATEST  abc=  updated 2026-10-15"
            "error codedeploy ListDeployments: AccessDenied no"
            "path: /c/deployed.edn"
            "text: /c/deployed.txt"]
           (str/split-lines shown)))
    (is (= "COMPLETE  5 rows, 737 B scanned (bytes-scanned: 737), 2.1 s" (first (str/split-lines insights))))
    (is (some #(= "statistics: bytesScanned=737 resultCount=5" %) (str/split-lines insights)))))

(deftest named-queries-are-one-step-scenarios-under-queries
  (dlb *home* "session" "start" "DEMO")
  (let [dir (fs/path *home* "workspaces" "default" "sessions" "DEMO" "queries")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "pause.scn")) "# @env local\n\n### wait pause\n# @for 100ms\n")
    (testing "a relative queries/<name>.scn runs from the session, and the run is listed under --scenario <name>"
      (let [r (dlb *home* "run" "queries/pause.scn" "--session" "DEMO")]
        (is (zero? (:exit r)) (:err r))
        (is (re-find #"OK  1 steps, 0 failed" (:out r))))
      (let [l (json-line (:out (dlb *home* "runs" "list" "--scenario" "pause" "--session" "DEMO" "--json")))
            runs (get-in l [:result :runs])]
        (is (= 1 (count runs)))
        (is (str/ends-with? (:scenario (first runs)) "queries/pause.scn"))
        (is (empty? (get-in (json-line (:out (dlb *home* "runs" "list" "--scenario" "nothing-like-it" "--session" "DEMO" "--json")))
                            [:result :runs])))))
    (testing "the help says so"
      (is (str/includes? (:out (dlb *home* "help" "runs" "list")) "queries/<name>.scn"))
      (is (str/includes? (:out (dlb *home* "help" "logs" "insights")) "bytes scanned"))
      (is (str/includes? (:out (dlb *home* "help" "logs" "insights")) "queries/<name>.insights")))))

(deftest deployed-show-without-a-cache-says-how-to-fetch
  (let [r (dlb *home* "deployed" "show" "--env" "local")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) "deployed/no-cache"))
    (is (str/includes? (:err r) "dlb deployed refresh --env local"))))

(deftest a-providers-operations-are-dlb-commands
  ;; M6.5: providers/sample.edn names the sample provider (babashka); its operations appear in the help and run like any other
  (let [d (fs/file *home* "providers")]
    (fs/create-dirs d)
    (spit (fs/file d "sample.edn") (pr-str {:command ["bb" (str root "/providers/sample/sample.bb")]}))
    (try
      (testing "generated help lists them"
        (is (str/includes? (:out (dlb *home* "help")) "sample")))
      (testing "positional argument from the provider's schema, result and error"
        (let [r (dlb *home* "sample" "echo" "hello" "--json")
              m (json-line (:out r))]
          (is (= 0 (:exit r)))
          (is (= "hello" (get-in m [:result :echo]))))
        (let [r (dlb *home* "sample" "fail")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "sample/sample-failure"))))
      (testing "events are printed as they stream"
        (let [r (dlb *home* "sample" "count" "--n" "2")]
          (is (= 0 (:exit r)))))
      (testing "provider.list"
        (is (str/includes? (:out (dlb *home* "provider" "list")) "sample")))
      (finally (dlb *home* "provider" "stop" "sample") (fs/delete-tree d)))))

;; --- M5: repo.clone, worktrees and CI as text -------------------------------------------------------------------------

(defn- git! [dir & args]
  (let [r (apply p/shell {:out :string :err :string :continue true :dir (str dir)}
                 "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c" "commit.gpgsign=false" "-c" "protocol.file.allow=always" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {})))
    (str/trim (:out r))))

(deftest repo-clone-and-worktrees-from-the-shell
  (let [t (fs/create-temp-dir {:prefix "m5-" :path (str (fs/path root "cli" "target"))})
        origin (str (fs/path t "origin.git")) work (str (fs/path t "work"))]
    (try
      (git! t "init" "--bare" "-b" "main" origin)
      (git! t "clone" "--quiet" origin work)
      (spit (str (fs/path work "a.txt")) "one\n")
      (git! work "add" ".") (git! work "commit" "-m" "one") (git! work "branch" "-M" "main") (git! work "push" "-u" "origin" "main")
      (is (zero? (:exit (dlb *home* "workspace" "open" "m5"))))
      (testing "help comes from the op schema"
        (is (str/includes? (:out (dlb *home* "help" "repo" "clone")) "dlb repo clone <url>"))
        (is (str/includes? (:out (dlb *home* "help" "worktree")) "dlb worktree start <repo>")))
      (testing "dlb repo clone <url> <name>"
        (let [r (dlb *home* "repo" "clone" origin "proj" "--ws" "m5" "--json")
              m (json-line (:out r))]
          (is (zero? (:exit r)) (:err r))
          (is (= "proj" (get-in m [:result :name])))
          (is (true? (get-in m [:result :bare])))
          (is (fs/exists? (fs/path *home* "workspaces" "m5" "repos" "proj.git" "HEAD")))))
      (testing "dlb worktree start <repo> <branch>: from the latest main, no questions"
        (spit (str (fs/path work "b.txt")) "two\n")
        (git! work "add" ".") (git! work "commit" "-m" "two") (git! work "push" "origin" "main")
        (let [r (dlb *home* "worktree" "start" "proj" "feat/x" "--ws" "m5" "--json")
              m (json-line (:out r))]
          (is (zero? (:exit r)) (:err r))
          (is (= "feat/x" (get-in m [:result :branch])))
          (is (= (git! work "rev-parse" "HEAD") (get-in m [:result :base-sha])) "the commit pushed after the clone is there")
          (is (fs/exists? (fs/path *home* "workspaces" "m5" "worktrees" "proj" "feat-x" "b.txt")))))
      (testing "dlb worktree list, and remove refuses changes"
        (let [out (:out (dlb *home* "worktree" "list" "--ws" "m5"))]
          (is (str/includes? out "1 row") out)
          (is (str/includes? out "feat/x")))
        (spit (str (fs/path *home* "workspaces" "m5" "worktrees" "proj" "feat-x" "dirty.txt")) "x")
        (let [r (dlb *home* "worktree" "remove" "proj" "feat-x" "--ws" "m5")]
          (is (= 2 (:exit r)))
          (is (str/includes? (:err r) "worktree/dirty") (:err r))
          (is (str/includes? (:err r) "force")))
        (let [r (dlb *home* "worktree" "remove" "proj" "feat-x" "--force" "--ws" "m5")]
          (is (zero? (:exit r)) (:err r)))
        (is (str/includes? (:out (dlb *home* "worktree" "list" "--ws" "m5")) "0 rows")))
      (finally (fs/delete-tree t)))))

(deftest ci-help-and-typed-errors
  (is (str/includes? (:out (dlb *home* "help" "ci")) "dlb ci runs <repo>"))
  (is (str/includes? (:out (dlb *home* "help" "ci" "diff")) "dlb ci diff <run>"))
  (dlb *home* "workspace" "open" "ci5")
  (dlb *home* "session" "start" "C1" "--ws" "ci5")
  (let [r (dlb *home* "ci" "log" "12345" "--ws" "ci5")]
    (is (= 2 (:exit r)))
    (is (str/includes? (:err r) "ci/bad-repo") (:err r))))

(deftest ci-against-real-github-when-there-is-a-token
  ;; read-only: babashka/fs (public) has failed and green runs of workflow ci; a job's log download needs a token
  (let [tok (:out (p/shell {:out :string :err :string :continue true} "gh" "auth" "token"))]
    (if (str/blank? tok)
      (clojure.test/do-report {:type :skip :message "ci-against-real-github-when-there-is-a-token: no gh auth token"})
      (do
        (dlb *home* "workspace" "open" "ci6")
        (dlb *home* "session" "start" "C2" "--ws" "ci6")
        (let [r (dlb *home* "ci" "runs" "babashka/fs" "ci" "--status" "failure" "--limit" "5" "--ws" "ci6")]
          (is (zero? (:exit r)) (:err r))
          (is (re-find #"path: .*/sessions/C2/ci/runs-babashka-fs-ci-" (:out r)) (:out r))
          (is (str/includes? (:out r) "rows: 5") "the rows are printed")
          (is (not (str/includes? (str (:out r) (:err r)) (str/trim tok))) "the token is never printed"))))))

(deftest fake-operations-are-in-the-generated-help
  ;; M6.6: positionals come from the schema (name, upstream), the topology mode fake is in topology set's help
  (let [h (:out (dlb *home* "help" "fake"))]
    (is (str/includes? h "fake record"))
    (is (str/includes? h "fake replay"))
    (is (str/includes? h "fake commit")))
  (is (str/includes? (:out (dlb *home* "help" "topology" "set")) "fake")))
