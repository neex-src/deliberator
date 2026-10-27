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
            [deliberator.store :as store]
            [deliberator.tfroot :as tfroot]))

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

(defn- values-root
  "The resources of a document: a state's `values`, a plan's `planned_values`, and for a refresh-only plan (empty `planned_values`) the
  refreshed state it carries as `prior_state` (B36)."
  [doc]
  (let [pm (get-in doc [:planned_values :root_module])]
    (or (get-in doc [:values :root_module])
        (when (or (seq (:resources pm)) (seq (:child_modules pm))) pm)
        (get-in doc [:prior_state :values :root_module])
        pm)))

(defn- arn-region [arn]
  (when (string? arn) (let [p (str/split arn #":")] (when (> (count p) 3) (not-empty (nth p 3))))))

(def ^:private kept-values
  "The only resource attributes that leave `terraform show -json` (B1): what `declared` reads, and nothing that can hold a secret."
  [:name :arn :id :bucket :function_name])

(defn- keep-module [m]
  (cond-> {:resources (mapv (fn [r] (-> (select-keys r [:address :mode :type :name :index :provider_name])
                                        (assoc :values (select-keys (:values r) kept-values))))
                            (:resources m))
           :child_modules (mapv keep-module (:child_modules m))}
    (:address m) (assoc :address (:address m))))

(defn- changed-attributes
  "The names of the attributes that differ between `before` and `after` of one change (names only: the values may be secrets)."
  [before after]
  (vec (sort (for [k (distinct (concat (keys before) (keys after)))
                   :when (not= (get before k) (get after k))]
               (name k)))))

(defn drifted
  "B36: what Terraform's own refresh found changed outside Terraform, from the `resource_drift` of a plan document
  (`terraform plan -refresh-only -out=P`, then `terraform.drift --plan P`): `[{:address :type :name :actions :attributes}]`, the
  attribute NAMES that differ and never their values. Empty for a state or a plan without drift."
  [doc]
  (vec (for [r (:resource_drift doc)
             :let [c (:change r)]]
         {:address (:address r) :type (:type r) :name (:name r)
          :actions (vec (:actions c))
          :attributes (changed-attributes (:before c) (:after c))})))

(defn extract
  "What of a Terraform JSON document is persisted (decision 54, B1): the versions and, per resource of the root and child modules
  (`values`, or `planned_values` of a plan, kept under the same key), address, mode, type, name, index, provider and the values
  `name arn id bucket function_name`. Secret strings, generated passwords, sensitive outputs and variables never get here;
  `declared` of the extract equals `declared` of the document."
  [doc]
  (let [k (if (get-in doc [:values :root_module]) :values :planned_values)]
    (cond-> (select-keys doc [:format_version :terraform_version])
      (values-root doc) (assoc k {:root_module (keep-module (values-root doc))})
      (seq (:resource_drift doc)) (assoc :drifted (drifted doc)))))

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
  (only for the kinds Terraform declares something of; the `default` event bus is never reported), `:matching` count.
  `unreadable` (the kinds whose listing failed, e.g. a read-only profile without `iam:ListRoles`; collection of names or keywords):
  what is declared of those is `:unknown`, neither matching nor missing (B37: a reader's `missing: 8` claimed roles and a bus were
  gone that it was never allowed to list)."
  ([declared found] (compare-with declared found nil))
  ([declared found unreadable]
   (let [dk (group-by :kind declared)
         unreadable (into #{} (map name) unreadable)
         have (fn [kind] (into #{} (map :name) (get found kind)))
         unknown (vec (for [d declared :when (contains? unreadable (:kind d))] (select-keys d [:kind :name :address])))
         missing (vec (for [d declared
                            :when (and (not (contains? unreadable (:kind d))) (not (contains? (have (:kind d)) (:name d))))]
                        (select-keys d [:kind :name :address])))
         names (fn [kind] (into #{} (map :name) (get dk kind)))
         undeclared (vec (for [[kind items] (sort-by key found)
                               :when (contains? dk kind)
                               {:keys [name]} items
                               :when (and (not (contains? (names kind) name))
                                          (not (and (= kind "eventbridge") (= name "default"))))]
                           {:kind kind :name name}))]
     (cond-> {:missing missing :undeclared undeclared
              :matching (- (count declared) (count missing) (count unknown))
              :declared (count declared)}
       (seq unknown) (assoc :unknown unknown)))))

(defn read-doc
  "The Terraform JSON document: `file` when given, else `terraform show -json [plan]` run in `dir` (read-only; a scrubbed
  environment without AWS credentials; `data-dir` is `TF_DATA_DIR`, for a root that `terraform.local` ran through its override
  file, whose backend metadata lives under the workspace). Throws `{:type :tf/unreadable}`."
  [{:keys [file dir plan data-dir]}]
  (let [txt (cond
              file (slurp (io/file file))
              dir (let [_ (when-not (and (.isDirectory (io/file dir)) (some #(str/ends-with? (.getName ^java.io.File %) ".tf") (.listFiles (io/file dir))))
                            (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))   ; B18: not "no drift"
                        env (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_"))) (System/getenv))
                        r (shell/with-sh-dir dir
                            (shell/with-sh-env (cond-> (assoc env "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1")
                                                   data-dir (assoc "TF_DATA_DIR" data-dir))
                              (apply shell/sh "terraform" "show" "-json" "-no-color" (when plan [plan]))))]
                    (when-not (zero? (:exit r))
                      (throw (ex-info (str "terraform show failed in " dir ": " (str/trim (:err r))) {:type :tf/unreadable :dir dir})))
                    (:out r))
              :else (throw (ex-info "give file or dir" {:type :tf/unreadable})))]
    (json/read-str txt :key-fn keyword)))

(defn drift!
  "`{:env :source :region :declared n :matching n :missing [..] :undeclared [..] :changed [..] :errors {..}}` (`changed` only for a refresh-only
  plan document, see `drifted`). The environment's resources come
  from `awsdiscover` (read calls, raw-persisted). Of the Terraform document only `extract` is persisted, first, and drift is
  computed from that extract: the full document holds the state's secret values (B1)."
  [ws env-name {:keys [file dir plan] :as src}]
  (let [e (env/load ws env-name)
        ;; a local environment and a root that terraform.local ran through its override file: its data dir is under the workspace
        data-dir (when (and dir (= :local (:kind e)) (.exists (io/file (str dir) tfroot/override-name)))
                   (let [dd (tfroot/data-dir ws dir)] (when (.isDirectory (io/file dd)) dd)))
        ;; a local root that points its own providers at the emulator (no override file) keeps its state under the workspace too
        ;; (`terraform.local` passes -state, m3 05b): `terraform show -json <state>` reads that one
        state (when (and dir (not plan) (not file) (= :local (:kind e)) (not data-dir))
                (let [p (tfroot/state-path ws dir)] (when (.isFile (io/file p)) p)))
        doc (extract (read-doc (cond-> src data-dir (assoc :data-dir data-dir) state (assoc :plan state))))
        _ (store/raw-put! ws (.getBytes (json/write-str doc) "UTF-8")
                          {:source :file :op "terraform.show" :extract :tf-declared :env (:name e) :at (java.time.Instant/now)
                           :content-type "application/json" :path (str (or file dir))})
        region (get-in e [:aws :region])
        d (declared doc region)
        found (discover/discover! ws env-name)
        c (compare-with d (:resources found) (keys (:errors found)))]
    (cond-> (assoc c :env (:name e) :source (or file dir) :region region :errors (:errors found))
      (seq (:drifted doc)) (assoc :changed (:drifted doc)))))
