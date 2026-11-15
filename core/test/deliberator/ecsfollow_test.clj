(ns deliberator.ecsfollow-test
  "The local runtime follows the local ECS service (cicd-aws piece 02), against the real thing: Floci 2.3.0 (ECS mock) and
  Podman on host port 14566 (the runtime tests' port: run under the suite lock), a `:container` service on the alpine image bound to
  ECS service `c/s`. Desired 0 stops the container within 2 s and it stays stopped (`runtime.ensure` does not bring it back, a restart is
  refused), desired 1 runs it again, a new revision with another environment value recreates it, a restart of the watch changes nothing,
  a start and a stop by hand write the record, and an unchanged poll writes nothing to the raw store."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.ecsfollow :as follow]
            [deliberator.ecsrecord :as rec]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.service :as service]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def ^:private floci-port 14566)
(def ^:private host-port 14577)

(defn- await-true
  "Polls `f` every 100 ms for `ms`; -> the milliseconds it took, nil when it never became true."
  [ms f]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (try (f) (catch Throwable _ false)) (- (System/currentTimeMillis) t0)
            (> (- (System/currentTimeMillis) t0) ms) nil
            :else (do (Thread/sleep 100) (recur))))))

(defn- ecs! [ws op req]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :ecs op req)]
    (when (aws/error? r) (throw (ex-info (str op " failed: " (pr-str (dissoc r :raw))) {:type :test/ecs})))
    r))

(defn- register! [ws v]
  (ecs! ws :RegisterTaskDefinition {:family "fam"
                                    :containerDefinitions [{:name "web" :image "alpine:3.20" :essential true
                                                            :environment [{:name "V" :value v}]}]}))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(deftest the-container-follows-the-ecs-record
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "ef-" id)
           net (str "deliberator-test-" id)
           c (podman/client (sup/endpoint) :ws ws)
           cfg (-> (runtime/default-config)
                   (assoc-in [:podman :endpoint] (sup/endpoint))
                   (assoc :network net)
                   (update :containers dissoc :postgres)
                   (assoc-in [:containers :floci :ports] {4566 floci-port})
                   (assoc-in [:containers :floci :name] (str net "-floci")))
           events (atom [])
           sub (follow/subscribe! #(swap! events conj %))
           watch (atom nil)
           role "service-web"
           ctr #(first (podman/list-containers c :all true :labels {"deliberator.workspace" ws "deliberator.role" role}))
           running? #(= "running" (:State (ctr)))
           row #(first (service/status ws "web"))
           watch! (fn [] (reset! watch (follow/start! {:home (store/home) :log (fn [& _] nil)})) (follow/poke!))
           unwatch! #(when-let [f @watch] (f) (reset! watch nil))]
       (store/write-edn! (runtime/config-path ws) cfg)
       (try
         (runtime/ensure! ws {:infra-only true})
         (runtime/sync-env! ws)
         (description/write! ws {:name "web" :kind :container :image sup/alpine-image :port 8080 :host-port host-port
                                 :cmd ["sleep" "600"] :env {"V" "1"} :taskdef {:container "web"}
                                 :ecs {:cluster "c" :service "s"}})
         (ecs! ws :CreateCluster {:clusterName "c"})
         (register! ws "1")
         (ecs! ws :CreateService {:cluster "c" :serviceName "s" :taskDefinition "fam:1" :desiredCount 1})
         (service/start! ws "web" {})
         (watch!)
         (testing "first sight is a baseline: the record is read, the container is left alone"
           (is (await-true 4000 #(= "fam:1" (:revision (rec/read-record ws "web")))))
           (is (running?))
           (let [r (row)]
             (is (= "running" (:status r)))
             (is (= {:cluster "c" :service "s" :desired 1 :revision "fam:1" :image "alpine:3.20"} (select-keys (:ecs r) [:cluster :service :desired :revision :image])))
             (is (= "revision 1 · alpine:3.20 (not used: the description's image runs)" (:text (:ecs r))))))
         (testing "an unchanged poll writes nothing to the raw store"
           (let [n #(count (filter (fn [f] (.endsWith (str f) ".edn")) (file-seq (.toFile (store/path ws "raw")))))
                 _ (loop [last -1]   ; the rollout state of a new service turns COMPLETED by itself once: let that response settle first
                     (Thread/sleep 2500)
                     (when (not= last (n)) (recur (n))))
                 before (n)]
             (Thread/sleep 3500)
             (is (<= (- (n) before) 1) "four polls: at most the one new response (a rollout state that turned COMPLETED), no blob per poll")))
         (testing "desired 0: the container is gone within 2 s, the rows say why, it stays down"
           (ecs! ws :UpdateService {:cluster "c" :service "s" :desiredCount 0})
           (let [ms (await-true 4000 #(not (running?)))]
             (is (some? ms))
             (is (<= ms 2500) (str "took " ms " ms")))
           (is (= "stopped (ecs desired 0)" (:status (row))))
           (is (= "stopped (ecs desired 0)" (:status (first (filter #(= "web" (:name %)) (service/list ws))))))
           (let [ev (first (filter #(= "service.stopped" (:event %)) @events))]
             (is (= "ecs desired 0" (:reason ev)))
             (is (= "web" (:name ev))))
           (runtime/ensure! ws {})
           (is (not (running?)) "runtime.ensure does not bring it back")
           (Thread/sleep 2500)
           (is (not (running?)) "and neither does the watch")
           (is (= "service/ecs-desired-zero" (:type (thrown #(service/restart! ws "web" {})))) "a restart is refused with the reason")
           (is (= 0 (:desired (follow/live ws {:cluster "c" :service "s"}))) "a restart wrote nothing"))
         (testing "desired 1: it runs again"
           (ecs! ws :UpdateService {:cluster "c" :service "s" :desiredCount 1})
           (is (some? (await-true 5000 running?)))
           (is (some? (await-true 4000 #(some (fn [e] (and (= "service.started" (:event e)) (= "ecs desired 1" (:reason e)))) @events)))))
         (testing "desired 3 is one container"
           (ecs! ws :UpdateService {:cluster "c" :service "s" :desiredCount 3})
           (is (some? (await-true 4000 #(= 3 (:desired (:ecs (row)))))))
           (is (str/includes? (str (:note (:ecs (row)))) "one container"))
           (is (= 1 (count (podman/list-containers c :all true :labels {"deliberator.workspace" ws "deliberator.role" role})))))
         (testing "a new revision recreates the container from its container definition"
           (let [old-id (:Id (ctr))]
             (register! ws "2")
             (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:2"})
             (is (some? (await-true 8000 #(and (running?) (not= old-id (:Id (ctr)))))))
             (is (some #(= "V=2" %) (get-in (podman/inspect c (:Id (ctr))) [:Config :Env])))
             (is (= "revision 2 · alpine:3.20 (not used: the description's image runs)" (:text (:ecs (row)))))))
         (testing "a restart of the watch changes nothing"
           (let [id1 (:Id (ctr))]
             (unwatch!) (watch!)
             (Thread/sleep 3000)
             (is (= id1 (:Id (ctr))))
             (is (running?))))
         (testing "a change made while nobody watches is applied by the next watch"
           (unwatch!)
           (ecs! ws :UpdateService {:cluster "c" :service "s" :desiredCount 0})
           (Thread/sleep 1500)
           (is (running?) "nobody watching: nothing happened")
           (watch!)
           (is (some? (await-true 4000 #(not (running?))))))
         (testing "a start by hand at desired 0 writes the record, a stop by hand writes it back"
           (service/start! ws "web" {})
           (is (running?))
           (is (= 1 (:desired (follow/live ws {:cluster "c" :service "s"}))))
           (Thread/sleep 2500)
           (is (running?) "the watch does not fight the record it was told")
           (service/stop! ws "web" {})
           (is (= 0 (:desired (follow/live ws {:cluster "c" :service "s"}))))
           (is (not (running?)))
           (Thread/sleep 2500)
           (is (not (running?))))
         (finally
           (unwatch!)
           (follow/unsubscribe! sub)
           (try (runtime/down! ws) (catch Exception _))
           (podman/remove-network! c net)))))))

(deftest a-service-without-a-binding-is-as-before
  (when-podman
   (tu/with-temp-home
    (let [ws "w"]
      (ops/call! "workspace.open" {} {:ws ws})
      (description/write! ws {:name "a" :kind :container :image sup/alpine-image :port 8080 :cmd ["sleep" "1"]})
      (is (nil? (rec/binding-of (description/read ws "a"))))
      (is (= "absent" (:status (first (service/status ws "a")))) "no binding, no ecs word in the status")
      (is (nil? (:ecs (first (service/status ws "a")))))
      (is (= (description/read ws "a") (rec/overlay ws (description/read ws "a"))))
      (is (= "service/invalid" (:type (thrown #(description/write! ws {:name "b" :kind :container :image "x" :port 1 :ecs {:cluster "c"}})))))))))

(deftest binding-words
  (is (= {:cluster "acme" :service "acme-billing"} (rec/parse-binding "acme/acme-billing")))
  (is (= :none (rec/parse-binding "none")))
  (is (nil? (rec/parse-binding "nonsense")))
  (is (nil? (rec/parse-binding "a/b/c"))))
