(ns deliberator.ports-estate-test
  "Per-workspace host ports against the running estate (m3 05b; `bb estate:up --ws W`, `ESTATE_WS=W`, `DELIBERATOR_HOME`; without
  them a SKIP line): a second workspace `W-b` of the same home is brought up by `bb estate:up --ws W-b` while W's estate runs. Nothing is
  pinned by hand: W-b's Floci, Postgres, services and JDWP ports come out different from W's, both estates answer, an order runs
  green through W-b's own services and Floci, and W's services are still healthy afterwards. W-b is taken down again."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.runtime :as runtime]
            [deliberator.steps.awscall] [deliberator.steps.dynamodb] [deliberator.steps.eventbridge] [deliberator.steps.http]
            [deliberator.steps.lambda] [deliberator.steps.logs] [deliberator.steps.scheduler]
            [deliberator.steps.sql] [deliberator.steps.sqs] [deliberator.steps.wait]))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(defn- bb [& args]
  (let [r (apply sh/sh "bb" (concat args [:dir (.getCanonicalPath (io/file ".."))]))]
    (assoc r :tail (str/join "\n" (take-last 12 (str/split-lines (str (:out r) (:err r))))))))

(defn- health [url]
  (try (let [c ^java.net.HttpURLConnection (.openConnection (java.net.URL. (str url "/actuator/health")))]
         (.setConnectTimeout c 3000) (.setReadTimeout c 3000)
         (.getResponseCode c))
       (catch Exception _ nil)))

(defn- host-ports [ws]
  (->> (:containers (runtime/status ws)) (mapcat (comp vals :ports)) (remove nil?) vec))

(deftest two-workspaces-run-side-by-side
  (if-let [ws (fu/estate-ws)]
    (let [ws2 (str ws "-b") sess (str "PORTS-" (System/currentTimeMillis))]
      (try
        (let [up (bb "estate:up" "--ws" ws2)]
          (is (zero? (:exit up)) (:tail up)))
        (testing "no host port is shared and nothing was pinned by hand for the second workspace"
          (let [p1 (host-ports ws) p2 (host-ports ws2)]
            (is (seq p1)) (is (seq p2))
            (is (empty? (filter (set p1) p2)) (str "ports of " ws ": " p1 ", of " ws2 ": " p2))
            (is (= (count p2) (count (set p2))) "no port twice inside the workspace")))
        (testing "each local environment points at its own Floci and its own services"
          (let [e1 (env/load ws "local") e2 (env/load ws2 "local")]
            (is (not= (get-in e1 [:aws :endpoint]) (get-in e2 [:aws :endpoint])))
            (doseq [t [:service/a :service/b]]
              (is (not= (get-in e1 [:targets t]) (get-in e2 [:targets t])) (str t))
              (is (= 200 (health (get-in e1 [:targets t]))) (str ws " " t))
              (is (= 200 (health (get-in e2 [:targets t]))) (str ws2 " " t)))))
        (testing "an order runs green through the second workspace's own services"
          (call "session.start" {:ws ws2 :key sess})
          (let [r (call "scenario.run" {:ws ws2 :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))})]
            (is (= :ok (:status r)) (str (select-keys r [:status :steps :failed :failed-checks])))))
        (testing "the first workspace is untouched by all of it"
          (let [e1 (env/load ws "local")]
            (doseq [t [:service/a :service/b]] (is (= 200 (health (get-in e1 [:targets t]))) (str ws " " t)))))
        (finally
          (let [down (bb "estate:down" "--ws" ws2)]
            (is (zero? (:exit down)) (:tail down))))))
    (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
