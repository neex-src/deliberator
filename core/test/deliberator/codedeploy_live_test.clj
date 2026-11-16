(ns deliberator.codedeploy-live-test
  "The CodeDeploy deploy view against the emulator the Acme estate runs (Floci, `ACME_FLOCI_URL`, default http://localhost:4566), on the three
  deployments the estate's pipelines made (ECS `d-803973498`, Lambda `d-86DA1FAC1` and the failing-hook `d-8B094564A`). Reads only. SKIPS with a clear line
  when that emulator is down or does not have those deployments (any other Floci would not): the same facts are in `codedeploy-test` on the saved answers.
  CodeDeploy deployments are read as the real API answers them, but this has only been checked against the local emulator, not against real CodeDeploy."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds]
            [deliberator.deploy :as d]
            [deliberator.floci-util :as fu]
            [deliberator.test-util :as tu]
            [deliberator.env :as env]))

(def ^:private url (or (System/getenv "ACME_FLOCI_URL") "http://localhost:4566"))
(def ^:private failed "d-8B094564A")
(def ^:private succeeded "d-86DA1FAC1")
(def ^:private ecs "d-803973498")

(defn- estate-has-the-deployments? []
  (try
    (let [{:keys [hostname port]} (let [u (java.net.URI. url)] {:hostname (.getHost u) :port (.getPort u)})
          c (aws/client {:api :codedeploy :region "eu-central-1"
                         :credentials-provider (creds/basic-credentials-provider {:access-key-id "test" :secret-access-key "test"})
                         :endpoint-override {:protocol :http :hostname hostname :port port}})
          ids (set (:deployments (aws/invoke c {:op :ListDeployments :request {}})))]
      (every? ids [failed succeeded ecs]))
    (catch Throwable _ false)))

(deftest the-deploy-view-reads-the-estates-codedeploy-deployments
  (if-not (estate-has-the-deployments?)
    (fu/skip! "codedeploy-live" (str "the Acme estate's Floci at " url " is down or has not the three deployments (bb up in acme-platform; the two pipelines make them)"))
    (tu/with-temp-home
      (env/save! "default" (fu/local-env {:aws {:endpoint url}}))
      (testing "the failed Lambda deployment: HOOK_EXECUTION_FAILURE, the hook named, the hook's own lines"
        (let [body (:body (d/show! "default" "local" "acme-maintenance-down" {:deployment failed}))]
          (is (str/includes? body "HOOK_EXECUTION_FAILURE"))
          (is (str/includes? body "BeforeAllowTraffic -> acme-codedeploy-hook"))
          (is (re-find #"\[hook acme-codedeploy-hook\] .*failing on purpose" body))
          (is (not (str/includes? body (str "[" succeeded "]"))) "the other deployment's lines are not in it")))
      (testing "the succeeded one: the alias 1 -> 2 and the probe"
        (let [body (:body (d/show! "default" "local" "acme-maintenance-down" {:deployment succeeded}))]
          (is (str/includes? body "version 1 -> 2"))
          (is (str/includes? body "the target version answered the probe"))
          (is (str/includes? body "lifecycle AllowTraffic"))))
      (testing "the ECS deployment: Succeeded, the revision and the image, and what the endpoint gave of its target"
        (let [r (d/show! "default" "local" "acme-billing" {:deployment ecs})]
          (is (= "Succeeded" (:status r)))
          (is (= 2 (:revision-after r)))
          (is (str/includes? (:body r) "acme:acme-billing   ECSTarget   Succeeded"))
          (is (str/includes? (:body r) "hook         the appspec names none"))))
      (testing "the lists: a Lambda's CodeDeploy deployments newest first, an ECS service's one with the commit"
        (let [l (d/list! "default" "local" "acme-maintenance-down" {})
              ids (map :id (filter #(= :codedeploy (:source %)) (:rows l)))]
          (is (= [failed succeeded] (take 2 ids))))
        (let [e (d/list! "default" "local" "acme-billing" {})
              row (some #(when (= ecs (:id %)) %) (:rows e))]
          (is (some? row))
          (is (= [nil 2] ((juxt :revision-before :revision-after) row)) "the first deployment of its group: nothing before")
          (is (some? (:git-sha row)) "the commit comes from the task definition's tag")))
      (testing "the commit finds the CodeDeploy deployment from the cache; a finished deployment follows to its end"
        (let [sha (:git-sha (some #(when (= ecs (:id %)) %) (:rows (d/list! "default" "local" "acme-billing" {}))))]
          (is (some #(= ecs (:deployment %)) (d/by-commit "default" sha))))
        (let [lines (atom [])
              r (d/follow! "default" "local" "acme-maintenance-down"
                           {:deployment failed :emit! #(swap! lines conj (:text %)) :poll-ms 200 :timeout-ms 20000 :wait-ms 0})]
          (is (= :failed (:ended r)))
          (is (some #(str/includes? % "HOOK_EXECUTION_FAILURE") @lines))
          (is (some #(str/includes? % "failing on purpose") @lines)))))))
