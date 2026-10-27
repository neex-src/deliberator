(ns deliberator.tfops-test
  "The Terraform operations that need no emulator: `terraform.graph`, `terraform.locate`, `terraform.apply-all` (on roots that use
  only Terraform's built-in provider, so the override's local backend and the redirect of `terraform_remote_state` are really
  run), and the drift row -> declaration link (M6.4)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.store :as store]
            [deliberator.tfdrift :as tfdrift])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def estate (.getCanonicalPath (io/file "../estate/terraform")))

(defn- tmp-home [] (str (Files/createTempDirectory "dlb-tfops" (make-array FileAttribute 0))))

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)))

(defn- err [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest graph-and-locate-ops
  (let [g (call "terraform.graph" {:roots [(str estate "/aws") (str estate "/iam")]})]
    (is (= [(str estate "/iam") (str estate "/aws")] (:order g)))
    (is (= [["iam" "aws" "data"]] (map (juxt :from :to :via) (:edges g)))))
  (is (= "bad-args" (some-> (err #(call "terraform.graph" {:roots []})) :type name)))
  (testing "locate"
    (let [r (call "terraform.locate" {:root (str estate "/floci") :address "module.stack.aws_sqs_queue.inbox"})]
      (is (str/ends-with? (:file r) "estate/terraform/modules/stack/main.tf"))
      (is (str/includes? (nth (str/split-lines (slurp (:file r))) (dec (:line r))) "aws_sqs_queue\" \"inbox\"")))
    (is (= :tf/not-found (:type (err #(call "terraform.locate" {:root (str estate "/floci") :address "aws_sqs_queue.nope"})))))))

(deftest a-drift-row-keeps-its-address-and-locate-finds-the-declaration
  (let [doc {:values {:root_module
                      {:resources [{:address "aws_ecs_cluster.estate" :type "aws_ecs_cluster" :name "estate" :values {:name "estate"}}]
                       :child_modules [{:address "module.stack"
                                        :resources [{:address "module.stack.aws_sqs_queue.inbox" :type "aws_sqs_queue" :name "inbox"
                                                     :values {:name "estate-inbox" :arn "arn:aws:sqs:eu-central-1:000000000000:estate-inbox"}}
                                                    {:address "module.stack.aws_dynamodb_table.messages[0]" :type "aws_dynamodb_table" :name "messages"
                                                     :values {:name "estate-messages"}}]}]}}}
        c (tfdrift/compare-with (tfdrift/declared doc "eu-central-1") {"sqs" [] "dynamodb" [] "ecs" [{:name "estate"}]})]
    (is (= ["module.stack.aws_sqs_queue.inbox" "module.stack.aws_dynamodb_table.messages[0]"] (map :address (:missing c))))
    (doseq [row (:missing c)
            :let [r (call "terraform.locate" {:root (str estate "/floci") :address (:address row)})]]
      (is (pos? (:line r)) (pr-str row))
      (let [[_ ty nm] (re-find #"(aws_\w+)\.(\w+)(?:\[\d+\])?$" (:address row))]
        (is (str/includes? (nth (str/split-lines (slurp (:file r))) (dec (:line r))) (str "\"" ty "\" \"" nm "\"")) (pr-str r))))))

(defn- put! [dir f text] (.mkdirs (io/file dir)) (spit (io/file dir f) text))

(defn- state-output [state-file out]
  (get-in (json/read-str (slurp state-file)) ["outputs" out "value"]))

(deftest apply-all-runs-the-roots-in-order-with-remote-state-redirected
  (let [home (tmp-home)
        base (str home "/repo")
        net (str base "/net") app (str base "/app")]
    (put! base ".gitignore" "deliberator_override.tf.json\n.terraform/\n")
    (put! net "main.tf" (str "terraform {\n  backend \"s3\" {\n    bucket = \"tf\"\n    key = \"net/terraform.tfstate\"\n    region = \"eu-central-1\"\n  }\n}\n"
                             "resource \"terraform_data\" \"x\" {\n  input = \"net-1\"\n}\n"
                             "output \"id\" { value = terraform_data.x.output }\n"))
    (put! app "main.tf" (str "data \"terraform_remote_state\" \"net\" {\n  backend = \"s3\"\n  config = {\n    bucket = \"tf\"\n    key = \"net/terraform.tfstate\"\n    region = \"eu-central-1\"\n  }\n}\n"
                             "output \"got\" { value = data.terraform_remote_state.net.outputs.id }\n"))
    (put! base ".git-marker" "")
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (let [events (atom [])
            r (call "terraform.apply-all" {:ws "w" :roots [app net]} events)]
        (testing "net first, then app, both ok, an event per root"
          (is (true? (:ok r)) (pr-str (map #(select-keys % [:dir :ok :summary]) (:results r))))
          (is (= [net app] (:order r)))
          (is (= [net app] (map :dir (:results r))))
          (is (= [[1 2 net] [2 2 app]] (map (juxt :index :of :dir) (filter #(= "tf.root" (:event %)) @events))))
          (is (every? #(str/starts-with? (:summary %) "Apply complete!") (:results r))))
        (testing "app read net's LOCAL state, not the s3 backend it names"
          (let [st (fn [d] (str (store/path "w" "terraform") "/" (deliberator.tfroot/root-id d) "/terraform.tfstate"))]
            (is (= "net-1" (state-output (st net) "id")))
            (is (= "net-1" (state-output (st app) "got")))))
        (testing "nothing of it in the checkouts but the override files"
          (is (= #{"main.tf" "deliberator_override.tf.json"} (set (map #(.getName %) (.listFiles (io/file net))))))
          (is (= #{"main.tf" "deliberator_override.tf.json"} (set (map #(.getName %) (.listFiles (io/file app)))))))
        (testing "destroy runs in the reverse order"
          (let [events (atom [])
                d (call "terraform.apply-all" {:ws "w" :roots [net app] :action "destroy"} events)]
            (is (true? (:ok d)) (pr-str (map #(select-keys % [:dir :ok :summary]) (:results d))))
            (is (= [app net] (:order d)))))))))

(deftest apply-all-stops-at-the-first-failure
  (let [home (tmp-home)
        base (str home "/repo")
        net (str base "/net") app (str base "/app")]
    (put! base ".gitignore" "deliberator_override.tf.json\n.terraform/\n")
    (put! net "main.tf" (str "terraform {\n  backend \"s3\" {\n    key = \"net\"\n  }\n}\n"
                             "resource \"terraform_data\" \"x\" {\n  input = file(\"/nonexistent/dlb-m6a\")\n}\n"))
    (put! app "main.tf" "data \"terraform_remote_state\" \"net\" {\n  backend = \"s3\"\n  config = {\n    key = \"net\"\n  }\n}\n")
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (let [r (call "terraform.apply-all" {:ws "w" :roots [app net]})]
        (is (false? (:ok r)))
        (is (= net (:failed r)))
        (is (= [app] (:skipped r)))
        (is (= 1 (count (:results r))))
        (is (str/starts-with? (:summary (first (:results r))) "Error:"))))))

(deftest drift-of-a-folder-without-terraform-files-is-an-error-not-no-drift
  ;; B18: it used to answer declared 0, missing 0
  (let [home (tmp-home)]
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (is (= :tf/no-root (:type (err #(call "terraform.drift" {:ws "w" :env "local" :dir home}))))))))

(deftest a-refresh-plan-shows-what-was-changed-by-hand-and-never-the-values
  ;; B36: `terraform plan -refresh-only -out=P` records what a person changed outside Terraform in `resource_drift`; the drift result
  ;; names the attributes (a parameter's value, a queue's visibility timeout) and keeps no value (a SecureString's would be a secret)
  (let [secret "s3cr3t-value-changed-by-hand"
        doc {:format_version "1.2" :terraform_version "1.9.0"
             :planned_values {:root_module {:resources [{:address "aws_ssm_parameter.p" :mode "managed" :type "aws_ssm_parameter" :name "p"
                                                         :values {:name "/x/y" :value "old"}}]}}
             :resource_drift [{:address "aws_ssm_parameter.p" :type "aws_ssm_parameter" :name "p"
                               :change {:actions ["update"] :before {:name "/x/y" :value "old" :version 1}
                                        :after {:name "/x/y" :value secret :version 2}}}
                              {:address "module.stack.aws_sqs_queue.events" :type "aws_sqs_queue" :name "events"
                               :change {:actions ["update"] :before {:visibility_timeout_seconds 60} :after {:visibility_timeout_seconds 45}}}]}
        x (tfdrift/extract doc)]
    (is (= [{:address "aws_ssm_parameter.p" :type "aws_ssm_parameter" :name "p" :actions ["update"] :attributes ["value" "version"]}
            {:address "module.stack.aws_sqs_queue.events" :type "aws_sqs_queue" :name "events" :actions ["update"]
             :attributes ["visibility_timeout_seconds"]}]
           (:drifted x)))
    (is (not (str/includes? (json/write-str x) secret)) "the persisted extract holds no value")
    (is (= (tfdrift/declared doc nil) (tfdrift/declared x nil)) "declared still agrees with the extract")
    (testing "a refresh-only plan keeps its resources in prior_state, planned_values is empty"
      (let [rp (-> doc (assoc :planned_values {:root_module {}})
                   (assoc :prior_state {:values {:root_module (get-in doc [:planned_values :root_module])}}))]
        (is (= [{:kind "ssm" :name "/x/y" :address "aws_ssm_parameter.p" :region nil}] (tfdrift/declared rp nil)))
        (is (= (tfdrift/declared rp nil) (tfdrift/declared (tfdrift/extract rp) nil)))))
    (testing "a state or a plan without drift has no :drifted"
      (is (not (contains? (tfdrift/extract (dissoc doc :resource_drift)) :drifted))))))

(deftest a-kind-that-could-not-be-listed-is-unknown-not-missing
  ;; B37: found with the reader on real AWS: `missing: 8` named the roles and the bus (the reader may not list iam or events)
  (let [declared [{:kind "sqs" :name "q1" :address "aws_sqs_queue.q1"} {:kind "sqs" :name "q2" :address "aws_sqs_queue.q2"}
                  {:kind "iam" :name "r1" :address "aws_iam_role.r1"} {:kind "eventbridge" :name "b1" :address "aws_cloudwatch_event_bus.b1"}]
        found {"sqs" [{:name "q1"}]}]
    (is (= {:matching 1 :declared 4 :missing [{:kind "sqs" :name "q2" :address "aws_sqs_queue.q2"}]
            :unknown [{:kind "iam" :name "r1" :address "aws_iam_role.r1"} {:kind "eventbridge" :name "b1" :address "aws_cloudwatch_event_bus.b1"}]
            :undeclared []}
           (tfdrift/compare-with declared found ["iam" :eventbridge])))
    (testing "without a failed listing the two are missing, as before"
      (is (= 3 (count (:missing (tfdrift/compare-with declared found)))))
      (is (not (contains? (tfdrift/compare-with declared found) :unknown))))))
