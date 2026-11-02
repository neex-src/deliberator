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
            [deliberator.process :as process]
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

(defn- blank-value?
  "Absent, null, an empty map, list or string: the provider's way of saying nothing."
  [v]
  (or (nil? v) (and (coll? v) (empty? v)) (and (string? v) (str/blank? v))))

(defn- changed-attributes
  "The names of the attributes that differ between `before` and `after` of one change (names only: the values may be secrets).
  Null against an empty map or list is not a difference: the AWS provider's refresh read `tags = {}` where the state said null on 46 of the
  65 resources of the real estate's own root, none of them touched by hand, and the explorer called them \"changed by hand\"."
  [before after]
  (vec (sort (for [k (distinct (concat (keys before) (keys after)))
                   :let [b (get before k) a (get after k)]
                   :when (and (not= b a) (not (and (blank-value? b) (blank-value? a))))]
               (name k)))))

(defn drifted
  "B36: what Terraform's own refresh found changed outside Terraform, from the `resource_drift` of a plan document
  (`terraform plan -refresh-only -out=P`, then `terraform.drift --plan P`): `[{:address :type :name :actions :attributes}]`, the
  attribute NAMES that differ and never their values. Empty for a state or a plan without drift."
  [doc]
  (vec (for [r (:resource_drift doc)
             :let [c (:change r)
                   attrs (changed-attributes (:before c) (:after c))]
             :when (or (seq attrs) (not= ["update"] (vec (:actions c))))]   ; an update whose only differences were nothing against nothing is not a change
         {:address (:address r) :type (:type r) :name (:name r)
          :actions (vec (:actions c))
          :attributes attrs})))

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

(defn- refresh-env
  "B39: the process environment of the refresh: the daemon's own minus every AWS_* and the shared files, plus the credentials and
  region of the environment's profile (and its endpoint, an emulator's). Terraform's provider reads them from here; nothing is
  written to disk."
  [ws e]
  (.mkdirs (.toFile (store/path ws "terraform-plugin-cache")))
  (merge (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_"))) (System/getenv))
         {"AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1"
          "TF_PLUGIN_CACHE_DIR" (str (store/path ws "terraform-plugin-cache"))}
         (env/credentials e)
         (when-let [ep (get-in e [:aws :endpoint])] {"AWS_ENDPOINT_URL" ep})))

(defn failure-reason
  "Why a Terraform run failed, from its transcript: the `Error:` lines (the first two, then a count), not the last three lines, which are
  the tail of the error box (`with module.stack.aws_iam_role.task[0], on ../modules/stack/main.tf line 352` named no cause; the cause, an
  AccessDenied for iam:GetRole of the deploy profile, was above it). Without an `Error:` line: the last three lines."
  [lines]
  (let [errs (->> lines (map #(str/replace % #"^[│|\s]+" "")) (filter #(str/starts-with? % "Error:")) distinct vec)
        cut (fn [s] (if (> (count s) 400) (str (subs s 0 400) "...") s))]
    (if (seq errs)
      (str (str/join " ; " (map cut (take 2 errs))) (when (> (count errs) 2) (str " (and " (- (count errs) 2) " more)")))
      (str/join " | " (take-last 3 (remove str/blank? lines))))))

(defn refresh!
  "B39: `terraform plan -refresh-only` of the root `dir` with the credentials of the remote environment `e`'s profile
  (`terraform init` first when the root has no `.terraform`), the plan saved to a file under the workspace, read back with
  `terraform show -json` and deleted. Nothing is applied and the state is not written (`-lock=false`: a refresh-only plan only
  reads). Terraform's transcript is persisted raw and streamed as `tf.line` events. -> `{:plan <file> :lines [..] :raw <ref>}`;
  the caller deletes the plan. Throws `:tf/refresh-remote-only`, `:tf/no-root`, `:tf/refresh-failed` (exit code and last lines)."
  [ws e ^java.io.File dir {:keys [vars sink on-cancel! budget-ms]}]
  (when-not (= :remote (:kind e))
    (throw (ex-info (str "a refresh uses the credentials of a remote environment; " (:name e) " is " (name (:kind e)))
                    {:type :tf/refresh-remote-only :env (:name e)
                     :hint "a local root is planned with terraform.local; point the drift at the remote environment whose resources the root declares"})))
  (when-not (and (.isDirectory dir) (some #(str/ends-with? (.getName ^java.io.File %) ".tf") (.listFiles dir)))
    (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))
  (let [penv (refresh-env ws e)
        plan (str (store/path ws "terraform" (str "refresh-" (random-uuid) ".tfplan")))
        _ (.mkdirs (.getParentFile (io/file plan)))
        run (fn [args] (process/run! (into ["terraform"] args)
                                     {:dir dir :env penv :on-cancel! on-cancel! :budget-ms budget-ms :what (str "terraform " (first args))
                                      :line! (fn [l] (when sink (sink {:event "tf.line" :line l})))}))
        init (when-not (.isDirectory (io/file dir ".terraform")) (run ["init" "-input=false" "-no-color"]))
        res (when (or (nil? init) (zero? (:exit init)))
              (run (concat ["plan" "-refresh-only" "-input=false" "-lock=false" "-no-color" (str "-out=" plan)]
                           (mapcat (fn [[k v]] ["-var" (str (name k) "=" v)]) vars))))
        lines (vec (concat (:lines init) (:lines res)))
        raw (store/raw-put! ws (.getBytes (str (str/join "\n" lines) "\n") "UTF-8")
                            {:source :file :op "terraform.refresh" :env (:name e) :at (java.time.Instant/now)
                             :content-type "text/plain" :path (str dir)})
        ok? (and res (zero? (:exit res)))]
    (when-not ok?
      (io/delete-file plan true)
      (throw (ex-info (str "terraform " (if res "plan -refresh-only" "init") " failed in " dir " (exit " (:exit (or (when-not res init) res)) "): "
                           (failure-reason lines))
                      {:type :tf/refresh-failed :dir (str dir) :sha (:sha raw)
                       :hint (str "the transcript is persisted (" (:sha raw) "); a root whose variables have no default needs `vars`"
                                  (when (re-find #"AccessDenied|not authorized|UnauthorizedOperation" (failure-reason lines))
                                    (str "; the profile of environment " (:name e) " cannot read everything the root declares (a refresh reads every resource, IAM roles too):"
                                         " refresh with an environment whose profile may, a read-only record on it is enough")))})))
    {:plan plan :lines lines :raw raw}))

(defn drift!
  "`{:env :source :region :declared n :matching n :missing [..] :undeclared [..] :changed [..] :errors {..}}` (`changed` only for a refresh-only
  plan document, see `drifted`). The environment's resources come
  from `awsdiscover` (read calls, raw-persisted). Of the Terraform document only `extract` is persisted, first, and drift is
  computed from that extract: the full document holds the state's secret values (B1)."
  [ws env-name {:keys [file dir plan refresh] :as src}]
  (let [e (env/load ws env-name)
        _ (when (and refresh (or file plan (not dir)))
            (throw (ex-info "refresh needs dir (and no file or plan)" {:type :tf/unreadable :hint "dlb terraform drift --dir ROOT --refresh"})))
        fresh (when refresh (refresh! ws e (io/file (str dir)) src))
        src (cond-> (dissoc src :refresh :vars :sink :on-cancel! :budget-ms) fresh (assoc :plan (:plan fresh)))
        ;; a local environment and a root that terraform.local ran through its override file: its data dir is under the workspace
        data-dir (when (and dir (= :local (:kind e)) (.exists (io/file (str dir) tfroot/override-name)))
                   (let [dd (tfroot/data-dir ws dir)] (when (.isDirectory (io/file dd)) dd)))
        ;; a local root that points its own providers at the emulator (no override file) keeps its state under the workspace too
        ;; (`terraform.local` passes -state, m3 05b): `terraform show -json <state>` reads that one
        state (when (and dir (not plan) (not file) (= :local (:kind e)) (not data-dir))
                (let [p (tfroot/state-path ws dir)] (when (.isFile (io/file p)) p)))
        doc (try (extract (read-doc (cond-> src data-dir (assoc :data-dir data-dir) state (assoc :plan state))))
                 (finally (when fresh (io/delete-file (:plan fresh) true))))   ; the plan file holds the state's secrets: not kept
        _ (store/raw-put! ws (.getBytes (json/write-str doc) "UTF-8")
                          {:source :file :op "terraform.show" :extract :tf-declared :env (:name e) :at (java.time.Instant/now)
                           :content-type "application/json" :path (str (or file dir))})
        region (get-in e [:aws :region])
        d (declared doc region)
        found (discover/discover! ws env-name)
        c (compare-with d (:resources found) (keys (:errors found)))]
    (cond-> (assoc c :env (:name e) :source (or file dir) :region region :errors (:errors found))
      ;; the explorer kind and the AWS name of a changed resource (its row in the tree), joined by address with what the document declares
      (seq (:drifted doc)) (assoc :changed (let [by-addr (into {} (map (juxt :address identity)) d)]
                                             (mapv #(let [x (by-addr (:address %))] (cond-> % x (assoc :kind (:kind x) :aws-name (:name x)))) (:drifted doc))))
      fresh (assoc :refreshed {:lines (count (:lines fresh)) :sha (get-in fresh [:raw :sha])}))))
