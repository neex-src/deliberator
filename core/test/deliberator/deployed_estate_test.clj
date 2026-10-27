(ns deliberator.deployed-estate-test
  "`deployed.refresh` and `deployed.show` against Floci's ECS mock (M4.2): a remote-shaped environment at the emulator, a
  cluster, a task definition with an environment variable and a service with `desiredCount 0` made as fixtures through a
  local record, then the two operations as the daemon calls them. Needs a running Floci (`DLB_TEST_FLOCI_URL`, SKIP
  otherwise). With `ESTATE_WS` set the estate's own cluster and task definitions are read too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [deliberator.aws :as aws]
            [deliberator.deployed :as d]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.ops :as ops]
            [deliberator.remote-util :as ru]
            [deliberator.store :as store]))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(defn- ok! [r what]
  (when (aws/error? r) (throw (ex-info (str what ": " (or (:message r) (:code r))) {:aws r})))
  r)

(def ^:private cluster "m4-deployed-test")
(def ^:private family "m4-deployed-test")

(defn- cleanup! [local svc-names td-arns]
  (doseq [s svc-names] (aws/invoke! {:ws "default"} local :ecs :DeleteService {:cluster cluster :service s :force true}))
  (doseq [a td-arns] (aws/invoke! {:ws "default"} local :ecs :DeregisterTaskDefinition {:taskDefinition a}))
  (aws/invoke! {:ws "default"} local :ecs :DeleteCluster {:cluster cluster}))

(defn- raw-text [ws sha] (slurp (io/file (str (store/raw-path ws sha)))))

(deftest-floci refresh-and-show-read-the-ecs-mock-and-cache-it
  (ru/with-remote-at-floci [remote local]
    (let [inv (fn [op req] (ok! (aws/invoke! {:ws "default"} local :ecs op req) (name op)))
          _ (aws/invoke! {:ws "default"} local :ecs :DeleteCluster {:cluster cluster})   ; a leftover of a killed run
          _ (inv :CreateCluster {:clusterName cluster})
          td1 (:taskDefinition (inv :RegisterTaskDefinition
                                    {:family family
                                     :containerDefinitions [{:name "app" :image "registry.example/app:1.0.0" :memory 128 :essential true
                                                             :environment [{:name "M4_LOOKS_SECRET" :value "hunter2-m4-local"}]}]}))
          td2 (:taskDefinition (inv :RegisterTaskDefinition
                                    {:family family
                                     :containerDefinitions [{:name "app" :image "registry.example/app:1.1.0" :memory 128 :essential true
                                                             :environment [{:name "M4_LOOKS_SECRET" :value "hunter2-m4-local"}]}]}))]
      (try
        (inv :CreateService {:cluster cluster :serviceName "m4-svc" :taskDefinition (str family ":" (:revision td2)) :desiredCount 0})
        (testing "nothing is cached before the first refresh: show says how to fetch"
          (let [e (try (call "deployed.show" {:ws "default" :env "dlbremote"}) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= :deployed/no-cache (:type e)))))
        (let [r (call "deployed.refresh" {:ws "default" :env "dlbremote"})]
          (testing "the refresh answers with counts and the files"
            (is (= "dlbremote" (:env r)))
            (is (pos? (:ecs r)) (pr-str r))
            (is (.isFile (io/file (:path r))))
            (is (.isFile (io/file (:text r))))
            (is (pos? (:raw r))))
          (testing "the cache has the service with its task definition revision and image tag"
            (let [snap (d/cached "default" "dlbremote")
                  svc (first (filter #(and (= :ecs (:kind %)) (= "m4-svc" (:name %))) (:services snap)))]
              (is (inst? (java.util.Date/from (:fetched-at snap))))
              (is (some? svc) (pr-str (map :name (:services snap))))
              (is (= cluster (:cluster svc)))
              (is (= (:revision td2) (:revision svc)))
              (is (= family (:family svc)))
              (is (= [{:name "app" :image "registry.example/app:1.1.0" :tag "1.1.0"}] (:containers svc)))
              (is (= 0 (:desired svc)))
              (testing "the view carries identity only, never environment variables"
                (is (not (str/includes? (pr-str snap) "hunter2")))
                (is (not (str/includes? (slurp (:text r)) "hunter2"))))))
          (testing "every response was persisted raw first, with the environment variable VALUE redacted (the remote rule)"
            (let [snap (d/cached "default" "dlbremote")
                  shas (keep :response (:raw snap))
                  td-raws (map #(raw-text "default" %) shas)]
              (is (every? #(.isFile (io/file (str (store/raw-path "default" %)))) shas))
              (is (not-any? #(str/includes? % "hunter2") td-raws))
              (is (some #(str/includes? % "M4_LOOKS_SECRET") td-raws) "the name stays")
              (is (some #(str/includes? % "<redacted>") td-raws)))))
        (testing "show answers from the cache, with its age, and never calls AWS"
          (cleanup! local ["m4-svc"] [(:taskDefinitionArn td1) (:taskDefinitionArn td2)])   ; Floci has nothing of it now
          (let [all (call "deployed.show" {:ws "default" :env "dlbremote"})
                one (call "deployed.show" {:ws "default" :env "dlbremote" :service "m4-svc"})]
            (is (some #(= "m4-svc" (:name %)) (:services all)) "still shown after it was deleted: it is the cached view")
            (is (re-find #"^\d+ (s|min)$" (:age all)))
            (is (= 1 (:count one)))
            (is (= [(:revision td2)] (map :revision (:services one))))
            (is (= (str "m4-deployed-test/m4-svc") (str (:cluster (first (:services one))) "/" (:name (first (:services one))))))))
        (testing "refresh again sees it gone"
          (call "deployed.refresh" {:ws "default" :env "dlbremote"})
          (is (not-any? #(= "m4-svc" (:name %)) (:services (d/cached "default" "dlbremote")))))
        (testing "the audit log has the reads, all of class read, none refused"
          (let [lines (map read-string (str/split-lines (slurp (str (aws/audit-path {:ws "default"})))))
                ops* (set (map :op lines))]
            (is (contains? ops* "ListClusters"))
            (is (contains? ops* "DescribeTaskDefinition") "the task definition of the cached service was read")
            (is (every? #(= :read (:class %)) lines))
            (is (every? #(not= :refused (:outcome %)) lines))))
        (finally (cleanup! local ["m4-svc"] [(:taskDefinitionArn td1) (:taskDefinitionArn td2)]))))))

(deftest-floci a-refresh-with-the-estate-reads-its-task-definitions
  (if-let [ws (fu/estate-ws)]
    (ru/with-remote-at-floci [remote local]
      ;; the estate registers cluster `estate` and the task definitions estate-service-a|b on Floci, no service (roadmap §7):
      ;; a service made here puts the estate's real task definition (image estate/service-a:local) into the view
      (let [fam (->> (aws/invoke! {:ws "default"} local :ecs :ListTaskDefinitions {:status "ACTIVE"}) :taskDefinitionArns
                     (filter #(str/includes? % "service-a")) last)]
        (if-not fam
          (fu/skip! (fu/current-test) "the estate has no service-a task definition on this Floci")
          (try
            (ok! (aws/invoke! {:ws "default"} local :ecs :CreateService {:cluster "estate" :serviceName "m4-estate-a" :taskDefinition fam :desiredCount 0
                                                                                  :launchType "FARGATE" :networkConfiguration {:awsvpcConfiguration {:subnets ["subnet-m4"]}}}) "CreateService")
            (call "deployed.refresh" {:ws "default" :env "dlbremote"})
            (let [svc (first (filter #(= "m4-estate-a" (:name %)) (:services (d/cached "default" "dlbremote"))))]
              (is (= "estate" (:cluster svc)))
              ;; the newest revision: `local` as Terraform made it, or the commit tag `ci.local` registered (ci_local_estate_test)
              (is (str/starts-with? (str (get-in svc [:containers 0 :image])) "estate/service-a:"))
              (is (= (get-in svc [:containers 0 :tag]) (subs (str (get-in svc [:containers 0 :image])) (count "estate/service-a:")))))
            (finally (aws/invoke! {:ws "default"} local :ecs :DeleteService {:cluster "estate" :service "m4-estate-a" :force true}))))))
    (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>)")))
