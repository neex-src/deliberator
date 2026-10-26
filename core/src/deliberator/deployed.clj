(ns deliberator.deployed
  "What is deployed in an environment, cached (M4.2, principle 2).

  `(refresh! ws env-name)` asks the environment (read calls only, each through `aws/invoke!`, so every request and response is
  raw-persisted before anything is read from it):

    ECS         ListClusters -> ListServices per cluster -> DescribeServices (10 per call) -> DescribeTaskDefinition (once per
                task definition ARN)
    Lambda      ListFunctions -> GetFunctionConfiguration per function (at most `lambda-cap`; past it the ListFunctions row stands)
    CodeDeploy  ListDeployments -> GetDeployment of the newest `deployment-cap` (where present: a service that is not there,
                or not allowed, is an entry in `:errors`, not a failure)

  and derives `cache/<env>/deployed.edn` (workspace level, atomic) with `:fetched-at`, `:services`, `:deployments`, `:errors` and
  the `:raw` refs `{:op :request :response}` of every call, plus the text view `cache/<env>/deployed.txt` (one row per service,
  tab separated, for grep and the editor). A section that fails is reported and the others still answer; when no section
  answered the old cache stays and the refresh throws `deployed/refresh-failed`. Only identity fields are extracted: names,
  ARNs, revisions, image references, counts, dates. Environment variables are never copied into the view, and the raw
  responses of a remote environment have their values redacted (`aws`).

  `(show ws env-name opts)` answers from the cache only, never from AWS, with its age. `:service` narrows to a service name,
  `cluster/name` or a function name; no cache is `deployed/no-cache` (hint: refresh); an unknown service is
  `deployed/not-found` with the known names."
  (:require [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant)
           (java.util Date)))

(def lambda-cap "GetFunctionConfiguration is called for at most this many functions." 300)
(def deployment-cap "GetDeployment is called for the newest this many deployments." 10)
(def ^:private max-pages 200)

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn cache-path [ws env-name] (store/path ws "cache" (store/safe-name env-name) "deployed.edn"))
(defn text-path [ws env-name] (store/path ws "cache" (store/safe-name env-name) "deployed.txt"))

(defn cached
  "The cached view of `env-name`, nil when none was fetched."
  [ws env-name] (store/read-edn (cache-path ws env-name)))

(defn- ->inst [x]
  (cond (instance? Instant x) x
        (instance? Date x) (.toInstant ^Date x)
        (number? x) (Instant/ofEpochMilli (long (* 1000 x)))
        :else nil))

;; --- calls ----------------------------------------------------------------------------------------------

(defn- ref-of [op r]
  (let [raw (aws/raw-refs r)]
    {:op op :request (get-in raw [:request :sha]) :response (get-in raw [:response :sha])}))

(defn- err-of [op r & [extra]]
  (merge (select-keys (assoc r :op op) [:op :category :code :message :hint]) extra))

(defn- call!
  "One call: -> {:r response-or-nil :ref ref :error err-or-nil}."
  [inv op svc req extra]
  (let [r (inv svc op req)]
    (cond-> {:ref (ref-of (name op) r)}
      (aws/error? r) (assoc :error (err-of (name op) r extra))
      (not (aws/error? r)) (assoc :r r))))

(defn- paged!
  "Calls until the response has no token: -> {:items [..] :refs [..] :errors [..]}. `items` is the response key holding
  the page's items, `tok` the response's token key and `req-tok` the request's."
  [inv svc op req items tok req-tok extra]
  (loop [token nil acc [] refs [] n 0]
    (let [{:keys [r ref error]} (call! inv op svc (cond-> req token (assoc req-tok token)) extra)
          refs (conj refs ref)]
      (cond error {:items acc :refs refs :errors [error]}
            (and (get r tok) (< n max-pages)) (recur (get r tok) (into acc (get r items)) refs (inc n))
            :else {:items (into acc (get r items)) :refs refs :errors []}))))

;; --- rows (pure) ------------------------------------------------------------------------------------------

(defn image-parts
  "`repo/name:tag@sha256:…` -> {:image full :tag \"tag\" :digest \"sha256:…\"} (absent parts left out)."
  [image]
  (let [[base digest] (str/split (str image) #"@" 2)
        slash (str/last-index-of base "/")
        colon (str/last-index-of base ":")
        tag (when (and colon (> colon (or slash -1))) (subs base (inc colon)))]
    (cond-> {:image image}
      tag (assoc :tag tag)
      digest (assoc :digest digest))))

(defn arn-tail [arn] (last (str/split (str arn) #"[/:]")))

(defn task-definition-row
  "The identity of a DescribeTaskDefinition answer: family, revision, containers (name, image, tag), cpu, memory."
  [r]
  (let [td (:taskDefinition r)]
    (cond-> {:arn (:taskDefinitionArn td) :family (:family td) :revision (:revision td) :status (:status td)
             :containers (mapv #(merge {:name (:name %)} (image-parts (:image %))) (:containerDefinitions td))}
      (:cpu td) (assoc :cpu (:cpu td))
      (:memory td) (assoc :memory (:memory td))
      (:registeredAt td) (assoc :registered-at (->inst (:registeredAt td))))))

(defn ecs-row
  "A service of DescribeServices plus its task definition row (nil when it could not be read)."
  [cluster-name s td]
  (let [deps (mapv (fn [d] (cond-> {:id (:id d) :status (:status d) :task-definition (:taskDefinition d)
                                    :desired (:desiredCount d) :running (:runningCount d)}
                             (:rolloutState d) (assoc :rollout-state (:rolloutState d))
                             (:updatedAt d) (assoc :updated-at (->inst (:updatedAt d)))))
                   (:deployments s))]
    (cond-> {:kind :ecs :cluster cluster-name :name (:serviceName s) :arn (:serviceArn s) :status (:status s)
             :desired (:desiredCount s) :running (:runningCount s) :pending (:pendingCount s)
             :task-definition (:taskDefinition s)
             :revision (or (:revision td) (some-> (:taskDefinition s) arn-tail parse-long))
             :deployments deps}
      (:launchType s) (assoc :launch-type (:launchType s))
      (:createdAt s) (assoc :created-at (->inst (:createdAt s)))
      (:family td) (assoc :family (:family td))
      (seq (:containers td)) (assoc :containers (:containers td))
      (:deploymentController s) (assoc :controller (get-in s [:deploymentController :type])))))

(defn lambda-row [f]
  (cond-> {:kind :lambda :name (:FunctionName f) :arn (:FunctionArn f) :version (:Version f)
           :runtime (:Runtime f) :handler (:Handler f) :memory (:MemorySize f) :timeout (:Timeout f)
           :code-sha256 (:CodeSha256 f) :code-size (:CodeSize f) :package-type (:PackageType f)
           :last-modified (:LastModified f)}
    (:State f) (assoc :state (:State f))
    (:LastUpdateStatus f) (assoc :last-update-status (:LastUpdateStatus f))
    (:Description f) (assoc :description (:Description f))
    (seq (:Architectures f)) (assoc :architectures (vec (:Architectures f)))
    (:RevisionId f) (assoc :revision-id (:RevisionId f))
    (:ImageUri f) (assoc :image (:ImageUri f))
    (:ResolvedImageUri f) (assoc :resolved-image (:ResolvedImageUri f))))

(defn deployment-row [d]
  (let [i (:deploymentInfo d)]
    (cond-> {:id (:deploymentId i) :application (:applicationName i) :group (:deploymentGroupName i) :status (:status i)
             :config (:deploymentConfigName i)}
      (:createTime i) (assoc :created-at (->inst (:createTime i)))
      (:completeTime i) (assoc :completed-at (->inst (:completeTime i)))
      (:creator i) (assoc :creator (:creator i))
      (get-in i [:errorInformation :code]) (assoc :error-code (get-in i [:errorInformation :code])))))

;; --- sections -----------------------------------------------------------------------------------------------

(defn- ecs-section! [inv]
  (let [cl (paged! inv :ecs :ListClusters {:maxResults 100} :clusterArns :nextToken :nextToken nil)
        refs (volatile! (:refs cl))
        errors (volatile! (:errors cl))
        per-cluster
        (doall
         (for [carn (:items cl)
               :let [cname (arn-tail carn)
                     sv (paged! inv :ecs :ListServices {:cluster carn :maxResults 100} :serviceArns :nextToken :nextToken {:cluster cname})
                     _ (vswap! refs into (:refs sv)) _ (vswap! errors into (:errors sv))
                     described (doall
                                (for [chunk (partition-all 10 (:items sv))
                                      :let [d (call! inv :DescribeServices :ecs {:cluster carn :services (vec chunk)} {:cluster cname})]]
                                  (do (vswap! refs conj (:ref d))
                                      (when (:error d) (vswap! errors conj (:error d)))
                                      (let [fails (get-in d [:r :failures])]
                                        (when (seq fails)
                                          (vswap! errors conj {:op "DescribeServices" :cluster cname
                                                               :message (str (count fails) " service(s) not described: "
                                                                             (str/join ", " (map #(str (arn-tail (:arn %)) " " (:reason %)) fails)))})))
                                      (get-in d [:r :services]))))]]
           [cname (apply concat described)]))
        arns (into (sorted-set) (for [[_ ss] per-cluster, s ss :when (:taskDefinition s)] (:taskDefinition s)))
        tds (into {} (for [arn arns
                           :let [d (call! inv :DescribeTaskDefinition :ecs {:taskDefinition arn} {:task-definition arn})]]
                       (do (vswap! refs conj (:ref d))
                           (when (:error d) (vswap! errors conj (:error d)))
                           [arn (when (:r d) (task-definition-row (:r d)))])))]
    {:rows (vec (for [[cname ss] per-cluster, s ss] (ecs-row cname s (get tds (:taskDefinition s)))))
     :refs @refs :errors @errors :clusters (mapv arn-tail (:items cl))}))

(defn- lambda-section! [inv]
  (let [ls (paged! inv :lambda :ListFunctions {:MaxItems 50} :Functions :NextMarker :Marker nil)
        fns (:items ls)
        detail (doall (pmap (fn [f] (call! inv :GetFunctionConfiguration :lambda {:FunctionName (:FunctionName f)} {:function (:FunctionName f)}))
                            (take lambda-cap fns)))]
    {:rows (vec (for [[f d] (map vector fns (concat detail (repeat nil)))]
                  (lambda-row (merge f (:r d)))))
     :refs (into (vec (:refs ls)) (map :ref) detail)
     :errors (into (vec (:errors ls)) (keep :error) detail)
     :truncated (when (> (count fns) lambda-cap) (count fns))}))

(def ^:private absent-codes
  "Error codes that say the service is not there for this account or emulator (not subscribed, not implemented): the
  section is `:absent`, not failed (\"where present\")."
  #{"SubscriptionRequiredException" "OptInRequired" "UnknownOperationException" "UnknownOperation" "NotImplemented"
    "NotImplementedException" "UnsupportedOperation" "InvalidAction"})

(defn- codedeploy-section! [inv]
  (let [ls (paged! inv :codedeploy :ListDeployments {} :deployments :nextToken :nextToken nil)]
    (if (and (seq (:errors ls)) (absent-codes (:code (first (:errors ls)))))
      {:rows [] :refs (:refs ls) :errors [] :absent (:code (first (:errors ls)))}
      (let [detail (doall (for [id (take deployment-cap (:items ls))]
                            (call! inv :GetDeployment :codedeploy {:deploymentId id} {:deployment id})))]
        {:rows (vec (for [d detail :when (:r d)] (deployment-row (:r d))))
         :refs (into (vec (:refs ls)) (map :ref) detail)
         :errors (into (vec (:errors ls)) (keep :error) detail)}))))

;; --- views --------------------------------------------------------------------------------------------------

(defn text-view
  "The tab separated view: kind, cluster, name, revision|version, tags|code sha, desired/running, updated."
  [{:keys [env fetched-at services deployments errors absent]}]
  (str "# deployed in " env ", fetched " fetched-at "\n"
       "# kind\tcluster\tname\trevision/version\timages/tags\tdesired/running\tupdated\n"
       (apply str (for [s services]
                    (str (name (:kind s)) "\t" (or (:cluster s) "-") "\t" (:name s) "\t"
                         (case (:kind s) :ecs (str "rev " (:revision s)) :lambda (str (:version s)) "") "\t"
                         (case (:kind s)
                           :ecs (str/join "," (for [c (:containers s)] (str (:name c) ":" (or (:tag c) (:digest c) (:image c)))))
                           :lambda (or (:image s) (:code-sha256 s) "")
                           "") "\t"
                         (case (:kind s) :ecs (str (:desired s) "/" (:running s)) "") "\t"
                         (or (some-> (case (:kind s) :ecs (->> (:deployments s) (keep :updated-at) sort last) (:last-modified s)) str) "")
                         "\n")))
       (when (seq deployments)
         (str "# codedeploy\n"
              (apply str (for [d deployments] (str "deployment\t" (:application d) "/" (:group d) "\t" (:id d) "\t" (:status d) "\t" (:created-at d) "\n")))))
       (when (seq absent)
         (str "# absent: " (str/join ", " (for [[sec code] absent] (str (name sec) " (" code ")"))) "\n"))
       (when (seq errors)
         (str "# errors\n"
              (apply str (for [[sec es] errors, e es] (str (name sec) "\t" (:op e) "\t" (:code e) " " (:message e) "\n")))))))

(defn age-text
  "`3 min`, `2 h 5 min`, `4 days` for a millisecond age."
  [ms]
  (let [s (quot (max 0 ms) 1000)]
    (cond (< s 60) (str s " s")
          (< s 3600) (str (quot s 60) " min")
          (< s 86400) (str (quot s 3600) " h " (rem (quot s 60) 60) " min")
          :else (str (quot s 86400) " days"))))

;; --- refresh / show -----------------------------------------------------------------------------------------

(defn refresh!
  "See the namespace docstring. -> `{:env :fetched-at :services n :ecs n :lambda n :deployments n :path :text :raw n [:errors {..}]}`."
  [ws env-name]
  (let [e (env/load ws env-name)
        ctx {:ws ws}
        inv (fn [svc op req] (aws/invoke! ctx e svc op req))
        sections {:ecs (ecs-section! inv) :lambda (lambda-section! inv) :codedeploy (codedeploy-section! inv)}
        errors (into (sorted-map) (for [[k v] sections :when (seq (:errors v))] [k (:errors v)]))
        answered? (fn [[_ v]] (or (seq (:rows v)) (empty? (:errors v))))
        now (store/now)]
    (when-not (some answered? sections)
      (let [first-err (-> errors vals first first)]
        (fail :deployed/refresh-failed
              (str "could not fetch what is deployed in environment " (:name e) ": " (or (:message first-err) (:code first-err) "no section answered"))
              :hint (:hint first-err) :errors errors)))
    (let [services (vec (concat (get-in sections [:ecs :rows]) (get-in sections [:lambda :rows])))
          snap (cond-> {:env (:name e) :fetched-at now :endpoint (or (get-in e [:aws :endpoint]) (get-in e [:aws :profile]))
                        :services services
                        :deployments (get-in sections [:codedeploy :rows])
                        :clusters (get-in sections [:ecs :clusters])
                        :raw (vec (mapcat :refs (vals sections)))}
                 (seq errors) (assoc :errors errors)
                 (get-in sections [:codedeploy :absent]) (assoc :absent {:codedeploy (get-in sections [:codedeploy :absent])})
                 (get-in sections [:lambda :truncated]) (assoc :truncated {:lambda (get-in sections [:lambda :truncated])}))
          target (store/write-edn! (cache-path ws (:name e)) snap)
          txt (store/write-bytes-atomic! (text-path ws (:name e)) (.getBytes ^String (text-view snap) StandardCharsets/UTF_8))]
      (cond-> {:env (:name e) :fetched-at now :services (count services)
               :ecs (count (get-in sections [:ecs :rows])) :lambda (count (get-in sections [:lambda :rows]))
               :deployments (count (:deployments snap))
               :path (str target) :text (str txt) :raw (count (:raw snap))}
        (seq errors) (assoc :errors errors)
        (seq (:absent snap)) (assoc :absent (:absent snap))))))

(defn show
  "The cached view of `env-name` with its age, narrowed by `:service`; never calls AWS. `:now` for tests."
  [ws env-name {:keys [service now]}]
  (let [snap (cached ws env-name)
        now (or now (store/now))]
    (when-not snap
      (fail :deployed/no-cache (str "nothing fetched yet for environment " env-name)
            :hint (str "dlb deployed refresh --env " env-name " fetches it (read calls only)")))
    (let [age (- (.toEpochMilli ^Instant now) (.toEpochMilli ^Instant (:fetched-at snap)))
          all (:services snap)
          picked (if (str/blank? service)
                   all
                   (filterv #(or (= service (:name %)) (= service (str (:cluster %) "/" (:name %)))) all))]
      (when (and (not (str/blank? service)) (empty? picked))
        (fail :deployed/not-found (str "no service " service " in the cached view of " env-name)
              :names (vec (sort (distinct (map :name all))))
              :hint "the names the cache holds are listed; dlb deployed refresh refetches"))
      (cond-> {:env (:env snap) :fetched-at (:fetched-at snap) :age-ms age :age (age-text age)
               :services picked :count (count picked)
               :path (str (cache-path ws env-name)) :text (str (text-path ws env-name))}
        (str/blank? service) (assoc :deployments (:deployments snap))
        (seq (:errors snap)) (assoc :errors (:errors snap))
        (seq (:absent snap)) (assoc :absent (:absent snap))))))
