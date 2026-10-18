(ns deliberator.awsdiscover
  "What exists in an environment, for the editor to complete names from: queues, tables, buses, functions, buckets, topics,
  parameters, secrets, streams, state machines. Read calls only, each through `aws/invoke!` (so raw-persisted like every
  call); a section that fails is reported in `:errors` and the others still answer."
  (:require [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.env :as env]))

(defn- last-seg [s] (last (str/split (str s) #"/")))

(def ^:private sections
  "kind -> [service op request extract], extract: response -> [{:name :ref}]."
  {"sqs" [:sqs :ListQueues {} #(for [u (:QueueUrls %)] {:name (last-seg u) :ref u})]
   "dynamodb" [:dynamodb :ListTables {} #(for [t (:TableNames %)] {:name t})]
   "eventbridge" [:eventbridge :ListEventBuses {} #(for [b (:EventBuses %)] {:name (:Name b) :ref (:Arn b)})]
   "lambda" [:lambda :ListFunctions {} #(for [f (:Functions %)] {:name (:FunctionName f) :ref (:FunctionArn f)})]
   "s3" [:s3 :ListBuckets {} #(for [b (:Buckets %)] {:name (:Name b)})]
   "sns" [:sns :ListTopics {} #(for [t (:Topics %)] {:name (last (str/split (:TopicArn t) #":")) :ref (:TopicArn t)})]
   "ssm" [:ssm :DescribeParameters {:MaxResults 50} #(for [p (:Parameters %)] {:name (:Name p) :ref (:Type p)})]
   "secretsmanager" [:secretsmanager :ListSecrets {:MaxResults 50} #(for [s (:SecretList %)] {:name (:Name s) :ref (:ARN s)})]
   "kinesis" [:kinesis :ListStreams {} #(for [s (:StreamNames %)] {:name s})]
   "states" [:states :ListStateMachines {} #(for [s (:stateMachines %)] {:name (:name s) :ref (:stateMachineArn s)})]})

(defn discover!
  "`{:env name :resources {kind [{:name :ref}]} :errors {kind message}}` of environment `env-name`."
  [ws env-name]
  (let [e (env/load ws env-name)
        results (pmap (fn [[kind [svc op req extract]]]
                        (let [r (aws/invoke! {:ws ws} e svc op req)]
                          [kind (if (aws/error? r)
                                  {:error (or (:message r) (:code r) (str (:category r)))}
                                  {:items (vec (sort-by :name (extract r)))})]))
                      sections)]
    {:env (:name e)
     :resources (into (sorted-map) (for [[k v] results :when (:items v)] [k (:items v)]))
     :errors (into (sorted-map) (for [[k v] results :when (:error v)] [k (:error v)]))}))
