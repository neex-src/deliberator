(ns deliberator.codedeploy
  "What a CodeDeploy deployment is, for the deploy view (cicd-aws piece 15b): its row, its appspec, its targets with their lifecycle
  events, the log lines of its hook functions, a rollback and why.

  CodeDeploy deployments are read as the real API answers them, but this has only been checked against the local emulator, not
  against real CodeDeploy (the account is on AWS's Free plan, which refuses the service). The shapes are the ones of aws-api's
  `codedeploy` descriptors: `GetDeployment`/`BatchGetDeployments` (`deploymentInfo`: status, `errorInformation`, `rollbackInfo`,
  `revision.appSpecContent`), `ListDeploymentTargets` + `BatchGetDeploymentTargets` (`ecsTarget`, `lambdaTarget`, `lifecycleEvents`
  with `diagnostics` {errorCode scriptName message logTail}). What the emulator (Floci 2.3.0) does not answer is shown as `the endpoint
  gave none`, never as an empty success: no `lambdaFunctionInfo`, no `diagnostics.logTail`, no `rollbackInfo`, no `deploymentOverview`,
  and `GetDeploymentTarget` is refused (`InvalidAction`; `BatchGetDeploymentTargets` is the call used).

  Every function that asks takes `call`, `(fn [service op request & [poll-key]])` -> the response or an aws error (the deploy view's own
  call, which persists every request and answer raw first); the pure functions take what the calls answered."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [deliberator.aws :as aws])
  (:import (java.time Instant OffsetDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(def emulator-note
  "The sentence the view and the ops say about what has been checked."
  "CodeDeploy deployments are read as the real API answers them, but this has only been checked against the local emulator, not against real CodeDeploy.")

(def id-cap "Deployment ids read per group (the API's order is not documented: all of them are fetched, then sorted)." 100)
(def list-cap "Deployments of a group kept (newest first)." 20)
(def hook-line-cap "Log lines shown per hook function." 500)

(defn ->inst [x]
  (cond (nil? x) nil
        (instance? Instant x) x
        (instance? Date x) (.toInstant ^Date x)
        (number? x) (Instant/ofEpochMilli (long (* 1000 x)))
        (string? x) (or (try (Instant/parse x) (catch Exception _ nil))
                        (try (.toInstant (OffsetDateTime/parse x (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSxx")))
                             (catch Exception _ nil)))
        :else nil))

(defn- tail-of [arn] (last (str/split (str arn) #"[/:]")))

;; --- the appspec -----------------------------------------------------------------------------------------

(defn- appspec-text [revision]
  (or (get-in revision [:appSpecContent :content]) (get-in revision [:string :content])))

(defn lambda-function-name
  "`name`, `name:alias` or a function ARN -> the function's name."
  [s]
  (let [s (str s)]
    (if (str/starts-with? s "arn:")
      (second (re-find #":function:([^:]+)" s))
      (first (str/split s #":" 2)))))

(defn parse-appspec
  "The revision of a deployment (`revision` of `deploymentInfo`; the appspec is JSON or YAML) -> `{:task-definition :container :port}` for an
  ECS service, `{:function :alias :current :target}` for a Lambda function, `:hooks [{:event :function}]`, or `{:note why}` when the appspec is
  not in the answer (an S3 or GitHub revision) or does not parse."
  [revision]
  (let [txt (appspec-text revision)]
    (if (str/blank? txt)
      {:note (str "the appspec is not in the answer (revision type " (or (:revisionType revision) "none") ")")}
      (try
        (let [spec (yaml/parse-string txt :keywords false)
              props (fn [type] (some (fn [r] (some (fn [[_ v]] (when (= type (get v "Type")) (get v "Properties"))) r)) (get spec "Resources")))
              ecs (props "AWS::ECS::Service")
              fn* (props "AWS::Lambda::Function")]
          (cond-> {:hooks (vec (for [h (get spec "Hooks") [event f] h :when f] {:event (str event) :function (lambda-function-name f)}))}
            ecs (assoc :task-definition (get ecs "TaskDefinition")
                       :container (get-in ecs ["LoadBalancerInfo" "ContainerName"]) :port (get-in ecs ["LoadBalancerInfo" "ContainerPort"]))
            fn* (assoc :function (get fn* "Name") :alias (get fn* "Alias")
                       :current (some-> (get fn* "CurrentVersion") str) :target (some-> (get fn* "TargetVersion") str))
            (not (or ecs fn*)) (assoc :note "the appspec names no ECS service and no Lambda function")))
        (catch Exception e {:note (str "the appspec does not parse: " (ex-message e))})))))

;; --- the deployment -------------------------------------------------------------------------------------

(defn rollout-of-status
  "The rollout state a CodeDeploy status stands for."
  [status]
  (case status
    "Succeeded" "COMPLETED"
    ("Failed" "Stopped") "FAILED"
    ("Created" "Queued" "InProgress" "Baking" "Ready") "IN_PROGRESS"
    nil))

(defn deployment-row
  "A row of the deploy list from a `deploymentInfo`."
  [info]
  (let [spec (parse-appspec (:revision info))
        status (:status info)
        err (:errorInformation info)
        rb (:rollbackInfo info)]
    (cond-> {:id (:deploymentId info) :source :codedeploy :application (:applicationName info) :group (:deploymentGroupName info)
             :platform (:computePlatform info) :status status :rollout-state (rollout-of-status status)
             :started (->inst (or (:startTime info) (:createTime info))) :created (->inst (:createTime info))
             :config (:deploymentConfigName info) :appspec spec}
      (:completeTime info) (assoc :finished (->inst (:completeTime info)))
      (:description info) (assoc :description (:description info))
      (:creator info) (assoc :creator (:creator info))
      (:code err) (assoc :error-code (:code err))
      (:message err) (assoc :rollout-reason (:message err))
      (seq rb) (assoc :rollback rb)
      (:autoRollbackConfiguration info) (assoc :auto-rollback (:autoRollbackConfiguration info))
      (seq (:deploymentStatusMessages info)) (assoc :status-messages (vec (:deploymentStatusMessages info)))
      (:additionalDeploymentStatusInfo info) (assoc :status-info (:additionalDeploymentStatusInfo info))
      (:deploymentOverview info) (assoc :overview (:deploymentOverview info))
      (:previousRevision info) (assoc :previous-appspec (parse-appspec (:previousRevision info)))
      (:function spec) (assoc :function (lambda-function-name (:function spec)) :alias (:alias spec)
                              :revision-before (some-> (:current spec) parse-long) :revision-after (some-> (:target spec) parse-long)
                              :image-before (:current spec) :image-after (:target spec)))))

(defn- absent? [e]
  (contains? #{"SubscriptionRequiredException" "OptInRequired" "UnknownOperationException" "UnknownOperation"
               "NotImplemented" "NotImplementedException" "UnsupportedOperation" "InvalidAction"} (:code e)))

(defn absent-error? "True for an answer that says the service or the call is not there." [e] (absent? e))

(defn- paged [call op req items]
  (loop [token nil acc [] n 0]
    (let [r (call :codedeploy op (cond-> req token (assoc :nextToken token)))]
      (cond (aws/error? r) {:items acc :error r}
            (and (:nextToken r) (< (count acc) id-cap) (< n 20)) (recur (:nextToken r) (into acc (get r items)) (inc n))
            :else {:items (into acc (get r items)) :more? (boolean (:nextToken r))}))))

(defn batch-deployments!
  "`BatchGetDeployments` of `ids` in chunks of 25 -> `{:infos [deploymentInfo..] :errors [..]}`."
  [call ids]
  (reduce (fn [acc chunk]
            (let [r (call :codedeploy :BatchGetDeployments {:deploymentIds (vec chunk)})]
              (if (aws/error? r)
                (update acc :errors conj {:op "BatchGetDeployments" :code (:code r) :message (:message r)})
                (update acc :infos into (:deploymentsInfo r)))))
          {:infos [] :errors []} (partition-all 25 ids)))

(defn group-deployments!
  "The newest `list-cap` deployments of one group -> `{:rows :errors :notes}` (rows: `deployment-row`, newest first)."
  [call app group]
  (let [ids (paged call :ListDeployments {:applicationName app :deploymentGroupName group} :deployments)]
    (if (:error ids)
      {:rows [] :errors [{:op "ListDeployments" :code (:code (:error ids)) :message (:message (:error ids))}]}
      (let [{:keys [infos errors]} (batch-deployments! call (:items ids))
            rows (->> infos (map deployment-row)
                      (sort-by #(- (.toEpochMilli ^Instant (or (:started %) Instant/EPOCH)))) (take list-cap) vec)]
        {:rows rows :errors errors
         :notes (when (:more? ids) [(str "ListDeployments of " app "/" group ": only the first " id-cap " ids were read")])}))))

(defn deployments!
  "The CodeDeploy deployments `keep?` accepts, over every group of every application: `(keep? group-info)` decides per group whether its
  deployments are looked at at all (an ECS service's group names the service; a Lambda has no such mark), `(accept? row)` per deployment.
  -> `{:rows :absent :errors :notes}`; `:absent` is the code of a service that is not there (the account refuses it, the emulator lacks it)."
  [call keep? accept?]
  (let [apps (paged call :ListApplications {} :applications)]
    (if (:error apps)
      (let [e (:error apps)]
        (if (absent? e) {:rows [] :absent (:code e)} {:rows [] :errors [{:op "ListApplications" :code (:code e) :message (:message e)}]}))
      (let [rows (atom []) errors (atom []) notes (atom [])]
        (doseq [app (:items apps)
                :let [gs (paged call :ListDeploymentGroups {:applicationName app} :deploymentGroups)]
                g (:items gs)
                :let [gi (call :codedeploy :GetDeploymentGroup {:applicationName app :deploymentGroupName g})]]
          (if (aws/error? gi)
            (swap! errors conj {:op "GetDeploymentGroup" :code (:code gi) :message (:message gi)})
            (when (keep? (:deploymentGroupInfo gi))
              (let [r (group-deployments! call app g)]
                (swap! rows into (filter accept? (:rows r)))
                (swap! errors into (:errors r))
                (swap! notes into (:notes r))))))
        {:rows (vec (sort-by #(- (.toEpochMilli ^Instant (or (:started %) Instant/EPOCH))) @rows)) :errors @errors :notes @notes}))))

(defn get-deployment!
  "`GetDeployment` -> the row, or `{:error {:op :code :message}}`."
  [call id]
  (let [r (call :codedeploy :GetDeployment {:deploymentId id})]
    (if (aws/error? r)
      {:error {:op "GetDeployment" :code (:code r) :message (:message r)}}
      {:row (deployment-row (:deploymentInfo r))})))

;; --- targets --------------------------------------------------------------------------------------------

(defn lifecycle-event
  "A `lifecycleEvents` entry, flat."
  [e]
  (let [d (:diagnostics e)]
    (cond-> {:name (:lifecycleEventName e) :status (:status e) :start (->inst (:startTime e)) :end (->inst (:endTime e))}
      (:errorCode d) (assoc :error-code (:errorCode d))
      (:scriptName d) (assoc :script (:scriptName d))
      (:message d) (assoc :message (:message d))
      (:logTail d) (assoc :log-tail (:logTail d)))))

(defn target
  "A `deploymentTarget` (ECS, Lambda, instance or CloudFormation) -> `{:type :id :arn :status :updated :events [...] ...}`."
  [t]
  (let [kind (:deploymentTargetType t)
        body (or (:ecsTarget t) (:lambdaTarget t) (:instanceTarget t) (:cloudFormationTarget t))
        lf (:lambdaFunctionInfo body)]
    (cond-> {:type kind :id (:targetId body) :arn (:targetArn body) :status (:status body) :updated (->inst (:lastUpdatedAt body))
             :events (mapv lifecycle-event (:lifecycleEvents body))}
      (seq (:taskSetsInfo body))
      (assoc :task-sets (mapv (fn [s] {:id (:identifer s) :label (:taskSetLabel s) :status (:status s) :desired (:desiredCount s)
                                       :pending (:pendingCount s) :running (:runningCount s) :traffic-weight (:trafficWeight s)})
                              (:taskSetsInfo body)))
      lf (assoc :lambda {:function (:functionName lf) :alias (:functionAlias lf) :current (:currentVersion lf) :target (:targetVersion lf)
                         :weight (:targetVersionWeight lf)}))))

(defn targets!
  "The targets of a deployment: `ListDeploymentTargets`, then `BatchGetDeploymentTargets` (25 ids per call).
  -> `{:targets [...] :listed [ids] :error {:op :code :message} :notes [...]}`. No `:error` and an empty `:listed` is the endpoint saying
  it has no target for the deployment: that is `the endpoint gave none`, which is not a success of an empty list."
  [call id poll-key]
  (let [l (paged call :ListDeploymentTargets {:deploymentId id} :targetIds)]
    (if (:error l)
      {:targets [] :listed [] :error {:op "ListDeploymentTargets" :code (:code (:error l)) :message (:message (:error l))}}
      (let [ids (vec (:items l))
            got (atom []) notes (atom [])]
        (doseq [chunk (partition-all 25 ids)
                :let [r (call :codedeploy :BatchGetDeploymentTargets {:deploymentId id :targetIds (vec chunk)} poll-key)]]
          (if (aws/error? r)
            (swap! notes conj (str "BatchGetDeploymentTargets: " (:code r) " " (:message r)))
            (swap! got into (map target (:deploymentTargets r)))))
        (when (and (seq ids) (empty? @got) (empty? @notes))
          (swap! notes conj "BatchGetDeploymentTargets answered no target for the listed ids"))
        {:targets @got :listed ids :notes @notes}))))

;; --- the hook's log lines ---------------------------------------------------------------------------------

(def ^:private deployment-id-re #"\bd-[0-9A-Z]{9}\b")

(defn hook-lines
  "From the events of a hook function's log group: the lines of this deployment `id`: the events inside the window are taken, and a line that
  names another deployment's id (a hook that logs its `DeploymentId`) is dropped. -> `{:entries [{:at :function :text}] :dropped n}`."
  [events function id]
  (let [mine (for [e events
                   line (str/split (str/replace (str (:message e)) #"\n$" "") #"\n")
                   :let [ids (set (re-seq deployment-id-re line))]]
               {:at (Instant/ofEpochMilli (long (:timestamp e))) :function function :text (str/replace line "\t" "  ") :ids ids :ts (:timestamp e)})
        ok (remove #(and (seq (:ids %)) (not (contains? (:ids %) id))) mine)]
    {:entries (vec (map #(select-keys % [:at :function :text :ts]) ok))
     :dropped (- (count mine) (count ok))}))

(defn- log-group-of [call function]
  (let [r (call :lambda :GetFunctionConfiguration {:FunctionName function})]
    (or (when-not (aws/error? r) (get-in r [:LoggingConfig :LogGroup])) (str "/aws/lambda/" function))))

(defn hooks!
  "The log lines of the hook functions of an appspec inside the window of the deployment: `from` .. `to` (instants).
  -> `{:entries [...] :functions [...] :notes [...] :dropped n}`, entries in time order, each saying which function."
  [call hooks id from to poll-prefix]
  (let [fns (vec (distinct (map :function hooks)))
        res (for [f fns
                  :let [group (log-group-of call f)
                        r (call :logs :FilterLogEvents {:logGroupName group :startTime (.toEpochMilli ^Instant from) :endTime (.toEpochMilli ^Instant to)}
                                (str poll-prefix ":" f))]]
              (if (aws/error? r)
                {:function f :group group :note (str "log group " group ": " (or (:code r) (some-> (:category r) name)) " " (or (:message r) ""))}
                (assoc (hook-lines (:events r) f id) :function f :group group)))]
    {:functions fns
     :entries (vec (sort-by (juxt :ts :function) (mapcat #(take hook-line-cap (:entries %)) res)))
     :notes (vec (keep :note res))
     :dropped (reduce + (map #(or (:dropped %) 0) res))
     :groups (into {} (map (juxt :function :group)) res)}))

;; --- rendering (pure) -------------------------------------------------------------------------------------

(defn- t [i] (some-> i str))
(defn- secs [a b] (when (and a b) (quot (- (.toEpochMilli ^Instant b) (.toEpochMilli ^Instant a)) 1000)))
(defn- kv [k v] (format "%-12s %s" k v))

(defn rollback-lines
  "Header lines about a rollback: this deployment was rolled back by another, or is one."
  [row]
  (let [rb (:rollback row)]
    (cond
      (seq rb)
      [(kv "rollback" (str/join "  " (keep identity [(when (:rollbackDeploymentId rb) (str "rolled back by " (:rollbackDeploymentId rb)))
                                                    (when (:rollbackTriggeringDeploymentId rb) (str "is the rollback of " (:rollbackTriggeringDeploymentId rb)))
                                                    (when (:rollbackMessage rb) (str "why: " (:rollbackMessage rb)))])))]
      (= "codeDeployRollback" (:creator row)) [(kv "rollback" "this deployment was created by an automatic rollback (the endpoint gave no rollbackInfo naming the failed one)")]
      :else [(kv "rollback" (str "none (the endpoint gave no rollbackInfo"
                                 (when-let [ar (:auto-rollback row)] (str "; auto rollback " (if (:enabled ar) (str "on for " (str/join "," (:events ar))) "off")))
                                 ")"))])))

(defn header-lines
  "The CodeDeploy lines of the deploy text's header (after `status`)."
  [row alias-now]
  (let [spec (:appspec row)]
    (concat
     [(kv "codedeploy" (str (:application row) "/" (:group row) "   " (:platform row) "   " (:config row)
                            (when (:creator row) (str "   by " (:creator row)))))]
     (when (:error-code row) [(kv "error" (str (:error-code row) "   " (:rollout-reason row)))])
     (when (:description row) [(kv "description" (:description row))])
     (when (:function row)
       [(kv "alias" (str (:alias row) " of " (:function row) "   version " (:current spec) " -> " (:target spec) "   (the appspec)"))])
     (when alias-now
       [(kv "alias now" (if (:version alias-now)
                          (str (:alias row) " -> version " (:version alias-now) "   (GetAlias, " (t (:at alias-now)) ")")
                          (str "not read: " (:error alias-now))))])
     (when (:task-definition spec) [(kv "appspec" (str "ECS task definition " (:task-definition spec) (when (:container spec) (str ", container " (:container spec) ":" (:port spec)))))])
     (when (:note spec) [(kv "appspec" (:note spec))])
     (for [h (:hooks spec)] (kv "hook" (str (:event h) " -> " (:function h))))
     (when (and (:appspec row) (empty? (:hooks spec)) (not (:note spec))) [(kv "hook" "the appspec names none")])
     (when (:overview row) [(kv "overview" (pr-str (:overview row)))])
     (when (:status-info row) [(kv "info" (:status-info row))])
     (for [m (:status-messages row)] (kv "message" m))
     (rollback-lines row)
     [(kv "note" emulator-note)])))

(defn target-lines
  "The `== targets` section."
  [{:keys [targets listed error notes]}]
  (concat
   [(str "== targets (" (cond error (str "not read: " (:op error) " " (:code error) " " (:message error))
                              (empty? listed) "the endpoint gave none: ListDeploymentTargets listed no target of this deployment"
                              :else (str "listed " (count listed) ", answered " (count targets))) ")")]
   (for [n notes] (str "# " n))
   (mapcat (fn [x]
             (concat
              [(str (:id x) "   " (:type x) "   " (:status x) "   updated " (t (:updated x)))]
              (when-let [l (:lambda x)]
                [(str "    function " (:function l) " alias " (:alias l) "  version " (:current l) " -> " (:target l)
                      (when (:weight l) (str "  weight " (:weight l))) "   (lambdaFunctionInfo)")])
              (for [s (:task-sets x)]
                (str "    task set " (:id s) (when (:label s) (str " " (:label s))) "  " (:status s) "  desired " (:desired s) " pending " (:pending s)
                     " running " (:running s) "  traffic " (:traffic-weight s)))
              (if (empty? (:events x))
                ["    (no lifecycle events in the answer)"]
                (mapcat (fn [e]
                          (concat
                           [(str "    lifecycle " (format "%-20s" (:name e)) " " (format "%-10s" (:status e)) " " (or (t (:start e)) "-") " .. " (or (t (:end e)) "-")
                                 (when-let [s (secs (:start e) (:end e))] (str "   (" s " s)")))]
                           (when (or (:error-code e) (:message e) (:script e))
                             [(str "        " (:error-code e) (when (:script e) (str " in " (:script e))) ": " (:message e))])
                           (when (:log-tail e) (for [l (str/split-lines (:log-tail e))] (str "        | " l)))))
                        (:events x)))))
           targets)))

(defn hook-section
  "The `== hook` section: the hook functions' own lines inside the window."
  [{:keys [entries functions notes dropped]} hooks-named?]
  (concat
   [(str "== hook log (" (count entries) " lines of " (if (seq functions) (str/join ", " functions) "no hook function")
         (when (pos? (or dropped 0)) (str "; " dropped " lines naming another deployment left out")) ", time order)")]
   (when-not hooks-named? ["# the appspec names no hook function: nothing to read"])
   (for [n notes] (str "# " n))
   (for [e entries] (str (t (:at e)) " [hook " (:function e) "] " (:text e)))))

(defn lifecycle-items
  "The lifecycle events of the targets as `{:at :message :id}`, for a follow's event stream."
  [targets]
  (vec (for [x targets e (:events x)
             :let [at (or (:end e) (:start e) (:updated x))]
             :when at]
         {:at at :id (str (:id x) "/" (:name e) "/" (:status e))
          :message (str "lifecycle " (:name e) " " (:status e) (when-let [s (secs (:start e) (:end e))] (str " (" s " s)"))
                        (when (:message e) (str "  " (:error-code e) ": " (:message e))))})))
