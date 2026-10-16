(ns deliberator.ops-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env.ops :as ops]))

(def contract
  "Every op pieces 06/07 use, with the class the brief fixes."
  {:sqs {:read ["GetQueueUrl" "GetQueueAttributes" "ReceiveMessage"]
         :mutate ["CreateQueue" "SendMessage" "DeleteMessage" "PurgeQueue"]}
   :eventbridge {:read ["ListRules" "DescribeRule"]
            :mutate ["PutEvents" "PutRule" "PutTargets" "CreateEventBus"]}
   :lambda {:read ["GetFunction" "ListFunctions"] :mutate ["Invoke" "CreateFunction" "DeleteFunction"]}
   :dynamodb {:read ["GetItem" "Query" "Scan" "DescribeTable"]
              :mutate ["PutItem" "CreateTable" "DeleteItem" "UpdateItem"]}
   :scheduler {:read ["ListSchedules" "GetSchedule"] :mutate ["CreateSchedule" "DeleteSchedule"]}
   :ssm {:read ["GetParameter" "GetParameters" "GetParametersByPath" "DescribeParameters"]
         :mutate ["PutParameter"]}
   :secretsmanager {:read ["GetSecretValue" "DescribeSecret" "ListSecrets"]
                    :mutate ["CreateSecret" "PutSecretValue"]}
   :logs {:read ["FilterLogEvents" "DescribeLogGroups" "DescribeLogStreams" "StartQuery" "GetQueryResults"]
          :mutate ["CreateLogGroup" "CreateLogStream" "PutLogEvents"]}
   :sts {:read ["GetCallerIdentity"] :mutate []}
   :ecs {:read ["DescribeServices" "DescribeTaskDefinition" "ListServices"] :mutate ["UpdateService"]}
   :codedeploy {:read ["ListDeployments" "GetDeployment"] :mutate ["CreateDeployment"]}})

(deftest table-covers-the-contract
  (doseq [[svc {:keys [read mutate]}] contract
          [cls ops] [[:read read] [:mutate mutate]]
          op ops]
    (testing (str svc " " op)
      (is (ops/known? svc op))
      (is (= cls (ops/op-class svc op)))
      (is (= cls (ops/op-class svc (keyword op)))))))

(deftest unknown-is-mutate
  (is (= :mutate (ops/op-class :sqs "SomethingNew")))
  (is (= :mutate (ops/op-class :nosuchservice "GetThing")))
  (is (not (ops/known? :sqs "SomethingNew"))))

(deftest alias-and-names
  (is (= :mutate (ops/op-class :events "PutEvents")))
  (is (= :read (ops/op-class :events :ListRules)))
  (is (= :eventbridge (ops/api-name :events))))

(deftest table-follows-the-naming-rule
  (let [rule #(if (re-find #"^(Get|List|Describe|Receive|Filter|Query|Scan|BatchGet)" %) :read :mutate)]
    (doseq [[s o c] (ops/all-ops)]
      (is (or (contains? ops/exceptions [(name s) o]) (= c (rule o)))
          (str s " " o " is " c " but its name says " (rule o))))))
