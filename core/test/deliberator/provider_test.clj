(ns deliberator.provider-test
  "M6.5: the provider protocol against a real provider process (the sample provider in babashka, providers/sample/sample.bb)
  behind a real daemon: describe, round trip, events, typed errors, crash and restart, unknown operations, lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.daemon :as daemon]
            [deliberator.ops :as ops]
            [deliberator.provider :as provider]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu]))

(def sample-bb (.getCanonicalPath (io/file (System/getProperty "user.dir") ".." "providers" "sample" "sample.bb")))

(defn bb? [] (try (zero? (.waitFor (.start (ProcessBuilder. ["bb" "--version"])))) (catch Exception _ false)))

(defn write-provider! [name cfg]
  (let [f (io/file (str (store/home)) "providers" (str name ".edn"))]
    (io/make-parents f)
    (spit f (pr-str cfg))))

(defn sample! [] (write-provider! "sample" {:command ["bb" sample-bb] :doc "test"}))

(defmacro with-daemon [[h] & body]
  `(tu/with-short-home
     (let [~h (daemon/start! {:idle-minutes 0})]
       (try ~@body (finally ((:stop! ~h)))))))

(defn call [c op args] (:terminal (tc/request c op args)))

(defn row [c name] (some #(when (= name (:name %)) %) (:result (call c "provider.list" {}))))

(deftest round-trip-describe-events-errors
  (when (bb?)
    (with-daemon [h]
      (sample!)
      (let [c (tc/connect (:endpoint h))]
        (try
          (testing "lazy: configured, not started"
            (is (= "stopped" (:status (row c "sample"))))
            (is (nil? (:pid (row c "sample")))))
          (testing "op.describe starts it and lists <name>.<op> with the schema"
            (let [d (:result (call c "op.describe" {}))
                  by (into {} (map (juxt :op identity)) d)]
              (is (every? by ["sample.echo" "sample.count" "sample.fail" "sample.crash"]))
              (is (= {:type "string" :required true} (select-keys (get-in by ["sample.echo" :args :text]) [:type :required])))
              (is (= "read" (:class (by "sample.echo"))))
              (is (= ["tick"] (:events (by "sample.count"))))
              (is (= ["n" "delay-ms" "ws"] (:order (by "sample.count"))))
              (is (some? (by "service.list")) "the built-in table is still there"))
            (is (= "running" (:status (row c "sample")))))
          (testing "round trip"
            (let [r (call c "sample.echo" {:text "hello"})]
              (is (true? (:ok r)))
              (is (= "hello" (get-in r [:result :echo])))
              (is (= (:pid (row c "sample")) (get-in r [:result :pid])))))
          (testing "arguments are validated by the daemon against the provider's schema"
            (let [r (call c "sample.echo" {})]
              (is (false? (:ok r)))
              (is (= "bad-args" (get-in r [:error :type])))
              (is (str/includes? (get-in r [:error :message]) "missing required argument text"))))
          (testing "events stream through, in order, before the terminal line"
            (let [{:keys [events terminal]} (tc/request c "sample.count" {:n 3})]
              (is (= [1 2 3] (mapv :n events)))
              (is (every? #(= "tick" (:event %)) events))
              (is (= {:total 3} (:result terminal)))))
          (testing "a provider error keeps its message and hint, its type is prefixed with the provider"
            (let [r (call c "sample.fail" {:why "nope"})]
              (is (false? (:ok r)))
              (is (= "sample/sample-failure" (get-in r [:error :type])))
              (is (= "nope" (get-in r [:error :message])))
              (is (str/includes? (get-in r [:error :hint]) "provider error"))))
          (testing "unknown operations: of a provider, of nobody"
            (let [r (call c "sample.nope" {})]
              (is (= "unknown-op" (get-in r [:error :type])))
              (is (some #{"sample.echo"} (get-in r [:error :known]))))
            (is (= "unknown-op" (get-in (call c "nobody.echo" {}) [:error :type]))))
          (finally (tc/close! c)))))))

(defn alive? [pid] (.isPresent (java.lang.ProcessHandle/of (long pid))))

(deftest crash-is-a-clear-error-and-the-next-call-restarts
  (when (bb?)
    (with-daemon [h]
      (sample!)
      (let [c (tc/connect (:endpoint h))]
        (try
          (let [pid1 (get-in (call c "sample.echo" {:text "x"}) [:result :pid])
                r (call c "sample.crash" {})]
            (testing "the call in flight fails with provider/crashed, naming the exit code and the stderr tail"
              (is (false? (:ok r)))
              (is (= "provider/crashed" (get-in r [:error :type])))
              (is (= 3 (get-in r [:error :exit])))
              (is (some #(str/includes? % "crashing on request") (get-in r [:error :stderr])))
              (is (str/includes? (get-in r [:error :hint]) "next call"))
              (is (str/ends-with? (get-in r [:error :log]) "logs/providers/sample.log")))
            (testing "the stderr is persisted raw under the home"
              (is (str/includes? (slurp (get-in r [:error :log])) "crashing on request")))
            (testing "provider.list says crashed"
              (loop [i 0] (when (and (< i 50) (not= "crashed" (:status (row c "sample")))) (Thread/sleep 100) (recur (inc i))))
              (is (= "crashed" (:status (row c "sample")))))
            (testing "the next call starts a fresh process"
              (let [r2 (call c "sample.echo" {:text "again"})]
                (is (true? (:ok r2)))
                (is (not= pid1 (get-in r2 [:result :pid])))
                (is (= "running" (:status (row c "sample"))))
                (is (= 1 (:restarts (row c "sample")))))))
          (finally (tc/close! c)))))))

(deftest start-failures-bad-config-and-reserved-names
  (when (bb?)
    (with-daemon [h]
      (write-provider! "dies" {:command ["bb" "-e" "(binding [*out* *err*] (println \"boom at start\")) (System/exit 4)"]})
      (write-provider! "nocmd" {:command ["/no/such/command-xyz"]})
      (write-provider! "service" {:command ["bb" sample-bb]})
      (write-provider! "broken" {:nope 1})
      (let [c (tc/connect (:endpoint h))]
        (try
          (let [r (call c "dies.anything" {})]
            (is (= "provider/start-failed" (get-in r [:error :type])))
            (is (str/includes? (get-in r [:error :message]) "boom at start")))
          (is (= "provider/start-failed" (get-in (call c "nocmd.x" {}) [:error :type])))
          (is (= "provider/name-taken" (get-in (call c "service.x" {}) [:error :type]) )
              "a provider named like a built-in area never shadows it (service.list stays the built-in)")
          (is (= "provider/bad-config" (get-in (call c "broken.x" {}) [:error :type])))
          (testing "provider.list shows every file with its status, op.describe survives them"
            (let [rows (into {} (map (juxt :name identity)) (:result (call c "provider.list" {})))]
              (is (= "bad-config" (:status (rows "broken"))))
              (is (= "bad-config" (:status (rows "service"))))
              (is (= "failed" (:status (rows "dies"))))
              (is (str/includes? (:error (rows "dies")) "boom at start")))
            (is (seq (:result (call c "op.describe" {})))))
          (finally (tc/close! c)))))))

(deftest stop-and-the-daemon-stops-its-providers
  (when (bb?)
    (with-daemon [h]
      (sample!)
      (let [c (tc/connect (:endpoint h))
            pid (get-in (call c "sample.echo" {:text "x"}) [:result :pid])]
        (try
          (is (alive? pid))
          (testing "provider.stop"
            (is (= {:name "sample" :stopped true} (:result (call c "provider.stop" {:name "sample"}))))
            (is (not (alive? pid)))
            (is (= "stopped" (:status (row c "sample"))))
            (is (= "provider/not-found" (get-in (call c "provider.stop" {:name "ghost"}) [:error :type]))))
          (testing "calling it again starts it, and stopping the daemon stops it"
            (let [pid2 (get-in (call c "sample.echo" {:text "y"}) [:result :pid])]
              (is (alive? pid2))
              (tc/close! c)
              ((:stop! h))
              (loop [i 0] (when (and (< i 50) (alive? pid2)) (Thread/sleep 100) (recur (inc i))))
              (is (not (alive? pid2)) "no provider process outlives its daemon")))
          (finally (try (tc/close! c) (catch Exception _ nil))))))))

(deftest cancel-reaches-the-provider
  (when (bb?)
    (tu/with-temp-home
      (let [reg (provider/registry (store/home) nil)
            cancel-fn (atom nil)
            events (atom [])]
        (write-provider! "sample" {:command ["bb" sample-bb]})
        (try
          (let [f (future (try (provider/call! reg "sample" "count" {:n 200 :delay-ms 50}
                                               {:emit! #(swap! events conj %) :on-cancel! #(reset! cancel-fn %)} #{"service"})
                               (catch clojure.lang.ExceptionInfo e (ex-data e))))]
            (loop [i 0] (when (and (< i 100) (empty? @events)) (Thread/sleep 50) (recur (inc i))))
            (is (seq @events) "ticks arrive while the call runs")
            (@cancel-fn)
            (is (= :provider/cancelled (:type (deref f 5000 nil)))))
          (finally (provider/stop-all! reg)))))))
