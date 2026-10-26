(ns deliberator.topology-estate-test
  "The progressive topology story against the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`,
  `DELIBERATOR_HOME`; without them a SKIP line): service A is `off` (its container is stopped by the next
  ensure, B is re-rendered and recreated, `order.scn` fails readably at the first `service/a` step), `local`
  again (green), then `external` with a second instance of A (`a2`, the same jar and task definition on host
  port 18089, started by the test) -> green again. `runs.diff` of the two green runs differs in the TOPOLOGY
  line and nothing else. The test puts the workspace back (topology local, `a2` removed)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.steps.awscall] [deliberator.steps.dynamodb] [deliberator.steps.eventbridge] [deliberator.steps.http]
            [deliberator.steps.lambda] [deliberator.steps.logs] [deliberator.steps.scheduler]
            [deliberator.steps.sql] [deliberator.steps.sqs] [deliberator.steps.wait]
            [deliberator.store :as store]))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(defn- run-order! [ws sess]
  (let [r (call "scenario.run" {:ws ws :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))})]
    (assoc r :run (:run (call "runs.show" {:ws ws :session sess :run (:run-id r)})))))

(defn- container [ws role]
  (let [c (podman/client (get-in (runtime/read-config ws) [:podman :endpoint]) :ws ws)]
    (first (podman/list-containers c :all true :labels {"deliberator.workspace" ws "deliberator.role" role}))))

(deftest off-local-external-story
  (if-let [ws (fu/estate-ws)]
    (let [sess (str "TOPO-" (System/currentTimeMillis))
          a (description/read ws "a")
          cfg0 (runtime/read-config ws)
          a2-container (store/container-name ws "service-a2")
          first-green (atom nil)]
      (call "session.start" {:ws ws :key sess})
      (try
        (testing "A off: the next ensure stops A's container and recreates B; the first service/a step fails readably"
          (let [r (call "topology.set" {:ws ws :env "local" :name "a" :mode "off"})]
            (is (= [{:name "b" :var "SERVICE_A_URL"}] (:dependents r))))
          (let [st (first (filter #(= "b" (:name %)) (call "service.status" {:ws ws})))]
            (is (true? (:drift st)) "B is marked for recreation"))
          (call "runtime.ensure" {:ws ws})
          (is (not= "running" (:state (container ws "service-a"))) "A is really gone")
          (is (= "off" (:status (first (call "service.status" {:ws ws :name "a"})))))
          (is (= "topology/service-not-local"
                 (:type (try (call "service.start" {:ws ws :name "a"}) (catch clojure.lang.ExceptionInfo e (ops/error-map e))))))
          (let [{:keys [status run]} (run-order! ws sess)
                publish (first (filter #(= "publish" (:name %)) (:steps run)))]
            (is (= :failed status))
            (is (= :error (:status publish)))
            (is (= "service a is off in environment local" (get-in publish [:error :message])))
            (is (= :off (get-in run [:topology :services "a" :mode])))))
        (testing "A local again: green"
          (call "topology.set" {:ws ws :env "local" :name "a" :mode "local"})
          (call "runtime.ensure" {:ws ws})
          (is (= "running" (:status (first (call "service.status" {:ws ws :name "a"})))))
          (reset! first-green (run-order! ws sess))
          (is (= :ok (:status @first-green)) (pr-str (select-keys @first-green [:status :failed]))))
        (testing "A external, served by a second instance on 18089: green again"
          (description/write! ws (assoc a :name "a2"))
          (store/write-edn! (runtime/config-path ws)
                            (assoc-in cfg0 [:services "a2"] {:ports {8081 18089 5005 15099}}))
          (call "service.start" {:ws ws :name "a2"})
          (call "topology.set" {:ws ws :env "local" :name "a" :mode "external" :url "http://127.0.0.1:18089"
                                :container-url (str "http://" a2-container ":8081") :log-container a2-container})
          (call "runtime.ensure" {:ws ws})
          (is (not= "running" (:state (container ws "service-a"))) "A's own container was stopped by the topology")
          (let [last-green (run-order! ws sess)
                d (call "runs.diff" {:ws ws :session sess :a (:run-id @first-green) :b (:run-id last-green)})
                changed (filter #(and (re-find #"^[-+]" %) (not (re-find #"^(---|\+\+\+)" %))) (str/split-lines (:diff d)))]
            (is (= :ok (:status last-green)) (pr-str (select-keys last-green [:status :failed])))
            (is (= 2 (count changed)) (:diff d))
            (is (every? #(str/includes? % "TOPOLOGY") changed) (:diff d))
            (is (str/includes? (:diff d) "+TOPOLOGY a=external http://127.0.0.1:18089"))))
        (finally
          (try (call "topology.set" {:ws ws :env "local" :name "a" :mode "local"}) (catch Exception _ nil))
          (try (let [c (podman/client (get-in (runtime/read-config ws) [:podman :endpoint]) :ws ws)]
                 (when-let [x (container ws "service-a2")] (podman/remove! c (:Id x))))
               (catch Exception _ nil))
          (io/delete-file (.toFile (description/path ws "a2")) true)
          (store/write-edn! (runtime/config-path ws) cfg0)
          (runtime/sync-env! ws)
          (call "runtime.ensure" {:ws ws}))))
    (fu/skip! "off-local-external-story" "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
