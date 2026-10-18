(ns deliberator.tfdrift
  "Terraform against an environment: which declared resources exist there and which exist undeclared. Reads only: the
  Terraform JSON document (`terraform show -json`, a state or a plan, from a file or by running it in a module directory,
  never `apply`) and `awsdiscover` of the environment. Nothing here evaluates Terraform or writes to AWS."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [deliberator.awsdiscover :as discover]
            [deliberator.env :as env]
            [deliberator.store :as store]))

(def ^:private type->kind
  "Terraform resource type -> [discover kind, attribute holding the name]."
  {"aws_sqs_queue" ["sqs" "name"]
   "aws_dynamodb_table" ["dynamodb" "name"]
   "aws_s3_bucket" ["s3" "bucket"]
   "aws_sns_topic" ["sns" "name"]
   "aws_ssm_parameter" ["ssm" "name"]
   "aws_secretsmanager_secret" ["secretsmanager" "name"]
   "aws_lambda_function" ["lambda" "function_name"]
   "aws_cloudwatch_event_bus" ["eventbridge" "name"]
   "aws_kinesis_stream" ["kinesis" "name"]
   "aws_sfn_state_machine" ["states" "name"]
   "aws_cloudwatch_log_group" ["logs" "name"]
   "aws_ecs_cluster" ["ecs" "name"]
   "aws_iam_role" ["iam" "name"]})

(defn- walk-module [m]
  (concat (:resources m) (mapcat walk-module (:child_modules m))))

(defn- values-root [doc]
  (or (get-in doc [:values :root_module])
      (get-in doc [:planned_values :root_module])))

(defn- arn-region [arn]
  (when (string? arn) (let [p (str/split arn #":")] (when (> (count p) 3) (not-empty (nth p 3))))))

(defn declared
  "`[{:kind :name :address :region}]` of the resources of a Terraform JSON document that the explorer knows kinds of.
  `region` (the environment's) keeps only resources whose ARN is in that region (several providers/regions in one root)."
  [doc region]
  (vec (for [r (walk-module (values-root doc))
             :let [[kind attr] (type->kind (:type r))
                   v (:values r)
                   name (get v (keyword attr))]
             :when (and kind (string? name))
             :let [rg (arn-region (:arn v))]
             :when (or (nil? region) (nil? rg) (= rg region))]
         {:kind kind :name name :address (:address r) :region rg})))

(defn compare-with
  "Drift of `declared` against `found` (`{kind [{:name}]}`): `:missing` declared and absent, `:undeclared` present and not declared
  (only for the kinds Terraform declares something of; the `default` event bus is never reported), `:matching` count."
  [declared found]
  (let [dk (group-by :kind declared)
        have (fn [kind] (into #{} (map :name) (get found kind)))
        missing (vec (for [d declared :when (not (contains? (have (:kind d)) (:name d)))] (select-keys d [:kind :name :address])))
        names (fn [kind] (into #{} (map :name) (get dk kind)))
        undeclared (vec (for [[kind items] (sort-by key found)
                              :when (contains? dk kind)
                              {:keys [name]} items
                              :when (and (not (contains? (names kind) name))
                                         (not (and (= kind "eventbridge") (= name "default"))))]
                          {:kind kind :name name}))]
    {:missing missing :undeclared undeclared
     :matching (- (count declared) (count missing))
     :declared (count declared)}))

(defn read-doc
  "The Terraform JSON document: `file` when given, else `terraform show -json [plan]` run in `dir` (read-only; a scrubbed
  environment without AWS credentials). Throws `{:type :tf/unreadable}`."
  [{:keys [file dir plan]}]
  (let [txt (cond
              file (slurp (io/file file))
              dir (let [env (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_"))) (System/getenv))
                        r (shell/with-sh-dir dir
                            (shell/with-sh-env (assoc env "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1")
                              (apply shell/sh "terraform" "show" "-json" "-no-color" (when plan [plan]))))]
                    (when-not (zero? (:exit r))
                      (throw (ex-info (str "terraform show failed in " dir ": " (str/trim (:err r))) {:type :tf/unreadable :dir dir})))
                    (:out r))
              :else (throw (ex-info "give file or dir" {:type :tf/unreadable})))]
    (json/read-str txt :key-fn keyword)))

(defn drift!
  "`{:env :source :region :declared n :matching n :missing [..] :undeclared [..] :errors {..}}`. The environment's resources come
  from `awsdiscover` (read calls, raw-persisted). The Terraform document is persisted next to the other raw data first."
  [ws env-name {:keys [file dir plan] :as src}]
  (let [e (env/load ws env-name)
        doc (read-doc src)
        _ (store/raw-put! ws (.getBytes (json/write-str doc) "UTF-8")
                          {:source :file :op "terraform.show" :env (:name e) :at (java.time.Instant/now)
                           :content-type "application/json" :path (str (or file dir))})
        region (get-in e [:aws :region])
        d (declared doc region)
        found (discover/discover! ws env-name)
        c (compare-with d (:resources found))]
    (assoc c :env (:name e) :source (or file dir) :region region :errors (:errors found))))
