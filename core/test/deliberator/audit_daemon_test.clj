(ns deliberator.audit-daemon-test
  "Audit of M1, layer 3 (daemon and protocol), odd inputs. No runtime: scenarios use `wait` and file
  `logs`. A cancelled run mid-`logs` leaves a closed run, a 5 MB blob is described not shipped, a
  scenario path may point anywhere, parallel runs on one session get their own directories, a run
  diffs equal to itself, a protocol line that cannot be parsed still gets an answer, a long run is
  not an idle daemon. Tests tagged `^:known-bug` run with `clojure -M:test:known-bugs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            
            [deliberator.daemon-test :refer [logs-fixture! ok! with-client with-daemon write-file]]
            [deliberator.env]
            [deliberator.protocol]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :refer [meta-for]]))

(defn- read-run-edn [run-dir] (store/read-edn (io/file run-dir "run.edn")))

;; --- cancel mid-logs ------------------------------------------------------------------------------

(deftest a-cancelled-run-is-closed-everywhere
  ;; The state is read from a SECOND connection and from disk: the requesting connection used to be lost by
  ;; the interrupt (F1, fixed); the state is still read from disk and a second connection.
  (with-daemon [h {:idle-minutes 0}]
    (with-client [a h]
      (with-client [b h]
        (ok! a "session.start" {:key "T-1"})
        (let [path (logs-fixture! 60)
              id (tc/send! a "scenario.run" {:path path :env "files"})
              started (tc/recv a)
              _ (is (= "step.started" (:event (tc/recv a))))
              ;; the run is registered before it can emit (F3): the cancel takes at once, no retry
              cancelled (:cancelled (:result (ok! b "scenario.cancel" {:run-id (:run-id started)})))
              _ (is (true? cancelled))
              shown (loop [n 0]
                      (let [r (:result (ok! b "runs.show" {:run (:run-id started)}))]
                        (if (or (= "cancelled" (get-in r [:run :status])) (>= n 150)) r (do (Thread/sleep 100) (recur (inc n))))))
              run-dir (get-in shown [:paths :run])
              run (read-run-edn run-dir)]
          (is (= :cancelled (:status run)) "run.edn says cancelled within 15 s of the cancel")
          (is (some? (:finished run)) "a finish time is recorded")
          (testing "no step is left running or without a status"
            (is (= 2 (count (:steps run))))
            (is (every? #{:cancelled :skipped} (map :status (:steps run))) (pr-str (map :status (:steps run)))))
          (testing "result.txt is written and says so"
            (is (re-find #"RUN cancelled" (slurp (io/file run-dir "result.txt")))))
          (testing "when the requesting connection survived, its terminal line says cancelled"
            (let [{:keys [terminal]} (tc/collect a id 2000)]
              (when (map? terminal) (is (= "cancelled" (get-in terminal [:result :status])))))))))))

(deftest a-long-run-is-not-an-idle-daemon
  (with-daemon [h {:idle-minutes 0.05}]
    (with-client [a h]
      (ok! a "session.start" {:key "T-1"})
      (let [path (write-file "long.scn" "### wait long\n# @for 7s\n")
            r (:terminal (tc/request a "scenario.run" {:path path} ))]
        (is (= "ok" (get-in r [:result :status])) (pr-str (:error r)))))))

;; --- the raw store through the protocol -----------------------------------------------------------------

(deftest raw-get-of-a-5mb-blob-describes-it-and-does-not-ship-it
  (with-daemon [h {:idle-minutes 0}]
    (let [bs (byte-array (* 5 1024 1024) (byte 65))
          put (store/raw-put! "default" bs (meta-for {:content-type "application/octet-stream"}))]
      (with-client [c h]
        (let [r (:result (ok! c "raw.get" {:sha (:sha put)}))]
          (is (= (* 5 1024 1024) (:bytes r)))
          (is (nil? (:text r)) "above 64 KB the text is not inlined")
          (is (= (str (:path put)) (:path r)) "the path to read it from is given")
          (is (= :http (keyword (get-in r [:meta :source])))))
        (testing "the connection still works after, and the meta op agrees"
          (is (= (:sha put) (:sha (:result (ok! c "raw.meta" {:sha (:sha put)}))))))
        (testing "bad shas are errors, not paths"
          (doseq [bad ["../../etc/passwd" "zz" "" (str/upper-case (:sha put)) (str (:sha put) "00")]]
            (let [e (:error (:terminal (tc/request c "raw.get" {:sha bad})))]
              (is (some? e) (pr-str bad))
              (is (not= "internal" (:type e)) (pr-str bad e)))))))))

;; --- scenario paths ------------------------------------------------------------------------------------

(deftest a-scenario-path-is-taken-as-written-wherever-it-points
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (let [outside (io/file (System/getProperty "user.dir") "target" "audit-outside" "far.scn")]
        (io/make-parents outside)
        (spit outside "### wait far\n# @for 50ms\n")
        (testing "absolute, outside the session and the repo's scenario folders"
          (is (= "ok" (:status (:result (ok! c "scenario.run" {:path (str outside)}))))))
        (testing "relative with .. climbs out of the session's scenarios directory (no containment)"
          (let [sd (io/file (str (session/dir "default" "T-1")) "scenarios")
                ;; `..` is resolved by the filesystem: relative to the real directory, not a symlinked home
                rel (str (.relativize (.toRealPath (.toPath sd) (make-array java.nio.file.LinkOption 0)) (.toRealPath (.toPath outside) (make-array java.nio.file.LinkOption 0))))]
            (is (str/starts-with? rel ".."))
            (is (= "ok" (:status (:result (ok! c "scenario.run" {:path rel})))))))
        (testing "missing, empty, a directory: a typed error each"
          (doseq [p ["nope.scn" "" "." "/"]]
            (let [e (:error (:terminal (tc/request c "scenario.run" {:path p})))]
              (is (= "scenario/not-found" (:type e)) (pr-str p e)))))))))

;; --- parallel runs on one session -----------------------------------------------------------------------

(deftest parallel-runs-of-one-scenario-in-one-session-do-not-collide
  (with-daemon [h {:idle-minutes 0}]
    (with-client [setup h]
      (ok! setup "session.start" {:key "T-1"}))
    (let [path (write-file "p.scn" "### wait one\n# @for 300ms\n# @assert elapsed >= 250\n")
          n 8
          results (doall (map deref
                              (doall (for [_ (range n)]
                                       (future (with-client [c h]
                                                 (:terminal (tc/request c "scenario.run" {:path path}))))))))
          dirs (map #(get-in % [:result :paths :run]) results)]
      (is (every? #(= "ok" (get-in % [:result :status])) results) (pr-str (map :error results)))
      (is (= n (count (set dirs))) "eight different run directories")
      (is (= n (count (set (map #(get-in % [:result :run-id]) results)))))
      (is (every? #(.isFile (io/file % "result.txt")) dirs))
      (with-client [c h]
        (is (= n (count (:runs (:result (ok! c "runs.list" {:limit 50}))))))))))

;; --- diff ---------------------------------------------------------------------------------------------

(deftest runs-diff-of-a-run-against-itself-and-against-nothing
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (let [path (write-file "d.scn" "### wait one\n# @for 100ms\n# @assert elapsed >= 9999\n")
            run (get-in (ok! c "scenario.run" {:path path}) [:result :run-id])
            self (:result (ok! c "runs.diff" {:a run :b run}))]
        (is (true? (:equal self)))
        (is (str/blank? (:diff self)))
        (is (= (:a self) (:b self)))
        (let [e (:error (:terminal (tc/request c "runs.diff" {:a run :b "no-such-run"})))]
          (is (= "runs/not-found" (:type e))))
        (testing "an absolute path outside the session is accepted as a run reference only when it is a run"
          (let [e (:error (:terminal (tc/request c "runs.diff" {:a run :b "/etc"})))]
            (is (= "runs/not-found" (:type e)))))))))

;; --- the protocol line -----------------------------------------------------------------------------------

(deftest odd-request-lines-get-a-typed-answer-and-the-connection-lives
  (with-daemon [h {:idle-minutes 0}]
    (doseq [[what line expected]
            [["args is a string" "{\"id\":\"1\",\"op\":\"hello\",\"args\":\"x\"}" "bad-request"]
             ["op is null" "{\"id\":\"1\",\"op\":null}" "bad-request"]
             ["a JSON array" "[1,2]" "bad-request"]
             ["a lone surrogate in the op" "{\"id\":\"1\",\"op\":\"he\\ud800llo\"}" "unknown-op"]
             ["unknown args are refused" "{\"id\":\"1\",\"op\":\"hello\",\"args\":{\"zzz\":1}}" nil]]]
      (with-client [c h]
        (tc/send-line! c line)
        (let [m (tc/recv c 3000)]
          (is (map? m) (str what ": " m))
          (when expected (is (= expected (get-in m [:error :type])) what)))
        (tc/send! c "hello" {})
        (is (true? (:ok (tc/recv c 3000))) (str what ": the connection still serves"))))))

(deftest a-pathologically-nested-line-gets-a-terminal-error
  ;; 300 000 `[` is under the 4 MB cap; the JSON reader overflows the stack, the handler dies and the
  ;; connection is closed with no terminal line. The daemon survives (checked), the client sees EOF.
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (tc/send-line! c (apply str (repeat 300000 "[")))
      (let [m (tc/recv c 5000)]
        (is (and (map? m) (false? (:ok m))) (str "got " (pr-str m)))))
    (with-client [c h]
      (is (true? (:ok (:terminal (tc/request c "hello" {})))) "other connections are unaffected"))))

(deftest stopping-the-daemon-with-a-run-going-finalises-the-run
  ;; stop! cancels the runs and then `(deref run 3000 nil)`: Run implements IDeref only, so the 3-arity
  ;; deref throws ClassCastException, the rest of stop! (server, endpoint, lock) never runs.
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (let [path (write-file "long.scn" "### wait long\n# @for 60s\n")
            _ (tc/send! c "scenario.run" {:path path})
            started (tc/recv c 5000)
            run-dir (io/file (str (store/home)) "workspaces" "default" "sessions" "T-1" "runs" (:run-id started))
            outcome (try ((:stop! h)) :stopped (catch Throwable e (class e)))]
        (is (= :stopped outcome) (str "stop! threw " outcome))
        (is (= :cancelled (:status (read-run-edn run-dir))))
        (is (.isFile (io/file run-dir "result.txt")))))))

(deftest a-local-environment-with-a-profile-is-a-typed-error-through-the-protocol
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (store/write-edn! (store/path "default" "environments" "local.edn")
                        (assoc deliberator.env/default-local
                               :aws {:endpoint "http://localhost:1" :region "eu-central-1" :account "000000000000"
                                     :profile "some-sso" :access-key "x" :secret-key "y"}))
      (let [path (write-file "p.scn" "### wait one\n# @for 10ms\n")]
        (doseq [[op args] [["env.describe" {:name "local"}] ["env.check" {:name "local"}]
                           ["scenario.run" {:path path :env "local"}]]]
          (let [e (:error (:terminal (tc/request c op args)))]
            (is (= "env/invalid" (:type e)) (str op ": " (pr-str e)))
            (is (some #(str/includes? (str %) "profile") (:problems e)) (str op ": " (pr-str e)))))))))

(deftest an-overlong-name-is-a-typed-invalid-name-not-an-internal-error
  ;; "A b", ".hidden", "-x" and "../x" are refused as store/invalid-name; 300 characters pass the check
  ;; and die in the filesystem (ENAMETOOLONG) as type "internal" with the full path in the message.
  (with-daemon [h {:idle-minutes 0}]
    (with-client [c h]
      (let [e (:error (:terminal (tc/request c "session.start" {:key (apply str (repeat 300 "k"))})))]
        (is (= "store/invalid-name" (:type e)) (pr-str (select-keys e [:type])))))))

(deftest an-interrupted-emitting-thread-must-not-close-the-clients-connection
  ;; `scenario.cancel` / a timeout interrupts the engine's threads (`shutdownNow`); a step thread whose
  ;; interrupt flag is still set when it emits an event writes to the SocketChannel, which NIO answers by
  ;; CLOSING the channel (ClosedByInterruptException). The requesting client then sees EOF instead of
  ;; its remaining events and the terminal line. Seen as a ~50 % failure of the cancel test in a fresh JVM.
  (let [ssc (doto (java.nio.channels.ServerSocketChannel/open)
              (.bind (java.net.InetSocketAddress. "127.0.0.1" 0)))
        port (.getPort ^java.net.InetSocketAddress (.getLocalAddress ssc))
        srv (deliberator.protocol/listen!
             ssc {:dispatch (fn [_ ctx]
                              (.interrupt (Thread/currentThread))
                              ((:emit! ctx) {:event "ping"})
                              {:done true})
                  :error-fn (fn [e] {:type "internal" :message (ex-message e)})})]
    (try
      (let [c (tc/connect (str "tcp://127.0.0.1:" port))
            {:keys [events terminal]} (tc/request c "anything" {})]
        (is (= ["ping"] (map :event events)) (pr-str events))
        (is (= {:done true} (:result terminal)) (pr-str terminal))
        (tc/close! c))
      (finally ((:stop! srv))))))
