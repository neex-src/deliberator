(ns deliberator.tfrefresh-test
  "B39: `terraform.drift` with `refresh` runs `terraform plan -refresh-only` itself, with the credentials of the
  environment's profile, so a value changed by hand shows as `changed` without anyone preparing a plan. Against the test Floci
  (pass B; SKIP when it is down): a root with one queue is applied with the remote-shaped environment's credentials, the queue's
  visibility timeout is changed with the API, and the drift of that environment names the attribute. The real account's run is
  `a sandbox AWS account/runs/deliberator-aws-20261028-ide-real.md`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.remote-util :as remote-util]
            [deliberator.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(def ^:private main-tf
  (str "terraform {\n  required_providers {\n    aws = { source = \"hashicorp/aws\", version = \"~> 6.0\" }\n  }\n}\n"
       "provider \"aws\" {\n  region = \"eu-central-1\"\n  skip_credentials_validation = true\n  skip_requesting_account_id = true\n"
       "  skip_metadata_api_check = true\n}\n"
       "resource \"aws_sqs_queue\" \"q\" {\n  name = \"dlbtest-b39-q\"\n  visibility_timeout_seconds = 60\n}\n"))

(deftest a-refresh-needs-a-remote-environment
  (let [home (str (Files/createTempDirectory "dlb-b39" (make-array FileAttribute 0)))]
    (binding [store/*home* (java.nio.file.Path/of home (make-array String 0))]
      (env/save! "w" env/default-local)
      (let [root (doto (io/file home "root") .mkdirs)
            _ (spit (io/file root "main.tf") main-tf)
            e (try (call "terraform.drift" {:ws "w" :env "local" :dir (str root) :refresh true}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :tf/refresh-remote-only (:type (ex-data e))) (some-> e ex-message))
        (is (str/includes? (str (:hint (ex-data e))) "terraform.local"))))))

(fu/deftest-floci drift-refresh-sees-a-change-made-by-hand
  (remote-util/with-remote-at-floci [remote local]
    (let [root (doto (io/file (str (Files/createTempDirectory "dlb-b39r" (make-array FileAttribute 0)))) (.deleteOnExit))
          _ (spit (io/file root "main.tf") main-tf)
          penv (merge (into {} (System/getenv)) (env/credentials remote)
                      {"AWS_ENDPOINT_URL" fu/url "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null"
                       "TF_IN_AUTOMATION" "1"})
          tf (fn [& args] (shell/with-sh-dir (str root) (shell/with-sh-env penv (apply shell/sh "terraform" (concat args ["-no-color"])))))
          init (tf "init" "-input=false")
          apply1 (when (zero? (:exit init)) (tf "apply" "-input=false" "-auto-approve"))]
      (try
        (is (zero? (:exit init)) (:err init))
        (is (zero? (:exit apply1)) (str (:out apply1) (:err apply1)))
        (let [url (:QueueUrl (aws/invoke! {:ws "default"} local :sqs :GetQueueUrl {:QueueName "dlbtest-b39-q"}))
              _ (aws/invoke! {:ws "default"} local :sqs :SetQueueAttributes {:QueueUrl url :Attributes {"VisibilityTimeout" "45"}})
              without (call "terraform.drift" {:ws "default" :env "dlbremote" :dir (str root)})
              with (call "terraform.drift" {:ws "default" :env "dlbremote" :dir (str root) :refresh true})]
          (testing "without a refresh the state says nothing about the value"
            (is (nil? (:changed without))))
          (testing "with it the attribute that was changed by hand is named, and never its value"
            (is (= 1 (count (:changed with))) (pr-str (dissoc with :errors)))
            (is (= ["aws_sqs_queue.q" ["update"]] ((juxt :address :actions) (first (:changed with)))))
            (is (some #{"visibility_timeout_seconds"} (:attributes (first (:changed with)))))
            (is (= 1 (:declared with)))
            (is (pos? (get-in with [:refreshed :lines])))
            (is (not (str/includes? (pr-str (:changed with)) "45")) "no value in the answer"))
          (testing "the transcript is persisted raw and the plan file (the state's secrets) is not kept"
            (is (some? (store/raw-get "default" (get-in with [:refreshed :sha]))))
            (is (empty? (filter #(str/ends-with? (.getName ^java.io.File %) ".tfplan") (file-seq (.toFile (store/path "default" "terraform"))))))))
        (finally
          (tf "destroy" "-input=false" "-auto-approve"))))))
