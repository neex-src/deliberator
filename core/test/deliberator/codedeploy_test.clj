(ns deliberator.codedeploy-test
  "`deliberator.codedeploy` and the CodeDeploy parts of `deliberator.deploy`, on the answers Floci 2.3.0 gave (fixtures/codedeploy_emulator.edn: three
  deployments of the Acme estate, ECS `d-803973498` Succeeded, Lambda `d-86DA1FAC1` Succeeded and `d-8B094564A` Failed with HOOK_EXECUTION_FAILURE,
  their targets, the hook function's log events), decoded as aws-api decodes them. Nothing here needs the estate: the one live test is
  `codedeploy_live_test`. The shapes the emulator does not answer (rollbackInfo, diagnostics.logTail, lambdaFunctionInfo) are written from the descriptors
  of aws-api's `codedeploy` and are marked so: they have never been seen from any endpoint."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.codedeploy :as cd]
            [deliberator.deploy :as d])
  (:import (java.time Instant)))

(def fx (edn/read-string (slurp (io/resource "deliberator/fixtures/codedeploy_emulator.edn"))))

(defn- err [m] (with-meta m {:deliberator.aws/error true}))

(defn- served
  "A `call` that answers from the fixture, as the emulator did."
  [& {:keys [events]}]
  (fn [svc op req & _]
    (case [svc op]
      [:codedeploy :ListApplications] (:list-applications fx)
      [:codedeploy :ListDeploymentGroups] (get-in fx [:list-groups (:applicationName req)])
      [:codedeploy :GetDeploymentGroup] (get-in fx [:get-group (str (:applicationName req) "/" (:deploymentGroupName req))])
      [:codedeploy :ListDeployments] (get-in fx [:list-deployments (str (:applicationName req) "/" (:deploymentGroupName req))])
      [:codedeploy :BatchGetDeployments] {:deploymentsInfo (filterv #(some #{(:deploymentId %)} (:deploymentIds req)) (get-in fx [:batch-get-deployments :deploymentsInfo]))}
      [:codedeploy :GetDeployment] (get-in fx [:get-deployment (:deploymentId req)])
      [:codedeploy :ListDeploymentTargets] (get-in fx [:list-targets (:deploymentId req)])
      [:codedeploy :BatchGetDeploymentTargets] (get-in fx [:batch-get-targets (:deploymentId req)])
      [:lambda :GetFunctionConfiguration] (:hook-config fx)
      [:lambda :GetAlias] (:get-alias fx)
      [:logs :FilterLogEvents] {:events (filterv #(<= (:startTime req 0) (:timestamp %) (:endTime req Long/MAX_VALUE)) (or events (:events (:hook-log-events fx))))}
      (err {:code "NotServed" :message (str svc " " op)}))))

(defn- row-of [id] (let [{:keys [row]} (cd/get-deployment! (served) id)] row))

(deftest the-three-deployments-become-rows
  (let [ecs (row-of "d-803973498") ok (row-of "d-86DA1FAC1") bad (row-of "d-8B094564A")]
    (testing "ECS: the appspec names the task definition"
      (is (= {:status "Succeeded" :rollout-state "COMPLETED" :platform "ECS" :application "acme" :group "billing"} (select-keys ecs [:status :rollout-state :platform :application :group])))
      (is (str/ends-with? (get-in ecs [:appspec :task-definition]) ":2"))
      (is (= "billing" (get-in ecs [:appspec :container]))))
    (testing "Lambda: the alias and the two versions come from the appspec, with the hook"
      (is (= {:function "acme-maintenance-down" :alias "live" :revision-before 1 :revision-after 2} (select-keys ok [:function :alias :revision-before :revision-after])))
      (is (= [{:event "BeforeAllowTraffic" :function "acme-codedeploy-hook"}] (get-in ok [:appspec :hooks]))))
    (testing "a failed deployment carries its error code and message"
      (is (= "FAILED" (:rollout-state bad)))
      (is (= "HOOK_EXECUTION_FAILURE" (:error-code bad)))
      (is (= "Hook function reported failure: BeforeAllowTraffic" (:rollout-reason bad)))
      (is (= [2 3] ((juxt :revision-before :revision-after) bad)))
      (is (= "run 1 FAIL_HOOK" (:description bad))))
    (is (instance? Instant (:started ok)))))

(deftest the-appspec-is-read_as_json_or_yaml_and_what_is_not_there_is_said
  (let [yaml "version: 0.0\nResources:\n  - myFn:\n      Type: AWS::Lambda::Function\n      Properties:\n        Name: f\n        Alias: live\n        CurrentVersion: 4\n        TargetVersion: 5\nHooks:\n  - BeforeAllowTraffic: \"arn:aws:lambda:eu-central-1:1:function:hk:live\"\n  - AfterAllowTraffic: other\n"
        r (cd/parse-appspec {:revisionType "String" :string {:content yaml}})]
    (is (= {:function "f" :alias "live" :current "4" :target "5"} (select-keys r [:function :alias :current :target])))
    (is (= [{:event "BeforeAllowTraffic" :function "hk"} {:event "AfterAllowTraffic" :function "other"}] (:hooks r))))
  (is (str/includes? (:note (cd/parse-appspec {:revisionType "S3" :s3Location {:bucket "b"}})) "revision type S3"))
  (is (str/includes? (:note (cd/parse-appspec {:appSpecContent {:content "{\"version\": \"0.0\"}"}})) "names no ECS service")))

(deftest the-deployments-of-an-ecs-service-are-those-of-the-group-that-names-it
  (let [{:keys [rows]} (cd/deployments! (served) (fn [gi] (some #(= "acme-billing" (:serviceName %)) (:ecsServices gi))) (constantly true))]
    (is (= ["d-803973498"] (map :id rows)))))

(deftest the-deployments-of-a-lambda-are-those-whose-appspec-moves-an-alias-of-it_newest_first
  (let [accept #(and (= "Lambda" (:platform %)) (= "acme-maintenance-down" (:function %)))
        {:keys [rows errors]} (cd/deployments! (served) #(empty? (:ecsServices %)) accept)]
    (is (= ["d-8B094564A" "d-86DA1FAC1"] (map :id rows)) "the emulator lists oldest first; the view sorts")
    (is (empty? errors))
    (is (empty? (:rows (cd/deployments! (served) #(empty? (:ecsServices %)) #(= "other-fn" (:function %))))))))

(deftest a-service-that-is-not-there-is-absent_not_an_empty_list
  (let [call (fn [svc op _ & _] (when (= [:codedeploy :ListApplications] [svc op])
                                  (err {:code "SubscriptionRequiredException" :message "The AWS Access Key Id needs a subscription for the service"})))]
    (is (= "SubscriptionRequiredException" (:absent (cd/deployments! call (constantly true) (constantly true)))))
    (is (empty? (:errors (cd/deployments! call (constantly true) (constantly true)))))
    (let [r (cd/deployments! (fn [& _] (err {:code "AccessDeniedException" :message "no"})) (constantly true) (constantly true))]
      (is (nil? (:absent r)) "a denial is an error, not an absence")
      (is (= "AccessDeniedException" (:code (first (:errors r))))))))

(deftest targets-with-their-lifecycle-events
  (testing "the Lambda target of the failed deployment: the hook event Failed with its diagnostics"
    (let [{:keys [targets listed error]} (cd/targets! (served) "d-8B094564A" nil)
          t (first targets) e (first (:events t))]
      (is (nil? error))
      (is (= ["acme-maintenance-down:live"] listed))
      (is (= {:type "LambdaFunction" :id "acme-maintenance-down:live" :status "Failed"} (select-keys t [:type :id :status])))
      (is (= ["BeforeAllowTraffic"] (map :name (:events t))) "AllowTraffic never ran")
      (is (= {:status "Failed" :error-code "UnknownError"} (select-keys e [:status :error-code])))
      (is (str/includes? (:message e) "acme-codedeploy-hook reported that lifecycle event BeforeAllowTraffic failed"))
      (is (nil? (:log-tail e)) "the emulator gives no logTail")))
  (testing "the ECS target: Install and AllowTraffic, a task set that runs nothing"
    (let [t (first (:targets (cd/targets! (served) "d-803973498" nil)))]
      (is (= ["Install" "AllowTraffic"] (map :name (:events t))))
      (is (= {:status "ACTIVE" :desired 1 :running 0 :traffic-weight 0.0} (select-keys (first (:task-sets t)) [:status :desired :running :traffic-weight])))))
  (testing "no target listed is `the endpoint gave none`, not an empty success"
    (let [r (cd/targets! (fn [svc op _ & _] (when (= :ListDeploymentTargets op) {:targetIds []})) "d-X" nil)
          txt (str/join "\n" (cd/target-lines r))]
      (is (empty? (:listed r)))
      (is (str/includes? txt "the endpoint gave none"))))
  (testing "a refused call is said as the call and its error"
    (let [r (cd/targets! (fn [& _] (err {:code "AccessDeniedException" :message "not allowed"})) "d-X" nil)]
      (is (= "ListDeploymentTargets" (get-in r [:error :op])))
      (is (str/includes? (str/join "\n" (cd/target-lines r)) "not read: ListDeploymentTargets AccessDeniedException not allowed")))))

(deftest the-hook-lines-of-a-deployment-are-the-ones-inside-its-window_and_not_another_deployments
  (let [ok (row-of "d-86DA1FAC1") bad (row-of "d-8B094564A")
        lines (fn [row] (cd/hooks! (served) (get-in row [:appspec :hooks]) (:id row) (.minusSeconds ^Instant (:started row) 5) (.plusSeconds ^Instant (:finished row) 30) "t"))
        okl (lines ok) badl (lines bad)]
    (testing "the succeeded one: its three lines, the probe answer among them, each saying which function"
      (is (some #(str/includes? (:text %) "the target version answered the probe") (:entries okl)))
      (is (every? #(= "acme-codedeploy-hook" (:function %)) (:entries okl)))
      (is (every? #(str/includes? (:text %) "d-86DA1FAC1") (:entries okl))))
    (testing "the failed one: the hook's own words about failing on purpose, and nothing of the other deployment"
      (is (some #(str/includes? (:text %) "failing on purpose") (:entries badl)))
      (is (not-any? #(str/includes? (:text %) "d-86DA1FAC1") (:entries badl))))
    (testing "a line naming another deployment inside the window is dropped and counted"
      (let [all (:events (:hook-log-events fx))
            r (cd/hook-lines all "acme-codedeploy-hook" "d-8B094564A")]
        (is (pos? (:dropped r)))
        (is (not-any? #(str/includes? (:text %) "d-86DA1FAC1") (:entries r)))))
    (testing "a hook whose log group is not there says so"
      (let [r (cd/hooks! (fn [svc op _ & _] (case [svc op] [:lambda :GetFunctionConfiguration] (err {:code "ResourceNotFoundException" :message "nf"})
                                                                [:logs :FilterLogEvents] (err {:code "ResourceNotFoundException" :message "The specified log group does not exist"})))
                         [{:event "BeforeAllowTraffic" :function "gone"}] "d-X" (Instant/now) (Instant/now) "t")]
        (is (empty? (:entries r)))
        (is (str/includes? (first (:notes r)) "/aws/lambda/gone"))))))

(defn- lambda-d [id & {:keys [targets]}]
  (let [call (served)
        row (row-of id)]
    {:env "local" :kind :lambda :cluster "lambda" :service "acme-maintenance-down" :row row :source :codedeploy :notes [] :events [] :event-count 0
     :started [] :replaced [] :log {:entries []} :fetched-at (Instant/parse "2026-11-16T06:00:00Z")
     :cd {:targets (cd/targets! call id nil)
          :hook (cd/hooks! call (get-in row [:appspec :hooks]) id (.minusSeconds ^Instant (:started row) 5) (.plusSeconds ^Instant (or (:finished row) (Instant/now)) 30) "t")
          :hooks-named? true :alias-now {:version "2" :at (Instant/parse "2026-11-16T06:00:00Z")}}}))

(deftest the-text-of-the-failed-lambda-deployment-says_why
  (let [txt (d/render-show (lambda-d "d-8B094564A"))]
    (is (str/includes? txt "HOOK_EXECUTION_FAILURE"))
    (is (str/includes? txt "Hook function reported failure: BeforeAllowTraffic"))
    (is (str/includes? txt "hook         BeforeAllowTraffic -> acme-codedeploy-hook"))
    (is (str/includes? txt "alias        live of acme-maintenance-down   version 2 -> 3"))
    (is (str/includes? txt "alias now    live -> version 2"))
    (is (str/includes? txt "lifecycle BeforeAllowTraffic"))
    (is (str/includes? txt "UnknownError: Lambda function acme-codedeploy-hook reported that lifecycle event BeforeAllowTraffic failed"))
    (is (re-find #"\[hook acme-codedeploy-hook\] .*failing on purpose" txt))
    (is (str/includes? txt "rollback     none (the endpoint gave no rollbackInfo)"))
    (is (str/includes? txt "has only been checked against the local emulator, not against real CodeDeploy"))
    (is (not (str/includes? txt "== tasks")) "a Lambda deployment has no tasks")))

(deftest the-text-of-the-succeeded-lambda-deployment-shows-the-alias-move-and-the-probe
  (let [txt (d/render-show (lambda-d "d-86DA1FAC1"))]
    (is (str/includes? txt "Succeeded   rollout COMPLETED"))
    (is (str/includes? txt "version 1 -> 2"))
    (is (re-find #"\[hook acme-codedeploy-hook\] .*answer=\{\"probe\":\"ok\"" txt))
    (is (re-find #"\[hook acme-codedeploy-hook\] .*the target version answered the probe" txt))
    (is (str/includes? txt "lifecycle AllowTraffic"))
    (is (not (str/includes? txt "HOOK_EXECUTION_FAILURE")))))

(deftest the-text-of-the-ecs-deployment-says-what-the-endpoint-gave
  (let [row (assoc (row-of "d-803973498") :revision-before 1 :revision-after 2 :image-before "billing:local" :image-after "billing:b508c373ed6d"
                   :git-sha "b508c373ed6d8bcbdf9902ac2a6882e03e14aa65" :commit-source "task definition tag git-sha" :run-id "1")
        call (served)
        txt (d/render-show {:env "local" :cluster "acme" :service "acme-billing" :row row :source :service-deployments :notes [] :events [] :event-count 0
                            :started [] :replaced [] :log {:entries []} :fetched-at (Instant/parse "2026-11-16T06:00:00Z")
                            :cd {:targets (cd/targets! call "d-803973498" nil) :hook nil :hooks-named? false}})]
    (is (str/includes? txt "Succeeded   rollout COMPLETED"))
    (is (str/includes? txt "revision     1 -> 2"))
    (is (str/includes? txt "image        billing:local -> billing:b508c373ed6d"))
    (is (str/includes? txt "acme:acme-billing   ECSTarget   Succeeded"))
    (is (str/includes? txt "lifecycle Install"))
    (is (str/includes? txt "task set ecs-svc/53a00868dc2541c38e8ce03b91a363d7  ACTIVE  desired 1 pending 0 running 0  traffic 0.0"))
    (is (str/includes? txt "hook         the appspec names none"))
    (is (not (str/includes? txt "== hook log")) "no hook named: no section of nothing")))

;; --- shapes from the descriptors only: the emulator does not answer them ---------------------------------------------

(deftest shapes_the_emulator_does_not_answer_are_read_as_the_descriptors_have_them
  (testing "rollbackInfo and an automatic rollback deployment"
    (let [row (cd/deployment-row {:deploymentId "d-AAAAAAAAA" :status "Failed" :creator "user" :computePlatform "Lambda" :applicationName "a" :deploymentGroupName "g"
                                  :createTime #inst "2026-11-16T05:00:00Z" :errorInformation {:code "ALARM_ACTIVE" :message "an alarm fired"}
                                  :rollbackInfo {:rollbackDeploymentId "d-BBBBBBBBB" :rollbackMessage "Deployment rolled back by an alarm"}
                                  :autoRollbackConfiguration {:enabled true :events ["DEPLOYMENT_FAILURE" "DEPLOYMENT_STOP_ON_ALARM"]}})
          txt (str/join "\n" (cd/rollback-lines row))]
      (is (str/includes? txt "rolled back by d-BBBBBBBBB"))
      (is (str/includes? txt "why: Deployment rolled back by an alarm")))
    (let [row (cd/deployment-row {:deploymentId "d-BBBBBBBBB" :status "Succeeded" :creator "codeDeployRollback" :createTime #inst "2026-11-16T05:01:00Z"
                                  :rollbackInfo {:rollbackTriggeringDeploymentId "d-AAAAAAAAA"}})]
      (is (str/includes? (str/join "\n" (cd/rollback-lines row)) "is the rollback of d-AAAAAAAAA"))))
  (testing "diagnostics with a log tail, and lambdaFunctionInfo (the weight of a canary)"
    (let [t (cd/target {:deploymentTargetType "LambdaFunction"
                        :lambdaTarget {:targetId "f:live" :status "InProgress"
                                       :lambdaFunctionInfo {:functionName "f" :functionAlias "live" :currentVersion "1" :targetVersion "2" :targetVersionWeight 10.0}
                                       :lifecycleEvents [{:lifecycleEventName "BeforeAllowTraffic" :status "Failed" :startTime #inst "2026-11-16T05:00:00Z" :endTime #inst "2026-11-16T05:00:05Z"
                                                          :diagnostics {:errorCode "ScriptFailed" :scriptName "hook.sh" :message "exit 1" :logTail "line one\nline two"}}]}})
          txt (str/join "\n" (cd/target-lines {:targets [t] :listed ["f:live"]}))]
      (is (= {:function "f" :alias "live" :current "1" :target "2" :weight 10.0} (:lambda t)))
      (is (str/includes? txt "version 1 -> 2  weight 10.0   (lambdaFunctionInfo)"))
      (is (str/includes? txt "ScriptFailed in hook.sh: exit 1"))
      (is (str/includes? txt "| line two"))
      (is (str/includes? txt "(5 s)")))))

(deftest status-stands-for-a-rollout-state
  (is (= {"Succeeded" "COMPLETED" "Failed" "FAILED" "Stopped" "FAILED" "InProgress" "IN_PROGRESS" "Created" "IN_PROGRESS" "Baking" "IN_PROGRESS"}
         (into {} (map (juxt identity cd/rollout-of-status)) ["Succeeded" "Failed" "Stopped" "InProgress" "Created" "Baking"])))
  (is (nil? (cd/rollout-of-status "SomethingNew"))))
