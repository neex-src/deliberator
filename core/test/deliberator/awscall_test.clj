(ns deliberator.awscall-test
  (:require [clojure.test :refer [deftest is testing]]
            [deliberator.awsmodel]
            [deliberator.env.ops :as ops]
            [deliberator.steps.awscall :as awscall]))

(deftest verbs-cover-every-operation-of-the-services
  (testing "service.Operation words for services outside the purpose-built kinds"
    (is (contains? awscall/verbs "ssm.GetParameter"))
    (is (contains? awscall/verbs "s3.ListBuckets"))
    (is (contains? awscall/verbs "sns.Publish"))
    (is (contains? awscall/verbs "states.StartExecution"))
    (is (> (count awscall/verbs) 1000))))

(deftest unknown-operations-are-mutations-unless-they-only-read
  (is (= :read (ops/op-class :s3 "ListBuckets")))
  (is (= :read (ops/op-class :iam "GetUser")))
  (is (= :mutate (ops/op-class :sns "Publish")))
  (is (= :mutate (ops/op-class :s3 "DeleteBucket")))
  (testing "the read prefixes count only for the services the generic kind added"
    (is (= :mutate (ops/op-class :sqs "GetThing")))
    (is (= :mutate (ops/op-class :nosuchservice "GetThing"))))
  (testing "the table still wins"
    (is (= :read (ops/op-class :sqs "ReceiveMessage")))))

(deftest operation-input-shapes-for-the-editor
  (let [{:keys [input class]} (deliberator.awsmodel/input "ssm" "PutParameter")]
    (is (= "mutate" class))
    (is (= ["Name" "Value"] (take 2 (:order input))))
    (is (= ["Standard" "Advanced" "Intelligent-Tiering"] (get-in input [:members "Tier" :enum])))
    (is (= "list" (get-in input [:members "Tags" :type]))))
  (is (nil? (deliberator.awsmodel/input "ssm" "NoSuchThing")))
  (testing "a recursive shape is cut, not followed forever"
    (is (some? (deliberator.awsmodel/input "states" "CreateStateMachine")))))
