(ns deliberator.steps.awscall
  "Kind `aws`: any operation of any AWS service on the classpath, `### aws <service>.<Operation> [name]`, e.g.
  `### aws ssm.GetParameter`, `### aws s3.ListBuckets`, `### aws sns.Publish`. The body is the operation's request
  as AWS JSON (member names as in the API reference, `{\"Name\": \"/estate/x\"}`), nothing or `{}` for none; `B` blobs are
  not decoded (use a purpose-built kind). The verbs are `service.Operation` for every operation of the services below, so
  completion in the editor lists them all. `$` is the response as JSON (the raw response when the service speaks JSON, else the
  parsed response data); the raw bytes are persisted as for every AWS call. Facts: `elapsed`. Reads and mutations are
  classified by `env.ops` (a name that is not in its table counts as a mutation, except `Get*`/`List*`/`Describe*`/`Head*`)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cognitect.aws.service :as service]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c]))

(def services
  "aws-api names of the services the `aws` kind can call (their artifacts are in deps.edn)."
  [:s3 :sns :sqs :ssm :secretsmanager :states :kms :kinesis :monitoring :logs :cloudformation :iam :sts :ecs
   :lambda :dynamodb :eventbridge :scheduler :codedeploy])

(defn- operations [api]
  (try (map name (keys (:operations (service/service-description (name api)))))
       (catch Throwable _ [])))

(def verbs
  "Every `service.Operation` word."
  (into #{} (for [api services, op (operations api)] (str (name api) "." op))))

(defn- request-from-body [^bytes body]
  (let [t (some-> body c/text str str/trim)]
    (if (str/blank? t) {}
        (let [m (try (json/read-str t :key-fn keyword)
                     (catch Exception e (sa/fail (str "the body is not JSON: " (ex-message e))
                                                 "the body of an aws step is the request JSON, e.g. {\"Name\": \"/estate/x\"}")))]
          (if (map? m) m (sa/fail "the body must be a JSON object" "e.g. {\"Name\": \"/estate/x\"}"))))))

(defn- as-json [r]
  (try (json/read-str (json/write-str r :default-write-fn (fn [x out o] (json/write (str x) out o))))
       (catch Throwable _ nil)))

(defn run [ctx step]
  (let [[svc op] (str/split (:verb step) #"\." 2)
        r (sa/call! ctx (keyword svc) (keyword op) (request-from-body (:body step)))
        res (sa/result ctx r op {:facts {"elapsed" (sa/elapsed r)}})]
    (assoc res :body-json (or (sa/parse-json (:response-bytes res)) (as-json r)))))

(def kind
  {:kind "aws" :verbs verbs :target :optional
   :directives []
   :facts []
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
