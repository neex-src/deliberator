(ns floci-measure
  "`bb floci:measure [--url U] [--out DIR] [--workers N] [--batch N] [--budget-s S] [--only T,T] [--classify-only] [--write FILE]`:
  which `aws_*` RESOURCE types a running Floci supports, MEASURED with real Terraform runs, never read off a documentation page.

  For every resource type of the AWS provider's schema a minimal resource is generated (the required attributes and required blocks,
  values by their schema types and by name: an ARN, a CIDR, an account id).
  1. Validation: ONE `terraform plan -json` of all of them. Terraform names every resource whose generated values the provider refuses;
     when the message says which value it wanted (`expected x to be one of [...]`, an invalid ARN, an account id) the value is
     repaired and the plan run again (up to 5 rounds). What stays invalid is `unproven` (the first error line is kept).
  2. The valid ones go in batches of `--batch` (default 25) through real `terraform apply -json` against Floci (`AWS_ENDPOINT_URL`, fake
     keys), then a refreshing `plan -json`, then `destroy -json`; Terraform reports every resource apart, so one refused resource does not
     hide the others. A batch the provider crashed in, or a resource that has no verdict, is run again in halves down to one resource.
  Every raw output is written to `<out>/` first (validate/round-N.jsonl, batches/<id>/{res.tf.json,apply.jsonl,plan.jsonl,destroy.jsonl}); the
  classes come from those files only (`--classify-only` re-derives them without a single Terraform run).

  Classes: `supported` (applied and destroyed; `:drift` when the refresh plan still wants a change), `unsupported` (Floci answered that it
  does not know the call or the service: `unsupported-signatures`), `unproven` (the run failed for a reason that is not Floci's: the generated
  resource was invalid, needed another object, the provider refused it, it did not answer within the budget; the first error line is kept).
  `--write FILE` writes the data file the survey reads (core/resources/deliberator/floci-aws-types.edn) with the date, the Floci image, its
  digest and the provider version. `bb/floci_measure_fixes.edn` ({type {attribute value}}) holds values chosen by hand."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(defn- arg [args k] (second (drop-while #(not= k %) args)))
(defn- flag? [args k] (boolean (some #{k} args)))

;; --- the generated resource -------------------------------------------------------------------------------

(defn- value-of
  "A plausible value for attribute `nm` of schema type `t`."
  [nm t]
  (let [t (if (sequential? t) t [t])
        k (first t)
        s (cond
            (re-find #"(?i)arn$|(^|_)role$" nm) "arn:aws:iam::000000000000:role/dlbm"
            (re-find #"(?i)account_id" nm) "000000000000"
            (re-find #"cidr" nm) "10.0.0.0/16"
            (re-find #"(?i)(^|_)(name|identifier|id)$" nm) "dlbm-x"
            (re-find #"(?i)policy|document|json" nm) "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
            (re-find #"(?i)region" nm) "eu-central-1"
            :else "dlbm")]
    (case k
      "string" s
      "number" 1
      "bool" false
      ("list" "set") [(value-of nm (second t))]
      "map" {"k" (value-of nm (second t))}
      "object" (into {} (for [[a at] (second t)] [(name a) (value-of (name a) at)]))
      s)))

(defn- block-values
  "The required attributes and required blocks of schema block `b`; `fix` {attribute-name value} wins (repairs and hand-written values)."
  [b fix]
  (merge
   (into {} (for [[n a] (:attributes b) :when (:required a)
                  :let [n (name n) t (:type a) v (get fix n (value-of n t))]]
              [n (if (and (sequential? t) (#{"list" "set"} (first t)) (string? v)) [v] v)]))
   (into {} (for [[n bt] (:block_types b)
                  :when (pos? (or (:min_items bt) 0))
                  :let [v (block-values (:block bt) fix) n (name n)]]
              [n (if (#{"list" "set"} (:nesting_mode bt)) [v] v)]))))

(def schema-state (atom nil))

(defn- resource-of [type fix]
  (block-values (get-in @schema-state [:resource_schemas (keyword type) :block]) fix))

(def recipes
  "`bb/floci_measure_recipes.edn`: types that cannot stand alone (a rule needs its security group, a policy its role):
  `{type {:deps {\"aws_x.label\" {attrs}} :cfg {attrs}}}`; `@` in a key or a value becomes `dep__<type without aws_>__` (the
  address says whose dependency it is), `%` the type without aws_ with `-` for `_` (a bucket name)."
  (delay (when (fs/exists? "bb/floci_measure_recipes.edn") (edn/read-string (slurp "bb/floci_measure_recipes.edn")))))

(defn- expand [t x]
  (let [short (subs t 4)]
    (cond
      (string? x) (-> x (str/replace "$ZIP" (str (fs/absolutize ".scratch/floci-measure/dummy.zip"))) (str/replace "@" (str "dep__" short "__")) (str/replace "%" (str/replace short "_" "-")))
      (map? x) (into {} (map (fn [[k v]] [(expand t k) (expand t v)])) x)
      (sequential? x) (mapv #(expand t %) x)
      :else x)))

(defn- root-doc [types fixes]
  (let [res (fn [t] (if-let [r (get @recipes t)]
                      (let [r (expand t r)]
                        (concat [[t "m" (merge (resource-of t (get fixes t {})) (:cfg r))]]
                                (for [[k cfg] (:deps r) :let [[dt dl] (str/split k #"\.")]] [dt dl cfg])))
                      [[t "m" (resource-of t (get fixes t {}))]]))]
    {"resource" (reduce (fn [m [t l cfg]] (assoc-in m [t l] cfg)) (sorted-map) (mapcat res types))}))

;; --- running Terraform ---------------------------------------------------------------------------------------

(defn- base-env [url cache]
  (merge (into {} (remove (fn [[k _]] (or (str/starts-with? k "AWS_") (str/starts-with? k "TF_")))) (System/getenv))
         {"AWS_ENDPOINT_URL" url "AWS_ACCESS_KEY_ID" "test" "AWS_SECRET_ACCESS_KEY" "test" "AWS_REGION" "eu-central-1"
          "TF_IN_AUTOMATION" "1" "TF_PLUGIN_CACHE_DIR" cache "TF_INPUT" "0"}))

(defn- tf!
  "`terraform args` in `dir`; output written to `out-file` as it comes, -> {:exit :ms :timeout?}."
  [dir env out-file budget-s & args]
  (let [t0 (System/currentTimeMillis)
        proc (p/process (into ["terraform"] args) {:dir (str dir) :env env :out :write :out-file (io/file out-file) :err :out :clear-env true})
        r (deref proc (* 1000 budget-s) ::timeout)]
    (if (= r ::timeout)
      (do (p/destroy-tree proc) @proc
          {:exit -1 :timeout? true :ms (- (System/currentTimeMillis) t0)})
      {:exit (:exit r) :ms (- (System/currentTimeMillis) t0)})))

(def provider-block
  {"provider" {"aws" {"region" "eu-central-1" "access_key" "test" "secret_key" "test"
                      "skip_credentials_validation" true "skip_metadata_api_check" true "skip_requesting_account_id" true
                      "skip_region_validation" true "s3_use_path_style" true}}})

(defn- init-dir! [wd url cache version]
  (fs/create-dirs wd)
  (spit (fs/file wd "base.tf.json")
        (json/generate-string (merge {"terraform" {"required_providers" {"aws" {"source" "hashicorp/aws" "version" version}}}} provider-block) {:pretty true}))
  (tf! wd (base-env url cache) (fs/file wd "init.txt") 600 "init" "-no-color" "-input=false"))

;; --- reading what Terraform printed --------------------------------------------------------------------------

(defn- events [file]
  (when (fs/exists? file)
    (vec (for [l (str/split-lines (slurp (str file)))
               :let [e (when (str/starts-with? l "{") (try (json/parse-string l true) (catch Exception _ nil)))]
               :when e]
           e))))

(defn- addr-of
  "[type main?] of an address: `aws_x.m` is the resource itself, `aws_y.dep__x__l` a dependency of the recipe of aws_x."
  [a]
  (when a
    (if-let [[_ short] (re-find #"\.dep__(.+?)__" a)]
      [(str "aws_" short) false]
      (when-let [[_ t] (re-find #"(?:^|resource\.)(aws_[a-z0-9_]+)\.m\b" a)] [t true]))))

(defn- by-type
  "{type {:started? :finished? :errored? :errors [text] :drift?}} of the events of one Terraform run (a dependency of a recipe
  counts only for its errors)."
  [evs]
  (reduce (fn [m e]
            (let [addr (or (get-in e [:hook :resource :addr]) (get-in e [:change :resource :addr]) (get-in e [:diagnostic :address])
                           (get-in e [:diagnostic :snippet :context]))
                  [t main?] (addr-of addr)]
              (if-not t
                m
                (case (:type e)
                  "apply_start" (if main? (assoc-in m [t :started?] true) m)
                  "apply_complete" (if main? (assoc-in m [t :finished?] true) m)
                  "apply_errored" (if main? (assoc-in m [t :errored?] true) m)
                  "planned_change" (if main? (assoc-in m [t :drift?] true) m)
                  "resource_drift" (if main? (assoc-in m [t :drift?] true) m)
                  "diagnostic" (if (= "error" (get-in e [:diagnostic :severity]))
                                 (update-in m [t :errors] (fnil conj []) (str (get-in e [:diagnostic :summary]) " " (get-in e [:diagnostic :detail])))
                                 m)
                  m))))
          {} evs))

(defn- repairs
  "{attribute value} read from an error text that says what the provider wanted."
  [text]
  (merge
   (into {} (for [[_ a vs] (re-seq #"expected (\w+) to be one of \[\"?([^\"\]]*)" text)] [a vs]))
   (into {} (for [[_ a vs] (re-seq #"Path: (\w+)\s+Given Value: \S+\s+Valid Values: \[(\S+)" text)] [a vs]))
   (into {} (for [[_ a vs] (re-seq #"Attribute (\w+) value must be one of: \[\"([^\"]+)\"" text)] [a vs]))
   (into {} (for [[_ a] (re-seq #"\"([\w.]+)\" \(dlbm[^)]*\) is an invalid ARN" text)] [(last (str/split a #"\.")) "arn:aws:iam::000000000000:role/dlbm"]))
   (into {} (for [[_ a] (re-seq #"\"([\w.]+)\" doesn't look like AWS Account ID" text)] [(last (str/split a #"\.")) "000000000000"]))))

(def unsupported-signatures
  "Text in a Terraform error that says FLOCI does not implement the call or the service (measured: Floci answers
  `UnknownOperationException: Unknown operation: ...` with a 404 for a service it has not; extend after reading
  `<out>/unproven.txt`, then `--classify-only`)."
  [#"(?i)UnknownOperation" #"(?i)InvalidAction" #"(?i)not (yet )?implemented" #"(?i)unsupported operation" #"(?i)UnsupportedOperation"
   #"(?i)no such (service|action)" #"(?i)\b501\b" #"(?i)unknown (service|action|operation)" #"(?i)UnsupportedService" #"(?i)NotImplemented"])

(defn- first-error [texts]
  (let [t (first (remove str/blank? texts))]
    (str/trim (or (first (remove str/blank? (str/split-lines (str t)))) ""))))

(def infrastructure-errors
  "Errors that say the RUN went wrong, not the resource: the provider process died or was cancelled in a batch. Such a type has no verdict yet."
  #"(?i)plugin did not respond|request cancelled|plugin (exited|crashed)|transport is closing|context (canceled|deadline)|connection reset|unexpected EOF")

(defn- classify
  "`{:class :why [:drift]}` of one type from the events of its batch (apply, plan, destroy), or :undecided."
  [t a p d]
  (let [at (get a t) pt (get p t) dt (get d t)]
    (cond
      (and (:errors at) (re-find infrastructure-errors (str/join "\n" (:errors at)))) :undecided
      (:errors at) (let [txt (str/join "\n" (:errors at))]
                     (if (some #(re-find % txt) unsupported-signatures)
                       {:class :unsupported :why (first-error (:errors at))}
                       {:class :unproven :why (first-error (:errors at))}))
      (:finished? at) (cond
                        (:errors dt) {:class :unproven :why (str "applied, but destroy said: " (first-error (:errors dt)))}
                        (:errors pt) {:class :unproven :why (str "applied, but the refresh plan said: " (first-error (:errors pt)))}
                        :else (cond-> {:class :supported :why "applied and destroyed"}
                                (:drift? pt) (assoc :drift true :why "applied and destroyed; the refresh plan still wants a change (a read answers differently from the write)")))
      (:started? at) {:class :unproven :why "apply did not answer within the budget (killed)"}
      :else :undecided)))

;; --- batches --------------------------------------------------------------------------------------------------------

(defn- run-batch!
  "Apply, refresh plan, destroy of `types` in worker directory `wd`; raw to `bdir`."
  [{:keys [url cache budget fixes]} wd bdir types]
  (fs/create-dirs bdir)
  (let [env (base-env url cache)
        f (fs/file wd "res.tf.json")
        _ (spit f (json/generate-string (root-doc types fixes) {:pretty true}))
        _ (spit (fs/file bdir "res.tf.json") (slurp (str f)))
        _ (spit (fs/file bdir "types.edn") (pr-str (vec types)))
        state (str (fs/file bdir "terraform.tfstate"))
        run (fn [what extra] (apply tf! wd env (fs/file bdir (str what ".jsonl")) budget what "-no-color" "-input=false" "-json" (str "-state=" state) extra))
        ra (run "apply" ["-auto-approve" "-refresh=false" "-parallelism=10"])
        rp (run "plan" ["-detailed-exitcode" "-parallelism=10"])
        rd (run "destroy" ["-auto-approve" "-parallelism=10"])]
    (fs/delete-if-exists f)
    (spit (fs/file bdir "run.edn") (pr-str {:apply ra :plan rp :destroy rd :at (str (java.time.Instant/now))}))))

(defn- batch-results
  "[{type result} [undecided type]] of one batch directory, from its raw files."
  [bdir]
  (let [types (edn/read-string (slurp (str (fs/file bdir "types.edn"))))
        a (by-type (events (fs/file bdir "apply.jsonl")))
        p (by-type (events (fs/file bdir "plan.jsonl")))
        d (by-type (events (fs/file bdir "destroy.jsonl")))
        rs (into {} (for [t types] [t (classify t a p d)]))]
    [(into {} (remove (comp #{:undecided} val)) rs) (vec (for [[t v] rs :when (= :undecided v)] t))]))

;; --- main ----------------------------------------------------------------------------------------------------------

(defn- container-image-info [image]
  (let [r (p/sh ["podman" "image" "inspect" image "--format" "{{.Id}}|{{.Digest}}|{{json .RepoDigests}}"])
        [id digest repo] (str/split (str/trim (:out r)) #"\|" 3)]
    {:image image :image-id id :digest digest :repo-digests (json/parse-string (or repo "[]"))}))

(defn- validate!
  "Phase 1 in directory `vdir` under out. -> {:fixes {type {attr value}} :valid [type] :invalid {type result}}."
  [{:keys [url cache budget fixes]} out vdir types version]
  (let [wd (fs/file out vdir)
        env (base-env url cache)]
    (init-dir! wd url cache version)
    (loop [round 1 fixes fixes pending (vec types) invalid {}]
      (spit (fs/file wd "res.tf.json") (json/generate-string (root-doc pending fixes) {:pretty true}))
      (spit (fs/file wd (str "res-" round ".json")) (slurp (str (fs/file wd "res.tf.json"))))
      (let [raw (fs/file wd (str "round-" round ".jsonl"))
            r (tf! wd env raw budget "plan" "-no-color" "-input=false" "-json" "-refresh=false" "-lock=false" "-state=validate.tfstate")
            errs (by-type (events raw))
            failing (into {} (filter (fn [[_ v]] (:errors v))) errs)
            rep (into {} (for [[t v] failing
                               :let [rp (repairs (str/join "\n" (:errors v)))]
                               :when (and (seq rp) (not= (get fixes t) (merge (get fixes t) rp)))]
                           [t rp]))
            invalid (into invalid (for [[t v] failing :when (not (contains? rep t))]
                                    [t {:class :unproven :why (str "invalid generated resource: " (first-error (:errors v)))}]))
            fixes' (reduce (fn [m [t rp]] (update m t merge rp)) fixes rep)
            pending' (vec (remove invalid pending))]
        (println "validate round" round ": pending" (count pending) "failing" (count failing) "repairable" (count rep) "invalid so far" (count invalid) "exit" (:exit r))
        (if (and (seq rep) (< round 5))
          (recur (inc round) fixes' pending' invalid)
          {:fixes fixes'
           :valid (vec (remove (set (concat (keys invalid) (keys rep))) pending'))
           :invalid (into invalid (for [t (keys rep)] [t {:class :unproven :why "still invalid after 5 rounds of repair"}]))})))))

(defn- verdicts
  "{type result} from the files under `out`: what validation found invalid (every pass, in order), then every batch in order
  (a later batch overrides an earlier verdict of the same type)."
  [out types]
  (let [vdec (reduce (fn [m d] (merge m (edn/read-string (slurp (str (fs/file d "invalid.edn"))))))
                     {} (sort (filter #(fs/exists? (fs/file % "invalid.edn")) (filter fs/directory? (fs/list-dir out)))))
        bat (reduce (fn [m bd] (merge m (first (batch-results bd)))) {}
                    (sort (filter fs/directory? (fs/list-dir (fs/file out "batches")))))]
    (into (sorted-map) (for [t types] [t (or (get bat t) (get vdec t) {:class :unproven :why "no verdict (not reached)"})]))))

(defn- write-result! [out types health args]
  (let [rows (verdicts out types)
        by (group-by (comp :class val) rows)
        meta (edn/read-string (slurp (str (fs/file out "meta.edn"))))
        res {:measured (str (java.time.LocalDate/now)) :floci-version (:version health)
             :provider {:source "hashicorp/aws" :version (:provider-version meta)}
             :image (container-image-info "docker.io/floci/floci:2.3.0")
             :method (str "for each resource type of the AWS provider schema a generated minimal resource (required attributes and blocks; for types that "
                          "need other objects the recipe in bb/floci_measure_recipes.edn); one terraform plan validates them all (a value the provider names "
                          "as wrong is repaired, up to 5 rounds); the valid ones are applied in batches of 25 with real terraform apply -json against Floci "
                          "(AWS_ENDPOINT_URL), then a refreshing plan and destroy; a type the run itself failed for (plugin crash in a batch) is run again in halves. "
                          "supported = applied and destroyed; unsupported = Floci's answer says the call or service is not implemented (UnknownOperationException ...); "
                          "unproven = anything else (invalid generated resource, needs another object, provider refused, no answer in the budget), with the first error line")
             :count {:types (count rows) :supported (count (:supported by)) :unsupported (count (:unsupported by)) :unproven (count (:unproven by))}
             :supported (into (sorted-set) (map key) (:supported by))
             :drift (into (sorted-set) (comp (filter (comp :drift val)) (map key)) (:supported by))
             :unsupported (into (sorted-map) (map (fn [[t v]] [t (:why v)])) (:unsupported by))
             :unproven (into (sorted-map) (map (fn [[t v]] [t (:why v)])) (:unproven by))}]
    (spit (fs/file out "result.edn") (with-out-str (pp/pprint res)))
    (spit (fs/file out "unproven.txt") (str/join "\n" (for [[t v] (:unproven res)] (str t "\t" v))))
    (when-let [w (arg args "--write")] (spit w (with-out-str (pp/pprint res))))
    (println "supported" (count (:supported res)) "(drift" (count (:drift res)) ") unsupported" (count (:unsupported res)) "unproven" (count (:unproven res)))))

(defn- run-batches! [ctx out valid workers version]
  (let [queue (LinkedBlockingQueue.)
        pool (LinkedBlockingQueue.)
        ids (atom (apply max 0 (keep #(parse-long (str (fs/file-name %))) (fs/list-dir (fs/file out "batches")))))]
    (doseq [i (range workers)]
      (let [wd (fs/file out "work" (str i))] (init-dir! wd (:url ctx) (:cache ctx) version) (.put pool wd)))
    (doseq [b (partition-all (:batch ctx) valid)] (.put queue (vec b)))
    ;; one wave: run everything queued; the types without a verdict are queued again in halves (a single one once more)
    (loop [retried #{}]
      (let [wave (loop [acc []] (if-let [b (.poll queue)] (recur (conj acc b)) acc))]
        (when (seq wave)
          (let [futs (doall (for [b wave]
                              (future
                                (let [wd (.take pool) id (format "%04d" (swap! ids inc)) bdir (fs/file out "batches" id)]
                                  (try (run-batch! ctx wd bdir b)
                                       (let [[_ undecided] (batch-results bdir)]
                                         (println "batch" id "types" (count b) "undecided" (count undecided) (str (java.time.LocalTime/now)))
                                         undecided)
                                       (catch Throwable e (spit (fs/file out "batch-error.txt") (str id " " e "\n") :append true) [])
                                       (finally (.put pool wd)))))))
                retried (reduce (fn [r undecided]
                                  (cond
                                    (empty? undecided) r
                                    (and (= 1 (count undecided)) (r (first undecided))) r     ; alone twice: "no verdict"
                                    :else (do (doseq [h (partition-all (max 1 (quot (count undecided) 2)) undecided)] (.put queue (vec h)))
                                              (if (= 1 (count undecided)) (conj r (first undecided)) r))))
                                retried (map deref futs))]
            (recur retried)))))))

(defn -main [& args]
  (let [url (or (arg args "--url") "http://localhost:14566")
        out (fs/absolutize (or (arg args "--out") (str ".scratch/floci-measure/" (java.time.LocalDate/now))))
        workers (parse-long (or (arg args "--workers") "4"))
        batch-size (parse-long (or (arg args "--batch") "25"))
        budget (parse-long (or (arg args "--budget-s") "600"))
        cache (str (fs/absolutize ".scratch/tf-plugin-cache"))
        only (some-> (arg args "--only") (str/split #","))
        sdir (fs/file out "schema")
        health (json/parse-string (slurp (str url "/_floci/health")) true)]
    (fs/create-dirs out)
    (when-not (fs/exists? (fs/file sdir "schema.json"))
      (fs/create-dirs sdir)
      (spit (fs/file sdir "main.tf") "terraform {\n  required_providers {\n    aws = {\n      source  = \"hashicorp/aws\"\n      version = \"~> 6.0\"\n    }\n  }\n}\n")
      (let [env (base-env url cache)]
        (tf! sdir env (fs/file sdir "init.txt") 600 "init" "-no-color")
        (p/shell {:dir (str sdir) :env env :clear-env true :out (str (fs/file sdir "schema.json"))} "terraform" "providers" "schema" "-json")))
    (let [schema (-> (slurp (str (fs/file sdir "schema.json"))) (json/parse-string true) :provider_schemas first val)
          version (second (re-find #"version\s*=\s*\"([^\"]+)\"" (slurp (str (fs/file sdir ".terraform.lock.hcl")))))
          types (map name (sort (keys (:resource_schemas schema))))
          types (vec types)
          hand (when (fs/exists? "bb/floci_measure_fixes.edn") (edn/read-string (slurp "bb/floci_measure_fixes.edn")))]
      (reset! schema-state schema)
      (spit (fs/file out "meta.edn") (pr-str {:provider-version version :floci (:version health) :types (count types)}))
      (println "provider" version "types" (count types) "floci" (:version health) "workers" workers "batch" batch-size "out" (str out))
      (when-not (flag? args "--classify-only")
        (let [resume? (flag? args "--resume")
              _ (fs/create-dirs (fs/file out "batches"))
              pass (count (filter #(str/starts-with? (str (fs/file-name %)) "validate") (fs/list-dir out)))
              vdir (if (and resume? (pos? pass)) (str "validate-" (inc pass)) "validate")
              todo (vec (cond->> (if resume?
                                   (for [[t v] (verdicts out types) :when (= :unproven (:class v))] t)
                                   types)
                          only (filter (set only))))
              ctx {:url url :cache cache :budget budget :fixes (or hand {}) :batch batch-size}
              {:keys [fixes valid invalid]} (validate! ctx out vdir todo version)]
          (spit (fs/file out vdir "invalid.edn") (pr-str invalid))
          (spit (fs/file out vdir "fixes.edn") (pr-str fixes))
          (println "to measure" (count todo) "valid" (count valid) "invalid" (count invalid))
          (run-batches! (assoc ctx :fixes fixes) out valid workers version)))
      (write-result! out types health args))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
