(ns deliberator.tfdrift-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(def secret-doc
  {:format_version "1.0" :terraform_version "1.15.9"
   :variables {:db_password {:value "var-secret-1"}}
   :values {:outputs {:task_definitions {:sensitive true :value "out-secret-2"}}
            :root_module
            {:resources [{:address "random_password.api_key" :mode "managed" :type "random_password" :name "api_key"
                          :values {:id "none" :result "pw-secret-3" :bcrypt_hash "hash-secret-4"}
                          :sensitive_values {:result true :bcrypt_hash true}}
                         {:address "aws_secretsmanager_secret_version.v" :mode "managed" :type "aws_secretsmanager_secret_version" :name "v"
                          :values {:id "arn:aws:secretsmanager:eu-central-1:0:secret:k|v1" :secret_string "string-secret-5"}}
                         {:address "aws_ssm_parameter.p" :mode "managed" :type "aws_ssm_parameter" :name "p"
                          :values {:name "/e/p" :arn "arn:aws:ssm:eu-central-1:0:parameter/e/p" :value "param-secret-6" :insecure_value "param-secret-7"}}]
             :child_modules [{:address "module.m"
                              :resources [{:address "module.m.aws_sqs_queue.q" :type "aws_sqs_queue" :name "q"
                                           :values {:name "q1" :arn "arn:aws:sqs:eu-central-1:0:q1" :tags {:owner "tag-8"}}}]}]}}})

(deftest only-the-extract-is-persisted-and-it-holds-no-secret
  (let [bytes (json/write-str (tf/extract secret-doc))]
    (doseq [n (range 1 8)]
      (is (not (str/includes? bytes (str "secret-" n))) (str "secret-" n " leaked: " bytes)))
    (is (not (str/includes? bytes "tag-8")) "tags are not on the allowlist either")
    (is (= (tf/declared secret-doc "eu-central-1") (tf/declared (tf/extract secret-doc) "eu-central-1"))
        "drift reads the extract and finds the same resources")
    (is (= #{["ssm" "/e/p"] ["sqs" "q1"]} (set (map (juxt :kind :name) (tf/declared (tf/extract secret-doc) "eu-central-1")))))
    (testing "a plan document keeps its planned_values key"
      (let [plan {:planned_values (:values secret-doc) :prior_state {:values (:values secret-doc)}}]
        (is (= (tf/declared plan nil) (tf/declared (tf/extract plan) nil)))
        (is (not (str/includes? (json/write-str (tf/extract plan)) "secret-")))))))
