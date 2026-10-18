(ns deliberator.awscall-test
  (:require [clojure.test :refer [deftest is testing]]
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
  (testing "the table still wins"
    (is (= :read (ops/op-class :sqs "ReceiveMessage")))))
