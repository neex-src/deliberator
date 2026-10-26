(ns deliberator.scenario.engine-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.fake-kind :as fake]
            [deliberator.scenario.engine :as engine]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as t])
  (:import (java.nio.file Files Path)))

(use-fixtures :once fake/with-kinds)
(use-fixtures :each (fn [f] (fake/reset-state!) (f)))

(def env {:name "local" :kind :local})
(def targets {"service/a" "http://svc-a:8081"})

(defn- resolve-target [k]
  (or (get targets k)
      (throw (ex-info (str "unknown target " k) {:type :env/unknown-target :target k}))))

(defn- scn-file [home text]
  (let [f (io/file (str home) "scn" "t.scn")]
    (io/make-parents f)
    (spit f text)
    (str f)))

(defmacro with-run
  "Binds a temp home, starts session T-1, writes `text` to a scenario file `path`."
  [[path text] & body]
  `(t/with-temp-home
     (session/start! "w" "T-1")
     (let [~path (scn-file (store/home) ~text)] ~@body)))

(defn- run! [path & opts]
  (apply engine/run! "w" env path :session "T-1" :resolve-target resolve-target opts))

(defn- step-edn [res n]
  (let [dir (io/file (get-in res [:paths :run]) "steps")
        d (first (filter #(str/starts-with? (.getName %) (format "%03d-" n)) (.listFiles dir)))]
    (store/read-edn (io/file d "step.edn"))))

(defn- run-edn [res] (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))
(defn- result-txt [res] (slurp (get-in res [:paths :result])))
(defn- statuses [res] (mapv (juxt :n :status) (:steps (run-edn res))))
(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))
(defn- n-runs [] (count (.listFiles (io/file (str (session/dir "w" "T-1")) "runs"))))

(deftest sequential-in-order-with-events
  (with-run [p "### fake one\n### fake two\n### fake three\n"]
    (let [sink (engine/collecting-sink)
          res (run! p :sink sink)
          ev (engine/events sink)]
      (is (= :ok (:status res)))
      (is (= {:steps 3 :failed 0} (select-keys res [:steps :failed])))
      (is (= [:run.started :step.started :step.finished :step.started :step.finished
              :step.started :step.finished :run.finished] (map :event ev)))
      (is (= [1 1 2 2 3 3] (map :step (filter :step ev))))
      (is (= :ok (:status (second (filter #(= :step.finished (:event %)) ev)))))
      (is (= "sessions/T-1/runs" (subs (:run (first ev)) 0 17)))
      (is (= (:run-id res) (:run-id (first ev))))
      (is (= [[:start 1] [:end 1] [:start 2] [:end 2] [:start 3] [:end 3]] @fake/trace))
      (is (= (:status (last ev)) :ok))
      (is (= [{:n 1 :status :ok} {:n 2 :status :ok} {:n 3 :status :ok}]
             (map #(select-keys % [:n :status]) (:steps (run-edn res))))))))

(deftest a-function-is-a-sink-and-a-broken-sink-breaks-nothing
  (with-run [p "### fake one\n"]
    (let [seen (atom [])]
      (is (= :ok (:status (run! p :sink #(swap! seen conj (:event %))))))
      (is (= [:run.started :step.started :step.finished :run.finished] @seen)))
    (is (= :ok (:status (run! p :sink (fn [_] (throw (Exception. "sink down")))))))
    (is (= :ok (:status (run! p))))))

(deftest concurrent-kinds-are-armed-before-the-first-sequential-step
  (with-run [p (str "### fake first\n# @log from-first\n# @sleep 100\n"
                    "### tail\n# @for 250\n"
                    "### fake second\n# @log from-second\n# @sleep 50\n")]
    (let [res (run! p)]
      (is (= :ok (:status res)))
      (is (= [:arm 2] (first @fake/trace)) "armed before anything ran")
      (is (< (.indexOf @fake/trace [:arm 2]) (.indexOf @fake/trace [:start 1])))
      (is (< (.indexOf @fake/trace [:start 2]) (.indexOf @fake/trace [:end 1]))
          "the tail runs beside the sequential steps")
      (is (= 2 (get-in (step-edn res 2) [:facts "lines"])) "it saw both log lines")
      (is (= "from-first\nfrom-second" (slurp (io/file (get-in res [:paths :run]) "steps" "002-tail" "display.txt"))))
      (is (= [[1 :ok] [2 :ok] [3 :ok]] (statuses res)) "all joined, in step order"))))

(deftest asserts-mark-and-continue
  (with-run [p (str "### fake a\n# @set status=500\n# @assert status == 201\n# @assert status == 500\n"
                    "### fake b\n# @assert len > 100\n"
                    "### fake c\n# @assert $.x == 1\n{\"x\": 1}\n")]
    (let [res (run! p)
          txt (result-txt res)]
      (is (= :failed (:status res)))
      (is (= {:steps 3 :failed 2} (select-keys res [:steps :failed])))
      (is (= [[1 :failed] [2 :failed] [3 :ok]] (statuses res)) "step 3 still ran")
      (is (str/includes? txt "✗ status == 201 (was 500)"))
      (is (str/includes? txt "✓ status == 500"))
      (is (str/includes? txt "✗ len > 100 (was 3)"))
      (is (str/includes? txt "✓ $.x == 1"))
      (is (str/includes? txt "\nFAILED 2/4\nRUN failed steps 3 failed 2\n"))
      (is (str/ends-with? txt "RUN failed steps 3 failed 2\n")))))

(deftest errors-do-not-halt-either
  (with-run [p (str "### fake a\n# @fail connection refused\n# @assert len > 0\n"
                    "### fake b\n# @assert len > 0\n")]
    (let [res (run! p)
          txt (result-txt res)]
      (is (= [[1 :error] [2 :ok]] (statuses res)))
      (is (= :failed (:status res)))
      (is (= {:message "connection refused" :type :fake/boom :hint "the fake kind was told to fail"}
             (:error (step-edn res 1))))
      (is (str/includes? txt "### fake a · error"))
      (is (str/includes? txt "ERROR: connection refused\nhint: the fake kind was told to fail"))
      (testing "bytes the failing kind had are persisted and linked (persist first)"
        (let [d (io/file (get-in res [:paths :run]) "steps" "001-fake-a")]
          (is (= "req" (slurp (io/file d "request.http"))))
          (is (= "partial" (slurp (io/file d "response.txt")))))))))

(deftest captures-feed-later-steps-and-a-missing-one-skips-them
  (with-run [p (str "### fake a\n# @capture oid = $.orderId\n# @capture st = status\n# @set status=7\n{\"orderId\": \"o-1\"}\n"
                    "### fake b\n# @assert $.seen == o-1-7\n# @capture gone = $.nothing\n{\"seen\": \"{{oid}}-{{st}}\"}\n"
                    "### fake c\n# @log needs {{gone}}\n"
                    "### fake d\n# @assert len > 0\n")]
    (let [res (run! p)
          txt (result-txt res)]
      (is (= [[1 :ok] [2 :failed] [3 :skipped] [4 :ok]] (statuses res)))
      (is (= {"oid" "o-1" "st" "7"} (:captures (step-edn res 1))))
      (is (str/includes? txt "captured: oid=o-1, st=7"))
      (is (str/includes? txt "✗ capture gone = $.nothing (no value)"))
      (is (str/includes? txt "### fake c · skipped\nskipped: variable {{gone}} is not available"))
      (is (= "o-1-7" (get-in (step-edn res 2) [:checks 0 :was]))))))

(deftest until-stops-at-the-first-pass
  (with-run [p "### fake poll\n# @until n >= 3 within 10s\n# @assert n == 3\n"]
    (let [t0 (System/currentTimeMillis)
          res (run! p)
          st (step-edn res 1)]
      (is (= :ok (:status res)))
      (is (< 900 (- (System/currentTimeMillis) t0) 2500) "two sleeps of 500 ms")
      (is (= 3 (count (:attempts st))))
      (is (= 3 (get-in st [:facts "n"])) "the last attempt's facts stand")
      (is (= 3 (:attempts (last (:checks st)))))
      (testing "every attempt's request is in the raw store, only the last is linked"
        (is (= 3 (count (distinct (map :request-sha (:attempts st))))))
        (is (= ["  #1" "  #2" "  #3"]
               (map #(String. ^bytes (store/raw-get "w" (:request-sha %))) (:attempts st)))
            "verb and target are nil")
        (is (= "  #3" (slurp (io/file (get-in res [:paths :run]) "steps" "001-fake-poll" "request.http"))))
        (is (Files/isSameFile (.toPath (io/file (get-in res [:paths :run]) "steps" "001-fake-poll" "request.http"))
                              (store/raw-path "w" (:request-sha (last (:attempts st))))))))))

(deftest until-gives-up-at-the-end-of-the-window
  (with-run [p "### fake poll\n# @until n >= 100 within 1s\n"]
    (let [res (run! p)
          st (step-edn res 1)
          txt (result-txt res)]
      (is (= :failed (:status res)))
      (is (<= 2 (count (:attempts st)) 4))
      (is (= (count (:attempts st)) (get-in st [:facts "n"])))
      (is (re-find #"✗ until n >= 100 \(was \d, attempt \d\)" txt)))))

(deftest timeout-is-an-error-for-that-step-only
  (with-run [p (str "### fake slow\n# @sleep 5000\n# @timeout 200ms\n"
                    "### fake next\n")]
    (let [t0 (System/currentTimeMillis)
          res (run! p)]
      (is (< (- (System/currentTimeMillis) t0) 3000))
      (is (= [[1 :error] [2 :ok]] (statuses res)))
      (is (re-find #"timed out after 200 ms" (get-in (step-edn res 1) [:error :message]))))))

(deftest cancel-closes-started-steps-and-still-writes-the-run
  (with-run [p (str "### fake quick\n"
                    "### tail\n# @for 5000\n"
                    "### fake hung\n# @sleep 20000\n"
                    "### fake never\n")]
    (let [sink (engine/collecting-sink)
          run (engine/start! "w" env p :session "T-1" :sink sink)
          deadline (+ (System/currentTimeMillis) 5000)]
      (while (and (< (System/currentTimeMillis) deadline)
                  (not (some #(and (= :step.started (:event %)) (= 3 (:step %))) (engine/events sink))))
        (Thread/sleep 20))
      (Thread/sleep 100)
      (engine/cancel! run)
      (let [res (deref (future @run) 8000 :hung)
            r (run-edn res)
            started (set (map :step (filter #(= :step.started (:event %)) (engine/events sink))))
            finished (set (map :step (filter #(= :step.finished (:event %)) (engine/events sink))))]
        (is (not= :hung res))
        (is (= :cancelled (:status res)))
        (is (= :cancelled (:status r)))
        (is (= [[1 :ok] [2 :cancelled] [3 :cancelled] [4 :skipped]] (statuses res)))
        (is (= started finished) "every started step is closed")
        (is (= :run.finished (:event (last (engine/events sink)))))
        (is (str/ends-with? (result-txt res) "RUN cancelled steps 4 failed 0\n"))
        (is (.exists (io/file (get-in res [:paths :run]) "steps" "004-fake-never" "step.edn")))))))

(deftest the-until-window-reaches-the-kind
  (testing "attempt-deadline is the end of the window when that comes first, never later than deadline"
    (with-run [p "### fake poll\n# @until n >= 100 within 1s\n"]
      (let [t0 (System/currentTimeMillis)
            _ (run! p)
            cs @fake/ctxs]
        (is (>= (count cs) 2))
        (doseq [c cs]
          (is (not (.isAfter (:attempt-deadline c) (:deadline c))))
          (is (<= (.toEpochMilli (:attempt-deadline c)) (+ t0 1500))
              "the attempt deadline is the window's end, not the 30 s step timeout")
          (is (< (.toEpochMilli (:attempt-deadline c)) (- (.toEpochMilli (:deadline c)) 20000)))))))
  (testing "without @until it is the deadline"
    (with-run [p "### fake one\n"]
      (run! p)
      (let [c (last @fake/ctxs)] (is (= (:deadline c) (:attempt-deadline c)))))))

(deftest a-timed-out-attempt-is-flagged
  (with-run [p "### fake slow\n# @sleep 5000\n# @timeout 200\n"]
    (let [res (run! p)
          c (first @fake/ctxs)]
      (is (= :error (:status (step-edn res 1))))
      (is (true? ((:attempt-cancelled? c)))))))

(deftest a-cancelled-step-says-cancelled-and-nothing-else
  (with-run [p "### tail\n# @for 5000\n"]
    (let [run (engine/start! "w" env p :session "T-1")]
      (Thread/sleep 300)
      (engine/cancel! run)
      (let [res @run]
        (is (= {:message "cancelled"} (:error (step-edn res 1))))
        (is (str/includes? (result-txt res) "### tail · cancelled"))
        (is (not (str/includes? (result-txt res) "InterruptedException")))
        (is (not (str/includes? (result-txt res) "ERROR")))))))

(deftest a-run-that-dies-outside-the-coordinator-is-closed
  (with-run [p "### fake one\n"]
    (with-redefs [engine/coordinate! (fn [& _] (throw (RuntimeException. "boom")))]
      (let [sink (engine/collecting-sink)
            res (engine/run! "w" env p :session "T-1" :sink sink)
            r (run-edn res)]
        (is (= :error (:status res)))
        (is (= :error (:status r)))
        (is (= "boom" (get-in r [:error :message])))
        (is (some? (:finished r)))
        (is (str/includes? (result-txt res) "RUN error"))
        (is (= :run.finished (:event (last (engine/events sink)))))))))

(deftest static-errors-are-thrown-before-anything-runs
  (with-run [p "### fake a\n"]
    (let [bad (fn [text] (thrown #(run! (scn-file (store/home) text))))]
      (is (= {:type :scenario/unknown-var :name "nope" :line 3 :known (:known (bad "### fake a\n# @log x\n# @set a={{nope}}\n"))}
             (select-keys (bad "### fake a\n# @log x\n# @set a={{nope}}\n") [:type :name :line :known])))
      (is (some #{"uuid" "correlation" "step.correlation"} (:known (bad "### fake a\n# @log x\n# @set a={{nope}}\n"))))
      (is (= 3 (:line (bad "### fake a\n# @set a=1\n# @assert nofact == 1\n"))))
      (is (re-find #"unknown fact `nofact` for fake; facts: elapsed, n, len"
                   (:message (bad "### fake a\n# @assert nofact == 1\n"))))
      (is (= 2 (:line (bad "### fake a\n# @assert status =~ 1\n"))))
      (is (= 2 (:line (bad "### fake a\n# @until status == 1\n"))))
      (is (= 2 (:line (bad "### fake a\n# @timeout soon\n"))))
      (is (= 3 (:line (bad "### fake a\n# @timeout 1s\n# @timeout 2s\n"))))
      (is (= 2 (:line (bad "### fake a\n# @capture x $.a\n"))))
      (is (= 2 (:line (bad "### tail\n# @assert $.a == 1\n"))) "tail has no JSON body")
      (is (= :scenario/parse (:type (bad "### nosuchkind\n"))))
      (is (= :scenario/parse (:type (bad "# only a header\n"))))
      (is (= :env/unknown-target (:type (bad "### fake a\n# @log {{service/zzz}}\n"))))
      (is (= 2 (:line (bad "### fake a\n# @log {{service/zzz}}\n"))))
      (is (= :scenario/unknown-var (:type (bad "### fake a\n# @capture x = status\n### tail\n# @for {{x}}\n")))
          "a concurrent step runs before captures exist")
      (is (= 0 (n-runs)) "no run directory was allocated by any of these"))))

(deftest targets-and-correlation-are-substituted
  (with-run [p (str "# @var who = me-{{correlation}}\n"
                    "### fake http://x/{{who}} a\n# @set msg={{service/a}}\n# @display {{correlation}}|{{step.correlation}}|{{env.name}}\n"
                    "### fake b\n# @display {{step.correlation}}\n")]
    (let [res (run! p)
          run-id (:run-id res)]
      (is (= :ok (:status res)))
      (is (= (str "http://x/me-" run-id) (:target (step-edn res 1))))
      (is (= "http://svc-a:8081" (get-in (step-edn res 1) [:facts "msg"])))
      (is (= (str run-id "|" run-id "-001|local")
             (slurp (io/file (get-in res [:paths :run]) "steps" "001-fake-a" "display.txt"))))
      (is (= (str run-id "-002") (slurp (io/file (get-in res [:paths :run]) "steps" "002-fake-b" "display.txt"))))
      (is (= (str run-id "-002") (:correlation (step-edn res 2)))))))

(deftest bodies-reach-the-kind-byte-exact
  (with-run [p "### fake a\n# @body ./pay.json\n### fake b\n{\"k\":\n\n  \"é{{x}}\"}\n\n\n"]
    (let [pay (byte-array [0x7b 0xff 0x00 0x0a 0x0a])]
      (with-open [o (io/output-stream (io/file (str (store/home)) "scn" "pay.json"))] (.write o pay))
      (let [res (run! p :vars {"x" "!"})
            d (io/file (get-in res [:paths :run]) "steps")
            resp #(Files/readAllBytes (.toPath (io/file d %1 %2)))]
        (is (java.util.Arrays/equals pay ^bytes (resp "001-fake-a" "response.json")))
        (is (= "{\"k\":\n\n  \"é!\"}\n" (String. ^bytes (resp "002-fake-b" "response.json") "UTF-8")))))))

(deftest run-directory-contents
  (with-run [p "### fake a\n# @set status=201\n{\"a\": 1}\n"]
    (let [res (run! p)
          dir (io/file (get-in res [:paths :run]))
          r (run-edn res)
          sd (io/file dir "steps" "001-fake-a")]
      (is (= (store/sha256 (Files/readAllBytes (.toPath (io/file p)))) (get-in r [:scenario :sha256])))
      (is (= p (get-in r [:scenario :path])))
      (is (= "local" (:env r)))
      (is (= "T-1" (:session r)))
      (is (= :ok (:status r)))
      (is (inst? (java.util.Date/from (:started r))))
      (is (contains? #{:virtual :platform} (:thread-model r)))
      (is (= engine/thread-model (:thread-model r)))
      (is (contains? (:vars r) "uuid"))
      (is (= [] (:volatile (assoc r :volatile (vec (remove #{"uuid" "now" "now.epoch" "correlation"} (:volatile r)))))))
      (is (= {:steps 1 :failed 0 :checks 0 :failed-checks 0} (:summary r)))
      (is (every? #(.exists (io/file sd %)) ["step.edn" "request.http" "response.json"]))
      (is (= "ok" (-> (step-edn res 1) :status name (subs 0 2))))
      (is (.exists (io/file dir "result.txt")))
      (is (= (get-in r [:scenario :sha256])
             (store/sha256 (Files/readAllBytes (.toPath (io/file dir "scenario.scn"))))))
      (is (Files/isSameFile (.toPath (io/file sd "response.json"))
                            (store/raw-path "w" (:response-sha (first (:attempts (step-edn res 1)))))))
      (is (= "application/json" (:content-type (store/raw-meta "w" (:response-sha (first (:attempts (step-edn res 1)))))))))))

(deftest only-some-steps
  (with-run [p "### fake a\n### fake b\n### fake c\n"]
    (let [res (run! p :steps #{2})]
      (is (= [[2 :ok]] (statuses res)))
      (is (= {:steps 1 :failed 0} (select-keys res [:steps :failed])))
      (is (= {:type :scenario/no-such-step :step 9} (select-keys (thrown #(run! p :steps #{9})) [:type :step]))))))

(deftest no-session-is-an-error
  (t/with-temp-home
    (let [p (scn-file (store/home) "### fake a\n")]
      (is (= :scenario/no-session (:type (thrown #(engine/run! "w" env p))))))))

(deftest the-current-session-is-the-default
  (with-run [p "### fake a\n"]
    (is (= :ok (:status (engine/run! "w" env p))))))

(deftest nothing-is-shared-between-runs
  (with-run [p "# @var id = {{uuid}}\n### fake a\n# @display {{id}}\n"]
    (let [a (run! p) b (run! p)
          disp #(slurp (io/file (get-in % [:paths :run]) "steps" "001-fake-a" "display.txt"))]
      (is (not= (:run-id a) (:run-id b)))
      (is (not= (disp a) (disp b))))))
