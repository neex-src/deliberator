(ns deliberator.tfdrift-test
  (:require [clojure.test :refer [deftest is testing]]
            [deliberator.tfdrift :as tf]))

(def doc
  {:values {:root_module
            {:resources [{:address "aws_ecs_cluster.estate" :type "aws_ecs_cluster" :values {:name "estate" :arn "arn:aws:ecs:eu-central-1:0:cluster/estate"}}
                         {:address "random_password.x" :type "random_password" :values {:id "none"}}
                         {:address "aws_ssm_parameter.other" :type "aws_ssm_parameter" :values {:name "/e/other" :arn "arn:aws:ssm:eu-west-1:0:parameter/e/other"}}]
             :child_modules [{:resources [{:address "module.m.aws_sqs_queue.q" :type "aws_sqs_queue" :values {:name "q1" :arn "arn:aws:sqs:eu-central-1:0:q1"}}
                                          {:address "module.m.aws_sqs_queue.gone" :type "aws_sqs_queue" :values {:name "gone" :arn "arn:aws:sqs:eu-central-1:0:gone"}}]}]}}})

(deftest declared-keeps-known-kinds-of-the-region
  (is (= #{["ecs" "estate"] ["sqs" "q1"] ["sqs" "gone"]} (set (map (juxt :kind :name) (tf/declared doc "eu-central-1")))))
  (is (= #{["ssm" "/e/other"]} (set (map (juxt :kind :name) (filter #(= "ssm" (:kind %)) (tf/declared doc "eu-west-1")))))))

(deftest compare-reports-missing-and-undeclared
  (let [d (tf/declared doc "eu-central-1")
        r (tf/compare-with d {"sqs" [{:name "q1"} {:name "hand-made"}] "ecs" [{:name "estate"}] "s3" [{:name "unrelated"}]
                              "eventbridge" [{:name "default"}]})]
    (is (= [{:kind "sqs" :name "gone" :address "module.m.aws_sqs_queue.gone"}] (:missing r)))
    (is (= [{:kind "sqs" :name "hand-made"}] (:undeclared r)))
    (is (= 2 (:matching r)))
    (is (= 3 (:declared r)))))
