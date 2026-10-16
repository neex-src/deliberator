(ns deliberator.audit-estate-test
  "Audit of M1, layer 5 (acceptance) as assertions, against the running estate: runs
  `estate/scenarios/order.scn` itself (needs `ESTATE_WS=<ws>`, `DELIBERATOR_HOME` and `bb estate:up`;
  without ESTATE_WS the test reports a skip, never a pass). What `bb acceptance` does not assert:
  one step directory per step whose request/response files are hard links into raw/, a second run
  that differs from the first only in normalised fields, and service B stopped: a readable failure,
  a FAILED n/m trailer, the later steps still run, no exception; after B is back the scenario is
  green again."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.floci-util]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.render :as render]
            [deliberator.session :as session]
            [deliberator.steps.dynamodb]
            [deliberator.steps.http]
            [deliberator.steps.logs]
            [deliberator.steps.sql]
            [deliberator.steps.sqs]
            [deliberator.store :as store])
  (:import (java.nio.file Files)))

(defn- run-order! [ws e]
  (let [res (engine/run! ws e (str (io/file "../estate/scenarios/order.scn")) :session "AUDIT-5"
                         :resolve-target #(env/target e (keyword %)))]
    (assoc res :text (slurp (get-in res [:paths :result])))))

(defn- head [s] (subs s 0 (min 1500 (count s))))

(defn- service-b-healthy? []
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "localhost" 8082) 500)
         true)
       (catch Exception _ false)))

(deftest the-order-scenario-twice-then-with-service-b-down
  (if-let [ws (System/getenv "ESTATE_WS")]
    (let [e (env/load ws "local")
          cfg (runtime/read-config ws)
          c (podman/client (get-in cfg [:podman :endpoint]) :ws ws)
          b-name (get-in cfg [:containers :service/b :name] "deliberator-service-b")]
      (session/start! ws "AUDIT-5")
      (let [r1 (run-order! ws e)
            r2 (run-order! ws e)]
        (testing "a green order, all checks"
          (is (= :ok (:status r1)) (head (:text r1)))
          (is (= 9 (:steps r1)))
          (is (= 21 (:checks r1)))
          (is (= 0 (:failed-checks r1))))
        (testing "one step directory per step, request/response files are hard links into raw/"
          (let [steps (.listFiles (io/file (get-in r1 [:paths :run]) "steps"))]
            (is (= 9 (count steps)))
            (doseq [d steps
                    f (.listFiles ^java.io.File d)
                    :when (re-matches #"(request|response)\..+" (.getName f))
                    :let [sha (store/sha256 (Files/readAllBytes (.toPath f)))]]
              (is (Files/isSameFile (.toPath f) (store/raw-path ws sha)) (str "not a link into raw/: " f)))))
        (testing "a second run differs from the first only in the normalised fields"
          (is (= :ok (:status r2)) (head (:text r2)))
          (is (str/blank? (render/diff (io/file (get-in r1 [:paths :run])) (io/file (get-in r2 [:paths :run]))))
              (render/diff (io/file (get-in r1 [:paths :run])) (io/file (get-in r2 [:paths :run])))))
        (testing "service B stopped: readable failures, a trailer, later steps ran, nothing thrown"
          (try
            (podman/stop! c b-name)
            (let [r3 (run-order! ws e)
                  run (store/read-edn (io/file (get-in r3 [:paths :run]) "run.edn"))]
              (is (= :failed (:status r3)))
              (is (= 9 (count (:steps run))) "every step ran or was reported")
              (is (pos? (:failed-checks r3)))
              (is (re-find #"(?m)^FAILED \d+/21$" (:text r3)) (head (:text r3)))
              (is (re-find #"✗ \$\.status\.S == ACKED|✗ until \$\.status\.S == ACKED" (:text r3))
                  "the ack assertion is named")
              (is (not (str/includes? (:text r3) "Exception")) (head (:text r3)))
              (is (not-any? #(= :error (:status %)) (:steps run))
                  (pr-str (map (juxt :n :status) (:steps run)))))
            (finally
              (podman/start! c b-name)
              (loop [n 0] (when (and (not (service-b-healthy?)) (< n 100)) (Thread/sleep 300) (recur (inc n)))))))
        (testing "with B back the scenario is green again"
          (Thread/sleep 5000)
          (let [r4 (run-order! ws e)]
            (is (= :ok (:status r4)) (head (:text r4)))))))
    (t/do-report {:type :skip :message "the-order-scenario-twice-then-with-service-b-down: set ESTATE_WS=<ws> (after bb estate:up --ws <ws>)"})))

(deftest ^:known-bug a-logs-step-on-a-container-that-stops-keeps-its-window
  ;; PROGRESS.md "Found along the way" (11), queued as 06b: the step ends ~250 ms after the container
  ;; stops instead of at its @for window, so a restart inside the window is not seen. Verified here.
  (if-let [ws (System/getenv "ESTATE_WS")]
    (let [e (env/load ws "local")
          cfg (runtime/read-config ws)
          c (podman/client (get-in cfg [:podman :endpoint]) :ws ws)
          b-name (get-in cfg [:containers :service/b :name] "deliberator-service-b")
          f (io/file (str (store/home)) "logs-window.scn")
          _ (do (io/make-parents f) (spit f "### logs log/service-b\n# @for 10s\n# @filter nothing-will-match-this\n"))]
      (session/start! ws "AUDIT-5")
      (try
        (let [t0 (System/currentTimeMillis)
              run (engine/start! ws e (str f) :session "AUDIT-5" :resolve-target #(env/target e (keyword %)))]
          (Thread/sleep 2000)
          (podman/stop! c b-name)
          @run
          (let [ms (- (System/currentTimeMillis) t0)]
            (is (>= ms 9000) (str "a 10 s window ended after " ms " ms"))))
        (finally
          (podman/start! c b-name)
          (loop [n 0] (when (and (not (service-b-healthy?)) (< n 100)) (Thread/sleep 300) (recur (inc n)))))))
    (t/do-report {:type :skip :message "a-logs-step-on-a-container-that-stops-keeps-its-window: set ESTATE_WS=<ws>"})))
