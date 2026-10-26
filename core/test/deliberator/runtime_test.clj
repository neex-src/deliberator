(ns deliberator.runtime-test
  "The runtime reconciler against the real Podman: real Floci 2.3.0 and Postgres 17 on host ports
  14566 / 15432 (never 4566 / 5432, which belong to whatever else runs on the machine), with
  names, network and workspace unique to each run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [deliberator.env :as env]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.net InetAddress ServerSocket)
           (java.time Instant)))

(def floci-port 14566)
(def real-account "000000000000")
(def pg-port 15432)

(defn- test-config [id]
  (-> (runtime/default-config)
      (assoc-in [:podman :endpoint] (sup/endpoint))
      (assoc :network (str "deliberator-test-" id))
      (assoc-in [:containers :floci :ports] {4566 floci-port})
      (assoc-in [:containers :floci :name] (str "deliberator-test-" id "-floci"))
      (assoc-in [:containers :postgres :ports] {5432 pg-port})
      (assoc-in [:containers :postgres :name] (str "deliberator-test-" id "-postgres"))
      (assoc-in [:containers :postgres :init] "init-sql")))

(defn- write-config! [ws cfg]
  (store/write-edn! (runtime/config-path ws) cfg))

(defn- names-now [c] (set (mapcat :Names (podman/list-containers c :all true))))

(deftest a-health-body-is-persisted-only-when-it-changed-or-fails
  ;; review 2026-10-25 R26: the IDE's 15 s status tick must not write a blob per probe
  (tu/with-temp-home
    (let [puts (atom 0)
          persist! #'runtime/persist-probe!
          url "http://localhost:1/health"
          body #(.getBytes ^String % "UTF-8")]
      (with-redefs [store/raw-put! (fn [& _] (swap! puts inc) {})]
        (persist! "w" url (body "{\"s\":\"ok\"}") 200 "application/json")
        (persist! "w" url (body "{\"s\":\"ok\"}") 200 "application/json")
        (persist! "w" url (body "{\"s\":\"ok\"}") 200 "application/json")
        (is (= 1 @puts) "the same 2xx body three times is persisted once")
        (persist! "w" url (body "{\"s\":\"changed\"}") 200 "application/json")
        (is (= 2 @puts) "a changed body is persisted")
        (persist! "w" url (body "{\"s\":\"changed\"}") 503 "application/json")
        (persist! "w" url (body "{\"s\":\"changed\"}") 503 "application/json")
        (is (= 4 @puts) "a non-2xx probe is persisted every time")
        (persist! "w" "http://localhost:2/health" (body "{\"s\":\"changed\"}") 200 "application/json")
        (is (= 5 @puts) "the memory is per URL")))))

;; --- pure ------------------------------------------------------------------------------------

(deftest desired-state
  (tu/with-temp-home
  (let [cfg (runtime/default-config)
        [floci postgres] (runtime/desired-all "w" cfg)]
    (is (= ["floci" "postgres"] (map :role [floci postgres])))
    (is (= "deliberator-w-floci" (:name floci)))
    (is (= "http://localhost:4566" (get-in floci [:env "FLOCI_BASE_URL"])))
    (is (= "deliberator-w-floci" (get-in floci [:env "FLOCI_SERVICES_LAMBDA_DOCKER_HOST_OVERRIDE"])))
    (is (= "true" (get-in floci [:env "FLOCI_SERVICES_ECS_MOCK"])))
    (is (= "docker.io/floci/floci:2.3.0" (:image floci)))
    (is (= "docker.io/library/postgres:17" (:image postgres)))
    (testing "the advertised URL follows the host port, and the hash follows the spec"
      (let [moved (first (runtime/desired-all "w" (assoc-in cfg [:containers :floci :ports] {4566 14566})))]
        (is (= "http://localhost:14566" (get-in moved [:env "FLOCI_BASE_URL"])))
        (is (not= (:hash floci) (:hash moved)))
        (is (= (:hash floci) (:hash (first (runtime/desired-all "w" cfg)))))))
    (is (= "service-a" (runtime/role-name :service/a)))
    (testing "the account of the local environment goes to Floci (M6.2); Floci's own fake one sets nothing"
      (is (not (contains? (:env floci) "FLOCI_DEFAULT_ACCOUNT_ID")))
      (env/save! "w" (assoc-in (env/load "w" "local") [:aws :account] "000000000000"))
      (let [[f2 p2] (runtime/desired-all "w" cfg)]
        (is (= "000000000000" (get-in f2 [:env "FLOCI_DEFAULT_ACCOUNT_ID"])))
        (is (not= (:hash floci) (:hash f2)) "a new account recreates Floci")
        (is (not (contains? (:env p2) "FLOCI_DEFAULT_ACCOUNT_ID")) "only Floci"))
      (let [over (first (runtime/desired-all "w" (assoc-in cfg [:containers :floci :env "FLOCI_DEFAULT_ACCOUNT_ID"] "111111111111")))]
        (is (= "111111111111" (get-in over [:env "FLOCI_DEFAULT_ACCOUNT_ID"])) "an explicit env of the role in runtime.edn wins"))))))

(deftest podman-down-is-reported
  (tu/with-temp-home
    (let [ws (str "rt-" (sup/rand-id))]
      (write-config! ws (assoc-in (runtime/default-config) [:podman :endpoint] "unix:///nonexistent/podman.sock"))
      (let [e (try (runtime/ensure! ws) (catch clojure.lang.ExceptionInfo e e))]
        (is (= :runtime/podman-unreachable (:type (ex-data e))))
        (is (str/includes? (:hint (ex-data e)) "podman.socket")))
      (is (false? (get-in (runtime/status ws) [:podman :reachable]))))))

;; --- the real thing --------------------------------------------------------------------------

(deftest ensure-status-logs-down
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "rt-" id)
           cfg (test-config id)
           c (podman/client (sup/endpoint) :ws ws)
           before (names-now c)
           events (atom [])
           opts {:sink #(swap! events conj %)}
           decoy (str "deliberator-test-" id "-decoy")]
       (let [f (.toFile (store/path ws "init-sql" "01.sql"))]
         (io/make-parents f)
         (spit f "create table t(x int); insert into t values (42);"))
       (write-config! ws cfg)
       (env/save! ws (assoc-in (env/load ws "local") [:aws :account] real-account))   ; M6.2: Floci answers as this account
       (try
         (let [s1 (runtime/ensure! ws opts)
               ev1 (map :event @events)]
           (testing "first ensure!: starting and ready for both roles"
             (is (= 2 (count (filter #{"runtime.starting"} ev1))))
             (is (= 2 (count (filter #{"runtime.ready"} ev1))))
             (is (= ["floci" "postgres"] (map :role (:containers s1))))
             (is (every? #(= :healthy (:health %)) (:containers s1)))
             (is (every? #(= :running (:state %)) (:containers s1)))
             (is (str/includes? (:detail (first (:containers s1))) "services running"))
             (is (str/includes? (:detail (second (:containers s1))) "accepting connections")))
           (testing "labels"
             (let [ctrs (podman/list-containers c :all true :labels {"deliberator.workspace" ws})]
               (is (= #{"floci" "postgres"} (set (map #(get-in % [:Labels :deliberator.role]) ctrs))))
               (is (every? #(= ws (get-in % [:Labels :deliberator.workspace])) ctrs))))
           (testing "ensure! twice is idempotent"
             (reset! events [])
             (let [s2 (runtime/ensure! ws opts)]
               (is (= (map :id (:containers s1)) (map :id (:containers s2))))
               (is (not-any? #{"runtime.starting" "runtime.pulling"} (map :event @events)))
               (is (every? #(= :healthy (:health %)) (:containers s2)))))
           (testing "CreateQueue through the mapped port advertises the mapped port"
             (let [sqs (aws/client {:api :sqs :region "us-east-1"
                                    :credentials-provider (creds/basic-credentials-provider {:access-key-id "test" :secret-access-key "test"})
                                    :endpoint-override {:protocol :http :hostname "localhost" :port floci-port}})
                   r (aws/invoke sqs {:op :CreateQueue :request {:QueueName "rt-q"}})]
               (is (= (str "http://localhost:" floci-port "/" real-account "/rt-q") (:QueueUrl r)) (pr-str r))
               (is (str/includes? (str (:QueueUrl (aws/invoke sqs {:op :GetQueueUrl :request {:QueueName "rt-q"}}))) real-account))
               (is (= (str "arn:aws:sqs:us-east-1:" real-account ":rt-q")
                      (get-in (aws/invoke sqs {:op :GetQueueAttributes :request {:QueueUrl (:QueueUrl r) :AttributeNames ["QueueArn"]}})
                              [:Attributes :QueueArn])) "the ARNs of the emulator carry the account")
               (is (some? (:MessageId (aws/invoke sqs {:op :SendMessage
                                                       :request {:QueueUrl (:QueueUrl r) :MessageBody "hi"}}))))))
           (testing "postgres ran the :init script"
             (let [pg (first (podman/list-containers c :labels {"deliberator.workspace" ws "deliberator.role" "postgres"}))
                   r (podman/exec! c (:Id pg) ["psql" "-U" "deliberator" "-d" "deliberator" "-tAc" "select x from t"])]
               (is (= "42" (str/trim (:stdout r))) (pr-str r))))
           (testing "logs: all, then since, mirrored to logs/services"
             (let [all (doall (runtime/logs ws :floci {}))
                   last-at (:at (last all))
                   later (doall (runtime/logs ws :floci {:since (.plusNanos ^Instant last-at 1)}))
                   from-first (doall (runtime/logs ws :floci {:since (:at (first all))}))
                   mirror (io/file (str (store/path ws "logs" "services" "floci")))]
               (is (seq all))
               (is (empty? later) "since the last line: nothing earlier comes back")
               (is (< (count from-first) (count all)))
               (is (every? #(not (.isBefore ^Instant (:at %) ^Instant (:at (first all)))) from-first))
               (is (pos? (count (.listFiles mirror))))
               (let [lines (mapcat #(str/split-lines (slurp %)) (.listFiles mirror))]
                 (is (= (count all) (count lines)) "mirroring twice added nothing")
                 (is (some #(str/includes? % "AWS Local Emulator") lines)))))
           (testing "a changed runtime.edn recreates only what changed"
             (write-config! ws (assoc-in cfg [:containers :postgres :env "POSTGRES_PASSWORD"] "other"))
             (let [drift (runtime/status ws)]
               (is (= [false true] (map :drift? (:containers drift)))))
             (let [s3 (runtime/ensure! ws opts)
                   [f1 p1] (:containers s1) [f3 p3] (:containers s3)]
               (is (= (:id f1) (:id f3)))
               (is (not= (:id p1) (:id p3)))
               (is (every? #(false? (:drift? %)) (:containers s3)))))
           (testing "down! removes only this workspace's containers"
             (podman/create! c {"name" decoy "image" sup/alpine-image "command" ["sleep" "60"]
                                "labels" {"deliberator.workspace" (str ws "-other") "deliberator.role" "x"}})
             (let [d (runtime/down! ws opts)
                   after (names-now c)]
               (is (= #{(str "deliberator-test-" id "-floci") (str "deliberator-test-" id "-postgres")}
                      (set (map :name (:removed d)))))
               (is (contains? after decoy))
               (is (every? after before) "everything that ran before (like the other project's floci) still runs")
               (is (empty? (podman/list-containers c :all true :labels {"deliberator.workspace" ws})))
               (is (= [:absent :absent] (map :state (:containers (runtime/status ws)))))))
           (testing "every response went through the raw store"
             (is (pos? (count (file-seq (.toFile (store/path ws "raw"))))))))
         (finally
           (try (runtime/down! ws) (catch Exception _))
           (podman/remove! c decoy)
           (podman/remove-network! c (:network cfg))))))))

(deftest port-conflict-is-reported
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "rt-" id)
           cfg (test-config id)
           c (podman/client (sup/endpoint) :ws ws)]
       (write-config! ws cfg)
       (with-open [_ (ServerSocket. floci-port 1 (InetAddress/getByName "127.0.0.1"))]
         (try
           (let [e (try (runtime/ensure! ws) (catch clojure.lang.ExceptionInfo e e))]
             (is (= :runtime/port-conflict (:type (ex-data e))) (pr-str e))
             (is (= floci-port (:port (ex-data e))))
             (is (str/includes? (ex-message e) "runtime.edn"))
             (is (empty? (podman/list-containers c :all true :labels {"deliberator.workspace" ws}))
                 "nothing was created"))
           (finally
             (try (runtime/down! ws) (catch Exception _))
             (podman/remove-network! c (:network cfg)))))))))

(deftest two-workspaces-side-by-side-do-not-collide-by-name
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           wa (str "rta-" id) wb (str "rtb-" id)
           net (str "deliberator-test-" id)
           cfg (fn [port] (-> (runtime/default-config)
                              (assoc-in [:podman :endpoint] (sup/endpoint))
                              (assoc :network net)
                              (update :containers dissoc :floci)
                              (assoc-in [:containers :postgres :ports] {5432 port})))
           c (podman/client (sup/endpoint) :ws wa)]
       (write-config! wa (cfg (sup/free-port)))
       (write-config! wb (cfg (sup/free-port)))
       (try
         (let [sa (runtime/ensure! wa) sb (runtime/ensure! wb)
               na (:name (first (:containers sa))) nb (:name (first (:containers sb)))]
           (is (= (str "deliberator-" wa "-postgres") na))
           (is (= (str "deliberator-" wb "-postgres") nb))
           (is (every? #(= :healthy (:health %)) (concat (:containers sa) (:containers sb))))
           (is (= [:running] (map :state (:containers (runtime/status wa)))))
           (testing "down! of one workspace leaves the other"
             (runtime/down! wa)
             (is (= [:absent] (map :state (:containers (runtime/status wa)))))
             (is (= [:running] (map :state (:containers (runtime/status wb)))))))
         (finally
           (try (runtime/down! wa) (catch Exception _))
           (try (runtime/down! wb) (catch Exception _))
           (podman/remove-network! c net)))))))

(deftest container-names-carry-the-workspace
  (tu/with-temp-home
  (is (= "deliberator-w1-service-a" (store/container-name "w1" "service-a")))
  (is (= "deliberator-default-floci" (:name (first (runtime/desired-all "default" (runtime/default-config))))))
  (is (= "deliberator-x-floci" (:name (first (runtime/desired-all "x" (runtime/default-config))))))
  (is (= "custom" (:name (first (runtime/desired-all "x" (assoc-in (runtime/default-config) [:containers :floci :name] "custom"))))))))
