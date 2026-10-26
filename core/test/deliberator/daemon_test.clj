(ns deliberator.daemon-test
  "Layer 3: the daemon in this JVM on a temporary home (unix socket and --tcp), driven by the tiny
  test client. Scenarios use the `wait` and `logs` (file) kinds, so no runtime is needed except for the
  tests that say so (they skip when the test Floci (floci-util/url) is down)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.aws :as aws]
            [deliberator.daemon :as daemon]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu])
  (:import (java.util UUID)))

(use-fixtures :once (fn [f] (try (f) (finally (fu/drop-queues! "dlbtest-") (env/close-clients!)))))

(defmacro with-daemon
  "Starts a daemon on a fresh short home (opts map), binds `h` to its handle and `home`; stops it."
  [[h opts] & body]
  `(tu/with-short-home
     (let [~h (daemon/start! ~opts)]
       (try ~@body (finally ((:stop! ~h)))))))

(defmacro with-client [[c h] & body]
  `(let [~c (tc/connect (:endpoint ~h))]
     (try ~@body (finally (tc/close! ~c)))))

(defn ok! [c op args & opts]
  (let [{:keys [terminal events]} (apply tc/request c op args opts)]
    (is (true? (:ok terminal)) (pr-str op args terminal))
    {:result (:result terminal) :events events}))

(defn write-file [name text]
  (let [f (io/file (str (store/home)) "scn" name)]
    (io/make-parents f)
    (spit f text)
    (str f)))

(def two-waits "### wait one\n# @for 100ms\n\n### wait two\n# @for 100ms\n")

(def expected-ops
  "The one deliberate change-detector of the op table (review 2026-10-25, R29): adding or renaming an op fails
  `op-describe-lists-every-operation-with-its-schema` until the name is added here, on purpose, in the same commit as its
  `dlb` help and docs. Every other test derives its expectations from `ops/table`."
  ["hello" "op.describe" "daemon.stop" "provider.list" "provider.stop" "runtime.sync-env" "service.adopt" "service.list" "service.show" "service.status" "service.build" "service.start" "service.stop" "service.restart" "service.reload" "topology.show" "topology.set" "workspace.show" "workspace.open" "explore.write" "session.note" "session.start" "session.end" "session.list" "session.current"
   "audit.show" "config.chain" "config.refresh" "deployed.refresh" "deployed.show" "logs.insights"
   "env.list" "env.describe" "env.check" "env.save" "env.create" "env.remove" "runtime.ensure" "runtime.status" "runtime.down" "runtime.logs" "runtime.set"
   "aws.describe" "aws.discover" "terraform.drift" "terraform.local" "terraform.graph" "terraform.apply-all" "terraform.locate"
   "aws.describe" "aws.discover" "terraform.drift" "terraform.local"
   "scenario.run" "scenario.cancel" "scenario.kinds" "scenario.parse" "repo.attach" "repo.detach" "repo.list" "repo.clone" "worktree.start" "worktree.list" "worktree.remove" "ci.runs" "ci.log" "ci.tail" "ci.diff" "logs.fetch" "logs.match" "marker.add" "marker.remove" "marker.list" "runs.list" "runs.show" "runs.render" "runs.diff" "raw.meta" "raw.get"])

;; --- lifecycle ----------------------------------------------------------------------------------

(deftest unix-socket-hello-and-version
  (with-daemon [h {:idle-minutes 0}]
    (let [dir (io/file (str (store/home)) "daemon")
          ep (str/trim (slurp (io/file dir "endpoint")))]
      (testing "endpoint file and files of the daemon"
        (is (= (:endpoint h) ep))
        (is (str/starts-with? ep "unix://"))
        (is (.exists (io/file dir "deliberatord.sock")))
        (is (= (str (:pid h)) (str/trim (slurp (io/file dir "lock")))))
        (is (str/includes? (slurp (io/file dir "daemon.log")) "started")))
      (with-client [c h]
        (let [{:keys [result]} (ok! c "hello" {:client "test" :version (daemon/version)})]
          (is (= (daemon/version) (:version result)))
          (is (= (:pid h) (:pid result)))
          (is (= (str (store/home)) (:home result)))
          (is (= ep (:endpoint result)))
          (is (true? (:compatible result))))
        (is (false? (:compatible (:result (ok! c "hello" {:client "test" :version "9.9.9"})))))))
    (testing "stop removes socket and endpoint, a log line explains why"
      ((:stop! h))
      (is (not (.exists (io/file (str (store/home)) "daemon" "endpoint"))))
      (is (not (.exists (io/file (str (store/home)) "daemon" "deliberatord.sock"))))
      (is (= :stopped @(:stopped h))))))

(deftest tcp-fallback
  (with-daemon [h {:idle-minutes 0 :tcp true}]
    (is (re-matches #"tcp://127\.0\.0\.1:\d+" (:endpoint h)))
    (is (= (:endpoint h) (str/trim (slurp (io/file (str (store/home)) "daemon" "endpoint")))))
    (is (not (.exists (io/file (str (store/home)) "daemon" "deliberatord.sock"))))
    (with-client [c h]
      (is (= (daemon/version) (:version (:result (ok! c "hello" {}))))))))

(deftest second-daemon-on-the-same-home-is-refused
  (with-daemon [h {:idle-minutes 0}]
    (let [e (try (daemon/start! {}) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :daemon/already-running (:type (ex-data e))))
      (is (= (:pid h) (:pid (ex-data e))))
      (is (str/includes? (ex-message e) "already running")))
    (testing "the first one still serves, and after it stopped a new one can start"
      (with-client [c h] (ok! c "hello" {}))
      ((:stop! h))
      (let [h2 (daemon/start! {:idle-minutes 0})]
        (try (with-client [c h2] (ok! c "hello" {}))
             (finally ((:stop! h2))))))))

(deftest idle-shutdown
  (with-daemon [h {:idle-minutes 0.1}]
    (with-client [c h] (ok! c "hello" {}))
    (is (= :idle (deref (:stopped h) 30000 :still-running)))
    (is (not (.exists (io/file (str (store/home)) "daemon" "endpoint"))))
    (is (not (.exists (io/file (str (store/home)) "daemon" "deliberatord.sock"))))
    (is (str/includes? (slurp (io/file (str (store/home)) "daemon" "daemon.log")) "stopping: idle"))))

;; --- protocol -----------------------------------------------------------------------------------

(deftest malformed-lines-get-a-terminal-error-and-the-connection-lives
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (doseq [[line what] [["this is not json" "not valid JSON"]
                           ["[1,2]" "must be a JSON object"]
                           ["{\"op\":\"hello\"}" "\"id\" is required"]
                           ["{\"id\":\"q\",\"args\":{}}" "\"op\" is required"]
                           ["{\"id\":\"q\",\"op\":\"hello\",\"args\":[1]}" "\"args\" must be an object"]]]
        (tc/send-line! c line)
        (let [m (tc/recv c)]
          (is (false? (:ok m)) line)
          (is (= "bad-request" (get-in m [:error :type])) line)
          (is (str/includes? (get-in m [:error :message]) what) line)
          (is (contains? m :id))))
      (tc/send-line! c "garbage")
      (is (nil? (:id (tc/recv c))) "undecodable lines are answered with id null")
      (tc/send-line! c "{\"id\":\"q\",\"args\":{}}")
      (is (= "q" (:id (tc/recv c))) "the id is echoed when it could be read")
      (testing "a blank line is ignored and the connection is still usable"
        (tc/send-line! c "")
        (is (= (daemon/version) (:version (:result (ok! c "hello" {})))))))))

(deftest a-line_over-the-limit-is-refused-not-fatal
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (tc/send-line! c (apply str (repeat (+ 1000 (* 4 1024 1024)) "x")))
      (let [m (tc/recv c)]
        (is (= "bad-request" (get-in m [:error :type])))
        (is (nil? (:id m))))
      (is (= (daemon/version) (:version (:result (ok! c "hello" {}))))))))

(deftest op-describe-lists-every-operation-with-its-schema
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (let [{:keys [result]} (ok! c "op.describe" {})
            by (into {} (map (juxt :op identity)) result)]
        (is (= (sort (keys ops/table)) (sort (keys by))) "op.describe lists every op of the table")
        (is (= (sort expected-ops) (sort (keys ops/table)))
            "the op table changed: add the op to expected-ops (the one deliberate change-detector) with its dlb help and docs")
        (doseq [{:keys [op class doc args events]} result]
          (is (#{"read" "mutate"} class) op)
          (is (not (str/blank? doc)) op)
          (is (vector? events) op)
          (doseq [[arg {:keys [type required doc]}] args]
            (is (#{"string" "int" "number" "bool" "string[]" "int[]" "object" "any"} type) (str op " " arg))
            (is (boolean? required))
            (is (not (str/blank? doc)) (str op " " arg))))
        (is (= {:type "string" :required true} (select-keys (get-in by ["scenario.run" :args :path]) [:type :required])))
        (is (= "int[]" (get-in by ["scenario.run" :args :steps :type])))
        (is (= "default" (get-in by ["session.list" :args :ws :default])))
        (is (= ["run.started" "step.started" "line" "confirm.required" "step.finished" "run.finished"] (get-in by ["scenario.run" :events])))
        (testing "order = the declared order of the arguments, positionals first by that order"
          (is (every? (fn [{:keys [order args]}] (= (set order) (set (map name (keys args))))) result))
          (is (= ["key" "title" "ws"] (get-in by ["session.start" :order])))
          (is (= ["a" "b" "session" "ws"] (get-in by ["runs.diff" :order])))
          (is (= ["role" "since" "follow" "tail" "ws"] (get-in by ["runtime.logs" :order])))
          (is (= ["path" "env" "session" "steps" "confirm" "continue" "ws"] (get-in by ["scenario.run" :order])))
          (is (= ["client" "version" "ws"] (get-in by ["hello" :order])) "ws is accepted (and ignored) by ops without one"))))))

(deftest daemon-stop-finalises-runs-and-exits
  (with-daemon [h {:idle-minutes 0}]
    (let [dir (io/file (str (store/home)) "daemon")]
      (with-client [c h]
        (ok! c "session.start" {:key "T-STOP"})
        (let [path (write-file "long.scn" "### wait long\n# @for 60s\n")
              runner (tc/connect (:endpoint h))
              fut (future (tc/request runner "scenario.run" {:path path}))]
          (try
            (loop [n 0] (when (and (zero? (:runs (:result (ok! c "hello" {})))) (< n 100)) (Thread/sleep 50) (recur (inc n))))
            (is (= 1 (:runs (:result (ok! c "hello" {})))) "hello counts the runs in flight")
            (let [{:keys [result]} (ok! c "daemon.stop" {})]
              (is (true? (:stopping result)))
              (is (= (:pid h) (:pid result)))
              (is (= 1 (count (:runs result)))))
            (is (= :requested (deref (:stopped h) 10000 :timeout)) "the same stop! ran")
            (is (not (.exists (io/file dir "endpoint"))))
            (is (not (.exists (io/file dir "deliberatord.sock"))))
            (let [t (:terminal (deref fut 5000 nil))]
              (is (true? (:ok t)) "the open run stream ends with a terminal, not a closed connection")
              (is (= "cancelled" (get-in t [:result :status]))))
            (let [run-dir (first (filter #(.isDirectory %) (.listFiles (io/file (str (store/home)) "workspaces" "default" "sessions" "T-STOP" "runs"))))
                  run (store/read-edn (.toPath (io/file run-dir "run.edn")))]
              (is (not= :running (:status run)) "the run was finalised, not left running")
              (is (.exists (io/file run-dir "result.txt"))))
            (finally (tc/close! runner) (future-cancel fut))))))))

(deftest runtime-sync-env-writes-config-and-points-the-log-targets
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (let [{:keys [result]} (ok! c "runtime.sync-env" {:ws "w-sync"})]
        (is (.exists (io/file (:path result))))
        (let [e (env/load "w-sync" "local")]
          (is (= "http://localhost:4566" (get-in e [:aws :endpoint])))
          (is (= {:container "deliberator-w-sync-service-a"} (get-in e [:targets :log/service-a]))))))))

(deftest arguments-are-validated-against-the-schema
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (let [err (fn [op args] (:error (:terminal (tc/request c op args))))
            e1 (err "scenario.run" {})
            e2 (err "session.start" {:key 5 :nope true})
            e3 (err "frobnicate" {})]
        (is (= "bad-args" (:type e1)))
        (is (= ["missing required argument path"] (:problems e1)))
        (is (contains? (:schema e1) :path))
        (is (= "bad-args" (:type e2)))
        (is (= ["unknown argument nope" "argument key must be string"] (:problems e2)))
        (is (= "unknown-op" (:type e3)))
        (is (some #{"hello"} (:known e3)))))))

;; --- state ops ----------------------------------------------------------------------------------

(deftest workspace-session-and-environment-ops
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (testing "reading an unknown workspace is workspace/not-found and writes nothing"
        (is (= "workspace/not-found" (get-in (tc/request c "workspace.show" {:ws "w1"}) [:terminal :error :type])))
        (is (= "workspace/not-found" (get-in (tc/request c "env.list" {:ws "w1"}) [:terminal :error :type])))
        (is (not (.exists (io/file (str (store/home)) "workspaces" "w1")))))
      (is (true? (:created (:result (ok! c "workspace.open" {:ws "w1"})))))
      (let [ws (:result (ok! c "workspace.show" {:ws "w1"}))]
        (is (= "w1" (:ws ws)))
        (is (str/ends-with? (:path ws) "workspaces/w1")))
      (is (nil? (:session (:result (ok! c "session.current" {:ws "w1"})))))
      (let [s (:result (ok! c "session.start" {:ws "w1" :key "T-1" :title "first"}))]
        (is (= "T-1" (get-in s [:session :key])))
        (is (.isDirectory (io/file (:path s) "runs"))))
      (ok! c "session.start" {:ws "w1" :key "T-2"})
      (let [l (:result (ok! c "session.list" {:ws "w1"}))]
        (is (= ["T-1" "T-2"] (map :key (:sessions l))))
        (is (= "T-2" (:current l))))
      (is (some? (get-in (ok! c "session.end" {:ws "w1"}) [:result :session :ended])))
      (is (nil? (:current (:result (ok! c "session.list" {:ws "w1"})))))
      (testing "environments: the default local one appears on first use"
        (let [envs (:environments (:result (ok! c "env.list" {:ws "w1"})))]
          (is (= ["local"] (map :name envs)))
          (is (= "local" (:kind (first envs)))))
        (let [d (:result (ok! c "env.describe" {:ws "w1" :name "local"}))]
          (is (= "local" (get-in d [:env :name])))
          (is (contains? (get-in d [:env :targets]) (keyword "queue/inbox")))
          (is (.exists (io/file (:path d)))))
        (is (= "env/not-found" (get-in (tc/request c "env.describe" {:ws "w1" :name "nope"}) [:terminal :error :type]))))
      (testing "errors from the namespaces carry their type"
        (is (= "store/invalid-name" (get-in (tc/request c "workspace.show" {:ws "../x"}) [:terminal :error :type])))))))

(deftest-floci env-check-compares-the-account
  (with-daemon [h {:idle-minutes 0}]
    (env/save! "w1" (fu/local-env))
    (with-client [c h]
      (let [r (:result (ok! c "env.check" {:ws "w1" :name "local"}))]
        (is (true? (:ok r)))
        (is (= (:account r) (:expected r)))
        (is (some #{"queue/inbox"} (:targets r)))))))

(deftest runtime-ops-answer-without-a-runtime
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "workspace.open" {:ws "w1"})
      (let [r (:result (ok! c "runtime.status" {:ws "w1"}))]
        (is (contains? r :podman))
        (is (vector? (:containers r))))
      (let [e (:error (:terminal (tc/request c "runtime.logs" {:ws "w1" :role "nosuchrole"})))]
        (is (string? (:message e)))
        (is (string? (:type e))))
      (is (= "logs/bad-arg" (get-in (tc/request c "runtime.logs" {:ws "w1" :role "x" :since "yesterday"})
                                [:terminal :error :type]))))))

;; --- scenarios ----------------------------------------------------------------------------------

(defn event-names [events] (mapv :event events))

(deftest scenario-run-streams-ordered-events-and-one-terminal-line
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (let [path (write-file "two.scn" two-waits)
            id (tc/send! c "scenario.run" {:path path :env "local"})
            {:keys [events terminal]} (tc/collect c id)
            res (:result terminal)]
        (is (= ["run.started" "step.started" "step.finished" "step.started" "step.finished" "run.finished"]
               (event-names events)))
        (is (= [1 1 2 2] (keep :step events)))
        (is (every? #(= id (:id %)) events))
        (is (= "ok" (:status res)))
        (is (= 2 (:steps res)))
        (is (= (:run-id res) (:run-id (first events))))
        (is (.isFile (io/file (get-in res [:paths :result]))))
        (testing "nothing follows the terminal line"
          (is (= :deliberator.test-client/timeout (tc/recv c 300))))
        (testing "runs.list / show / render / diff"
          (let [second-run (:result (ok! c "scenario.run" {:path path}))
                l (:result (ok! c "runs.list" {}))]
            (is (= [(:run-id second-run) (:run-id res)] (map :run-id (:runs l))))
            (is (= "ok" (:status (first (:runs l)))))
            (let [s (:result (ok! c "runs.show" {:run (:run-id res)}))]
              (is (= 2 (count (get-in s [:run :steps]))))
              (is (= "wait" (get-in s [:run :steps 0 :kind])))
              (is (.isFile (io/file (get-in s [:paths :run-edn])))))
            (is (= (:run-id res) (get-in (ok! c "runs.show" {:run (str "sessions/T-1/runs/" (:run-id res))}) [:result :run :run-id])))
            (let [r (:result (ok! c "runs.render" {:run (:run-id res)}))]
              (is (str/starts-with? (:trailer r) "RUN ok steps 2")))
            (let [d (:result (ok! c "runs.diff" {:a (:run-id res) :b (:run-id second-run)}))]
              (is (true? (:equal d)) (:diff d))
              (is (= "" (:diff d))))
            (is (= "runs/not-found" (get-in (tc/request c "runs.show" {:run "19990101-000000-x"}) [:terminal :error :type])))))
        (testing "raw.meta and raw.get on the scenario blob"
          (let [sha (get-in (:result (ok! c "runs.show" {:run (:run-id res)})) [:run :scenario :sha256])
                m (:result (ok! c "raw.meta" {:sha sha}))
                g (:result (ok! c "raw.get" {:sha sha}))]
            (is (= "file" (get-in m [:meta :source])))
            (is (= two-waits (:text g)))
            (is (= (count two-waits) (:bytes g)))
            (is (= "store/not-found" (get-in (tc/request c "raw.get" {:sha (apply str (repeat 64 "a"))}) [:terminal :error :type])))
            (is (= "store/invalid-sha" (get-in (tc/request c "raw.meta" {:sha "xyz"}) [:terminal :error :type])))))))))

(deftest scenario-errors-are-terminal-errors
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (is (= "session/none" (get-in (tc/request c "scenario.run" {:path "x.scn"}) [:terminal :error :type])))
      (ok! c "session.start" {:key "T-1"})
      (is (= "scenario/not-found" (get-in (tc/request c "scenario.run" {:path "/no/such.scn"}) [:terminal :error :type])))
      (let [bad (write-file "bad.scn" "### nosuchkind\n")
            e (:error (:terminal (tc/request c "scenario.run" {:path bad})))]
        (is (= "scenario/parse" (:type e)))
        (is (number? (:line e))))
      (testing "a relative path is looked up in the session's scenarios directory"
        (let [dir (io/file (str (session/dir "default" "T-1")) "scenarios")]
          (spit (io/file dir "rel.scn") two-waits)
          (is (= "ok" (:status (:result (ok! c "scenario.run" {:path "rel.scn"}))))))))))

(defn logs-fixture!
  "A file to tail, an environment `files` whose `log/f` is that file, and a logs scenario."
  [secs]
  (let [log (io/file (str (store/home)) "app.log")]
    (spit log "")
    (env/save! "default" (assoc env/default-local :name "files"
                                :targets (assoc (:targets env/default-local) :log/f {:file (str log)})))
    (write-file "tail.scn" (str "### logs log/f tail\n# @filter needle\n# @for " secs "s\n\n### wait after\n# @for 100ms\n"))))

(deftest scenario-cancel-ends-a-running-logs-step
  (with-daemon [h {:idle-minutes 0}]
    (with-client [a h]
      (with-client [b h]
        (ok! a "session.start" {:key "T-1"})
        (let [path (logs-fixture! 60)
              id (tc/send! a "scenario.run" {:path path :env "files"})
              t0 (System/currentTimeMillis)
              started (tc/recv a)
              _ (is (= "run.started" (:event started)))
              run-id (:run-id started)
              _ (is (= "step.started" (:event (tc/recv a))))
              line (loop [n 0]
                     (spit (io/file (str (store/home)) "app.log") "needle one\n" :append true)
                     (let [m (tc/recv a 300)]
                       (cond (and (map? m) (= "line" (:event m))) m
                             (and (< n 40) (or (= m :deliberator.test-client/timeout) (map? m))) (recur (inc n))
                             :else m)))
              _ (is (= "line" (:event line)) "log lines of the step stream as line events")
              _ (is (str/includes? (:text line) "needle one"))
              c (:result (ok! b "scenario.cancel" {:run-id run-id}))
              {:keys [events terminal]} (tc/collect a id)]
          (is (true? (:cancelled c)))
          (is (< (- (System/currentTimeMillis) t0) 15000) "ended long before the 60 s window")
          (is (= "cancelled" (get-in terminal [:result :status])))
          (is (= "run.finished" (:event (last events))))
          (is (= "cancelled" (:status (first (filter #(= "step.finished" (:event %)) events)))))
          (testing "cancelling a run that is no longer running says so"
            (is (false? (:cancelled (:result (ok! b "scenario.cancel" {:run-id run-id}))))))
          (testing "run.edn records the cancellation"
            (is (= "cancelled" (:status (first (:runs (:result (ok! b "runs.list" {}))))))))
          )))))

(deftest cancel-on-close-cancels-the-run
  (with-daemon [h {:idle-minutes 0}]
    (with-client [b h]
      (ok! b "session.start" {:key "T-1"})
      (let [path (logs-fixture! 60)
            a (tc/connect (:endpoint h))
            _ (tc/send! a "scenario.run" {:path path :env "files"} :cancel-on-close true)
            started (tc/recv a)
            _ (tc/recv a)]
        (is (= "run.started" (:event started)))
        (tc/close! a)
        (let [deadline (+ (System/currentTimeMillis) 15000)]
          (loop []
            (let [st (:status (first (:runs (:result (ok! b "runs.list" {})))))]
              (cond (= "cancelled" st) (is true)
                    (> (System/currentTimeMillis) deadline) (is false (str "still " st))
                    :else (do (Thread/sleep 100) (recur))))))))))

(deftest a-run-without-cancel-on-close-survives-its-client
  (with-daemon [h {:idle-minutes 0}]
    (with-client [b h]
      (ok! b "session.start" {:key "T-1"})
      (let [path (write-file "two.scn" "### wait one\n# @for 1s\n")
            a (tc/connect (:endpoint h))]
        (tc/send! a "scenario.run" {:path path})
        (is (= "run.started" (:event (tc/recv a))))
        (tc/close! a)
        (let [deadline (+ (System/currentTimeMillis) 15000)]
          (loop []
            (let [st (:status (first (:runs (:result (ok! b "runs.list" {})))))]
              (cond (= "ok" st) (is true)
                    (> (System/currentTimeMillis) deadline) (is false (str "still " st))
                    :else (do (Thread/sleep 100) (recur))))))))))

(deftest two-clients-at-once
  (with-daemon [h {:idle-minutes 0}]
    (with-client [a h]
      (with-client [b h]
        (ok! a "session.start" {:key "T-1"})
        (let [path (write-file "slow.scn" "### wait one\n# @for 1500ms\n")
              ia (tc/send! a "scenario.run" {:path path})
              ib (tc/send! b "scenario.run" {:path path})
              t0 (System/currentTimeMillis)]
          (testing "a quick request on a third connection is answered while both runs go"
            (with-client [c h]
              (is (= (daemon/version) (:version (:result (ok! c "hello" {})))))
              (is (< (- (System/currentTimeMillis) t0) 1200))))
          (let [ra (tc/collect a ia) rb (tc/collect b ib)]
            (is (= "ok" (get-in ra [:terminal :result :status])))
            (is (= "ok" (get-in rb [:terminal :result :status])))
            (is (not= (get-in ra [:terminal :result :run-id]) (get-in rb [:terminal :result :run-id])))
            (testing "events go to the requester only"
              (is (every? #(= ia (:id %)) (:events ra)))
              (is (every? #(= ib (:id %)) (:events rb))))))))
    (testing "requests on one connection run concurrently and are answered by id"
      (with-client [a h]
        (let [path (write-file "slow2.scn" "### wait one\n# @for 1s\n")
              i1 (tc/send! a "scenario.run" {:path path})
              i2 (tc/send! a "hello" {})]
          (loop [seen []]
            (let [m (tc/recv a)]
              (cond (and (= i2 (:id m)) (contains? m :ok)) (is (not-any? #(= i1 %) (map :id (filter :ok seen))) "hello before the run's terminal line")
                    (map? m) (recur (conj seen m))
                    :else (is false "no answer")))))))))

;; --- remote-shaped environments (Floci) ---------------------------------------------------------

(defn remote-env
  "A remote-shaped record on Floci; `:read-only false` because these runs mutate through the token flow (a read-only
  record refuses a mutation flat, `aws_test`)."
  [& {:as over}]
  (env/validate (merge {:name "dlbdev" :kind :remote :read-only false
                        :aws {:profile "dlbtest" :region "eu-central-1" :account "000000000000"
                              :endpoint fu/url}
                        :targets {:queue/q (str "dlbtest-d-" (subs (str (UUID/randomUUID)) 0 8))}}
                       over)))

(defmacro with-profile [& body]
  `(let [f# (io/file (str (store/home)) "credentials")]
     (spit f# "[dlbtest]\naws_access_key_id = test\naws_secret_access_key = test\n")
     (binding [env/*credentials-file* f#] ~@body)))

(deftest-floci confirm-required-is-the-terminal-error-of-a-run-that-hit-it
  (tu/with-short-home
    (with-profile
      (let [e (remote-env)
            _ (env/save! "default" e)
            q (:queue/q (:targets e))]
        (is (not (aws/error? (aws/invoke! {:ws "default"} (fu/local-env) :sqs :CreateQueue {:QueueName q}))))
        (let [h (daemon/start! {:idle-minutes 0})]
          (try
            (with-client [c h]
              (ok! c "session.start" {:key "T-1"})
              (let [path (write-file "send.scn" "### sqs send queue/q\nhello\n")
                    {:keys [events terminal]} (tc/request c "scenario.run" {:path path :env "dlbdev"})
                    err (:error terminal)]
                (is (false? (:ok terminal)))
                (is (= "confirm-required" (:type err)))
                (is (string? (:token err)))
                (is (str/includes? (:summary err) "SendMessage"))
                (is (= 1 (:step err)))
                (is (= [(:token err)] (map :token (:confirmations err))))
                (is (= "failed" (get-in err [:result :status])) "the run completed; its summary rides on the error")
                (is (.isFile (io/file (get-in err [:result :paths :result]))))
                (is (= "run.finished" (:event (last events))))
                (is (= "error" (:status (first (filter #(= "step.finished" (:event %)) events)))))
                (testing "the token is an event of its own, never in the step's error"
                  (let [ev (first (filter #(= "confirm.required" (:event %)) events))]
                    (is (= (:token err) (:token ev)))
                    (is (= 1 (:step ev)))
                    (is (not (str/includes? (pr-str (filter #(= "step.finished" (:event %)) events)) (:token err))))))
                (testing "the step.finished event says why"
                  (is (str/includes? (get-in (first (filter #(= "step.finished" (:event %)) events)) [:error :message])
                                     "needs confirmation")))
                (testing "confirm is threaded to aws/invoke!: a token that was not issued for the request is refused there"
                  (let [{:keys [events terminal]} (tc/request c "scenario.run" {:path path :env "dlbdev" :confirm "bogus"})]
                    (is (true? (:ok terminal)) "the run completes; its step failed")
                    (is (= "failed" (get-in terminal [:result :status])))
                    (is (str/includes? (get-in (first (filter #(= "step.finished" (:event %)) events)) [:error :message])
                                       "confirmation token"))))
                (testing "a read-only scenario on the remote environment runs without confirmation"
                  (let [path (write-file "recv.scn" "### sqs receive queue/q\n# @keep\n# @wait 1s\n")
                        r (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev"}))]
                    (is (true? (:ok r)) (pr-str r))
                    (is (= "ok" (get-in r [:result :status])))))))
            (finally ((:stop! h)))))))))

(defn- err [c op args] (:error (:terminal (tc/request c op args))))

(deftest env-create-env-save-and-env-remove-offline
  ;; the credentials cannot be fetched (no file, no CLI): a remote record is written only with `check false`
  (tu/with-short-home
    (binding [env/*credentials-file* (io/file (str (store/home)) "no-credentials")
              env/*aws-cli* "/no/such/aws"]
      (let [h (daemon/start! {:idle-minutes 0})]
        (try
          (with-client [c h]
            (ok! c "workspace.open" {:ws "w1"})
            (testing "the record is validated before anything is asked of AWS"
              (is (= "env/invalid" (:type (err c "env.create" {:ws "w1" :name "dev" :profile "p" :account "12" :region "eu-central-1"}))))
              (is (= "env/invalid" (:type (err c "env.create" {:ws "w1" :name "local" :profile "p" :account "123456789012" :region "r"})))
                  "the name local is the local environment's")
              (is (= "store/invalid-name" (:type (err c "env.create" {:ws "w1" :name "../x" :profile "p" :account "123456789012" :region "r"}))))
              (is (= ["local"] (map :name (:environments (:result (ok! c "env.list" {:ws "w1"})))))))
            (testing "a profile that cannot log in refuses the record, with the CLI's words and `aws sso login`"
              (let [e (err c "env.create" {:ws "w1" :name "dev" :profile "dlbtest-sso" :account "123456789012" :region "eu-central-1"})]
                (is (= "check-failed" (:type e)))
                (is (re-find #"cannot be started" (:message e)) (pr-str e))
                (is (re-find #"aws sso login --profile dlbtest-sso" (:hint e)) (pr-str e)))
              (is (= ["local"] (map :name (:environments (:result (ok! c "env.list" {:ws "w1"}))))) "nothing written"))
            (testing "check false writes it anyway (authoring offline): read-only by default"
              (let [r (:result (ok! c "env.create" {:ws "w1" :name "dev" :profile "dlbtest-sso" :account "123456789012"
                                                    :region "eu-central-1" :check false :description "dev account"}))]
                (is (= {:env "dev" :kind "remote" :account "123456789012" :checked false :read-only true}
                       (select-keys r [:env :kind :account :checked :read-only])))
                (is (.isFile (io/file (:path r)))))
              (let [rec (:env (:result (ok! c "env.describe" {:ws "w1" :name "dev"})))]
                (is (= {:profile "dlbtest-sso" :region "eu-central-1" :account "123456789012"} (:aws rec)))
                (is (= "dev account" (:description rec)))
                (is (true? (:read-only rec)))))
            (testing "an existing name is not overwritten by create; save replaces the whole record"
              (is (= "env/exists" (:type (err c "env.create" {:ws "w1" :name "dev" :profile "p" :account "123456789012" :region "r" :check false}))))
              (ok! c "env.save" {:ws "w1" :name "dev" :check false
                                 :record {:kind "remote" :read-only false :aws {:profile "other" :region "eu-west-1" :account "123456789012"}
                                          :targets {}}})
              (is (= "other" (get-in (:env (:result (ok! c "env.describe" {:ws "w1" :name "dev"}))) [:aws :profile]))))
            (testing "remove deletes the record and only the record; local stays"
              (let [r (:result (ok! c "env.remove" {:ws "w1" :name "dev"}))]
                (is (= {:removed "dev" :kind "remote"} (select-keys r [:removed :kind])))
                (is (not (.exists (io/file (:path r))))))
              (is (= "env/not-found" (:type (err c "env.remove" {:ws "w1" :name "dev"}))))
              (is (= "env/protected" (:type (err c "env.remove" {:ws "w1" :name "local"}))))
              (is (= ["local"] (map :name (:environments (:result (ok! c "env.list" {:ws "w1"}))))))))
          (finally ((:stop! h))))))))

(deftest-floci env-create-asks-the-profile-who-it-is-first
  ;; a remote-shaped record on Floci (decision 09's trick): sts answers 000000000000
  (tu/with-short-home
    (with-profile
      (let [h (daemon/start! {:idle-minutes 0})]
        (try
          (with-client [c h]
            (ok! c "workspace.open" {:ws "w1"})
            (testing "the wrong account is refused and nothing is written"
              (let [e (err c "env.create" {:ws "w1" :name "dev" :profile "dlbtest" :region "eu-central-1" :endpoint fu/url
                                           :account "111111111111"})]
                (is (= "account-mismatch" (:type e)))
                (is (= "111111111111" (:expected e)))
                (is (= "000000000000" (:found e))))
              (is (= ["local"] (map :name (:environments (:result (ok! c "env.list" {:ws "w1"})))))))
            (testing "the right one is created, checked, read-only; env.check agrees"
              (let [r (:result (ok! c "env.create" {:ws "w1" :name "dev" :profile "dlbtest" :region "eu-central-1" :endpoint fu/url
                                                    :account "000000000000"}))]
                (is (= {:env "dev" :kind "remote" :account "000000000000" :checked true :read-only true}
                       (select-keys r [:env :kind :account :checked :read-only]))))
              (is (true? (:ok (:result (ok! c "env.check" {:ws "w1" :name "dev"}))))))
            (testing "a record saved over it with another account is checked again"
              (let [e (err c "env.save" {:ws "w1" :name "dev"
                                         :record {:kind "remote" :aws {:profile "dlbtest" :region "eu-central-1" :endpoint fu/url
                                                                       :account "222222222222"}
                                                  :targets {}}})]
                (is (= "account-mismatch" (:type e))))
              (is (= "000000000000" (get-in (:env (:result (ok! c "env.describe" {:ws "w1" :name "dev"}))) [:aws :account]))))
            (testing "remove, and the next call on it fails as an unknown environment"
              (ok! c "env.remove" {:ws "w1" :name "dev"})
              (is (= "env/not-found" (:type (err c "env.check" {:ws "w1" :name "dev"}))))))
          (finally ((:stop! h))))))))

(deftest-floci a-run-on-a-remote-environment-checks-the-account-first
  (tu/with-short-home
    (with-profile
      (env/save! "default" (remote-env :aws {:profile "dlbtest" :region "eu-central-1" :account "111111111111"
                                             :endpoint fu/url}))
      (let [h (daemon/start! {:idle-minutes 0})]
        (try
          (with-client [c h]
            (ok! c "session.start" {:key "T-1"})
            (let [path (write-file "w.scn" "### wait one\n# @for 100ms\n")
                  e (:error (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev"})))]
              (is (= "account-mismatch" (:type e)))
              (is (= "111111111111" (:expected e)))
              (is (= "000000000000" (:found e)))
              (is (empty? (:runs (:result (ok! c "runs.list" {})))) "nothing ran")))
          (finally ((:stop! h))))))))
