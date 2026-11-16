(ns deliberator.console
  "The AWS console link of a thing in a remote environment (cicd-aws piece 05), and the sign-in that goes with it.

  `(url ws env-name {:keys [kind name reveal]})` -> `{:url <plain deep link> :kind :name :env :region :signin ...}`. Kinds: `ecs-service` (`cluster/service`), `task-definition`
  (`family:revision`), `lambda`, `log-group`, `bus`, `rule` (`bus/rule`, or the rule of the default bus), `queue`, `table`, `parameter`. A `local` environment has no console
  (`console/no-console`). The plain link is a page that asks the person to sign in.

  The SIGN-IN, when the environment's record says `:console {:signin true}`: `sts:GetFederationToken` with the record's own credentials and a policy that only reads
  (the session is the intersection of the profile's permissions and the policy: whatever the profile may do, the session cannot write, and it cannot read a secret
  value, an object or a table's items), 900 seconds; the federation endpoint turns it into a sign-in token and the sign-in link is
  `https://signin.aws.amazon.com/federation?Action=login&Issuer=deliberator&Destination=<plain link>&SigninToken=<token>`. THE LINK IS A SECRET: it is built only for
  `:reveal true`, handed over in the result in memory and nowhere else: not persisted, not logged, not in an event, not in a raw blob (the STS answer is redacted
  by the store like every secret-bearing call), not in any file or text the daemon writes. A profile that may not ask for a federation token (the sandbox's reader)
  gets the plain link and `:signin-error` saying why."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.env :as env])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def kinds #{"ecs-service" "task-definition" "lambda" "log-group" "bus" "rule" "queue" "table" "parameter"})

(def session-seconds "How long the federated session lives." 900)

(def read-only-policy
  "What a console session may do whatever the profile may: read the structure of things and the logs, never a secret value, a parameter's value, an object, an item or a message."
  {"Version" "2012-10-17"
   "Statement" [{"Effect" "Allow"
                 "Action" ["ecs:Describe*" "ecs:List*" "events:Describe*" "events:List*" "events:TestEventPattern" "lambda:Get*" "lambda:List*"
                           "logs:Describe*" "logs:Get*" "logs:FilterLogEvents" "logs:ListTagsForResource" "logs:StartLiveTail"
                           "sqs:GetQueueAttributes" "sqs:GetQueueUrl" "sqs:List*" "dynamodb:Describe*" "dynamodb:List*"
                           "ssm:Describe*" "ssm:List*" "cloudwatch:Describe*" "cloudwatch:GetMetric*" "cloudwatch:List*"
                           "states:Describe*" "states:List*" "sns:GetTopicAttributes" "sns:List*" "apigateway:GET"
                           "ec2:Describe*" "iam:List*" "iam:GetRole" "tag:Get*" "resource-groups:*"]
                 "Resource" "*"}
                {"Effect" "Deny"
                 "Action" ["secretsmanager:GetSecretValue" "ssm:GetParameter*" "kms:Decrypt" "s3:GetObject" "dynamodb:GetItem" "dynamodb:Query" "dynamodb:Scan"
                           "sqs:ReceiveMessage" "lambda:InvokeFunction"]
                 "Resource" "*"}]})

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))
(defn- enc [s] (-> (URLEncoder/encode (str s) "UTF-8") (str/replace "+" "%20")))

(defn base [region] (str "https://" region ".console.aws.amazon.com"))

(defn link
  "The plain deep link of `kind` `name` (pure). `account` is needed for a queue's URL."
  [{:keys [region account]} kind name]
  (let [b (base region) q (str "?region=" region)
        [a z] (let [i (str/index-of (str name) "/")] (if i [(subs name 0 i) (subs name (inc i))] [nil name]))]
    (case kind
      "ecs-service" (do (when-not a (fail :console/bad-name "an ECS service is cluster/service" :hint "e.g. acme/acme-billing"))
                        (str b "/ecs/v2/clusters/" (enc a) "/services/" (enc z) "/health" q))
      "task-definition" (let [[fam rev] (str/split (str name) #"[:/]" 2)]
                          (str b "/ecs/v2/task-definitions/" (enc fam) (when rev (str "/" (enc rev) "/containers")) q))
      "lambda" (str b "/lambda/home" q "#/functions/" (enc name))
      "log-group" (str b "/cloudwatch/home" q "#logsV2:log-groups/log-group/" (str/replace (enc name) "%" "$25"))
      "bus" (str b "/events/home" q "#/eventbus/" (enc name))
      "rule" (str b "/events/home" q "#/eventbus/" (enc (or a "default")) "/rules/" (enc z))
      "queue" (str b "/sqs/v3/home" q "#/queues/" (enc (if (str/starts-with? (str name) "http") name (str "https://sqs." region ".amazonaws.com/" account "/" name))))
      "table" (str b "/dynamodbv2/home" q "#table?name=" (enc name))
      "parameter" (str b "/systems-manager/parameters/" (enc name) "/description" q "&tab=Table")
      (fail :console/bad-kind (str "no console link for kind " (pr-str kind)) :hint (str "one of " (str/join ", " (sort kinds)))))))

(defn signin-link
  "The sign-in link of `destination` for the federation sign-in token `token` (pure)."
  [destination token]
  (str "https://signin.aws.amazon.com/federation?Action=login&Issuer=deliberator&Destination=" (enc destination) "&SigninToken=" (enc token)))

(defn- federation-token!
  "-> `{:token .. :expires ..}` through the record's own credentials; nothing of it is written anywhere. Throws `console/signin-failed`."
  [ws e]
  (let [r (aws/invoke! {:ws ws} e :sts :GetFederationToken {:Name "deliberator-console" :DurationSeconds session-seconds :Policy (json/write-str read-only-policy)})]
    (when (aws/error? r)
      (fail :console/signin-failed (str "no sign-in session: " (or (:code r) (some-> (:category r) name)) " " (:message r))
            :hint "the profile needs sts:GetFederationToken; without it the plain link is all there is"))
    (let [c (:Credentials r)
          session (json/write-str {"sessionId" (:AccessKeyId c) "sessionKey" (:SecretAccessKey c) "sessionToken" (:SessionToken c)})
          resp (.send ^HttpClient (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build)
                      (-> (HttpRequest/newBuilder (URI. (str "https://signin.aws.amazon.com/federation?Action=getSigninToken&Session=" (enc session))))
                          (.timeout (Duration/ofSeconds 15)) .build)
                      (HttpResponse$BodyHandlers/ofString))]
      (when-not (= 200 (.statusCode resp))
        (fail :console/signin-failed (str "the federation endpoint answered " (.statusCode resp)) :hint "the plain link still works"))
      {:token (get (json/read-str (.body resp)) "SigninToken") :expires (some-> (:Expiration c) str)})))

(defn url
  "See the namespace docstring."
  [ws env-name {:keys [kind name reveal]}]
  (let [e (env/load ws env-name)]
    (when (= :local (:kind e))
      (fail :console/no-console (str "environment " env-name " is the local emulator: it has no console") :hint "name a remote environment"))
    (when (or (str/blank? kind) (str/blank? name)) (fail :console/no-thing "console.url needs a kind and a name" :hint (str "kind: " (str/join ", " (sort kinds)))))
    (let [plain (link (:aws e) kind name)
          asks? (boolean (get-in e [:console :signin]))
          base-r {:env env-name :kind kind :name name :region (get-in e [:aws :region]) :url plain :signin asks?}]
      (cond
        (not asks?) base-r
        (not reveal) (assoc base-r :note "the record asks for the sign-in: add reveal to build the sign-in link (a 15 minute read-only session; the link is a secret)")
        :else (try (let [{:keys [token expires]} (federation-token! ws e)]
                     (assoc base-r :signin-url (signin-link plain token) :expires-at expires :read-only true))
                   (catch clojure.lang.ExceptionInfo ex
                     (if (= :console/signin-failed (:type (ex-data ex)))
                       (assoc base-r :signin-error (ex-message ex))
                       (throw ex))))))))
