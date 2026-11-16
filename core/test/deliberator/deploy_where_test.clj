(ns deliberator.deploy-where-test
  "`deploy.where` for a local CI run: an ECS deployment is found by its commit, a Lambda deployment (nothing in it names the run) by the time of
  the run's deploy job, and says so. No AWS, no emulator: the caches and a kept run are written by hand in the shapes the daemon writes them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.deploy :as d]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.time Instant)))

(defn- i [s] (Instant/parse s))

(defn- keep! [ws repo-key n jobs]
  (store/write-edn! (store/path ws "ci" "local" repo-key (str n "-1") "run.edn") {:run (str n) :jobs jobs}))

(defn- list! [ws service rows & {:keys [cluster] :or {cluster "lambda"}}]
  (store/write-edn! (store/path ws "cache" "local" "deploy" cluster service "list.edn") {:env "local" :cluster cluster :service service :rows rows}))

(def lambda-ok {:id "d-86DA1FAC1" :source :codedeploy :platform "Lambda" :status "Succeeded" :env "local" :cluster "lambda" :service "acme-maintenance-down"
                :started (i "2026-11-16T05:42:16Z") :created (i "2026-11-16T05:42:16Z") :function "acme-maintenance-down" :revision-after 2})
(def lambda-other (assoc lambda-ok :id "d-8B094564A" :started (i "2026-11-16T05:43:11Z") :created (i "2026-11-16T05:43:11Z") :revision-after 3))
(def ecs-cd {:id "d-803973498" :source :codedeploy :platform "ECS" :status "Succeeded" :env "local" :cluster "acme" :service "acme-billing"
             :started (i "2026-11-16T05:40:15Z") :created (i "2026-11-16T05:40:15Z") :git-sha "b508c373ed6d8bcbdf9902ac2a6882e03e14aa65" :run-id "1" :revision-after 2})

(deftest a-local-run-finds-the-lambda-deployment-created-in-its-deploy-job_marked_as_by-time
  (tu/with-temp-home
    (let [ws "default"]
      (env/save! ws (fu/local-env))
      (keep! ws "the-user-acme-lambdas" 1 [{:id "deploy" :name "deploy" :started-at "2026-11-16T05:41:48Z" :completed-at "2026-11-16T05:42:24Z"}])
      (keep! ws "the-user-acme-billing" 1 [{:id "deploy" :name "deploy" :started-at "2026-11-16T05:00:00Z" :completed-at "2026-11-16T05:01:00Z"}])
      (list! ws "acme-maintenance-down" [lambda-ok lambda-other])
      (testing "the deployment created inside the window, and not the one made a minute later"
        (let [r (d/where ws {:run-id "local-1" :repo "the-user/acme-lambdas"})]
          (is (= ["d-86DA1FAC1"] (map :deployment (:rows r))))
          (let [row (first (:rows r))]
            (is (str/starts-with? (:tied row) "by time: created inside the window of the deploy job of local-1 (the-user-acme-lambdas"))
            (is (nil? (:cluster row)) "a Lambda has no cluster: the spec of the hit is the function")
            (is (= "acme-maintenance-down" (:service row))))
          (is (str/includes? (first (:notes r)) "(by time: "))))
      (testing "the run number counts per repository: billing's local-1 has another window and finds nothing here"
        (is (empty? (:rows (d/where ws {:run-id "local-1" :repo "the-user/acme-billing"}))))
        (is (= ["d-86DA1FAC1"] (map :deployment (:rows (d/where ws {:run-id "local-1"})))) "without the repository every repository's local-1 is looked at"))
      (testing "a GitHub run id has no window: nothing is claimed"
        (is (empty? (:rows (d/where ws {:run-id "43758165494"}))))))))

(deftest an-ecs-deployment-is-found-by-its-commit_not-by-time
  (tu/with-temp-home
    (let [ws "default"]
      (env/save! ws (fu/local-env))
      (list! ws "acme-billing" [ecs-cd] :cluster "acme")
      (let [r (d/where ws {:sha "b508c373ed6d"})]
        (is (= ["d-803973498"] (map :deployment (:rows r))))
        (is (nil? (:tied (first (:rows r)))) "a tie by the commit is not marked as a guess")
        (is (= ["deployed to local: acme-billing revision 2"] (:notes r)))))))
