(ns deliberator.tfroot-test
  "What a root says about itself (`tfroot/analyse`), the override file made from it (M6.1) and the graph of several roots
  (M6.3), all from the .tf text of temp roots and of the estate's own."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.store :as store]
            [deliberator.tfroot :as tfroot])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir [] (.toFile (Files/createTempDirectory "dlb-tfroot" (make-array FileAttribute 0))))

(defn- root!
  "Makes directory `name` under `base` with the given `file content` pairs; returns its path."
  [base name & files]
  (let [d (io/file base name)]
    (.mkdirs d)
    (doseq [[f c] (partition 2 files)] (spit (io/file d f) c))
    (str d)))

(def estate (.getCanonicalPath (io/file "../estate/terraform")))

(deftest endpoint-keys
  (is (= ["sqs" "dynamodb" "events" "logs" "ecs" "iam" "lambda" "ec2" "apigatewayv2" "stepfunctions" "sts" "rds" "s3" "ssm" "elbv2"]
         (map tfroot/endpoint-key
              ["aws_sqs_queue" "aws_dynamodb_table" "aws_cloudwatch_event_rule" "aws_cloudwatch_log_group" "aws_ecs_service"
               "aws_iam_role_policy" "aws_lambda_function" "aws_vpc" "aws_apigatewayv2_api" "aws_sfn_state_machine"
               "aws_caller_identity" "aws_db_instance" "aws_s3_bucket_policy" "aws_ssm_parameter" "aws_lb_listener"])))
  (is (nil? (tfroot/endpoint-key "aws_something_new")))
  (is (nil? (tfroot/endpoint-key "aws_iam_policy_document")) "never calls AWS")
  (is (nil? (tfroot/endpoint-key "random_password"))))

(deftest the-estate-roots
  (let [aws (tfroot/analyse (str estate "/aws"))
        floci (tfroot/analyse (str estate "/floci"))]
    (testing "the real-AWS root: one provider, none of its own endpoints, so the override is needed"
      (is (= 1 (count (:providers aws))))
      (is (false? (:emulator? aws)))
      (is (= #{} (:unmapped aws)))
      (is (every? (:services aws) ["sqs" "dynamodb" "lambda" "iam" "events" "logs" "ssm" "scheduler" "apigatewayv2" "ecs" "ec2"])))
    (testing "the Floci root points its own providers (and its alias) at the emulator: exempt"
      (is (= [nil "local2"] (map :alias (:providers floci))))
      (is (true? (:emulator? floci))))
    (testing "a module of the root does not declare a provider"
      (is (= [] (:module-providers aws))))))

(deftest override-document
  (let [a (tfroot/analyse (str estate "/aws"))
        doc (tfroot/override-doc a {:endpoint "http://localhost:4566" :region "eu-central-1" :access-key "AK" :secret-key "SK"
                                    :state-path "/ws/terraform/aws-1/terraform.tfstate"})
        p (first (get-in doc ["provider" "aws"]))]
    (is (str/starts-with? (get doc "//") tfroot/generated-marker))
    (is (= {"local" {"path" "/ws/terraform/aws-1/terraform.tfstate"}} (get-in doc ["terraform" "backend"])))
    (is (= ["AK" "SK" true true true true true]
           ((juxt #(get % "access_key") #(get % "secret_key") #(get % "skip_credentials_validation") #(get % "skip_metadata_api_check")
                  #(get % "skip_requesting_account_id") #(get % "skip_region_validation") #(get % "s3_use_path_style")) p)))
    (is (not (contains? p "region")) "the root's own region is kept")
    (is (= #{"http://localhost:4566"} (set (vals (first (get p "endpoints"))))))
    (is (contains? (first (get p "endpoints")) "sts") "sts always")
    (is (= [] (tfroot/coverage-problems a doc)))))

(deftest override-nulls-credentials-and-adds-a-missing-region
  (let [base (tmp-dir)
        r (root! base "r" "main.tf" "provider \"aws\" {\n  profile = \"prod\"\n  allowed_account_ids = [\"1\"]\n}\nprovider \"aws\" {\n  alias = \"b\"\n  region = \"eu-west-1\"\n}\nresource \"aws_sqs_queue\" \"q\" { name = \"q\" }\n")
        a (tfroot/analyse r)
        doc (tfroot/override-doc a {:endpoint "http://x:1" :region "eu-central-1" :access-key "t" :secret-key "t" :state-path "/s"})
        [p1 p2] (get-in doc ["provider" "aws"])]
    (is (= [nil "b"] (map #(get % "alias") [p1 p2])))
    (is (contains? p1 "profile") "present, and null")
    (is (nil? (get p1 "profile")))
    (is (contains? p1 "allowed_account_ids"))
    (is (= "eu-central-1" (get p1 "region")) "a block without a region gets the environment's")
    (is (not (contains? p2 "region")))
    (is (not (contains? p2 "profile")))))

(deftest coverage-refuses-what-an-override-cannot-reach
  (let [base (tmp-dir)
        doc-of #(tfroot/override-doc (tfroot/analyse %) {:endpoint "http://x:1" :region "r" :access-key "t" :secret-key "t" :state-path "/s"})]
    (testing "AWS resources without any provider block: nothing to override"
      (let [r (root! base "noprov" "main.tf" "resource \"aws_sqs_queue\" \"q\" { name = \"q\" }\n")]
        (is (some #(str/includes? % "declares no provider") (tfroot/coverage-problems (tfroot/analyse r) (doc-of r))))))
    (testing "a provider inside a module is out of reach"
      (root! base "m" "p.tf" "provider \"aws\" { region = \"x\" }\n")
      (let [r (root! base "withmod" "main.tf" "provider \"aws\" { region = \"x\" }\nmodule \"m\" { source = \"../m\" }\n")]
        (is (some #(str/includes? % "declares its own provider") (tfroot/coverage-problems (tfroot/analyse r) (doc-of r))))))
    (testing "a hand-written override that misses an alias, and one that points at AWS"
      (let [r (root! base "alias" "main.tf" "provider \"aws\" { region = \"x\" }\nprovider \"aws\" {\n  alias = \"b\"\n  region = \"y\"\n}\n")
            a (tfroot/analyse r)]
        (is (= 1 (count (tfroot/coverage-problems a {"provider" {"aws" [{"endpoints" [{"s3" "http://localhost:1"}]}]}}))))
        (is (some #(str/includes? % "amazonaws.com")
                  (tfroot/coverage-problems a {"provider" {"aws" [{"endpoints" [{"s3" "https://s3.amazonaws.com"}] } {"alias" "b"}]}})))))
    (testing "no AWS at all: nothing to refuse"
      (let [r (root! base "none" "main.tf" "resource \"random_password\" \"p\" { length = 8 }\n")]
        (is (= [] (tfroot/coverage-problems (tfroot/analyse r) (doc-of r))))))))

(def ^:private role-root
  "resource \"aws_iam_role\" \"shared\" {\n  name = \"estate-shared\"\n}\n")

(deftest graph-of-the-estate-roots
  (let [g (tfroot/graph [(str estate "/aws") (str estate "/iam")])]
    (is (= [(str estate "/iam") (str estate "/aws")] (:order g)) "the IAM root first, although it was listed second")
    (is (= [{:from "iam" :to "aws" :via "data" :ref "aws_iam_role estate-shared"}]
           (map #(select-keys % [:from :to :via :ref]) (:edges g))))
    (let [e (first (:edges g))]
      (is (str/ends-with? (:file e) "aws/stack.tf"))
      (is (pos? (:line e))))
    (is (= [{:name "aws" :dir (str estate "/aws")} {:name "iam" :dir (str estate "/iam")}] (:roots g)))
    (is (= [] (:unresolved g)))
    (testing "no edge, no reordering"
      (is (= [(str estate "/aws") (str estate "/floci")] (:order (tfroot/graph [(str estate "/aws") (str estate "/floci")])))))))

(deftest graph-reads-remote-state-by-s3-key-and-local-path
  (let [base (tmp-dir)
        net (root! base "net" "main.tf" "terraform {\n  backend \"s3\" {\n    bucket = \"tf\"\n    key = \"net/terraform.tfstate\"\n  }\n}\n")
        app (root! base "app" "main.tf" "data \"terraform_remote_state\" \"net\" {\n  backend = \"s3\"\n  config = {\n    bucket = \"tf\"\n    key = \"net/terraform.tfstate\"\n  }\n}\n")
        db (root! base "db" "main.tf" "data \"terraform_remote_state\" \"net\" {\n  backend = \"local\"\n  config = { path = \"../net2/terraform.tfstate\" }\n}\ndata \"terraform_remote_state\" \"app\" {\n  backend = \"s3\"\n  config = { bucket = \"tf\", key = \"app/terraform.tfstate\" }\n}\n")
        _ (root! base "net2" "main.tf" "# default local state\n")
        net2 (str base "/net2")
        app2 (root! base "app2" "main.tf" "terraform {\n  backend \"s3\" {\n    bucket = \"tf\"\n    key = \"app/terraform.tfstate\"\n  }\n}\n")
        g (tfroot/graph [db app net net2 app2])]
    (testing "the order puts what is read first, otherwise keeps the order given"
      (is (= ["net" "app" "net2" "app2" "db"] (map #(.getName (io/file %)) (:order g)))))
    (is (= #{["net" "app" "remote_state"] ["net2" "db" "remote_state"] ["app2" "db" "remote_state"]}
           (set (map (juxt :from :to :via) (:edges g)))))
    (testing "a remote state that points at no given root is listed"
      (let [g2 (tfroot/graph [db app net])]
        (is (= ["data.terraform_remote_state.net" "data.terraform_remote_state.app"] (map :ref (filter #(= "db" (:root %)) (:unresolved g2)))))))
    (testing "remote-state-targets: the local state of the root a block reads"
      (binding [store/*home* (str (tmp-dir))]
        (is (= {"net" (tfroot/state-path "w" net)} (tfroot/remote-state-targets "w" app [app net])))
        (is (= {} (tfroot/remote-state-targets "w" net [app net])))))))

(deftest graph-cycles-and-duplicates
  (let [base (tmp-dir)
        a (root! base "a" "main.tf" "terraform {\n  backend \"s3\" {\n    key = \"a\"\n  }\n}\ndata \"terraform_remote_state\" \"b\" {\n  backend = \"s3\"\n  config = { key = \"b\" }\n}\n")
        b (root! base "b" "main.tf" "terraform {\n  backend \"s3\" {\n    key = \"b\"\n  }\n}\ndata \"terraform_remote_state\" \"a\" {\n  backend = \"s3\"\n  config = { key = \"a\" }\n}\n")]
    (let [e (try (tfroot/graph [a b]) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :tf/cycle (:type (ex-data e))))
      (is (= #{"a" "b"} (set (:cycle (ex-data e))))))
    (is (= :tf/bad-roots (:type (ex-data (try (tfroot/graph [a a]) (catch clojure.lang.ExceptionInfo e e))))))))
