(ns deliberator.deploy
  "What a deploy did (cicd-aws piece 04): the deployments of an ECS service or the versions of a Lambda, one deployment as ONE
  text file, the same file growing while a rollout runs, and the lookup from a commit to the deployments that carry it.

  `(list! ws env-name service opts)`  the deployments of `service` (`name`, `cluster/name`, or a Lambda function), newest first.
      ECS: `ListServiceDeployments` + `DescribeServiceDeployments` + `DescribeServiceRevisions` (revision and images before and
      after) + `DescribeTaskDefinition` with tags (`git-sha`, `run-id`: where the revision came from). Where the call is not there
      or not allowed (the account's reader policy has no `ecs:ListServiceDeployments`) the answer is `DescribeServices`'
      `deployments`, said in `:notes`. CodeDeploy deployments of the group that serves the service are added where the account has it
      (absent: `:absent`; any other failure: `:errors`). A Lambda: `ListVersionsByFunction` and the `$LATEST` state.
  `(show! ws env-name service opts)`  `:deployment` (default the newest; `ecs-svc/<n>`, `<n>`, a service-deployment ARN or a
      CodeDeploy id) as `cache/<env>/deploy/<cluster>/<service>/<deployment>.txt`: header (status, rollout state and reason, window,
      revision and image before -> after, commit and CI run with where they came from), `== events` (the service's events inside the
      deployment's window), `== tasks` (the tasks the deployment STARTED, matched by `startedBy = ecs-svc/<id>`, and the ones it
      replaced; stop code, reason, exit codes) and `== log` (the log lines of the started tasks' containers in time order, each line
      saying which container: CloudWatch streams `<prefix>/<container>/<task id>` from the task definition's awslogs options, or on a
      local environment the runtime container of the workspace service bound to the ECS service). `:cached true` answers from the
      file with its age, no AWS call.
  `(follow! ws env-name service opts)`  the same file, rewritten atomically after every change while the rollout runs; every new
      event, task state and log line is emitted as `deploy.line`. Ends when the rollout completes, fails (a rollout that keeps starting
      tasks that die is `failing` after three of them), is cancelled, or after `:timeout-ms`; a failure ends with the stop reasons and
      the container's last lines. The polls are quiet (`aws/invoke!` ctx `:poll`): a request and an answer are persisted when the
      answer's bytes changed.
  CodeDeploy (piece 15b, `deliberator.codedeploy`): a CodeDeploy deployment is a row of `list!` (of an ECS service whose group names it, and of a
      Lambda function whose alias the appspec moves) and `show!` / `follow!` take its id (`d-...`): the header (application, group,
      status, error code and message, the appspec's task definition or alias versions, a rollback and why), `== targets` with the
      lifecycle events of each target (status, times, diagnostics), `== hook log` (the lines of the hook functions the appspec names,
      inside the deployment's window, each saying which function) and, for an ECS service, the plain view's events, tasks and container
      lines. What the endpoint does not answer is said as `the endpoint gave none`.
      CodeDeploy deployments are read as the real API answers them, but this has only been checked against the local emulator, not
      against real CodeDeploy (the account is on AWS's Free plan, which refuses the service).
  `(by-commit ws sha)`, `(by-run-id ws run-id)`, `(commit-notes ws sha)`  the seam for `ci.*`: no AWS call, only the files of
      `cache/<env>/deployed.edn` (the current revision of every service) and `cache/<env>/deploy/**.edn` (what list and show saw)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.codedeploy :as cd]
            [deliberator.deployed :as deployed]
            [deliberator.env :as env]
            [deliberator.podman :as podman]
            [deliberator.service :as service]
            [deliberator.service.description :as description]
            [deliberator.steps.logs :as logs]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant OffsetDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

(def list-cap "Deployments listed (newest first)." 20)
(def log-cap "Log lines shown per task container; the text says when more were there." 2000)
(def default-poll-ms 2000)
(def default-timeout-ms (* 15 60 1000))
(def failing-after "A rollout is `failing` when this many tasks it started died and none runs." 3)

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

;; --- small pure things ----------------------------------------------------------------------------------

(def ^:private ->inst cd/->inst)

(defn- ms->inst [ms] (when ms (Instant/ofEpochMilli (long ms))))
(defn- before? [a b] (and a b (.isBefore ^Instant a ^Instant b)))
(defn- slug [s] (str/replace (str s) #"[^A-Za-z0-9._-]+" "-"))
(defn- tail-of [arn] (deployed/arn-tail arn))
(defn- short-id [id] (let [s (str id)] (if (> (count s) 8) (subs s 0 8) s)))

(defn- seconds [a b] (when (and a b) (quot (- (.toEpochMilli ^Instant b) (.toEpochMilli ^Instant a)) 1000)))

(defn deployment-id
  "`ecs-svc/<n>` of a service-revision ARN (the id `DescribeServices` and a task's `startedBy` use)."
  [revision-arn] (when revision-arn (str "ecs-svc/" (tail-of revision-arn))))

(defn rollout-of-status
  "The rollout state a service-deployment status stands for."
  [status]
  (case status
    "SUCCESSFUL" "COMPLETED"
    ("PENDING" "IN_PROGRESS" "STOP_REQUESTED" "ROLLBACK_REQUESTED" "ROLLBACK_IN_PROGRESS") "IN_PROGRESS"
    ("STOPPED" "ROLLBACK_SUCCESSFUL" "ROLLBACK_FAILED") "FAILED"
    nil))

(defn images-text [containers]
  (str/join ", " (for [c containers] (str (:name c) ":" (or (:tag c) (:digest c) (:image c))))))

(defn commit-of
  "`{:git-sha :commit-source :run-id}` of a task definition's `tags` and its containers: the tag `git-sha`; else an image tag that
  looks like a commit (the pile's tag = the commit's first 12 characters)."
  [tags containers]
  (let [tag (some #(when (and (:tag %) (re-matches #"[0-9a-f]{7,40}" (:tag %))) (:tag %)) containers)]
    (cond-> {}
      (get tags "git-sha") (assoc :git-sha (get tags "git-sha") :commit-source "task definition tag git-sha")
      (and (not (get tags "git-sha")) tag) (assoc :git-sha tag :commit-source "image tag")
      (get tags "run-id") (assoc :run-id (get tags "run-id")))))

;; --- calls ---------------------------------------------------------------------------------------------------

(defn- call
  "One call through `aws/invoke!`. With `pkey` and a ctx `:poll-state` the call is a quiet poll. -> the response or the error."
  [{:keys [ws env refs poll-state]} svc op req & [pkey]]
  (let [r (aws/invoke! (cond-> {:ws ws} (and poll-state pkey) (assoc :poll {:state poll-state :key pkey})) env svc op req)
        raw (aws/raw-refs r)]
    (when refs (swap! refs conj {:op (name op) :request (get-in raw [:request :sha]) :response (get-in raw [:response :sha])}))
    r))

(defn- paged [ctx svc op req items tok req-tok]
  (loop [token nil acc [] n 0]
    (let [r (call ctx svc op (cond-> req token (assoc req-tok token)))]
      (cond (aws/error? r) {:items acc :error r}
            (and (get r tok) (< n 100)) (recur (get r tok) (into acc (get r items)) (inc n))
            :else {:items (into acc (get r items))}))))

;; --- the service ---------------------------------------------------------------------------------------------

(defn- describe-service [ctx cluster name pkey]
  (let [r (call ctx :ecs :DescribeServices {:cluster cluster :services [name]} pkey)]
    (when-not (aws/error? r) (first (:services r)))))

(defn resolve-service!
  "`service` (`name`, `cluster/name`) -> `{:kind :ecs :cluster :service :svc <DescribeServices entry>}` or `{:kind :lambda :function name
  :config ..}`. An unknown name is `deploy/not-found`."
  [ctx spec]
  (when (str/blank? spec) (fail :deploy/no-service "deploy needs a service" :hint "an ECS service (name or cluster/name) or a Lambda function"))
  (let [[c n] (if (str/includes? spec "/") (str/split spec #"/" 2) [nil spec])
        clusters (if c [c] (let [p (paged ctx :ecs :ListClusters {:maxResults 100} :clusterArns :nextToken :nextToken)]
                             (mapv tail-of (:items p))))
        ecs (some (fn [cl] (when-let [s (describe-service ctx cl n nil)]
                             (when (:serviceName s) {:kind :ecs :cluster cl :service n :svc s})))
                  clusters)]
    (or ecs
        (when-not c
          (let [r (call ctx :lambda :GetFunctionConfiguration {:FunctionName spec})]
            (when-not (aws/error? r) {:kind :lambda :function spec :config r})))
        (fail :deploy/not-found (str "no ECS service or Lambda function " spec " in environment " (:name (:env ctx)))
              :hint "name it as the service (or cluster/service); dlb deployed show lists what the environment has"))))

;; --- ECS rows -------------------------------------------------------------------------------------------------

(defn- td-info!
  "A task definition's identity, tags and containers with their log configuration, once per ARN per ctx."
  [{:keys [tds] :as ctx} arn]
  (when arn
    (or (get @tds arn)
        (let [r (call ctx :ecs :DescribeTaskDefinition {:taskDefinition arn :include ["TAGS"]})
              td (:taskDefinition r)
              info (when (and td (not (aws/error? r)))
                     {:arn arn :family (:family td) :revision (:revision td)
                      :tags (into {} (map (juxt :key :value)) (:tags r))
                      :containers (mapv (fn [c] (merge {:name (:name c)} (deployed/image-parts (:image c))
                                                       (when-let [lc (:logConfiguration c)]
                                                         {:log {:driver (:logDriver lc) :options (:options lc)}})))
                                        (:containerDefinitions td))})]
          (when info (swap! tds assoc arn info))
          info))))

(defn- row-of
  "The row of a deployment: `base` (id, source, status, times) with the revision and image before and after."
  [ctx base td-before td-after]
  (let [b (td-info! ctx td-before) a (td-info! ctx td-after)]
    (cond-> (merge base
                   {:revision-before (some-> td-before tail-of parse-long) :revision-after (some-> td-after tail-of parse-long)
                    :task-definition-before td-before :task-definition-after td-after}
                   (commit-of (:tags a) (:containers a)))
      b (assoc :image-before (images-text (:containers b)) :family (:family b))
      a (assoc :image-after (images-text (:containers a)) :family (:family a)))))

(defn- sd-base [sd svc]
  (let [id (deployment-id (get-in sd [:targetServiceRevision :arn] (:targetServiceRevisionArn sd)))
        live (some #(when (= id (:id %)) %) (:deployments svc))
        status (:status sd)]
    (cond-> {:id id :source :service-deployments :arn (:serviceDeploymentArn sd) :status status
             :rollout-state (or (rollout-of-status status) (:rolloutState live))
             :started (->inst (or (:startedAt sd) (:createdAt sd))) :created (->inst (:createdAt sd))}
      (:finishedAt sd) (assoc :finished (->inst (:finishedAt sd)))
      ;; DescribeServices can lag the deployment's own status: its reason is only used while both say the same
      (or (:statusReason sd) (and live (= (:rolloutState live) (rollout-of-status status)) (:rolloutStateReason live)))
      (assoc :rollout-reason (or (:statusReason sd) (:rolloutStateReason live)))
      (:rollback sd) (assoc :rollback (:rollback sd))
      (get-in sd [:deploymentCircuitBreaker :status]) (assoc :circuit-breaker (get-in sd [:deploymentCircuitBreaker :status]))
      (get-in sd [:targetServiceRevision :arn]) (assoc :target-revision (get-in sd [:targetServiceRevision :arn]))
      (:sourceServiceRevisions sd) (assoc :source-revision (:arn (first (:sourceServiceRevisions sd)))
                                          :before-id (deployment-id (:arn (first (:sourceServiceRevisions sd))))))))

(defn- service-deployment-rows!
  "-> `{:rows [...] :notes [...]}`; rows nil with a note when `ListServiceDeployments` is not there or not allowed."
  [ctx cluster service svc limit]
  (let [l (call ctx :ecs :ListServiceDeployments {:cluster cluster :service service :maxResults limit} (str "lsd:" cluster "/" service))]
    (if (aws/error? l)
      {:notes [(str "ListServiceDeployments: " (or (:code l) (some-> (:category l) name)) " " (or (:message l) "")
                    (when (:hint l) (str " (" (:hint l) ")")))]}
      (let [arns (mapv :serviceDeploymentArn (take limit (:serviceDeployments l)))
            d (when (seq arns) (call ctx :ecs :DescribeServiceDeployments {:serviceDeploymentArns arns} (str "dsd:" cluster "/" service)))
            sds (if (and d (not (aws/error? d))) (:serviceDeployments d)
                    (:serviceDeployments l))
            note (when (and d (aws/error? d)) (str "DescribeServiceDeployments: " (:code d) " " (:message d)))
            rev-arns (vec (distinct (mapcat (fn [sd] (cons (or (get-in sd [:targetServiceRevision :arn]) (:targetServiceRevisionArn sd))
                                                           (map :arn (:sourceServiceRevisions sd))))
                                            sds)))
            rv (when (seq rev-arns) (call ctx :ecs :DescribeServiceRevisions {:serviceRevisionArns rev-arns} (str "dsr:" cluster "/" service)))
            revs (when (and rv (not (aws/error? rv))) (into {} (map (juxt :serviceRevisionArn identity)) (:serviceRevisions rv)))]
        {:notes (cond-> [] note (conj note) (and rv (aws/error? rv)) (conj (str "DescribeServiceRevisions: " (:code rv) " " (:message rv))))
         :rows (vec (for [sd sds
                          :let [base (sd-base sd svc)
                                after (get revs (:target-revision base))
                                before (get revs (:source-revision base))]]
                      (assoc (row-of ctx base (:taskDefinition before) (:taskDefinition after))
                             :desired (get-in sd [:targetServiceRevision :requestedTaskCount]))))}))))

(defn- fallback-rows!
  "The rows `DescribeServices` alone gives: the service's `deployments` (the ones ECS still lists), newest first."
  [ctx svc]
  (let [ds (sort-by #(- (.toEpochMilli ^Instant (or (->inst (:createdAt %)) Instant/EPOCH))) (:deployments svc))]
    (vec (for [[d older] (map vector ds (concat (rest ds) [nil]))
               :let [state (:rolloutState d)
                     done? (or (#{"COMPLETED" "FAILED"} state) (= "INACTIVE" (:status d)))]]
           (row-of ctx
                   (cond-> {:id (:id d) :source :describe-services :status (:status d)
                            :rollout-state (or state (when (= "INACTIVE" (:status d)) "COMPLETED"))
                            :started (->inst (:createdAt d)) :created (->inst (:createdAt d))
                            :desired (:desiredCount d)}
                     done? (assoc :finished (->inst (:updatedAt d)))
                     (:rolloutStateReason d) (assoc :rollout-reason (:rolloutStateReason d))
                     older (assoc :before-id (:id older)))
                   (:taskDefinition older) (:taskDefinition d))))))

(defn- cd-call
  "The call function `deliberator.codedeploy` takes: this ctx's `call`; in a follow every call is a quiet poll under a key made of
  the question."
  [ctx]
  (fn [svc op req & [pkey]]
    (call ctx svc op req (or pkey (when (:poll-state ctx) (str "cd:" (name svc) ":" (name op) ":" (pr-str req)))))))

(defn- cd-ecs-rows
  "The rows of CodeDeploy deployments (newest first) with the revision and image the appspec's task definition stands for: after = the
  task definition the appspec names, before = the one the next older deployment of the same group named."
  [ctx rows]
  (vec (map-indexed
        (fn [i r]
          (let [older (some #(when (and (= (:group %) (:group r)) (= (:application %) (:application r))) %) (drop (inc i) rows))]
            (row-of ctx r (get-in older [:appspec :task-definition]) (get-in r [:appspec :task-definition]))))
        rows)))

(defn- codedeploy-rows!
  "-> `{:rows :absent :errors :notes}` of the CodeDeploy deployments of the groups whose `ecsServices` name the service."
  [ctx cluster service]
  (let [r (cd/deployments! (cd-call ctx)
                           (fn [gi] (some #(and (= service (:serviceName %)) (or (nil? cluster) (= cluster (:clusterName %)))) (:ecsServices gi)))
                           (constantly true))]
    (assoc r :rows (cd-ecs-rows ctx (:rows r)))))

(defn- newest-first [rows]
  (vec (sort-by #(- (.toEpochMilli ^Instant (or (:started %) Instant/EPOCH))) rows)))

;; --- Lambda ------------------------------------------------------------------------------------------------------

(defn- lambda-rows! [ctx fname config]
  (let [v (paged ctx :lambda :ListVersionsByFunction {:FunctionName fname} :Versions :NextMarker :Marker)
        vs (sort-by #(- (.toEpochMilli ^Instant (or (->inst (:LastModified %)) Instant/EPOCH))) (:items v))
        rows (for [[f older] (map vector vs (concat (rest vs) [nil]))]
               (cond-> {:id (str (:Version f)) :source :lambda :version (:Version f) :status (or (:State f) "Active")
                        :rollout-state (case (or (:LastUpdateStatus f) "Successful") "Successful" "COMPLETED" "InProgress" "IN_PROGRESS" "FAILED")
                        :started (->inst (:LastModified f)) :code-sha256 (:CodeSha256 f)
                        :revision-before (:Version older) :revision-after (:Version f)
                        :image-after (or (:ImageUri f) (:CodeSha256 f)) :image-before (when older (or (:ImageUri older) (:CodeSha256 older)))}
                 (:LastUpdateStatusReason f) (assoc :rollout-reason (:LastUpdateStatusReason f))
                 (:Description f) (assoc :description (:Description f))))]
    {:rows (vec rows) :latest config
     :notes (when (:error v) [(str "ListVersionsByFunction: " (:code (:error v)) " " (:message (:error v)))])}))

;; A Lambda function's CodeDeploy deployments: those whose appspec moves an alias of the function (a group does not name the function).
(defn- lambda-cd-rows! [ctx fname]
  (cd/deployments! (cd-call ctx)
                   (fn [gi] (and (empty? (:ecsServices gi)) (let [p (:computePlatform gi)] (or (nil? p) (= "Lambda" p)))))
                   #(and (= "Lambda" (:platform %)) (= fname (:function %)))))

;; --- tasks, events -----------------------------------------------------------------------------------------------

(defn- task-row [t tds]
  (let [td (:taskDefinitionArn t)]
    (cond-> {:id (tail-of (:taskArn t)) :arn (:taskArn t) :task-definition td :revision (some-> td tail-of parse-long)
             :started-by (:startedBy t) :last-status (:lastStatus t) :desired-status (:desiredStatus t)
             :created (->inst (:createdAt t)) :started (->inst (or (:startedAt t) (:createdAt t)))
             :containers (mapv (fn [c] (cond-> {:name (:name c) :last-status (:lastStatus c) :image (:image c)}
                                         (some? (:exitCode c)) (assoc :exit-code (:exitCode c))
                                         (:reason c) (assoc :reason (:reason c))))
                               (:containers t))
             :family (:family (get tds td))}
      (:stoppedAt t) (assoc :stopped (->inst (:stoppedAt t)))
      (:stopCode t) (assoc :stop-code (:stopCode t))
      (:stoppedReason t) (assoc :stopped-reason (:stoppedReason t))
      (:healthStatus t) (assoc :health (:healthStatus t)))))

(defn- fetch-tasks! [ctx cluster service]
  (let [arns (vec (distinct (mapcat (fn [st]
                                      (:items (paged ctx :ecs :ListTasks {:cluster cluster :serviceName service :desiredStatus st}
                                                     :taskArns :nextToken :nextToken)))
                                    ["RUNNING" "STOPPED"])))
        described (mapcat (fn [i chunk]
                            (let [r (call ctx :ecs :DescribeTasks {:cluster cluster :tasks (vec chunk)} (str "dt:" cluster "/" service ":" i))]
                              (when-not (aws/error? r) (:tasks r))))
                          (range) (partition-all 100 arns))]
    (mapv #(task-row % @(:tds ctx)) described)))

(defn window-end
  "Where the window of `dep` closes: ECS writes the closing events (`deployment completed`, `has reached a steady state`) a minute after the
  deployment's `finishedAt`, so 150 s later, but never past the start of the next deployment (`:next-started`)."
  [dep now]
  (let [e (.plusSeconds ^Instant (or (:finished dep) now) 150)]
    (if (and (:next-started dep) (before? (:next-started dep) e)) (:next-started dep) e)))

(defn tasks-of
  "`{:started [...] :replaced [...]}` of `tasks` for deployment `dep` (a row): started = `startedBy` is the deployment's id; replaced = stopped by
  the scheduler inside the window of the deployment (or naming it in the stop reason) that it did not start. A CodeDeploy deployment has no
  `startedBy`: its started tasks are the ones created inside its window."
  [dep tasks now]
  (let [from (some-> (:started dep) (.minusSeconds 2))
        to (window-end dep now)
        started (if (= :codedeploy (:source dep))
                  (filterv #(and (not (before? (:created %) from)) (not (before? to (:created %)))) tasks)
                  (filterv #(= (:id dep) (:started-by %)) tasks))
        ids (set (map :id started))
        mentions (fn [t] (re-seq #"ecs-svc/[0-9a-zA-Z]+" (str (:stopped-reason t))))]
    {:started (vec (sort-by :created started))
     :replaced (vec (sort-by :stopped
                             (for [t tasks
                                   :when (and (not (ids (:id t))) (:stopped t) (= "ServiceSchedulerInitiated" (:stop-code t))
                                              (not (before? (:stopped t) from)) (not (before? to (:stopped t)))
                                              ;; the real stop reason is `Scaling activity initiated by (deployment ecs-svc/<id>)`, the id of the
                                              ;; deployment whose task is scaled down: the one before this
                                              (if-let [m (seq (mentions t))]
                                                (some (set [(:id dep) (:before-id dep)]) m)
                                                true))]
                               t)))}))

(defn events-in
  "The service `events` (DescribeServices) inside the window of `dep`, oldest first."
  [events dep now]
  (let [from (some-> (:started dep) (.minusSeconds 1))
        to (window-end dep now)]
    (->> events
         (map (fn [e] {:at (->inst (:createdAt e)) :message (:message e) :id (:id e)}))
         (filter (fn [e] (or (and (not (before? (:at e) from)) (not (before? to (:at e))))
                             (str/includes? (str (:message e)) (str (:id dep))))))
         (sort-by :at)
         vec)))

;; --- logs --------------------------------------------------------------------------------------------------------

(defn log-sources
  "For each started task and each container of its task definition: where its lines are. `local` is a function
  `(fn [container-name]) -> runtime container name or nil`, used on a local environment."
  [tasks tds local-env? local]
  (vec (for [t tasks
             c (or (:containers (get tds (:task-definition t))) (map #(select-keys % [:name]) (:containers t)))
             :let [{:keys [driver options]} (:log c)
                   opt (fn [k] (or (get options k) (get options (keyword k))))   ; aws-api keys a string map with keywords
                   prefix (opt "awslogs-stream-prefix") group (opt "awslogs-group")
                   container (when local-env? (local (:name c)))]]
         (cond
           container {:kind :container :container container :name (:name c) :task t}
           (and (= "awslogs" driver) group prefix)
           {:kind :cloudwatch :group group :stream (str prefix "/" (:name c) "/" (:id t)) :name (:name c) :task t}
           (= "awslogs" driver) {:kind :none :name (:name c) :task t :reason (str "the awslogs configuration of container " (:name c) " has no stream prefix")}
           driver {:kind :none :name (:name c) :task t :reason (str "container " (:name c) " logs with the driver " driver)}
           :else {:kind :none :name (:name c) :task t
                  :reason (str "container " (:name c) (if local-env? " is not a workspace service of this environment's runtime" " has no log configuration"))}))))

(defn- local-resolver [ws cluster svc]
  (let [rows (try (service/list ws) (catch Throwable _ []))
        descs (try (description/list ws) (catch Throwable _ []))
        bound (some #(when (and (= cluster (get-in % [:ecs :cluster])) (= svc (get-in % [:ecs :service]))) (:name %)) descs)]
    (fn [cname]
      (let [n (or bound
                  (some #(when (= cname (:name %)) cname) rows)
                  (some #(when (str/ends-with? (str svc) (str "-" (:name %))) (:name %)) rows))]
        (some #(when (= n (:name %)) (:container %)) rows)))))

(defn- cw-fetch!
  "New events of the streams of one log group since `since-ms`: -> vector of FilterLogEvents events (capped)."
  [ctx group streams since-ms pkey]
  (loop [token nil acc []]
    (let [r (call ctx :logs :FilterLogEvents
                  (cond-> {:logGroupName group :logStreamNames (vec (take 100 streams)) :startTime since-ms}
                    token (assoc :nextToken token))
                  pkey)]
      (cond (aws/error? r) {:events acc :error r}
            (and (:nextToken r) (< (count acc) (* 2 log-cap (max 1 (count streams)))))
            (recur (:nextToken r) (into acc (:events r)))
            :else {:events (into acc (:events r))}))))

(defn- collect-logs!
  "Adds the new lines of every source to `lstate` (an atom `{:lines {src-key [entry..]} :seen {..} :since {..} :notes {..} :capped {..}}`) and
  returns the entries in time order. An entry `{:at :container :task :text}`."
  [ctx sources lstate local-env? ws now]
  (let [cw (group-by :group (filter #(= :cloudwatch (:kind %)) sources))]
    (doseq [[group ss] cw
            :let [streams (distinct (map :stream ss))
                  by-stream (into {} (map (juxt :stream identity)) ss)
                  start (or (some-> (get-in @lstate [:since group]) (- 10000))
                            (- (.toEpochMilli ^Instant (:created (:task (first (sort-by #(:created (:task %)) ss))))) 5000))
                  {:keys [events error]} (cw-fetch! ctx group streams (max 0 start) (str "cw:" group))]]
      (if error
        (swap! lstate assoc-in [:notes group] (str "FilterLogEvents on " group ": " (or (:code error) (:category error)) " " (:message error)))
        (do (swap! lstate update :notes dissoc group)
            (doseq [e (sort-by (juxt :timestamp :eventId) events)
                    :let [k [group (:logStreamName e) (:eventId e)] s (by-stream (:logStreamName e))]
                    :when (and s (not (contains? (:seen @lstate) k)))]
              (let [lines (str/split (str/replace (str (:message e)) #"\n$" "") #"\n")
                    key [:cw group (:logStreamName e)]]
                (doseq [l lines]
                  (if (>= (count (get-in @lstate [:lines key])) log-cap)
                    (swap! lstate assoc-in [:capped key] true)
                    (swap! lstate update-in [:lines key] (fnil conj [])
                           {:at (ms->inst (:timestamp e)) :container (:name s) :task (short-id (:id (:task s))) :text l})))
                (swap! lstate update :seen (fnil conj #{}) k)
                (swap! lstate update-in [:since group] (fnil max 0) (:timestamp e)))))))
    (let [cs (group-by :container (filter #(= :container (:kind %)) sources))]
      (doseq [[cname ss] cs
              :let [key [:container cname]
                    from (apply min-key #(.toEpochMilli ^Instant %) (map #(:created (:task %)) ss))
                    to (some (fn [t] (when-not (:stopped t) now)) (map :task ss))
                    to (or to (apply max-key #(.toEpochMilli ^Instant %) (map #(or (:stopped (:task %)) now) ss)))
                    last-at (get-in @lstate [:since key])
                    got (try (vec (podman/logs (logs/podman-client {:ws ws}) cname :since (or last-at (.minusSeconds ^Instant from 1))))
                             (catch Throwable e (swap! lstate assoc-in [:notes key] (str "container " cname ": " (ex-message e))) nil))
                    seen-at (get-in @lstate [:seen key] #{})
                    fresh (vec (for [e got
                                     :let [at (:at e)]
                                     :when (and at (not (before? at from)) (not (before? (.plusSeconds ^Instant to 5) at))
                                                (or (nil? last-at) (.isAfter ^Instant at ^Instant last-at)
                                                    (and (= at last-at) (not (seen-at (:text e))))))]
                                 e))]]
        (when (seq fresh)
          (store/raw-put! ws (.getBytes ^String (str (str/join "\n" (map #(str (:at %) " " (:text %)) fresh)) "\n") StandardCharsets/UTF_8)
                          {:source :podman :op (str "deploy logs " cname) :env (keyword (:name (:env ctx))) :at (store/now) :content-type "text/plain"})
          (doseq [e fresh]
            (if (>= (count (get-in @lstate [:lines key])) log-cap)
              (swap! lstate assoc-in [:capped key] true)
              (swap! lstate update-in [:lines key] (fnil conj []) {:at (:at e) :container cname :task nil :text (:text e)})))
          (let [mx (apply max-key #(.toEpochMilli ^Instant %) (map :at fresh))]
            (swap! lstate assoc-in [:since key] mx)
            (swap! lstate assoc-in [:seen key] (into (if (= mx last-at) seen-at #{}) (comp (filter #(= mx (:at %))) (map :text)) fresh))))))
    (let [st @lstate
          valid (set (concat (for [s sources :when (= :cloudwatch (:kind s))] [:cw (:group s) (:stream s)])
                             (for [s sources :when (= :container (:kind s))] [:container (:container s)])))]
      {:entries (vec (sort-by (juxt (comp #(.toEpochMilli ^Instant %) :at)) (mapcat val (select-keys (:lines st) valid))))
       :notes (vec (concat (vals (:notes st))
                           (for [s sources :when (= :none (:kind s))] (:reason s))
                           (for [[k _] (:capped st)] (str "capped at " log-cap " lines: " (pr-str (vec (rest k)))))))
       :local? local-env?})))

;; --- collect and render --------------------------------------------------------------------------------------------

(defn- ctx-of [ws env-name poll-state]
  {:ws ws :env (env/load ws env-name) :refs (atom []) :tds (atom {}) :poll-state poll-state})

(defn- pick
  "The row `id` names (`ecs-svc/<n>`, `<n>`, an ARN, a CodeDeploy id; a prefix of at least 6 characters), the newest when blank."
  [rows id]
  (if (str/blank? id)
    (first rows)
    (let [id (str id)]
      (or (some #(when (or (= id (:id %)) (= id (:arn %)) (= (str "ecs-svc/" id) (:id %))) %) rows)
          (let [m (filter #(and (>= (count id) 6) (str/includes? (str (:id %)) id)) rows)] (when (= 1 (count m)) (first m)))))))

(defn- list-rows!
  "The rows of an ECS service (service deployments, else the fallback, plus CodeDeploy's), newest first, and what was said on the way."
  [ctx {:keys [cluster service svc]} limit]
  (let [sd (service-deployment-rows! ctx cluster service svc limit)
        rows (or (:rows sd) (fallback-rows! ctx svc))
        cd (codedeploy-rows! ctx cluster service)]
    {:rows (newest-first (into (vec rows) (:rows cd)))
     :source (if (:rows sd) :service-deployments :describe-services)
     :notes (vec (concat (:notes sd) (:notes cd) (when-not (:rows sd) ["the rows come from DescribeServices: only the deployments ECS still lists"])))
     :absent (when-let [a (:absent cd)] {:codedeploy a}) :errors (:errors cd)}))

(defn- cd-detail!
  "What a CodeDeploy deployment `row` has beyond its row: its targets with their lifecycle events, the hook functions' log lines inside its
  window and, for a Lambda, where the alias is now. -> `{:targets {...} :hook {...} :alias-now {...}}`."
  [ctx row now]
  (let [call* (cd-call ctx)
        spec (:appspec row)
        from (some-> (:started row) (.minusSeconds 5))
        to (.plusSeconds ^Instant (or (:finished row) now) 30)
        alias-r (when (and (:function row) (:alias row))
                  (let [r (call ctx :lambda :GetAlias {:FunctionName (:function row) :Name (:alias row)} (str "alias:" (:function row) ":" (:alias row)))]
                    (if (aws/error? r) {:error (str (:code r) " " (:message r))} {:version (:FunctionVersion r) :at now})))]
    {:targets (cd/targets! call* (:id row) (str "cdt:" (:id row)))
     :hook (when (and from (seq (:hooks spec))) (cd/hooks! call* (:hooks spec) (:id row) from to "cdh"))
     :hooks-named? (boolean (seq (:hooks spec)))
     :alias-now alias-r}))

(defn- cd-id? [s] (boolean (re-matches #"d-[0-9A-Za-z]{9}" (str s))))

(defn- collect-lambda!
  "`collect!` for a Lambda function: one of its CodeDeploy deployments (`dep-id`, default the newest). There are no tasks and no container lines:
  the targets and the hook's lines are the view."
  [ctx target dep-id]
  (let [fname (:function target)
        now (store/now)
        call* (cd-call ctx)
        direct (when (cd-id? dep-id)
                 (let [r (cd/get-deployment! call* dep-id)]
                   (cond (:error r) (fail :deploy/not-found (str "no deployment " dep-id ": " (:code (:error r)) " " (:message (:error r))) :hint "dlb deploy list names them")
                         (not= fname (:function (:row r)))
                         (fail :deploy/not-found (str "deployment " dep-id " is not of function " fname " (it moves " (or (:function (:row r)) "no function") ")")
                               :hint "dlb deploy list FUNCTION names its deployments")
                         :else (:row r))))
        listing (when-not direct (lambda-cd-rows! ctx fname))
        row (or direct (pick (:rows listing) dep-id))]
    (when-not row
      (fail :deploy/not-found (str "no CodeDeploy deployment " (or dep-id "(newest)") " of function " fname)
            :ids (mapv :id (:rows listing)) :hint "dlb deploy list FUNCTION names them; a function that was never deployed through CodeDeploy has only versions"))
    (let [detail (cd-detail! ctx row now)]
      {:env (:name (:env ctx)) :kind :lambda :cluster "lambda" :service fname :row row :rows (:rows listing) :source :codedeploy
       :notes (:notes listing) :absent (when (:absent listing) {:codedeploy (:absent listing)}) :errors (:errors listing)
       :events [] :event-count 0 :started [] :replaced [] :log {:entries []}
       :cd detail :cd-events (cd/lifecycle-items (get-in detail [:targets :targets])) :fetched-at now})))

(defn collect!
  "Everything one deployment's text needs, fetched now: -> a map for `render-show`. `lstate` is the log state atom (see `collect-logs!`)."
  [ctx target dep-id lstate {:keys [limit] :or {limit list-cap}}]
  (let [{:keys [cluster service]} target
        now (store/now)
        svc (or (describe-service ctx cluster service (str "ds:" cluster "/" service)) (:svc target))
        listing (list-rows! ctx (assoc target :svc svc) limit)
        row (pick (:rows listing) dep-id)
        row (when row (let [newer (last (take-while #(not= (:id row) (:id %)) (:rows listing)))] (cond-> row newer (assoc :next-started (:started newer)))))]
    (when-not row
      (fail :deploy/not-found (str "no deployment " (or dep-id "(newest)") " of " cluster "/" service)
            :ids (mapv :id (:rows listing)) :hint "dlb deploy list names them"))
    (let [cd-d (when (= :codedeploy (:source row)) (cd-detail! ctx row now))
          tasks (fetch-tasks! ctx cluster service)
          _ (doseq [t tasks] (td-info! ctx (:task-definition t)))
          {:keys [started replaced]} (tasks-of row tasks now)
          local? (= :local (:kind (:env ctx)))
          sources (log-sources started @(:tds ctx) local? (when local? (local-resolver (:ws ctx) cluster service)))
          lg (collect-logs! ctx sources lstate local? (:ws ctx) now)]
      {:env (:name (:env ctx)) :cluster cluster :service service :row row :rows (:rows listing) :source (:source listing)
       :notes (:notes listing) :absent (:absent listing) :errors (:errors listing)
       :events (events-in (:events svc) row now) :event-count (count (:events svc))
       :started started :replaced replaced :log lg
       :cd cd-d :cd-events (when cd-d (cd/lifecycle-items (get-in cd-d [:targets :targets])))
       :service-state (select-keys svc [:desiredCount :runningCount :pendingCount])
       :fetched-at now})))

(defn- t-str [i] (some-> i str))

(defn- header-line [k v] (format "%-12s %s" k v))

(defn render-show
  "The deploy text (pure)."
  [{:keys [env cluster service row source notes events event-count started replaced log fetched-at service-state absent errors cd kind]}]
  (let [{:keys [id status rollout-state rollout-reason finished]} row
        begin (:started row)
        lambda? (= :lambda kind)
        lines
        (concat
         [(header-line "deploy" (str (if lambda? "lambda " (str cluster "/")) service "   " id))
          (header-line "env" (str env "   fetched " (t-str fetched-at)))
          (header-line "status" (str status (when rollout-state (str "   rollout " rollout-state)) (when rollout-reason (str "   " rollout-reason))))
          (header-line "window" (str (t-str begin) " .. " (if finished (str (t-str finished) "   (" (seconds begin finished) " s)") "still running")))]
         (when-not lambda?
           [(header-line "revision" (str (or (:revision-before row) "-") " -> " (or (:revision-after row) "?")
                                         (when (:family row) (str "   (" (:family row) ":" (or (:revision-before row) "-") " -> " (:family row) ":" (:revision-after row) ")"))))
            (header-line "image" (str (or (:image-before row) "-") " -> " (or (:image-after row) "?")))
            (header-line "commit" (if (:git-sha row) (str (:git-sha row) "   (" (:commit-source row) ")") "unknown   (no git-sha tag on the task definition, no commit-like image tag)"))
            (header-line "ci run" (if (:run-id row) (str (:run-id row) "   (task definition tag run-id)") "unknown"))])
         [(header-line "source" (name source))]
         (when (:circuit-breaker row) [(header-line "breaker" (:circuit-breaker row))])
         (when (seq service-state)
           [(header-line "service" (str "desired " (:desiredCount service-state) "  running " (:runningCount service-state) "  pending " (:pendingCount service-state)))])
         (when cd (cd/header-lines row (:alias-now cd)))
         (for [n notes] (header-line "note" n))
         (for [[k c] absent] (header-line "absent" (str (name k) " (" c ")")))
         (for [e errors] (header-line "error" (str (:op e) " " (:code e) " " (:message e))))
         (when cd
           (concat [""] (cd/target-lines (:targets cd))
                   [""] (cd/hook-section (or (:hook cd) {}) (:hooks-named? cd))))
         (when-not lambda?
           (concat
            [""
             (str "== events (" (count events) " inside the window; the service keeps its last " event-count ")")]
            (for [e events] (str (t-str (:at e)) "  " (:message e)))
            [""
             (str "== tasks (started by this deployment: " (count started) "; replaced by it: " (count replaced) ")")]
            (mapcat (fn [[kind t]]
                      (cons (str (format "%-9s" kind) (short-id (:id t)) "  rev " (:revision t) "  " (:last-status t) "  created " (t-str (:created t))
                                 (when (:stopped t) (str "  stopped " (t-str (:stopped t)) "  " (:stop-code t) ": " (:stopped-reason t))))
                            (for [c (:containers t)]
                              (str "    container " (:name c) "  " (:last-status c)
                                   (when (some? (:exit-code c)) (str "  exit " (:exit-code c))) (when (:reason c) (str "  " (:reason c)))))))
                    (concat (map vector (repeat "started") started) (map vector (repeat "replaced") replaced)))
            [""
             (str "== log (" (count (:entries log)) " lines of the containers of the started tasks, time order)")]
            (for [n (:notes log)] (str "# " n))
            (for [e (:entries log)] (str (t-str (:at e)) " [" (:container e) (when (:task e) (str "/" (:task e))) "] " (:text e))))))]
    (str (str/join "\n" lines) "\n")))

(defn render-lambda
  "The text of a Lambda's versions and CodeDeploy deployments (pure)."
  [{:keys [env function rows latest fetched-at notes absent errors]}]
  (let [cds (filter #(= :codedeploy (:source %)) rows)
        vs (remove #(= :codedeploy (:source %)) rows)]
    (str (str/join "\n"
              (concat [(header-line "lambda" function) (header-line "env" (str env "   fetched " (t-str fetched-at)))
                       (header-line "latest" (str (:State latest) "  update " (:LastUpdateStatus latest) (when (:LastUpdateStatusReason latest) (str ": " (:LastUpdateStatusReason latest)))
                                                  "  code " (:CodeSha256 latest) "  modified " (:LastModified latest)))]
                      (for [n notes] (header-line "note" n))
                      (for [[k c] absent] (header-line "absent" (str (name k) " (" c ")")))
                      (for [e errors] (header-line "error" (str (:op e) " " (:code e) " " (:message e))))
                      (when (seq cds) [(header-line "note" cd/emulator-note)])
                      (when (seq cds)
                        (concat ["" (str "== codedeploy deployments (" (count cds) ", newest first)")]
                                (for [r cds]
                                  (str (:id r) "  " (format "%-9s" (:status r)) " " (t-str (:started r)) " .. " (or (t-str (:finished r)) "running")
                                       "  alias " (:alias r) " " (or (:revision-before r) "?") " -> " (or (:revision-after r) "?")
                                       "  " (:application r) "/" (:group r)
                                       (when (:error-code r) (str "  " (:error-code r) ": " (:rollout-reason r)))
                                       (when (:description r) (str "  \"" (:description r) "\""))))))
                      ["" "== versions (newest first)"]
                      (for [r vs] (str (format "%-8s" (:version r)) (t-str (:started r)) "  " (:status r) "  update " (:rollout-state r)
                                       "  code " (:code-sha256 r) (when (:rollout-reason r) (str "  " (:rollout-reason r)))
                                       (when (:description r) (str "  \"" (:description r) "\""))))))
         "\n")))

(defn render-list
  "The table of `list!` (pure): one row per deployment."
  [{:keys [env cluster service rows notes absent errors fetched-at function]}]
  (str "# deployments of " (or function (str cluster "/" service)) " in " env ", fetched " (t-str fetched-at) "\n"
       "# id\tstatus\trollout\tstarted\tfinished\trevision\timage\tcommit\tci run\n"
       (apply str (for [r rows]
                    (str (:id r) "\t" (:status r) "\t" (or (:rollout-state r) "-") "\t" (t-str (:started r)) "\t" (or (t-str (:finished r)) "-") "\t"
                         (or (:revision-before r) "-") " -> " (or (:revision-after r) "?") "\t"
                         (or (:image-before r) "-") " -> " (or (:image-after r) "?") "\t"
                         (or (:git-sha r) "-") "\t" (or (:run-id r) "-")
                         (when (= :codedeploy (:source r))
                           (str "\tcodedeploy " (:application r) "/" (:group r) "  " (:platform r) (when (:error-code r) (str "  " (:error-code r)))))
                         (when (:rollout-reason r) (str "\t" (:rollout-reason r))) "\n")))
       (when (some #(= :codedeploy (:source %)) rows) (str "# note: " cd/emulator-note "\n"))
       (apply str (for [n notes] (str "# note: " n "\n")))
       (apply str (for [[k c] absent] (str "# absent: " (name k) " (" c ")\n")))
       (apply str (for [e errors] (str "# error: " (:op e) " " (:code e) " " (:message e) "\n")))))

;; --- files ----------------------------------------------------------------------------------------------------------

(defn dir [ws env-name cluster service] (store/path ws "cache" (store/safe-name env-name) "deploy" (slug cluster) (slug service)))
(defn show-path [ws env-name cluster service id] (.resolve (dir ws env-name cluster service) (str (slug id) ".txt")))
(defn list-path [ws env-name cluster service] (.resolve (dir ws env-name cluster service) "list.txt"))

(defn- write-text! [path ^String s]
  (store/write-bytes-atomic! path (.getBytes s StandardCharsets/UTF_8)))

(defn- keep-row [env-name cluster service row]
  (-> row (select-keys [:id :source :status :rollout-state :started :finished :revision-before :revision-after :image-before :image-after
                        :git-sha :run-id :commit-source :family :rollout-reason :version :code-sha256 :description
                        :application :group :platform :error-code :function :alias :config :created])
      (assoc :env env-name :cluster cluster :service service)))

(defn- age-of [^Instant at now] (deployed/age-text (- (.toEpochMilli ^Instant now) (.toEpochMilli at))))

;; --- operations -----------------------------------------------------------------------------------------------------

(declare list-fetch!)

(defn- cached-list
  "The answer of the last `list!` of `service-spec` read from its `list.edn`, with its age; nil when there is none. No AWS call."
  [ws env-name service-spec limit]
  (let [base (store/path ws "cache" (store/safe-name env-name) "deploy")
        [c n] (if (str/includes? (str service-spec) "/") (str/split service-spec #"/" 2) [nil service-spec])
        d (first (for [cd (or (.listFiles (.toFile base)) []) :when (or (nil? c) (= (slug c) (.getName cd)))
                       :let [sd (io/file cd (slug n))] :when (.isFile (io/file sd "list.edn"))] sd))]
    (when d
      (let [m (store/read-edn (.toPath (io/file d "list.edn")))
            at (:fetched-at m)
            txt (io/file d "list.txt")]
        (cond-> {:env env-name :service (or (:service m) (:function m)) :kind (or (:kind m) :ecs) :source (:source m)
                 :count (count (:rows m)) :rows (vec (take limit (:rows m))) :cached true :fetched-at at :path (str txt) :text (str txt)}
          (:cluster m) (assoc :cluster (:cluster m))
          (:latest m) (assoc :latest (:latest m))
          (seq (:notes m)) (assoc :notes (:notes m))
          at (assoc :age (age-of at (store/now)))
          (.isFile txt) (assoc :table (slurp txt)))))))

(defn list!
  "See the namespace docstring. `:cached true` answers from the file of the last list with its age and no AWS call (`deploy/no-cache` when there is none).
  -> `{:env :service :cluster :kind :source :count :rows :path :text :table :notes :absent :errors :raw [:cached :age]}`."
  [ws env-name service-spec {:keys [limit cached] :or {limit list-cap}}]
  (if cached
    (or (cached-list ws env-name service-spec limit)
        (fail :deploy/no-cache (str "no cached deployments of " service-spec " in " env-name) :hint "dlb deploy list without --cached fetches them"))
    (list-fetch! ws env-name service-spec limit)))

(defn- list-fetch! [ws env-name service-spec limit]
  (let [ctx (ctx-of ws env-name nil)
        target (resolve-service! ctx service-spec)
        now (store/now)]
    (if (= :lambda (:kind target))
      (let [{vrows :rows :keys [latest notes]} (lambda-rows! ctx (:function target) (:config target))
            cdr (lambda-cd-rows! ctx (:function target))
            rows (newest-first (into (vec vrows) (:rows cdr)))
            notes (vec (concat notes (:notes cdr)))
            absent (when (:absent cdr) {:codedeploy (:absent cdr)})
            errors (:errors cdr)
            d {:env env-name :function (:function target) :rows rows :latest latest :notes notes :absent absent :errors errors :fetched-at now}
            p (.resolve (store/path ws "cache" (store/safe-name env-name) "deploy" "lambda" (slug (:function target))) "list.txt")
            tbl (render-lambda d)
            lat {:state (:State latest) :update-status (:LastUpdateStatus latest) :reason (or (:LastUpdateStatusReason latest) (:StateReason latest))
                 :code-sha256 (:CodeSha256 latest) :modified (:LastModified latest)}]
        (write-text! p tbl)
        (store/write-edn! (.resolveSibling p "list.edn")
                          {:env env-name :function (:function target) :kind :lambda :source :lambda :fetched-at now
                           :rows (mapv #(keep-row env-name "lambda" (:function target) %) rows) :notes (vec notes)
                           :latest lat})
        (cond-> {:env env-name :service (:function target) :kind :lambda :latest lat :source :lambda :count (count rows) :rows (vec (take limit rows))
                 :path (str p) :text (str p) :table tbl :notes (vec notes) :raw (count @(:refs ctx)) :fetched-at now}
          absent (assoc :absent absent)
          (seq errors) (assoc :errors errors)))
      (let [{:keys [cluster service]} target
            listing (list-rows! ctx target limit)
            rows (vec (take limit (:rows listing)))
            d {:env env-name :cluster cluster :service service :rows rows :notes (:notes listing) :absent (:absent listing)
               :errors (:errors listing) :fetched-at now}
            tbl (render-list d)
            p (list-path ws env-name cluster service)]
        (write-text! p tbl)
        (store/write-edn! (.resolveSibling p "list.edn")
                          {:env env-name :cluster cluster :service service :fetched-at now :source (:source listing) :kind :ecs :notes (:notes listing)
                           :rows (mapv #(keep-row env-name cluster service %) rows) :raw @(:refs ctx)})
        (cond-> {:env env-name :service service :cluster cluster :kind :ecs :source (:source listing) :count (count rows) :rows rows
                 :path (str p) :text (str p) :table tbl :raw (count @(:refs ctx)) :fetched-at now}
          (seq (:notes listing)) (assoc :notes (:notes listing))
          (seq (:absent listing)) (assoc :absent (:absent listing))
          (seq (:errors listing)) (assoc :errors (:errors listing)))))))

(defn- persist-show! [ws env-name d]
  (let [{:keys [cluster service row]} d
        p (show-path ws env-name cluster service (:id row))]
    (write-text! p (render-show d))
    (store/write-edn! (.resolveSibling p (str (slug (:id row)) ".edn")) {:row (keep-row env-name cluster service row) :fetched-at (:fetched-at d)})
    p))

(defn- cached-show [ws env-name service-spec dep-id]
  (let [base (store/path ws "cache" (store/safe-name env-name) "deploy")
        [c n] (if (str/includes? (str service-spec) "/") (str/split service-spec #"/" 2) [nil service-spec])
        dirs (for [cd (or (.listFiles (.toFile base)) []) :when (or (nil? c) (= (slug c) (.getName cd)))
                   :let [sd (io/file cd (slug n))] :when (.isDirectory sd)] sd)
        d (first dirs)]
    (when d
      (let [id (or dep-id (some-> (store/read-edn (.toPath (io/file d "list.edn"))) :rows first :id))
            f (when id (io/file d (str (slug (if (re-matches #"[0-9]+" (str id)) (str "ecs-svc/" id) id)) ".txt")))]
        (when (and f (.isFile f))
          {:path (str f) :body (slurp f) :cached true
           :age (age-of (.toInstant (java.util.Date. (.lastModified f))) (store/now))})))))

(defn show!
  "See the namespace docstring. -> `{:path :body :deployment :status :rollout-state :started :finished :tasks :lines :cached?}`."
  [ws env-name service-spec {:keys [deployment cached]}]
  (if-let [c (when cached (cached-show ws env-name service-spec deployment))]
    c
    (do
      (when cached (fail :deploy/no-cache (str "no cached deploy text of " service-spec " in " env-name) :hint "dlb deploy show without --cached fetches it"))
      (let [ctx (ctx-of ws env-name nil)
            target (resolve-service! ctx service-spec)]
        (if (= :lambda (:kind target))
          (if (str/blank? deployment)
            (let [r (list! ws env-name service-spec {})]
              {:path (:path r) :body (:table r) :kind :lambda :deployment (:id (first (:rows r))) :lines 0})
            (let [d (collect-lambda! ctx target deployment)
                  p (persist-show! ws env-name d)
                  row (:row d)]
              {:path (str p) :body (slurp (.toFile p)) :kind :lambda :deployment (:id row) :status (:status row) :rollout-state (:rollout-state row)
               :started (:started row) :finished (:finished row) :error-code (:error-code row) :revision-before (:revision-before row)
               :revision-after (:revision-after row) :tasks 0 :lines (count (get-in d [:cd :hook :entries])) :raw (count @(:refs ctx))}))
          (let [d (collect! ctx target deployment (atom {}) {})
                p (persist-show! ws env-name d)
                row (:row d)]
            {:path (str p) :body (slurp (.toFile p)) :deployment (:id row) :status (:status row) :rollout-state (:rollout-state row)
             :started (:started row) :finished (:finished row) :revision-before (:revision-before row) :revision-after (:revision-after row)
             :image-before (:image-before row) :image-after (:image-after row) :git-sha (:git-sha row) :run-id (:run-id row)
             :tasks (count (:started d)) :lines (count (:entries (:log d))) :raw (count @(:refs ctx))}))))))

;; --- follow ---------------------------------------------------------------------------------------------------------

(defn- terminal? [row] (boolean (or (#{"COMPLETED" "FAILED"} (:rollout-state row)) (:finished row))))

(defn failing?
  "True when the deployment started `failing-after` tasks that died and none of its tasks runs (a rollout without a circuit breaker never fails by itself)."
  [started]
  (and (>= (count (filter #(= "STOPPED" (:last-status %)) started)) failing-after)
       (not-any? #(#{"RUNNING" "PENDING" "PROVISIONING" "ACTIVATING"} (:last-status %)) started)))

(defn- failure-lines
  "The last words of a failed rollout: every stopped task it started with its stop code, reason and exit codes, and the container's last lines;
  for a CodeDeploy deployment its error, the lifecycle events that failed and the hook's last lines."
  [{:keys [started log cd row]}]
  (concat (when (:error-code row)
            [(str "codedeploy " (:id row) " " (:status row) ": " (:error-code row) " " (:rollout-reason row))])
          (for [x (get-in cd [:targets :targets]) e (:events x) :when (= "Failed" (:status e))]
            (str "lifecycle " (:name e) " of " (:id x) " failed" (when (:message e) (str ": " (:error-code e) " " (:message e)))))
          (for [e (take-last 10 (get-in cd [:hook :entries]))] (str (t-str (:at e)) " [hook " (:function e) "] " (:text e)))
          (for [t (take-last 3 (filter #(= "STOPPED" (:last-status %)) started))]
            (str "task " (short-id (:id t)) " stopped " (t-str (:stopped t)) " " (:stop-code t) ": " (:stopped-reason t)
                 (when-let [cs (seq (keep #(when (some? (:exit-code %)) (str (:name %) " exit " (:exit-code %))) (:containers t)))] (str "  (" (str/join ", " cs) ")"))))
          (for [e (take-last 20 (:entries log))] (str (t-str (:at e)) " [" (:container e) (when (:task e) (str "/" (:task e))) "] " (:text e)))))

(defn- new-items
  "What `d` has that `seen` (a set of keys) has not: -> `[[at text kind key] ...]` sorted by time. A task shows at its creation the first time it
  is seen, and at the instant the change was seen afterwards."
  [d seen now]
  (->> (concat (for [e (:events d)] [(:at e) (str (t-str (:at e)) " event  " (:message e)) :event [:event (:id e) (:message e)]])
               (for [t (concat (:started d) (:replaced d))
                     :let [first? (not-any? #(and (= :task (first %)) (= (:id t) (second %))) seen)
                           at (cond first? (or (:created t) now) (:stopped t) (:stopped t) :else now)]]
                 [at
                  (str (t-str at) " task   " (short-id (:id t)) " rev " (:revision t) " " (:last-status t)
                       (when (:stop-code t) (str "  " (:stop-code t) ": " (:stopped-reason t))))
                  :task [:task (:id t) (:last-status t)]])
               (for [e (:cd-events d)] [(:at e) (str (t-str (:at e)) " event  " (:message e)) :event [:event (:id e) (:message e)]])
               (for [e (get-in d [:cd :hook :entries])]
                 [(:at e) (str (t-str (:at e)) " [hook " (:function e) "] " (:text e)) :log [:hook (:ts e) (:function e) (:text e)]])
               (let [n (volatile! {})]
                 (for [e (:entries (:log d))
                       :let [k [(:at e) (:container e) (:task e) (:text e)]
                             c (get (vswap! n update k (fnil inc 0)) k)]]
                   [(:at e) (str (t-str (:at e)) " [" (:container e) (when (:task e) (str "/" (:task e))) "] " (:text e)) :log (into [:log c] k)])))
       (remove #(contains? seen (nth % 3)))
       (sort-by (comp #(.toEpochMilli ^Instant %) first))))

(defn follow!
  "See the namespace docstring. Opts: `:deployment :emit! :cancelled? :poll-ms :timeout-ms`. -> `{:path :deployment :status :rollout-state :ended :lines}`."
  [ws env-name service-spec {:keys [deployment emit! cancelled? poll-ms timeout-ms wait-ms] :or {poll-ms default-poll-ms timeout-ms default-timeout-ms wait-ms 20000}}]
  (let [cancelled? (or cancelled? (constantly false))
        emit! (or emit! (fn [_]))
        ctx (ctx-of ws env-name (atom {}))
        target (resolve-service! ctx service-spec)
        lstate (atom {})
        seen (atom #{})
        deadline (+ (System/currentTimeMillis) timeout-ms)
        line! (fn [kind text at & [path]] (emit! (cond-> {:event "deploy.line" :kind (name kind) :text text :at (str at)} path (assoc :path (str path)))))
        last-text (atom nil)
        announced (atom false)
        waiting (atom false)
        wait-until (+ (System/currentTimeMillis) wait-ms)]
    (loop [dep-id deployment grace 0 patience 0]
      (let [d (try (if (= :lambda (:kind target))
                     (collect-lambda! ctx target dep-id)
                     (collect! ctx target dep-id lstate {:limit 5}))
                   (catch clojure.lang.ExceptionInfo e
                     ;; a deploy that has not started yet: nothing to follow until it appears (while we are waiting for it)
                     (if (and (= :deploy/not-found (:type (ex-data e))) (nil? dep-id) (< patience 3)) ::wait (throw e))))]
        (if (= ::wait d)
          (do (Thread/sleep (long poll-ms)) (recur dep-id grace (inc patience)))
          (if (and (nil? dep-id) (nil? deployment) (terminal? (:row d)) (< (System/currentTimeMillis) wait-until) (not (cancelled?)))
            ;; `deploy follow` right after a deploy was sent: the newest one may still be the one before it
            (do (reset! lstate {})   ; what was read for the finished one is not this deployment's
                (when (compare-and-set! waiting false true)
                  (line! :status (str (:id (:row d)) " is finished; waiting up to " (quot wait-ms 1000) " s for a newer deployment") (store/now)))
                (Thread/sleep (long (min poll-ms 1000)))
                (recur nil 0 0))
          (let [row (:row d)
                text (render-show d)
                items (new-items d @seen (store/now))]
            (when (not= text @last-text)
              (write-text! (show-path ws env-name (:cluster d) (:service d) (:id row)) text)
              (store/write-edn! (.resolveSibling (show-path ws env-name (:cluster d) (:service d) (:id row)) (str (slug (:id row)) ".edn"))
                                {:row (keep-row env-name (:cluster d) (:service d) row) :fetched-at (:fetched-at d)})
              (reset! last-text text))
            (when (compare-and-set! announced false true)
              (line! :status (str "following " (:cluster d) "/" (:service d) " " (:id row) "  " (:status row) " rollout " (:rollout-state row)) (store/now)
                     (show-path ws env-name (:cluster d) (:service d) (:id row))))
            (doseq [[at txt kind k] items] (line! kind txt at) (swap! seen conj k))
            (let [fail? (and (not (terminal? row)) (failing? (:started d)))
                  done? (or (terminal? row) fail?)
                  ended (cond (and (terminal? row) (= "FAILED" (:rollout-state row))) :failed
                              (terminal? row) :completed
                              fail? :failing
                              (cancelled?) :cancelled
                              (> (System/currentTimeMillis) deadline) :timeout)]
              (cond
                ;; one more look: the last log lines come a moment after the rollout ends, and ECS writes "has reached a steady state" after it
                (and done? (not fail?) (= :completed ended) (< grace 40)
                     (or (< grace 1) (and (not= :codedeploy (:source row)) (not-any? #(str/includes? (str (:message %)) "steady state") (:events d)))))
                (do (Thread/sleep 3000) (recur (:id row) (inc grace) 0))
                (and done? (not fail?) (zero? grace) (not= :completed ended))
                (do (Thread/sleep 3000) (recur (:id row) 1 0))
                (or done? ended)
                (let [fl (when (#{:failed :failing} ended) (failure-lines d))
                      summary (str "== rollout " (case ended :completed "COMPLETED" :failed "FAILED" :failing "FAILING: the service keeps starting tasks that die, no task runs"
                                                    :cancelled "stopped following" :timeout "still running, stopped following")
                                   "  " (:status row) (when (:rollout-reason row) (str "  " (:rollout-reason row))))]
                  (line! :status summary (store/now))
                  (doseq [l fl] (line! :failure l (store/now)))
                  (when (seq fl)
                    (let [p (show-path ws env-name (:cluster d) (:service d) (:id row))]
                      (write-text! p (str (render-show d) "\n" summary "\n" (str/join "\n" fl) "\n"))))
                  {:path (str (show-path ws env-name (:cluster d) (:service d) (:id row))) :deployment (:id row) :status (:status row)
                   :rollout-state (:rollout-state row) :ended ended :tasks (count (:started d)) :lines (count (:entries (:log d))) :follow true})
                :else (do (Thread/sleep (long poll-ms)) (recur (:id row) grace 0)))))))))))

;; --- the seam for ci.* -------------------------------------------------------------------------------------------------

(defn- cache-rows
  "Every deployment row the workspace's caches know, across environments: the current revision of each service of `deployed.edn` and the
  rows of `deploy/**.edn`. No AWS call."
  [ws]
  (vec
   (concat
    (for [e (env/names ws)
          :let [snap (deployed/cached ws e)]
          s (:services snap)
          :when (and (= :ecs (:kind s)) (or (:git-sha s) (:run-id s)))]
      {:env e :cluster (:cluster s) :service (:name s) :revision (:revision s) :deployment nil :status (:status s)
       :started (->> (:deployments s) (keep :updated-at) sort last) :git-sha (:git-sha s) :run-id (:run-id s) :current? true})
    (for [e (env/names ws)
          :let [root (.toFile (store/path ws "cache" (store/safe-name e) "deploy"))]
          f (file-seq root)
          :when (and (.isFile ^java.io.File f) (str/ends-with? (.getName ^java.io.File f) ".edn"))
          :let [m (try (store/read-edn (.toPath ^java.io.File f)) (catch Throwable _ nil))]
          r (concat (:rows m) (when (:row m) [(:row m)]))
          :when (or (:git-sha r) (:run-id r))]
      {:env (:env r) :cluster (:cluster r) :service (:service r) :revision (:revision-after r) :deployment (:id r) :status (:status r)
       :rollout-state (:rollout-state r) :started (:started r) :git-sha (:git-sha r) :run-id (:run-id r)}))))

(defn- sha-match? [want have]
  (and (string? want) (string? have) (>= (count want) 7)
       (or (str/starts-with? have want) (str/starts-with? want have))))

(defn- newest-distinct
  "`rows` newest first; the current revision of a service is dropped when a cached deployment of the same revision is there."
  [rows]
  (let [rows (vals (reduce (fn [m r] (assoc m ((juxt :env :cluster :service :deployment :revision :current?) r) r)) {} rows))   ; list.edn and <id>.edn both know a deployment
        hist (remove :current? rows)
        covered (set (map (juxt :env :cluster :service :revision) hist))]
    (->> (concat hist (remove #(covered ((juxt :env :cluster :service :revision) %)) (filter :current? rows)))
         (sort-by #(- (.toEpochMilli ^Instant (or (:started %) Instant/EPOCH))))
         vec)))

(defn by-commit
  "The deployments that carry commit `sha` (the full commit or a prefix of at least 7 characters, either way round), newest first, from the
  caches only: `[{:env :cluster :service :revision :deployment :status :started :run-id :git-sha :current?}]`. The current revision of a service
  appears once, as `:current? true`, unless a deployment row of the same revision is cached too."
  [ws sha]
  (newest-distinct (filter #(sha-match? sha (:git-sha %)) (cache-rows ws))))

(defn by-run-id
  "As `by-commit` for a CI run id (exact match)."
  [ws run-id]
  (newest-distinct (filter #(= (str run-id) (str (:run-id %))) (cache-rows ws))))

(defn commit-notes
  "`[\"deployed to aws: acme-billing revision 12\" ...]` for commit `sha`: one per service and revision, newest first."
  [ws sha]
  (vec (distinct (for [r (by-commit ws sha)]
                   (str "deployed to " (:env r) ": " (:service r) (when (:revision r) (str " revision " (:revision r))))))))

(defn- local-run-windows
  "The windows of the local runs numbered like `run-id` (`local-<n>`; the number is per repository, so `repo` narrows): the deploy job's window (every job
  when none is named like that) from the kept `run.edn`. -> `[{:repo :run :from :to :what}]`."
  [ws run-id repo]
  (when-let [[_ n] (re-matches #"local-(\d+)" (str run-id))]
    (let [root (.toFile (store/path ws "ci" "local"))]
      (vec (for [rd (or (.listFiles root) []) :when (and (.isDirectory rd) (or (str/blank? repo) (= (str/replace repo #"[^A-Za-z0-9._-]+" "-") (.getName rd))))
                 af (or (.listFiles rd) []) :when (re-matches (re-pattern (str n "-\\d+")) (.getName af))
                 :let [f (io/file af "run.edn")] :when (.isFile f)
                 :let [m (try (store/read-edn (.toPath f)) (catch Throwable _ nil))
                       jobs (:jobs m)
                       dj (seq (filter #(re-find #"(?i)deploy" (str (:id %) " " (:name %))) jobs))
                       js (or dj jobs)
                       from (->> js (keep #(->inst (:started-at %))) sort first)
                       to (->> js (keep #(->inst (:completed-at %))) sort last)]
                 :when (and from to)]
             {:repo (.getName rd) :run run-id :from from :to to :what (if dj "deploy job" "run")})))))

(defn- cd-cache-rows
  "The CodeDeploy rows the caches know (`deploy/**.edn`), a Lambda's with no cluster. No AWS call."
  [ws]
  (vec (for [e (env/names ws)
             :let [root (.toFile (store/path ws "cache" (store/safe-name e) "deploy"))]
             f (file-seq root)
             :when (and (.isFile ^java.io.File f) (str/ends-with? (.getName ^java.io.File f) ".edn"))
             :let [m (try (store/read-edn (.toPath ^java.io.File f)) (catch Throwable _ nil))]
             r (concat (:rows m) (when (:row m) [(:row m)]))
             :when (= :codedeploy (:source r))]
         {:env (:env r) :cluster (when-not (= "lambda" (:cluster r)) (:cluster r)) :service (:service r) :revision (:revision-after r) :deployment (:id r)
          :status (:status r) :rollout-state (:rollout-state r) :started (:started r) :created (:created r) :git-sha (:git-sha r) :run-id (:run-id r)
          :platform (:platform r)})))

(defn by-run-window
  "The CodeDeploy deployments (from the caches) created inside the window of the deploy job of local run `run-id`: nothing but the time ties them
  (a Lambda deployment's description is `run <GITHUB_RUN_ID>`, which is 1 under act; an ECS one has the commit through its task definition). Each row says so
  in `:tied`. -> rows newest first."
  [ws run-id repo]
  (let [wins (local-run-windows ws run-id repo)
        rows (cd-cache-rows ws)]
    (->> (for [w wins r rows
               :let [at (or (:created r) (:started r))]
               :when (and at (not (before? at (.minusSeconds ^Instant (:from w) 2))) (not (before? (.plusSeconds ^Instant (:to w) 2) at)))]
           (assoc r :tied (str "by time: created inside the window of the " (:what w) " of " run-id " (" (:repo w) ", " (:from w) " .. " (:to w) ")")))
         (reduce (fn [m r] (assoc m [(:env r) (:service r) (:deployment r)] r)) {})
         vals
         (sort-by #(- (.toEpochMilli ^Instant (or (:started %) Instant/EPOCH))))
         vec)))

(defn where
  "`deploy.where`: the deployments that carry a commit (`:sha`) or were made by a CI run (`:run-id`), from the caches only: `{:rows [...] :notes [...]}`
  (the rows of `by-commit` / `by-run-id`, the strings of `commit-notes`). A run `local-<n>` also finds the CodeDeploy deployments created inside the
  window of its deploy job (`:repo` narrows the run number to a repository), each marked `:tied`."
  [ws {:keys [sha run-id repo]}]
  (cond (not (str/blank? sha)) {:rows (by-commit ws sha) :notes (commit-notes ws sha)}
        (not (str/blank? run-id)) (let [rs (by-run-id ws run-id)
                                        ids (set (map :deployment rs))
                                        ws* (remove #(contains? ids (:deployment %)) (by-run-window ws run-id repo))
                                        rs (vec (concat rs ws*))]
                                    {:rows rs :notes (vec (distinct (for [r rs] (str "deployed to " (:env r) ": " (:service r) (when (:revision r) (str " revision " (:revision r)))
                                                                                    (when (:tied r) (str " (" (:tied r) ")"))))))})
        :else (fail :deploy/no-key "deploy.where needs a sha or a run-id" :hint "dlb deploy where --sha <commit>")))
