(ns deliberator.tfsurvey-test
  "`terraform.survey` and the supported-only apply: the text half needs nothing running; the rest runs REAL
  Terraform (init, plan, apply, destroy) against the real Floci of `DLB_TEST_FLOCI_URL` (SKIP line when it is down), on one fixture
  root per problem class (`core/test/fixtures/tf-survey/roots/*`: a datadog provider, a lookup by tag, an aws type Floci has not, a
  variable without a default, a git module that cannot be fetched, a data source that never answers, broken syntax, a local module)
  and on copies of the estate's roots. A verdict asserted here is one Terraform produced: the persisted raw files are asserted too."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.store :as store]
            [deliberator.tfsurvey :as svy]))

(def fixtures (.getCanonicalPath (io/file "test/fixtures/tf-survey")))

(def home
  "A home that stays between runs (the provider cache under it holds the downloaded providers), under the repository's .scratch."
  (let [d (io/file "../.scratch/tf-survey-test-home")] (.mkdirs d) (.getCanonicalPath d)))

(def ws "svy")

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)))

(defmacro ^:private with-home [& body]
  `(binding [store/*home* home]
     (env/save! ws (fu/local-env))
     ~@body))

(defn- row [r short] (first (filter #(= short (last (str/split (:name %) #"/"))) (:roots r))))
(defn- problems [r] (set (map (juxt :class :subject) (:problems r))))
(defn- classes [r] (set (map :class (:problems r))))

;; --- the text half ------------------------------------------------------------------------------------------

(deftest module-sources-by-kind
  (is (= {"./x" :local "../modules/q" :local
          "git::https://example.org/a/b.git?ref=v1" :git "github.com/acme/terraform-x" :git "git@github.com:acme/x.git" :git
          "terraform-aws-modules/vpc/aws" :registry "terraform-aws-modules/vpc/aws//modules/x" :registry "app.terraform.io/acme/net/aws" :registry
          "s3::https://s3.amazonaws.com/b/m.zip" :other "https://example.org/m.zip" :other}
         (into {} (map (fn [s] [s (svy/module-kind s)])) ["./x" "../modules/q" "git::https://example.org/a/b.git?ref=v1" "github.com/acme/terraform-x"
                                                           "git@github.com:acme/x.git" "terraform-aws-modules/vpc/aws" "terraform-aws-modules/vpc/aws//modules/x"
                                                           "app.terraform.io/acme/net/aws" "s3::https://s3.amazonaws.com/b/m.zip" "https://example.org/m.zip"]))))

(deftest roots-are-found-and-the-others-say-why
  (let [{:keys [roots not-roots]} (svy/discover [fixtures])
        names (set (map #(.getName (io/file (:dir %))) roots))]
    (testing "every directory with .tf files that nobody calls, and no library module, is a root"
      (is (= #{"runs" "other-provider" "lookup" "unsupported-type" "missing-var" "git-module" "hang" "broken-syntax" "with-module"} names)))
    (testing "a module called with a local source is that root's, a library module nobody calls is not run, each says why"
      (let [why (into {} (map (juxt #(.getName (io/file (:dir %))) :why)) not-roots)]
        (is (str/includes? (get why "queue" "") "a module of") (pr-str why))
        (is (str/includes? (get why "queue" "") "with-module"))
        (is (str/includes? (get why "unused-library" "") "library module") (pr-str why))))
    (testing "a root is also given by itself, and its signals are listed"
      (let [{:keys [roots]} (svy/discover [(str fixtures "/roots/runs")])]
        (is (= 1 (count roots)))
        (is (some #{"called-by-nobody"} (:signals (first roots))))))))

(deftest the-text-of-a-root
  (testing "missing variables, tfvars that are not loaded"
    (let [inv (svy/inventory (str fixtures "/roots/missing-var"))]
      (is (= [["environment" "string" "placeholder"] ["retention_days" "number" "0"]]
             (map (juxt :name :type :placeholder) (:variables-without-default inv))))
      (is (= ["dev.tfvars"] (:tfvars inv)))
      (is (= [] (:auto-tfvars inv)) "dev.tfvars is not loaded by Terraform on its own")))
  (testing "providers by kind, resource types"
    (let [inv (svy/inventory (str fixtures "/roots/other-provider"))]
      (is (= {"aws" :emulated "datadog" :other} (into {} (map (juxt :name :kind)) (:providers inv))))
      (is (= {"aws_sqs_queue" 1 "datadog_monitor" 1} (:resources inv)))))
  (testing "modules by kind"
    (is (= [["shared" "git"]] (map (juxt :name :kind) (:modules (svy/inventory (str fixtures "/roots/git-module"))))))
    (is (= [["queue" "local"]] (map (juxt :name :kind) (:modules (svy/inventory (str fixtures "/roots/with-module"))))))))

(deftest what-terraform-printed-is-read-as-it-came
  (let [lines ["{\"@level\":\"info\",\"type\":\"apply_start\",\"hook\":{\"resource\":{\"addr\":\"data.external.slow\"}}}"
               "{\"@level\":\"info\",\"type\":\"apply_start\",\"hook\":{\"resource\":{\"addr\":\"data.aws_vpc.main\"}}}"
               "{\"@level\":\"info\",\"type\":\"apply_complete\",\"hook\":{\"resource\":{\"addr\":\"data.aws_vpc.main\"}}}"
               "{\"@level\":\"error\",\"type\":\"diagnostic\",\"diagnostic\":{\"severity\":\"error\",\"summary\":\"no matching EC2 VPC found\",\"detail\":\"\",\"address\":\"data.aws_vpc.main\",\"range\":{\"filename\":\"main.tf\",\"start\":{\"line\":17}}}}"
               "panic: runtime error: invalid memory address"]
        p (svy/parse-json-lines lines)]
    (is (= ["data.external.slow"] (:hung p)) "started, never finished: the one it was waiting for when it was killed")
    (is (= ["panic: runtime error: invalid memory address"] (:plain p)) "a line that is not JSON is kept")
    (is (= [{:class "lookup" :subject "data.aws_vpc.main" :where "main.tf:17"}]
           [(select-keys (svy/classify-diagnostic (first (:diagnostics p)) []) [:class :subject :where])]))))

(deftest diagnostics-become-classes
  (let [c (fn [d mods] (select-keys (svy/classify-diagnostic d mods) [:class :subject]))]
    (is (= {:class "missing-var" :subject "environment"}
           (c {:summary "No value for required variable" :detail "The root module input variable \"environment\" is not set, and has no default value."} [])))
    (is (= {:class "module-fetch" :subject "git::https://x/y.git"}
           (c {:summary "Failed to download module" :detail "Could not download module \"shared\" (main.tf:3) source code from \"git::https://x/y.git\": error downloading"} [])))
    (is (= {:class "module-fetch" :subject "acme/net/aws"}
           (c {:summary "Module not installed" :detail "Module \"net\" is not available."} [{:name "net" :source "acme/net/aws"}])))
    (is (= {:class "other-provider" :subject "zscaler/zpa"}
           (c {:summary "Failed to query available provider packages"
               :detail "Could not retrieve the list of available versions for provider zscaler/zpa: provider registry.terraform.io does not have a provider named registry.terraform.io/zscaler/zpa"} [])))
    (is (= {:class "other-provider" :subject "datadog"} (c {:summary "x" :address "datadog_monitor.a"} [])))
    (is (= {:class "lookup" :subject "module.net.data.aws_vpc.main"} (c {:summary "no matching EC2 VPC found" :address "module.net.data.aws_vpc.main[0]"} [])))
    (is (= {:class "other" :subject "Unsupported argument"} (c {:summary "Unsupported argument" :detail "An argument named \"x\" is not expected here."} [])))))

(deftest the-graph-gives-what-depends-on-what
  (let [g (svy/parse-graph "digraph G {\n  rankdir = \"RL\";\n  \"data.aws_vpc.main\" [label=\"data.aws_vpc.main\"];\n  \"aws_sqs_queue.q\" [label=\"aws_sqs_queue.q\"];\n  \"aws_sqs_queue.r\" [label=\"aws_sqs_queue.r\"];\n  \"aws_sqs_queue.r\" -> \"data.aws_vpc.main\";\n  \"aws_sqs_queue.s\" -> \"local.x\";\n  \"local.x\" -> \"aws_sqs_queue.r\";\n}")]
    (is (= #{"data.aws_vpc.main" "aws_sqs_queue.q" "aws_sqs_queue.r" "aws_sqs_queue.s"} (:nodes g)))
    (is (= #{"aws_sqs_queue.r" "data.aws_vpc.main"} (get-in g [:deps "aws_sqs_queue.s"])) "through a local, transitively")))

(deftest the-measured-list-is-data-with-its-provenance
  (let [m (svy/measured)]
    (is (map? m) "core/resources/deliberator/floci-aws-types.edn")
    (is (re-matches #"\d{4}-\d\d-\d\d" (:measured m)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (get-in m [:image :digest])))
    (is (= "2.3.0" (:floci-version m)))
    (is (pos? (count (:supported m))))
    (is (= :supported (:state (svy/type-state "aws_sqs_queue"))))
    (is (= :unsupported (:state (svy/type-state "aws_xray_group"))))
    (is (= :unproven (:state (svy/type-state "aws_not_a_type"))))))

;; --- real Terraform against the real Floci -----------------------------------------------------------------------

(fu/deftest-floci the-fixtures-each-say-what-they-are
  (with-home
    (let [events (atom [])
          r (call "terraform.survey" {:ws ws :dirs [fixtures] :budget-s 60} events)
          by #(row r %)]
      (testing "nine roots, the two modules are not roots"
        (is (= 9 (count (:roots r))) (pr-str (map :name (:roots r))))
        (is (= 2 (count (:not-roots r)))))
      (testing "runs: the full plan passed"
        (is (= "runs" (:verdict (by "runs"))) (:table r))
        (is (= [] (:problems (by "runs"))))
        (is (= 2 (get-in (by "runs") [:resources :local]))))
      (testing "other-provider: the aws part runs, the datadog resource does not"
        (is (= "runs-partly" (:verdict (by "other-provider"))))
        (is (contains? (problems (by "other-provider")) ["other-provider" "datadog"]) (pr-str (:problems (by "other-provider"))))
        (is (= ["aws_sqs_queue.alerts"] (:targets (by "other-provider"))))
        (is (= ["datadog_monitor.queue_depth"] (map :address (:skipped (by "other-provider"))))))
      (testing "lookup: the VPC lookup fails in Terraform, the queue that names itself after it is skipped, the other one runs"
        (let [l (by "lookup")]
          (is (= "runs-partly" (:verdict l)) (:table r))
          (is (contains? (problems l) ["lookup" "data.aws_vpc.main"]) (pr-str (:problems l)))
          (is (= ["aws_sqs_queue.plain"] (:targets l)))
          (is (= [{:address "aws_sqs_queue.per_vpc" :via "data.aws_vpc.main"}] (map #(select-keys % [:address :via]) (:skipped l))))))
      (testing "unsupported-type: Floci has no X-Ray"
        (let [u (by "unsupported-type")]
          (is (= "runs-partly" (:verdict u)))
          (is (contains? (problems u) ["unsupported-service" "aws_xray_group"]) (pr-str (:problems u)))
          (is (= ["aws_sqs_queue.plain"] (:targets u)))))
      (testing "missing-var: planned with placeholders, said so"
        (let [m (by "missing-var")]
          (is (= "runs-partly" (:verdict m)))
          (is (= #{["missing-var" "environment"] ["missing-var" "retention_days"]} (problems m)))
          (is (= {"environment" "placeholder" "retention_days" "0"} (:placeholders m)))))
      (testing "git-module: init cannot fetch it, a row, not an error"
        (let [g (by "git-module")]
          (is (= "does-not-run" (:verdict g)))
          (is (= #{"module-fetch"} (classes g)) (pr-str (:problems g)))
          (is (str/includes? (:subject (first (:problems g))) "127.0.0.1:9/nobody/terraform-shared.git"))))
      (testing "hang: the plan is killed at the budget, the data source it waited for is named, the rest runs"
        (let [h (by "hang")]
          (is (= "runs-partly" (:verdict h)) (:table r))
          (is (contains? (problems h) ["lookup" "data.external.slow"]) (pr-str (:problems h)))
          (is (some #(and (= "plan" (:what %)) (:timeout? %)) (:calls h)) "the first plan was killed, seen in its call record")
          (is (= ["aws_sqs_queue.plain"] (:targets h)))))
      (testing "broken syntax: init says so, a row"
        (let [b (by "broken-syntax")]
          (is (= "does-not-run" (:verdict b)))
          (is (= #{"other"} (classes b)) (pr-str (:problems b)))))
      (testing "a root that calls a local module runs through it"
        (is (= "runs" (:verdict (by "with-module"))) (:table r)))
      (testing "the raw output is persisted first, named, in the survey folder; the table is text"
        (is (.isDirectory (io/file (:folder r))))
        (is (.exists (io/file (:folder r) "survey.txt")))
        (is (.exists (io/file (:folder r) "survey.json")))
        (doseq [x (:roots r)]
          (is (.exists (io/file (:folder x) "row.json")) (:name x))
          (is (.exists (io/file (:folder x) "init.jsonl")) (:name x)))
        (let [h (by "hang")]
          (is (str/includes? (slurp (io/file (:folder h) "plan-1-full.jsonl")) "data.external.slow") "what Terraform printed before it was killed is kept"))
        (is (str/includes? (:table r) "VERDICT"))
        (is (str/includes? (:table r) "runs-partly")))
      (testing "progress is events"
        (is (= 9 (count (filter #(= "survey.root" (:event %)) @events)))))
      (testing "the newest survey is shown at once, with its age, and runs nothing"
        (let [l (call "terraform.survey" {:ws ws :latest true})]
          (is (= (:stamp r) (:stamp l)))
          (is (= (:table r) (:table l)))
          (is (integer? (:age-s l))))))))

(defn- copy-estate! []
  (let [t (str (.getCanonicalPath (io/file (str "../.scratch/tf-survey-estate-" (System/nanoTime)))))
        _ (.mkdirs (io/file t))
        r (shell/sh "sh" "-c" (str "tar -C ../estate --exclude=.terraform --exclude=.build --exclude='*.tfstate*' -cf - terraform lambda | tar -C " t " -xf -"))]
    (assert (zero? (:exit r)) (:err r))
    (shell/sh "git" "init" "-q" t)
    (spit (io/file t ".gitignore") "deliberator_override.tf.json\n.terraform/\n.build/\n")
    t))

(fu/deftest-floci the-estates-roots-say-runs
  (with-home
    (let [t (copy-estate!)
          floci (str t "/terraform/floci") iam (str t "/terraform/iam") aws-root (str t "/terraform/aws")]
      (try
        (let [r (call "terraform.survey" {:ws ws :dirs [floci iam aws-root] :budget-s 240})
              by #(row r %)]
          (testing "the estate's own Floci root and the IAM root run"
            (is (= "runs" (:verdict (by "floci"))) (:table r))
            (is (= "runs" (:verdict (by "iam"))) (:table r)))
          (testing "the aws root looks a role up that the iam root declares: nothing of it exists here yet, and the row says what to apply first"
            (let [a (by "aws")]
              (is (#{"runs-partly" "runs"} (:verdict a)) (:table r))
              (when (= "runs-partly" (:verdict a))
                (is (some #(= "lookup" (:class %)) (:problems a)) (pr-str (:problems a)))
                (is (= ["iam"] (distinct (map :from (:needs a)))) (pr-str (:needs a))))))
          (testing "after the iam root is applied the aws root runs: Terraform finds the role"
            (let [ap (call "terraform.apply-all" {:ws ws :roots [iam] :vars {:prefix "svy"}})]
              (is (true? (:ok ap)) (pr-str ap)))
            (let [r2 (call "terraform.survey" {:ws ws :dirs [aws-root] :budget-s 240})]
              (is (= "runs" (:verdict (first (:roots r2)))) (:table r2))))
          (call "terraform.apply-all" {:ws ws :roots [iam] :action "destroy" :vars {:prefix "svy"}}))
        (finally (shell/sh "rm" "-rf" t))))))

(defn- queue-exists? [name]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :sqs :ListQueues {:QueueNamePrefix name})]
    (boolean (seq (:QueueUrls r)))))

(fu/deftest-floci supported-only-applies-what-runs-and-lists-the-rest
  (with-home
    (let [root (str fixtures "/roots/unsupported-type")]
      (try
        (let [r (call "terraform.local" {:ws ws :dir root :action "apply" :supported-only true :budget-s 120})]
          (testing "only the queue is applied, the X-Ray group is skipped with its class"
            (is (true? (:ok r)) (pr-str (select-keys r [:ok :summary :skipped])))
            (is (str/starts-with? (:summary r) "Apply complete! Resources: 1 added"))
            (is (= ["aws_sqs_queue.plain"] (:targets r)))
            (is (= [["aws_xray_group.slow" "unsupported-service" "aws_xray_group"]]
                   (map (juxt :address :class :subject) (:skipped r))))
            (is (str/includes? (:output r) "skipped aws_xray_group.slow: unsupported-service aws_xray_group") "the list is in the persisted text too")
            (is (queue-exists? "svy-unsupported-plain") "the queue is really on Floci"))
          (testing "a second run changes nothing"
            (is (str/starts-with? (:summary (call "terraform.local" {:ws ws :dir root :action "apply" :supported-only true :budget-s 120}))
                                  "Apply complete! Resources: 0 added"))))
        (finally
          (let [d (call "terraform.local" {:ws ws :dir root :action "destroy" :supported-only true :budget-s 120})]
            (testing "destroy works the same way"
              (is (true? (:ok d)) (pr-str (select-keys d [:ok :summary])))
              (is (str/starts-with? (:summary d) "Destroy complete! Resources: 1 destroyed"))
              (is (not (queue-exists? "svy-unsupported-plain"))))))))))

(fu/deftest-floci supported-only-skips-what-depends-on-a-failed-lookup
  (with-home
    (let [root (str fixtures "/roots/lookup")]
      (try
        (let [r (call "terraform.local" {:ws ws :dir root :action "apply" :supported-only true :budget-s 120})]
          (is (true? (:ok r)) (pr-str (select-keys r [:ok :summary])))
          (is (= ["aws_sqs_queue.plain"] (:targets r)))
          (is (= [["aws_sqs_queue.per_vpc" "lookup" "data.aws_vpc.main"]] (map (juxt :address :class :subject) (:skipped r))))
          (is (queue-exists? "svy-lookup-plain")))
        (finally (call "terraform.local" {:ws ws :dir root :action "destroy" :supported-only true :budget-s 120}))))
    (testing "a root where nothing can run is a refusal with the reason, nothing applied"
      (let [r (call "terraform.local" {:ws ws :dir (str fixtures "/roots/git-module") :action "apply" :supported-only true :budget-s 60})]
        (is (false? (:ok r)))
        (is (str/includes? (:summary r) "module-fetch"))))))
