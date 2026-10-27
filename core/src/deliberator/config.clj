(ns deliberator.config
  "The configuration chain of a service: the Spring placeholders of its module, the task definition
  entry each one is supplied by, and the SSM parameter or secret that entry points at.

  `(chain ws name env)` is offline: it reads the module's `application*.properties|yml`, the description's
  adopted `:taskdef {:environment :secrets}` (the authoritative names), `taskdef.json.tftpl` and `main.tf` of the
  description's `:taskdef :module` (navigation only, Terraform is never evaluated) and the cached snapshot of `env`
  (`config/<env>.edn`). It never calls AWS; without a snapshot every parameter is `unknown`.

  `(refresh! ws env opts)` is the only fetch: `ssm GetParametersByPath` (paged, recursive, no decryption) +
  `secretsmanager ListSecrets` + `DescribeSecret`, every response raw-persisted by `aws/invoke!` first, then the
  snapshot `config/<env>.edn` in the workspace (+ `sessions/<key>/config/<env>-<ts>.edn` under the open session).
  Secret values are never asked for. Every `file` in a result is absolute."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.config.sources :as src]
            [deliberator.env :as env]
            [deliberator.repo :as repo]
            [deliberator.service.description :as description]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.time Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

;; snapshot ------------------------------------------------------------------------------------

(defn snapshot-path [ws env] (store/path ws "config" (str (store/safe-name env) ".edn")))

(defn snapshot
  "The cached snapshot of `env`, nil when none was fetched."
  [ws env] (store/read-edn (snapshot-path ws env)))

(defn- ->inst [x]
  (cond (instance? Instant x) x
        (instance? Date x) (.toInstant ^Date x)
        (number? x) (Instant/ofEpochMilli (long (* 1000 x)))
        :else nil))

(defn- aws-error [op r]
  (select-keys (assoc r :op op) [:op :category :code :message :hint]))

(defn- raw-ref [op r]
  (let [raw (aws/raw-refs r)]
    {:op op :request (get-in raw [:request :sha]) :response (get-in raw [:response :sha])}))

(defn- param-row [p]
  (let [secure? (= "SecureString" (:Type p))]
    (cond-> {:name (:Name p) :type (:Type p) :version (:Version p) :arn (:ARN p)
             :last-modified (->inst (:LastModifiedDate p)) :data-type (:DataType p)}
      secure? (assoc :secure true)
      (not secure?) (assoc :value (:Value p)))))

(defn- secret-row [m]
  (cond-> {:name (:Name m) :arn (:ARN m) :description (:Description m)
           :last-changed (->inst (:LastChangedDate m)) :last-accessed (->inst (:LastAccessedDate m))
           :created (->inst (:CreatedDate m)) :deleted (->inst (:DeletedDate m))
           :kms-key-id (:KmsKeyId m) :rotation-enabled (:RotationEnabled m)}
    (:VersionIdsToStages m) (assoc :version-stages (into (sorted-map) (map (fn [[k v]] [(name k) (vec v)])) (:VersionIdsToStages m)))
    (seq (:Tags m)) (assoc :tags (into (sorted-map) (map (fn [t] [(:Key t) (:Value t)])) (:Tags m)))))

(defn- paged
  "Calls `f` with the request plus `:NextToken` until the token is gone; -> {:pages [result] :error err-or-nil :refs [..]}."
  [op f request]
  (loop [token nil pages [] refs []]
    (let [r (f (cond-> request token (assoc :NextToken token)))
          refs (conj refs (raw-ref op r))]
      (cond (aws/error? r) {:pages pages :refs refs :error (aws-error op r)}
            (:NextToken r) (recur (:NextToken r) (conj pages r) refs)
            :else {:pages (conj pages r) :refs refs}))))

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC))

(defn refresh!
  "Fetches the parameters and secrets (metadata) of environment `env` and writes the snapshot. `opts`: `:path` (default
  \"/\"). -> `{:env :fetched-at :parameters n :secrets n :path :copy :raw [..] :errors {..}}`; throws
  `config/refresh-failed` when neither section could be fetched (the old snapshot stays)."
  [ws env-name {:keys [path] :or {path "/"}}]
  (let [e (env/load ws env-name)
        ctx {:ws ws}
        inv (fn [svc op req] (aws/invoke! ctx e svc op req))
        ssm (paged "GetParametersByPath" #(inv :ssm :GetParametersByPath %)
                   {:Path path :Recursive true :WithDecryption false :MaxResults 10})
        sec (paged "ListSecrets" #(inv :secretsmanager :ListSecrets %) {:MaxResults 100})
        listed (when-not (:error sec) (mapcat :SecretList (:pages sec)))
        described (when-not (:error sec)
                    (mapv (fn [m] (let [r (inv :secretsmanager :DescribeSecret {:SecretId (or (:ARN m) (:Name m))})]
                                    {:list m :ref (raw-ref "DescribeSecret" r) :desc (if (aws/error? r) nil r)
                                     :error (when (aws/error? r) (aws-error "DescribeSecret" r))}))
                          listed))
        errors (cond-> {}
                 (:error ssm) (assoc :ssm (:error ssm))
                 (:error sec) (assoc :secrets (:error sec))
                 (some :error described) (assoc :describe (vec (keep :error described))))
        now (store/now)]
    (when (and (:error ssm) (:error sec))
      (fail :config/refresh-failed (str "could not fetch the configuration of environment " (:name e) ": " (get-in ssm [:error :message]))
            :hint (get-in ssm [:error :hint]) :errors errors))
    (let [params (when-not (:error ssm) (into (sorted-map) (map (fn [p] [(:Name p) (param-row p)])) (mapcat :Parameters (:pages ssm))))
          secrets (when-not (:error sec)
                    (into (sorted-map) (map (fn [{:keys [list desc]}] (let [row (secret-row (merge list desc))] [(:name row) row])) described)))
          snap (cond-> {:env (:name e) :fetched-at now :endpoint (or (get-in e [:aws :endpoint]) (get-in e [:aws :profile]))
                        :path path
                        :raw (vec (concat (:refs ssm) (:refs sec) (map :ref described)))}
                 params (assoc :parameters params)
                 secrets (assoc :secrets secrets)
                 (seq errors) (assoc :errors errors))
          target (store/write-edn! (snapshot-path ws (:name e)) snap)
          copy (when-let [s (session/current ws)]
                 (str (store/write-edn! (store/path ws "sessions" (:key s) "config"
                                                    (str (:name e) "-" (.format stamp now) ".edn"))
                                        snap)))]
      (cond-> {:env (:name e) :fetched-at now :parameters (count params) :secrets (count secrets)
               :path (str target) :raw (:raw snap)}
        copy (assoc :copy copy)
        (seq errors) (assoc :errors errors)))))

;; parameter lookup ----------------------------------------------------------------------------

(defn- strip-suffix [id] (str/replace id #"-[A-Za-z0-9]{6}$" ""))

(defn- lookup-param
  "The parameter view of a `valueFrom` against `snap` (nil snapshot = unknown)."
  [snap value-from env-name]
  (let [ref (src/parse-ref value-from)
        base {:ref value-from :fetched-at (:fetched-at snap)}
        unknown (fn [reason] (assoc base :exists nil :status :unknown :reason reason))]
    (cond
      (nil? ref) (assoc base :exists nil :status :unresolved
                        :reason (if (str/blank? value-from) "no valueFrom" "valueFrom is a template expression, not evaluated"))
      (nil? snap) (assoc (unknown (str "no snapshot of environment " env-name))
                         :name (or (:name ref) (:id ref)) :kind (:kind ref)
                         :hint (str "dlb config refresh --env " env-name))
      :else
      (case (:kind ref)
        :ssm
        (let [nm (:name ref) b (assoc base :name nm :kind :ssm)
              p (get-in snap [:parameters nm])]
          (cond (get-in snap [:errors :ssm]) (assoc b :exists nil :status :unknown :reason "the last refresh could not list parameters")
                p (assoc b :exists true :status :present
                         :metadata (dissoc p :value :name) :value (:value p) :secure (:secure p))
                (and (:hierarchical? ref) (str/starts-with? nm (:path snap "/"))) (assoc b :exists false :status :missing)
                :else (assoc b :exists nil :status :unknown :reason "outside the path the snapshot listed")))
        :secret
        (let [b (assoc base :kind :secret :name (strip-suffix (:id ref)))
              secrets (:secrets snap)
              s (or (first (filter #(= (:arn ref) (:arn %)) (vals secrets)))
                    (get secrets (:id ref))
                    (get secrets (strip-suffix (:id ref))))]
          (cond (get-in snap [:errors :secrets]) (assoc b :exists nil :status :unknown :reason "the last refresh could not list secrets")
                s (assoc b :name (:name s) :exists true :status :present :metadata s
                         :json-key (:json-key ref))
                :else (assoc b :exists false :status :missing)))))))

;; chain ---------------------------------------------------------------------------------------

(defn- abs-path [^File f] (str (.getAbsoluteFile f)))

(defn- read-text [^File f] (when (.isFile f) (slurp f :encoding "UTF-8")))

(defn- config-files
  "[{:file File :kind :properties|:yaml}] under the module's resources, sorted."
  [^File module]
  (vec (for [d [(io/file module "src/main/resources") (io/file module "src/main/resources/config")]
             :when (.isDirectory d)
             ^File f (sort-by #(.getName ^File %) (.listFiles d))
             :let [n (.getName f)]
             :when (and (.isFile f) (re-matches #"application(-[^/]+)?\.(properties|ya?ml)" n))]
         {:file f :kind (if (str/ends-with? n ".properties") :properties :yaml)})))

(defn- taskdef-dir
  "The directory of the description's Terraform module (relative to the attached repository), or nil."
  [ws {:keys [repo taskdef]}]
  (when-let [m (:module taskdef)]
    (let [f (io/file m)]
      (cond (.isAbsolute f) f
            repo (when-let [r (first (filter #(= repo (:name %)) (repo/checkouts ws)))] (io/file (:path r) m))))))

(defn- relaxed [n] (str/upper-case (str/replace n #"[.\-]" "_")))

(defn- taskdef-entry
  "The task definition entry that supplies `nm`: rendered (adopted) first, then the declaration in main.tf only.
  Names match exactly, then in Spring's relaxed form."
  [{:keys [env-map secret-map tf-map tf-file tftpl-file tftpl-info]} nm]
  (let [find-in (fn [m] (or (when (contains? m nm) [nm :exact])
                            (when-let [k (first (filter #(= (relaxed nm) (relaxed %)) (keys m)))] [k :relaxed])))
        [kind key match rendered-value]
        (or (when-let [[k mt] (find-in env-map)] [:env k mt (get env-map k)])
            (when-let [[k mt] (find-in secret-map)] [:secret k mt (get secret-map k)])
            (when-let [[k mt] (find-in tf-map)] [(get-in tf-map [k :kind]) k mt nil]))]
    (when kind
      (let [adopted? (boolean (or (contains? env-map key) (contains? secret-map key)))
            tf (get tf-map key)
            lit (get-in tftpl-info [:literals key])
            loop-line (get-in tftpl-info [:loops kind])
            value (cond adopted? rendered-value (:literal? tf) (:value tf))]
        (cond-> {:kind kind :name key :match match :adopted adopted?
                 :literal (boolean (:literal? tf)) :expr (:expr tf)}
          (= kind :env) (assoc :value value)
          (= kind :secret) (assoc :value-from value)
          (or lit loop-line) (assoc :file (some-> tftpl-file abs-path) :line (or (:line lit) loop-line)
                                    :via (if lit :literal :loop))
          tf (assoc :source {:file (some-> tf-file abs-path) :line (:line tf) :expr (:expr tf) :literal (boolean (:literal? tf))}))))))

(defn- with-param [e snap env-name]
  (if (= :secret (:kind e))
    (assoc e :parameter (lookup-param snap (:value-from e) env-name))
    e))

(defn chain
  "See the namespace docstring. -> `{:service :env :module :snapshot :files :entries :extra :broken :unresolved :hint}`."
  [ws svc env-name]
  (let [desc (or (description/read ws svc)
                 (fail :service/not-found (str "no service " (pr-str svc) " in workspace " (store/ws-name ws))
                       :known (description/names ws) :hint "service.adopt a module, or dlb service list"))
        _ (env/load ws env-name)
        module (description/module-dir ws desc)
        snap (snapshot ws env-name)
        tdir (taskdef-dir ws desc)
        tftpl-file (some-> tdir (io/file "taskdef.json.tftpl"))
        tf-file (some-> tdir (io/file "main.tf"))
        tftpl-info (some-> tftpl-file read-text src/tftpl)
        tf-text (some-> tf-file read-text)
        tf-map (if tf-text (src/main-tf tf-text (get-in desc [:taskdef :key])) {})
        ctx {:env-map (get-in desc [:taskdef :environment]) :secret-map (get-in desc [:taskdef :secrets])
             :tf-map tf-map :tf-file tf-file :tftpl-file tftpl-file :tftpl-info tftpl-info}
        files (config-files module)
        texts (for [{:keys [file kind]} files] (assoc {:file file :kind kind} :text (slurp file :encoding "UTF-8")))
        defined (into #{} (mapcat #(src/defined-keys (:text %) (:kind %))) texts)
        entries (vec (for [{:keys [file kind text]} texts
                           p (src/placeholders text kind)
                           :let [internal? (contains? defined (:placeholder p))
                                 td (when-not internal? (some-> (taskdef-entry ctx (:placeholder p)) (with-param snap env-name)))
                                 e (cond-> (assoc p :file (abs-path file) :internal internal?)
                                     td (assoc :taskdef (dissoc td :parameter) :parameter (:parameter td))
                                     (and (not td) (not internal?) (some? (:default p))) (assoc :uses-default true))]]
                       e))
        used (set (keep #(get-in % [:taskdef :name]) entries))
        extra (vec (for [[kind m] [[:env (:env-map ctx)] [:secret (:secret-map ctx)]]
                         [k _] m
                         :when (not (used k))
                         :let [td (-> (taskdef-entry (assoc ctx :tf-map tf-map) k) (with-param snap env-name))]
                         :when td]
                     (cond-> {:taskdef (dissoc td :parameter)} (:parameter td) (assoc :parameter (:parameter td)))))
        broken (vec (concat
                     (for [e entries :when (and (not (:internal e)) (not (:taskdef e)) (nil? (:default e)))]
                       {:kind :placeholder-without-entry
                        :reason (str "no environment or secrets entry named " (:placeholder e) " in the task definition and no default")
                        :entry e})
                     (for [e (concat entries extra) :when (= :missing (get-in e [:parameter :status]))]
                       {:kind :parameter-missing
                        :reason (str (name (get-in e [:parameter :kind])) " " (get-in e [:parameter :name]) " is not in the snapshot of " env-name)
                        :entry e})))
        unresolved (vec (for [e (concat entries extra) :when (= :unresolved (get-in e [:parameter :status]))]
                          {:kind :unresolved :reason (get-in e [:parameter :reason]) :entry e}))]
    {:service svc :env env-name :module (abs-path module)
     :snapshot (if snap
                 {:present true :path (str (snapshot-path ws env-name)) :fetched-at (:fetched-at snap)
                  :age-ms (.toMillis (Duration/between ^Instant (:fetched-at snap) (store/now)))}
                 {:present false :path (str (snapshot-path ws env-name))})
     :files (mapv #(abs-path (:file %)) texts)
     :taskdef-files (cond-> {} tftpl-file (assoc :tftpl (abs-path tftpl-file)) tf-file (assoc :main-tf (abs-path tf-file)))
     :entries entries :extra extra :broken broken :unresolved unresolved
     :hint (when-not snap (str "no snapshot of environment " env-name ": dlb config refresh --env " env-name))}))
