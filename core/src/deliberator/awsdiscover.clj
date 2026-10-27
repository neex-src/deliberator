(ns deliberator.awsdiscover
  "What exists in an environment, for the editor to complete names from: queues, tables, buses, functions, buckets, topics,
  parameters, secrets, streams, log groups, clusters (and their services, task definitions), roles, state machines. Read calls only, each through `aws/invoke!` (so
  raw-persisted like every call), paged to the end (each service's own token: `NextToken`, `nextToken`, `Marker`,
  `LastEvaluatedTableName`, ...) up to `cap` items per kind (2 000: a real estate passes 50 parameters easily, and past the
  first page `terraform.drift` used to report declared resources as missing, review 2026-10-25 R5). A kind the cap stopped is
  listed in `:truncated {kind n}`; a section that fails is reported in `:errors` (the pages it got are kept) and the others
  still answer."
  (:require [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.env :as env]))

(def cap "Items kept per kind; `:truncated` says when a kind had more." 2000)

(def ^:private max-pages
  "A server that returns the same token for ever must not loop: pages of at least one item reach `cap` long before this."
  400)

(defn- last-seg [s] (last (str/split (str s) #"/")))

(defn- token
  "A `next` fn: the response's `resp-key` goes into the request as `req-key`."
  [resp-key req-key]
  (fn [req r] (when-let [t (get r resp-key)] (assoc req req-key t))))

(def ^:private sections
  "kind -> {:svc :op :req :extract (response -> [{:name :ref}]) :next (request response -> the next request, nil at the end)}."
  {"sqs" {:svc :sqs :op :ListQueues :req {:MaxResults 1000}
          :extract #(for [u (:QueueUrls %)] {:name (last-seg u) :ref u}) :next (token :NextToken :NextToken)}
   "dynamodb" {:svc :dynamodb :op :ListTables :req {:Limit 100}
               :extract #(for [t (:TableNames %)] {:name t}) :next (token :LastEvaluatedTableName :ExclusiveStartTableName)}
   "eventbridge" {:svc :eventbridge :op :ListEventBuses :req {:Limit 100}
                  :extract #(for [b (:EventBuses %)] {:name (:Name b) :ref (:Arn b)}) :next (token :NextToken :NextToken)}
   "lambda" {:svc :lambda :op :ListFunctions :req {}
             :extract #(for [f (:Functions %)] {:name (:FunctionName f) :ref (:FunctionArn f)}) :next (token :NextMarker :Marker)}
   "s3" {:svc :s3 :op :ListBuckets :req {}
         :extract #(for [b (:Buckets %)] {:name (:Name b)}) :next (token :ContinuationToken :ContinuationToken)}
   "sns" {:svc :sns :op :ListTopics :req {}
          :extract #(for [t (:Topics %)] {:name (last (str/split (:TopicArn t) #":")) :ref (:TopicArn t)}) :next (token :NextToken :NextToken)}
   "ssm" {:svc :ssm :op :DescribeParameters :req {:MaxResults 50}
          :extract #(for [p (:Parameters %)] {:name (:Name p) :ref (:Type p)}) :next (token :NextToken :NextToken)}
   "secretsmanager" {:svc :secretsmanager :op :ListSecrets :req {:MaxResults 100}
                     :extract #(for [s (:SecretList %)] {:name (:Name s) :ref (:ARN s)}) :next (token :NextToken :NextToken)}
   "kinesis" {:svc :kinesis :op :ListStreams :req {}
              :extract #(for [s (:StreamNames %)] {:name s})
              :next (fn [req r] (cond (:NextToken r) (assoc (dissoc req :ExclusiveStartStreamName) :NextToken (:NextToken r))
                                      (and (:HasMoreStreams r) (seq (:StreamNames r))) (assoc req :ExclusiveStartStreamName (last (:StreamNames r)))))}
   "logs" {:svc :logs :op :DescribeLogGroups :req {:limit 50}
           :extract #(for [g (:logGroups %)] {:name (:logGroupName g) :ref (:arn g)}) :next (token :nextToken :nextToken)}
   "ecs" {:svc :ecs :op :ListClusters :req {:maxResults 100}
          :extract #(for [a (:clusterArns %)] {:name (last-seg a) :ref a}) :next (token :nextToken :nextToken)}
   "ecs-taskdefs" {:svc :ecs :op :ListTaskDefinitions :req {:maxResults 100}
                   :extract #(for [a (:taskDefinitionArns %)] {:name (last-seg a) :ref a}) :next (token :nextToken :nextToken)}
   "iam" {:svc :iam :op :ListRoles :req {:MaxItems 1000}
          :extract #(for [r (:Roles %)] {:name (:RoleName r) :ref (:Arn r)})
          :next (fn [req r] (when (and (:IsTruncated r) (:Marker r)) (assoc req :Marker (:Marker r))))}
   "states" {:svc :states :op :ListStateMachines :req {:maxResults 1000}
             :extract #(for [s (:stateMachines %)] {:name (:name s) :ref (:stateMachineArn s)}) :next (token :nextToken :nextToken)}})

(defn- section!
  "Every page of one section -> `{:items [..] :error msg|nil :truncated? bool}`; the items of the pages before an error are kept."
  [ws e {:keys [svc op req extract next]}]
  (loop [req req items [] pages 0]
    (let [r (aws/invoke! {:ws ws} e svc op req)]
      (if (aws/error? r)
        {:items items :error (or (:message r) (:code r) (str (:category r))) :truncated? false}
        (let [items (into items (extract r))
              req' (next req r)]
          (cond
            (nil? req') {:items items :truncated? false}
            (>= (count items) cap) {:items (vec (take cap items)) :truncated? true}
            (>= (inc pages) max-pages) {:items items :truncated? true}
            :else (recur req' items (inc pages))))))))

(defn- list-all!
  "Every page of one list call -> `{:items [..] :error msg|nil}`; `k` is the response key holding the items."
  [ws e svc op req k]
  (loop [req req items [] pages 0]
    (let [r (aws/invoke! {:ws ws} e svc op req)]
      (if (aws/error? r)
        {:items items :error (or (:message r) (:code r) (str (:category r)))}
        (let [items (into items (get r k))]
          (if (and (:nextToken r) (< (inc pages) max-pages) (< (count items) cap))
            (recur (assoc req :nextToken (:nextToken r)) items (inc pages))
            {:items items}))))))

(defn- ecs-services!
  "The ECS services of `clusters` (items of the `ecs` section; their `:ref` is the cluster ARN) -> `{:items [..] :error msg|nil :truncated? bool}`.
  Per cluster `ListServices`, then `DescribeServices` 10 at a time, then `DescribeTaskDefinition` once per distinct definition (B31): an item is
  `{:name \"<cluster>/<service>\" :ref <service ARN> :cluster :task-definition :containers [container names]}`, so the editor can tie a service to
  the workspace service whose container it runs (the Terraform name of the service need not say it). A call that fails is reported, the rest stays."
  [ws e clusters]
  (let [errors (volatile! [])
        err! (fn [what r] (vswap! errors conj (str what ": " (or (:message r) (:code r) (str (:category r))))))
        services (vec (for [{cname :name carn :ref} clusters
                            :let [ls (list-all! ws e :ecs :ListServices {:cluster carn :maxResults 100} :serviceArns)
                                  _ (when (:error ls) (err! (str "ListServices " cname) {:message (:error ls)}))]
                            chunk (partition-all 10 (:items ls))
                            :let [d (aws/invoke! {:ws ws} e :ecs :DescribeServices {:cluster carn :services (vec chunk)})]
                            s (if (aws/error? d) (do (err! (str "DescribeServices " cname) d) []) (:services d))]
                        (assoc s ::cluster cname)))
        tds (into {} (for [arn (into (sorted-set) (keep :taskDefinition) services)
                           :let [d (aws/invoke! {:ws ws} e :ecs :DescribeTaskDefinition {:taskDefinition arn})]]
                       [arn (if (aws/error? d)
                              (do (err! "DescribeTaskDefinition" d) [])
                              (vec (keep :name (get-in d [:taskDefinition :containerDefinitions]))))]))
        items (vec (for [s services]
                     (cond-> {:name (str (::cluster s) "/" (:serviceName s)) :ref (:serviceArn s) :cluster (::cluster s)}
                       (:taskDefinition s) (assoc :task-definition (:taskDefinition s))
                       (seq (get tds (:taskDefinition s))) (assoc :containers (get tds (:taskDefinition s))))))
        items' (vec (take cap items))]
    (cond-> {:items items' :truncated? (> (count items) cap)}
      (seq @errors) (assoc :error (str/join "; " (take 3 @errors))))))

(defn discover!
  "`{:env name :resources {kind [{:name :ref}]} :errors {kind message} [:truncated {kind n}]}` of environment `env-name`:
  every kind that answered (its items sorted by name, at most `cap`), the kinds that failed, and the kinds the cap cut
  (`n` = the items kept). `ecs` lists the clusters, `ecs-services` their services (name `cluster/service`, with the container
  names of the task definition) and `ecs-taskdefs` the task definitions (`family:revision`)."
  [ws env-name]
  (let [e (env/load ws env-name)
        results (into {} (pmap (fn [[kind sec]] [kind (section! ws e sec)]) sections))
        results (cond-> results
                  (seq (get-in results ["ecs" :items])) (assoc "ecs-services" (ecs-services! ws e (get-in results ["ecs" :items]))))
        truncated (into (sorted-map) (for [[k v] results :when (:truncated? v)] [k (count (:items v))]))]
    (cond-> {:env (:name e)
             :resources (into (sorted-map) (for [[k v] results :when (or (seq (:items v)) (not (:error v)))] [k (vec (sort-by :name (:items v)))]))
             :errors (into (sorted-map) (for [[k v] results :when (:error v)] [k (:error v)]))}
      (seq truncated) (assoc :truncated truncated))))
