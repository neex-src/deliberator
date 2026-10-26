(ns deliberator.hcl-test
  "`deliberator.hcl` reads the shape of .tf files (blocks, labels, lines, literal attributes) and never evaluates them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [deliberator.hcl :as hcl])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def sample "# a comment with provider \"aws\" { in it
terraform {
  backend \"s3\" {
    bucket = \"b\"
    key    = \"iam/terraform.tfstate\" // trailing
  }
}
/* block comment
resource \"aws_sqs_queue\" \"ghost\" { name = \"no\" }
*/
provider \"aws\" {
  alias  = \"x\"
  region = var.region
  skip_requesting_account_id = true
  endpoints { s3 = \"http://${var.e}:4566\" }
}
data \"terraform_remote_state\" \"iam\" {
  backend = \"s3\"
  config  = { bucket = \"b\", key = \"iam/terraform.tfstate\" }
}
resource \"aws_sqs_queue\" \"inbox\" {
  name   = \"q-${var.p}\"
  policy = <<EOT
{ \"resource\": \"aws_sqs_queue\" \"fake\" { name = \"in heredoc\" } }
EOT
  tags = { a = \"b\" }
}
resource \"aws_sqs_queue\" \"plain\" {
  name = \"plain-q\"
}
")

(deftest blocks-labels-lines-and-literals
  (let [bs (hcl/parse sample)
        by (fn [t & labels] (first (filter #(and (= t (:type %)) (= (vec labels) (:labels %))) bs)))]
    (testing "comments and heredocs hide what looks like blocks"
      (is (= ["terraform" "provider" "data" "resource" "resource"] (map :type bs)))
      (is (nil? (by "resource" "aws_sqs_queue" "ghost")))
      (is (nil? (by "resource" "aws_sqs_queue" "fake"))))
    (testing "literals, templates, objects, booleans"
      (let [p (by "provider" "aws")]
        (is (= "x" (hcl/lit p "alias")))
        (is (nil? (hcl/lit p "region")) "an expression is not a literal")
        (is (true? (hcl/bool p "skip_requesting_account_id")))
        (is (= ["endpoints"] (map :type (:blocks p))))
        (is (nil? (hcl/lit (first (:blocks p)) "s3")) "a string with ${} is a template, never a literal"))
      (is (nil? (hcl/lit (by "resource" "aws_sqs_queue" "inbox") "name")))
      (is (= "plain-q" (hcl/lit (by "resource" "aws_sqs_queue" "plain") "name")))
      (is (= {"bucket" "b" "key" "iam/terraform.tfstate"}
             (into {} (map (fn [[k v]] [k (:lit v)])) (get-in (by "data" "terraform_remote_state" "iam") [:attrs "config" :obj]))))
      (is (= "iam/terraform.tfstate" (hcl/lit (first (:blocks (by "terraform"))) "key"))))
    (testing "lines are the lines of the file"
      (is (= 11 (:line (by "provider" "aws"))))
      (is (= 21 (:line (by "resource" "aws_sqs_queue" "inbox")))))))

(defn- tmp-dir [] (.toFile (Files/createTempDirectory "dlb-hcl" (make-array FileAttribute 0))))

(deftest locate-follows-local-modules
  (let [root (tmp-dir)
        mod (io/file root "modules" "stack")]
    (.mkdirs mod)
    (.mkdirs (io/file root "app"))
    (spit (io/file root "app" "main.tf") "variable \"p\" {}\n\nmodule \"stack\" {\n  source = \"../modules/stack\"\n}\n\nmodule \"reg\" {\n  source = \"terraform-aws-modules/x/aws\"\n}\n\nresource \"aws_ecs_cluster\" \"estate\" {\n  name = \"e\"\n}\n\ndata \"aws_vpc\" \"default\" {\n  default = true\n}\n")
    (spit (io/file mod "q.tf") "\n\nresource \"aws_sqs_queue\" \"inbox\" {\n  name = \"i\"\n}\n")
    (let [app (str (io/file root "app"))
          at (fn [a] (some-> (hcl/locate app a) (update :file #(.getName (io/file %)))))]
      (is (= {:file "main.tf" :line 11} (select-keys (at "aws_ecs_cluster.estate") [:file :line])))
      (is (= {:file "main.tf" :line 15} (select-keys (at "data.aws_vpc.default[0]") [:file :line])) "an index is dropped")
      (is (= {:file "q.tf" :line 3} (select-keys (at "module.stack.aws_sqs_queue.inbox") [:file :line])) "into the module's directory")
      (is (= {:file "q.tf" :line 3} (select-keys (at "module.stack[\"a\"].aws_sqs_queue.inbox[2]") [:file :line])))
      (is (= {:file "main.tf" :line 7 :module-only true} (select-keys (at "module.reg.aws_x.y") [:file :line :module-only]))
          "a registry module: the module block itself")
      (is (nil? (at "aws_sqs_queue.nope")))
      (is (nil? (hcl/locate (str (io/file root "none")) "aws_sqs_queue.inbox")) "a directory without .tf files"))))
