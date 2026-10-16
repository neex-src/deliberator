(ns deliberator.topology-test
  "Progressive topology without containers: the stored shape and its validation, the resolution of `service/<n>`
  and `log/service-<n>` (`env/target`), `topology.show|set` (strict about the workspace), the dependents' rendering
  in the runtime specs (hash changes, secret becomes env, a non-local service is not desired), `service.list`'s
  mode, `env.describe`, and a run against an `off` service (fails at the step, records the topology). The live
  story with containers is `estate_test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.steps.http]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))
(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(defn- seed! [ws]
  (call "workspace.open" {:ws ws})
  (description/write! ws {:name "a" :kind :spring-boot :port 8081 :jdwp 5005 :module "/m/a" :build {:artifact "a.jar"}})
  (description/write! ws {:name "b" :kind :spring-boot :port 8082 :jdwp 5006 :module "/m/b" :build {:artifact "b.jar"}
                          :depends {"SERVICE_A_URL" "a"}
                          :taskdef {:environment {"REGION" "eu"} :secrets {"SERVICE_A_URL" "/p/a-url" "API_KEY" "/p/key"}}}))

(def ^:private rec
  {:name "t" :kind :local :aws {:endpoint "http://localhost:4566" :region "r" :account "000000000000"
                               :credentials {:access-key-id "k" :secret-access-key "s"}}
   :targets {:service/a "http://localhost:8081" :log/service-a {:container "c-a"}}})

(deftest stored-shape-is-validated
  (is (empty? (env/problems (assoc rec :topology {"a" {:mode :external :url "http://127.0.0.1:18089"} "b" {:mode :off}}))))
  (doseq [bad [{"a" {:mode :external}} {"a" {:mode :external :url "ftp://x"}} {"a" {:mode :off :url "http://x"}}
               {"a" {:mode :weird}} {"a" {:mode :off :extra 1}} {"a" {:mode :external :url "http://x:1" :log {:container ""}}} [] {:a {:mode :off}}]]
    (is (seq (env/problems (assoc rec :topology bad))) (pr-str bad))))

(deftest resolution-follows-the-mode
  (let [e (assoc rec :topology {"a" {:mode :external :url "http://127.0.0.1:18089" :log {:container "c-a2"}}})]
    (is (= "http://localhost:8081" (env/target rec :service/a)) "no entry: the container's host URL")
    (is (= "http://127.0.0.1:18089" (env/target e "service/a")))
    (is (= {:container "c-a2"} (env/target e :log/service-a)) "an external instance names its log")
    (is (= {:container "c-a"} (env/target (assoc-in e [:topology "a" :log] nil) :log/service-a)) "without :log the local one stays")))

(deftest off-is-a-typed-error-naming-service-and-environment
  (let [e (assoc rec :topology {"a" {:mode :off}})
        x (try (env/target e :service/a) (catch clojure.lang.ExceptionInfo x x))]
    (is (= :topology/service-off (:type (ex-data x))))
    (is (str/includes? (ex-message x) "service a is off in environment t"))
    (is (str/includes? (:hint (ex-data x)) "dlb topology set t a local"))))

(deftest topology-ops-set-show-and-dependents
  (tu/with-temp-home
    (seed! "w")
    (testing "strict about an unknown workspace"
      (is (= "workspace/not-found" (:type (thrown #(call "topology.show" {:ws "nope" :env "local"})))))
      (is (= "workspace/not-found" (:type (thrown #(call "topology.set" {:ws "nope" :env "local" :name "a" :mode "off"}))))))
    (let [h0 (:hash (call "topology.show" {:ws "w" :env "local"}))]
      (is (= {"a" "local" "b" "local"} (update-vals (:services (call "topology.show" {:ws "w" :env "local"})) :mode)))
      (let [r (call "topology.set" {:ws "w" :env "local" :name "a" :mode "external" :url "http://127.0.0.1:18089/" :log-container "c-a2"})]
        (is (= {:mode "external" :url "http://127.0.0.1:18089"} (get-in r [:services "a"])) "the trailing slash goes")
        (is (= [{:name "b" :var "SERVICE_A_URL"}] (:dependents r)))
        (is (not= h0 (:hash r))))
      (is (= {:mode :external :url "http://127.0.0.1:18089" :log {:container "c-a2"}} (get-in (env/load "w" "local") [:topology "a"])))
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "local"})
      (is (nil? (:topology (env/load "w" "local"))) "local removes the entry")
      (is (= h0 (:hash (call "topology.show" {:ws "w" :env "local"})))))
    (testing "bad calls"
      (is (= "bad-args" (:type (thrown #(call "topology.set" {:ws "w" :env "local" :name "a" :mode "external"})))))
      (is (= "bad-args" (:type (thrown #(call "topology.set" {:ws "w" :env "local" :name "a" :mode "off" :url "http://x:1"})))))
      (is (= "bad-args" (:type (thrown #(call "topology.set" {:ws "w" :env "local" :name "a" :mode "maybe"})))))
      (is (= "service/not-found" (:type (thrown #(call "topology.set" {:ws "w" :env "local" :name "zz" :mode "off"})))))
      (is (= "env/not-found" (:type (thrown #(call "topology.set" {:ws "w" :env "nope" :name "a" :mode "off"}))))))))

(defn- desired-b [ws]
  (let [cfg (runtime/read-config ws)]
    (first (filter #(= "service-b" (:role %)) (runtime/desired-all ws cfg)))))

(deftest dependents-are-rendered-from-the-local-topology
  (tu/with-temp-home
    (seed! "w")
    (runtime/sync-env! "w")
    (let [local (desired-b "w")]
      (is (= {"API_KEY" "/p/key" "SERVICE_A_URL" "/p/a-url"} (:secrets local)))
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "external" :url "http://127.0.0.1:18089"})
      (let [ext (desired-b "w")]
        (is (= {"API_KEY" "/p/key"} (:secrets ext)) "the URL leaves the secrets")
        (is (= "http://host.containers.internal:18089" (get-in ext [:env "SERVICE_A_URL"])) "a loopback host is the host for a container")
        (is (not= (:hash local) (:hash ext)) "a changed spec: the container is recreated at the next ensure"))
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "external" :url "http://other.example:9"})
      (is (= "http://other.example:9" (get-in (desired-b "w") [:env "SERVICE_A_URL"])))
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "off"})
      (is (= "http://a.off.invalid" (get-in (desired-b "w") [:env "SERVICE_A_URL"])))
      (testing "an off or external service is not desired as a container; its role is still declared"
        (is (nil? (get (runtime/effective-containers "w" (runtime/read-config "w")) :service/a)))
        (is (some? (get (runtime/declared-containers "w" (runtime/read-config "w")) :service/a)))
        (is (= #{"service-a"} (runtime/topology-stopped-roles "w"))))
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "local"})
      (is (= local (desired-b "w")) "back to local: the same spec, the same hash")
      (is (some? (get (runtime/effective-containers "w" (runtime/read-config "w")) :service/a))))))

(deftest only-the-local-environment-renders-containers
  (tu/with-temp-home
    (seed! "w")
    (runtime/sync-env! "w")
    (env/save! "w" (assoc rec :name "dev" :topology {"a" {:mode :off}}))
    (call "topology.set" {:ws "w" :env "dev" :name "a" :mode "off"})
    (is (= {"API_KEY" "/p/key" "SERVICE_A_URL" "/p/a-url"} (:secrets (desired-b "w"))))
    (is (= "off" (:mode (first (filter #(= "a" (:name %)) (call "service.list" {:ws "w" :env "dev"}))))))
    (is (= "local" (:mode (first (filter #(= "a" (:name %)) (call "service.list" {:ws "w"}))))))))

(deftest starting-a-service-the-topology-serves-elsewhere-is-refused
  (tu/with-temp-home
    (seed! "w")
    (call "topology.set" {:ws "w" :env "local" :name "a" :mode "off"})
    (let [e (thrown #(call "service.start" {:ws "w" :name "a"}))]
      (is (= "topology/service-not-local" (:type e)))
      (is (str/includes? (:hint e) "dlb topology set local a local")))
    (is (= "topology/service-not-local" (:type (thrown #(call "service.restart" {:ws "w" :name "a"})))))
    (let [list (call "service.list" {:ws "w"})]
      (is (= ["off" "local"] (mapv :mode list))))
    (call "topology.set" {:ws "w" :env "local" :name "a" :mode "external" :url "http://127.0.0.1:18089"})
    (is (= {:mode "external" :url "http://127.0.0.1:18089"} (select-keys (first (call "service.list" {:ws "w"})) [:mode :url])))))

(deftest describe-shows-the-resolved-targets
  (tu/with-temp-home
    (seed! "w")
    (runtime/sync-env! "w")
    (call "topology.set" {:ws "w" :env "local" :name "a" :mode "external" :url "http://127.0.0.1:18089" :log-container "c-a2"})
    (let [d (call "env.describe" {:ws "w" :name "local"})]
      (is (= "http://127.0.0.1:18089" (get-in d [:env :targets :service/a])))
      (is (= {:container "c-a2"} (get-in d [:env :targets :log/service-a])))
      (is (= "http://localhost:8082" (get-in d [:env :targets :service/b])))
      (is (= "external" (get-in d [:topology "a" :mode]))))
    (call "topology.set" {:ws "w" :env "local" :name "a" :mode "off"})
    (is (= "off" (get-in (call "env.describe" {:ws "w" :name "local"}) [:env :targets :service/a])))))

(deftest a-run-against-an-off-service-fails-at-its-step-and-records-the-topology
  (tu/with-temp-home
    (seed! "w")
    (runtime/sync-env! "w")
    (call "session.start" {:ws "w" :key "s"})
    (let [scn (io/file (tu/temp-home) "t.scn")]
      (spit scn "# @env local\n\n### http first\n# @assert status == 200\nGET {{service/a}}/x\n")
      (call "topology.set" {:ws "w" :env "local" :name "a" :mode "off"})
      (let [r (call "scenario.run" {:ws "w" :path (str scn) :session "s"})
            run (:run (call "runs.show" {:ws "w" :run (:run-id r) :session "s"}))
            txt (slurp (get-in (call "runs.show" {:ws "w" :run (:run-id r) :session "s"}) [:paths :result]))]
        (is (= :error (:status (first (:steps run)))) "the step fails, the plan did not")
        (is (= :topology/service-off (get-in run [:steps 0 :error :type])))
        (is (str/includes? txt "ERROR: service a is off in environment local"))
        (is (= {:a :off :b :local} (into {} (for [[n m] (get-in run [:topology :services])] [(keyword n) (:mode m)]))))
        (is (str/includes? txt "TOPOLOGY a=off b=local"))
        (is (re-matches #"[0-9a-f]{16}" (get-in run [:topology :hash])))))))
