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
