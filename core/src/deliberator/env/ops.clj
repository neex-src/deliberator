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
                       "ListEventBuses" "TestEventPattern" "ListRuleNamesByTarget")
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
                     "GetQueryResults" "GetLogEvents" "StopQuery")
                  (m "CreateLogGroup" "CreateLogStream" "PutLogEvents" "DeleteLogGroup"
                     "DeleteLogStream"))
     :sts (r "GetCallerIdentity" "GetFederationToken")
     :ecs (merge (r "DescribeServices" "DescribeTaskDefinition" "ListServices" "ListTasks"
                    "DescribeTasks" "DescribeClusters" "ListClusters" "ListTaskDefinitions"
                    "ListServiceDeployments" "DescribeServiceDeployments" "DescribeServiceRevisions")
                 (m "UpdateService" "RegisterTaskDefinition" "CreateService" "DeleteService"
                    "CreateCluster" "RunTask" "StopTask"))
     :ec2 (r "DescribeNetworkInterfaces")
     :codedeploy (merge (r "ListDeployments" "GetDeployment" "ListApplications" "GetApplication"
                           "ListDeploymentGroups" "GetDeploymentGroup")
                        (m "CreateDeployment" "StopDeployment" "CreateApplication"))}))

(def exceptions
  "`[service op]` pairs whose class differs from what their name suggests, with the reason."
  {["logs" "StartQuery"] "starts a read-only Insights query, changes no resource"
   ["logs" "StopQuery"] "stops an Insights query that was started to read, changes no resource (the cancel of logs.insights)"})

(def generic-read-services
  "Services only the generic `aws` step kind reaches (not in `table`): there `Get*`/`List*`/`Describe*`/`Head*` count as reads.
  Everywhere else an operation that is not in the table is a mutation (a remote one needs confirmation)."
  #{:s3 :sns :states :kms :kinesis :monitoring :cloudformation :iam :ec2})

(defn op-class
  "`:read` or `:mutate` for `service`/`op`; `:mutate` when the pair is unknown."
  [service op]
  (get-in table [(api-name service) (name op)]
          (if (and (contains? generic-read-services (api-name service)) (re-find #"^(Get|List|Describe|Head)" (name op)))
            :read
            :mutate)))

(defn known?
  "True when `service`/`op` is in the table."
  [service op]
  (contains? (get table (api-name service) {}) (name op)))

(defn all-ops
  "Every `[service op class]` in the table."
  []
  (for [[s ops] table, [o c] ops] [s o c]))


(def secret-bearing
  "`[service op]` pairs whose response carries secret values (reads and mutations alike). `deliberator.aws` redacts
  their persisted bodies on a remote environment, and on any environment when the caller asks (`:redact-secrets`):
  Secrets Manager and SSM values, the temporary credentials of STS, the secret key of a new IAM access key, the
  plaintext of KMS, and the tokens of a Cognito sign-in. The table is the precise layer; `secret-member?` is the
  catch-all behind it."
  #{[:secretsmanager "GetSecretValue"] [:ssm "GetParameter"] [:ssm "GetParameters"] [:ssm "GetParametersByPath"]
    [:sts "AssumeRole"] [:sts "AssumeRoleWithSAML"] [:sts "AssumeRoleWithWebIdentity"] [:sts "AssumeRoot"]
    [:sts "GetSessionToken"] [:sts "GetFederationToken"]
    [:iam "CreateAccessKey"] [:iam "CreateServiceSpecificCredential"] [:iam "ResetServiceSpecificCredential"]
    [:kms "Decrypt"] [:kms "GenerateDataKey"] [:kms "GenerateDataKeyPair"] [:kms "GenerateDataKeyWithoutPlaintext"]
    [:kms "GenerateRandom"] [:kms "DeriveSharedSecret"]
    [:cognito-idp "InitiateAuth"] [:cognito-idp "AdminInitiateAuth"] [:cognito-idp "RespondToAuthChallenge"]
    [:cognito-idp "AdminRespondToAuthChallenge"] [:cognito-idp "GetTokensFromRefreshToken"]})

(defn secret-bearing?
  "True when `service`/`op` returns secret values (see `secret-bearing`)."
  [service op]
  (contains? secret-bearing [(api-name service) (name op)]))

(def secret-member-pattern
  "Member names (request or response, any service) whose values are secrets: the name rule that redacts what no table
  row names yet. Matched against the whole member name, case-insensitive, as a suffix (`SecretAccessKey`,
  `PrivateKeyPlaintext`, `TemporaryPassword`, `SharedSecret`, `aws_secret_access_key`) so `Credentials` itself is not a match: its
  `AccessKeyId` and `Expiration` stay readable, its `SecretAccessKey` and `SessionToken` do not. `NextToken`,
  `ClientToken` and the like are not secrets and are not matched."
  #"(?i)(SecretAccessKey|SecretKey|SessionToken|Plaintext|Password|SecretString|SecretBinary|PrivateKey|AccessToken|IdToken|RefreshToken|Secret|Passphrase)$")

(defn secret-member?
  "True when a member named `k` (keyword or string) holds a secret by its name."
  [k]
  (boolean (re-find secret-member-pattern (name k))))
