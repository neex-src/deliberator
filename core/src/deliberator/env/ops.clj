(ns deliberator.env.ops
  "The operation class table: every AWS operation the core issues, listed once per service with its
  class, `:read` or `:mutate`. `(op-class service op)` accepts the service as aws-api's keyword
  (`:sqs`, `:eventbridge`, ...; `:events` is accepted as an alias) and the op as a keyword or
  string (`:SendMessage`, \"SendMessage\"). An operation that is not in the table is `:mutate`: the
  safe answer for anything nobody classified yet.

  How a kind declares what it uses: it calls `deliberator.aws/invoke!` with a service and op; if the
  pair is not in `table`, add it here in the same commit (a test checks that `table` agrees with the
  naming rule: Get/List/Describe/Receive/Filter/Query/Scan are reads and everything else (Invoke,
  DeleteMessage, ...) mutates, with the exceptions in `exceptions`, and that every op pieces 06 and 07 use is present).")


(def aliases
  "Service names callers may use besides aws-api's own."
  {:events :eventbridge})

(defn api-name
  "aws-api service keyword for `service` (keyword or string, aliases resolved)."
  [service]
  (let [k (keyword (name service))]
    (get aliases k k)))

(def table
  "service -> {op-name-string class}."
  (let [r (fn [& ops] (zipmap ops (repeat :read)))
        m (fn [& ops] (zipmap ops (repeat :mutate)))]
    {:sqs (merge (r "GetQueueUrl" "GetQueueAttributes" "ReceiveMessage" "ListQueues"
                    "ListQueueTags")
                 (m "CreateQueue" "SendMessage" "SendMessageBatch" "DeleteMessage"
                    "DeleteMessageBatch" "PurgeQueue" "DeleteQueue" "SetQueueAttributes"
                    "ChangeMessageVisibility" "TagQueue"))
     :eventbridge (merge (r "ListRules" "DescribeRule" "ListTargetsByRule" "DescribeEventBus"
                       "ListEventBuses")
                    (m "PutEvents" "PutRule" "PutTargets" "CreateEventBus" "DeleteRule"
                       "RemoveTargets" "DeleteEventBus" "EnableRule" "DisableRule"))
     :lambda (merge (r "GetFunction" "ListFunctions" "GetFunctionConfiguration"
                       "ListVersionsByFunction")
                    (m "Invoke" "CreateFunction" "DeleteFunction" "UpdateFunctionCode"
                       "UpdateFunctionConfiguration"))
     :dynamodb (merge (r "GetItem" "Query" "Scan" "DescribeTable" "BatchGetItem" "ListTables")
                      (m "PutItem" "CreateTable" "DeleteItem" "UpdateItem" "DeleteTable"
                         "BatchWriteItem"))
     :scheduler (merge (r "ListSchedules" "GetSchedule" "ListScheduleGroups")
                       (m "CreateSchedule" "DeleteSchedule" "UpdateSchedule"))
     :ssm (merge (r "GetParameter" "GetParameters" "GetParametersByPath" "DescribeParameters"
                    "GetParameterHistory")
                 (m "PutParameter" "DeleteParameter" "DeleteParameters"))
     :secretsmanager (merge (r "GetSecretValue" "DescribeSecret" "ListSecrets")
                            (m "CreateSecret" "PutSecretValue" "DeleteSecret" "UpdateSecret"))
     :logs (merge (r "FilterLogEvents" "DescribeLogGroups" "DescribeLogStreams" "StartQuery"
                     "GetQueryResults" "GetLogEvents")
                  (m "CreateLogGroup" "CreateLogStream" "PutLogEvents" "DeleteLogGroup"
                     "DeleteLogStream"))
     :sts (r "GetCallerIdentity")
     :ecs (merge (r "DescribeServices" "DescribeTaskDefinition" "ListServices" "ListTasks"
                    "DescribeTasks" "DescribeClusters" "ListClusters" "ListTaskDefinitions")
                 (m "UpdateService" "RegisterTaskDefinition" "CreateService" "DeleteService"
                    "CreateCluster" "RunTask" "StopTask"))
     :codedeploy (merge (r "ListDeployments" "GetDeployment" "ListApplications" "GetApplication"
                           "ListDeploymentGroups" "GetDeploymentGroup")
                        (m "CreateDeployment" "StopDeployment" "CreateApplication"))}))

(def exceptions
  "`[service op]` pairs whose class differs from what their name suggests, with the reason."
  {["logs" "StartQuery"] "starts a read-only Insights query, changes no resource"})

(defn op-class
  "`:read` or `:mutate` for `service`/`op`; `:mutate` when the pair is unknown."
  [service op]
  (get-in table [(api-name service) (name op)] :mutate))

(defn known?
  "True when `service`/`op` is in the table."
  [service op]
  (contains? (get table (api-name service) {}) (name op)))

(defn all-ops
  "Every `[service op class]` in the table."
  []
  (for [[s ops] table, [o c] ops] [s o c]))


(def secret-bearing
  "`[service op]` pairs that are `:read` but whose response carries secret values. `deliberator.aws` redacts
  their persisted bodies on a remote environment, and on any environment when the caller asks (`:redact-secrets`)."
  #{[:secretsmanager "GetSecretValue"] [:ssm "GetParameter"] [:ssm "GetParameters"] [:ssm "GetParametersByPath"]})

(defn secret-bearing?
  "True when `service`/`op` returns secret values (GetSecretValue, GetParameter(s) with decryption)."
  [service op]
  (contains? secret-bearing [(api-name service) (name op)]))
