(ns deliberator.aws-test
  "Layer 2: `aws/invoke!` against the test Floci (`floci-util/url`, default :14566). Resources are prefixed `dlbtest-`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.env.ops :as ops]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.session :as session]
            [deliberator.steps.sqs :as sqs]
            [deliberator.store :as store]
            [deliberator.test-util :refer [fixed-clock temp-home with-temp-home]])
  (:import (java.time Instant)
           (java.util UUID)))

(use-fixtures :once (fn [f] (try (f) (finally (fu/drop-queues! "dlbtest-") (env/close-clients!)))))

(def ctx {:ws "w"})

(def local (env/validate (fu/local-env)))

(def remote-base
  {:name "dlbdev" :kind :remote
   :aws {:profile "dlbtest" :region "eu-central-1" :account "111111111111"
         :endpoint fu/url}
   :targets {:service/a "https://a.example.test"}})

(defn remote
  "A remote-shaped record pointed at the test Floci. `:read-only true` (the default of a record) refuses every
  mutation flat, so the tests that mutate pass `:read-only false` and go through the token flow."
  [& {:as over}] (env/validate (merge remote-base over)))

(defn writable [& {:as over}] (remote :read-only false over))

(defn uniq [prefix] (str "dlbtest-" prefix "-" (subs (str (UUID/randomUUID)) 0 8)))

(defmacro with-profile
  "Binds the credentials file of the remote profile to a static-key file under the temp home."
  [& body]
  `(let [f# (io/file (temp-home) "credentials")]
     (spit f# "[dlbtest]\naws_access_key_id = test\naws_secret_access_key = test\n")
     (binding [env/*credentials-file* f#] ~@body)))

(defn blob [sha] (String. (store/raw-get "w" sha) "UTF-8"))

(defn confirmed!
  "Runs a mutate against the remote env through the whole confirmation flow."
  [c e svc op req]
  (let [r1 (aws/invoke! c e svc op req)]
    (assert (= :confirm-required (:type r1)) (pr-str r1))
    (aws/invoke! (assoc c :confirm (:token r1)) e svc op req)))

(deftest-floci local-calls-persist-raw-request-and-response
  (with-temp-home
    (let [q (uniq "q")
          created (aws/invoke! ctx local :sqs :CreateQueue {:QueueName q})
          url (:QueueUrl created)]
      (is (not (aws/error? created)))
      (is (str/ends-with? url (str "/" q)))
      (let [raw (aws/raw-refs created)
            rm (store/raw-meta "w" (-> raw :request :sha))
            sm (store/raw-meta "w" (-> raw :response :sha))]
        (testing "request blob: serialised request with meta"
          (is (str/includes? (blob (-> raw :request :sha)) q))
          (is (= {:source :aws :op "CreateQueue" :env "local" :role :request :service :sqs}
                 (select-keys rm [:source :op :env :role :service])))
          (is (instance? Instant (:at rm))))
        (testing "response blob: the bytes the server sent, before decoding"
          (is (= url (get (json/read-str (blob (-> raw :response :sha))) "QueueUrl")))
          (is (= {:source :aws :op "CreateQueue" :env "local" :role :response :status 200}
                 (select-keys sm [:source :op :env :role :status])))
          (is (re-find #"json" (:content-type sm)))))
      (testing "send, receive, delete round trip; bodies are byte exact"
        (let [body "line one\nline two\n"]
          (is (not (aws/error? (aws/invoke! ctx local :sqs :SendMessage {:QueueUrl url :MessageBody body}))))
          (let [r (aws/invoke! ctx local :sqs :ReceiveMessage {:QueueUrl url :WaitTimeSeconds 2})
                m (first (:Messages r))]
            (is (= body (:Body m)))
            (is (str/includes? (blob (-> (aws/raw-refs r) :response :sha)) "line one\\nline two\\n"))
            (is (not (aws/error? (aws/invoke! ctx local :sqs :DeleteMessage
                                              {:QueueUrl url :ReceiptHandle (:ReceiptHandle m)})))))))
      (testing "a local call is neither audited nor gated"
        (is (= [] (aws/audit-lines ctx)))
        (is (not (.exists (.toFile (aws/audit-path ctx))))))
      (aws/invoke! ctx local :sqs :DeleteQueue {:QueueUrl url}))))

(deftest-floci server-errors-become-structured-errors
  (with-temp-home
    (let [r (aws/invoke! ctx local :sqs :GetQueueUrl {:QueueName "dlbtest-does-not-exist"})]
      (is (aws/error? r))
      (is (= :aws/error (:type r)))
      (is (= "QueueDoesNotExist" (:code r)))
      (is (keyword? (:category r)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote fu/hostport)) (:hint r)))
      (is (re-find #"000000000000" (:hint r)))
      (is (= 400 (-> r :raw :status)))
      (is (str/includes? (blob (-> r :raw :response :sha)) "QueueDoesNotExist")
          "the error body is on disk too"))))

(deftest fingerprint-ignores-the-run-id-only
  (let [fp @#'aws/fingerprint-request
        send (fn [run body] {:QueueUrl "q" :MessageBody body
                             :MessageAttributes {"correlation" {:DataType "String" :StringValue run}
                                                 "kind" {:DataType "String" :StringValue "a"}}})]
    (is (= (fp (send "run-1" "hello")) (fp (send "run-2" "hello"))) "two runs, same fingerprint")
    (is (not= (fp (send "run-1" "hello")) (fp (send "run-1" "other"))) "a changed body changes it")
    (is (not= (fp (send "run-1" "hello")) (fp (assoc (send "run-1" "hello") :QueueUrl "q2"))) "so does the target")
    (is (not= (fp (send "run-1" "hello"))
              (fp (assoc-in (send "run-1" "hello") [:MessageAttributes "kind" :StringValue] "b")))
        "so does any other attribute")
    (is (= (fp {:Entries [(send "r1" "x")]}) (fp {:Entries [(send "r2" "x")]})) "batch entries too")))

(deftest unreachable-endpoint-persists-the-anomaly
  (with-temp-home
    (let [e (assoc-in local [:aws :endpoint] "http://localhost:1")
          r (aws/invoke! ctx e :sqs :ListQueues {})]
      (is (aws/error? r))
      (is (= :unavailable (:category r)))
      (is (re-find #"bb runtime:up" (:hint r)))
      (is (re-find #"localhost:1" (:hint r)))
      (is (= :anomaly (:role (store/raw-meta "w" (-> r :raw :response :sha))))))))

(deftest-floci aws-profile-in-the-process-environment-changes-nothing
  (with-temp-home
    (env/save! "w" (fu/local-env))
    (let [home (str (store/home))
          code "(require '[deliberator.env :as env] '[cognitect.aws.client.api :as aws]) (let [e (env/load \"w\" \"local\") r (aws/invoke (env/aws-client e :sqs) {:op :ListQueues :request {}})] (prn {:aws-profile (System/getenv \"AWS_PROFILE\") :ok (not (:cognitect.anomalies/category r)) :queue-urls (contains? r :QueueUrls)}))"
          pb (ProcessBuilder. ^java.util.List
                              [(str (System/getProperty "java.home") "/bin/java")
                               "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" code])
          penv (.environment pb)]
      (doseq [[k v] {"DELIBERATOR_HOME" home
                     "AWS_PROFILE" "dlbtest-no-such-profile"
                     "AWS_ACCESS_KEY_ID" "AKIABOGUSBOGUSBOGUS" "AWS_SECRET_ACCESS_KEY" "bogus"
                     "AWS_SESSION_TOKEN" "bogus"
                     "AWS_REGION" "ap-southeast-2" "AWS_DEFAULT_REGION" "ap-southeast-2"
                     "AWS_CONFIG_FILE" "/nonexistent/config"
                     "AWS_SHARED_CREDENTIALS_FILE" "/nonexistent/credentials"
                     "AWS_ENDPOINT_URL" "http://127.0.0.1:1"}]
        (.put penv k v))
      (.redirectErrorStream pb true)
      (let [p (.start pb)
            out (slurp (.getInputStream p))
            done? (.waitFor p 150 java.util.concurrent.TimeUnit/SECONDS)
            line (last (filter #(str/starts-with? % "{:aws-profile") (str/split-lines out)))]
        (is done? out)
        (is (some? line) out)
        (is (= {:aws-profile "dlbtest-no-such-profile" :ok true :queue-urls true}
               (some-> line read-string))
            "the child saw AWS_PROFILE and the local client still talked to Floci")))))

(deftest-floci read-only-refuses-mutations-flat
  (with-temp-home
    (with-profile
      (let [e (remote)
            c (assoc ctx :session (:key (session/start! "w" "RO-1")))
            p (str "/dlbtest/" (uniq "ro"))
            r (aws/invoke! c e :ssm :PutParameter {:Name p :Value "on" :Type "String"})]
        (is (true? (:read-only e)) "the default of a remote record")
        (testing "no token, nothing runs, the error says why"
          (is (aws/error? r))
          (is (= :read-only (:category r)))
          (is (nil? (:token r)))
          (is (str/includes? (:message r) "read-only"))
          (is (str/includes? (:hint r) ":read-only false"))
          (is (aws/error? (aws/invoke! c e :ssm :GetParameter {:Name p})) "the parameter was never written")
          (is (= "ParameterNotFound" (:code (aws/invoke! c e :ssm :GetParameter {:Name p})))))
        (testing "a token presented to a read-only record is refused too, unused"
          (let [w (writable) r1 (aws/invoke! c w :ssm :PutParameter {:Name p :Value "on" :Type "String"})]
            (is (= :confirm-required (:type r1)))
            (is (= :read-only (:category (aws/invoke! (assoc c :confirm (:token r1)) e :ssm :PutParameter {:Name p :Value "on" :Type "String"}))))
            (is (not (aws/error? (aws/invoke! (assoc c :confirm (:token r1)) w :ssm :PutParameter {:Name p :Value "on" :Type "String"})))
                "the same token still works on the writable record: the refusal did not burn it")
            (confirmed! c w :ssm :DeleteParameter {:Name p})))
        (testing "reads pass, the gate is for mutations"
          (is (not (aws/error? (aws/invoke! c e :sts :GetCallerIdentity {})))))
        (testing "audit: the refusal is one line with its reason"
          (let [ls (aws/audit-lines c)
                refused (filter #(= :read-only (:reason %)) ls)]
            (is (= 2 (count refused)) (pr-str ls))
            (is (every? #(and (= :refused (:outcome %)) (= :mutate (:class %)) (nil? (:confirmation %))) refused))))))))

(deftest-floci confirmation-flow-on-a-remote-environment
  (with-temp-home
    (with-profile
      (let [e (writable)
            c (assoc ctx :session (:key (session/start! "w" "T-1")))
            p (str "/dlbtest/" (uniq "flag"))
            req {:Name p :Value "on" :Type "String"}
            r1 (aws/invoke! c e :ssm :PutParameter req)]
        (testing "a mutate without a token is refused with a summary"
          (is (aws/error? r1))
          (is (= :confirm-required (:type r1)))
          (is (string? (:token r1)))
          (is (re-find #"PutParameter" (:summary r1)))
          (is (str/includes? (:summary r1) p))
          (is (str/includes? (:summary r1) "dlbdev"))
          (is (str/includes? (:summary r1) "111111111111")))
        (testing "a token for another request is refused and not burnt"
          (let [r (aws/invoke! (assoc c :confirm (:token r1)) e :ssm :PutParameter (assoc req :Value "off"))]
            (is (= :confirmation (:category r)))))
        (testing "the retry with the token succeeds"
          (let [r (aws/invoke! (assoc c :confirm (:token r1)) e :ssm :PutParameter req)]
            (is (not (aws/error? r)))
            (is (= 1 (:Version r)))))
        (testing "the token is single use"
          (let [r (aws/invoke! (assoc c :confirm (:token r1)) e :ssm :PutParameter req)]
            (is (aws/error? r))
            (is (= :confirmation (:category r)))
            (is (re-find #"already used" (:message r)))))
        (testing "a read needs no token"
          (let [r (aws/invoke! c e :ssm :GetParameter {:Name p})]
            (is (= "on" (get-in r [:Parameter :Value])))))
        (testing "audit.log: one line per attempt with time, env, op, class, bytes, confirmation"
          (let [ls (aws/audit-lines c)]
            (is (= [:confirm-required :refused :ok :refused :ok] (map :outcome ls)))
            (is (= [nil nil :used nil nil] (map :confirmation ls)))
            (is (every? #(and (instance? Instant (:at %)) (= "dlbdev" (:env %)) (:op %) (:class %)) ls))
            (is (= [:mutate :mutate :mutate :mutate :read] (map :class ls)))
            (is (pos? (:bytes (nth ls 2))))
            (is (.endsWith (str (aws/audit-path c)) "sessions/T-1/audit.log"))))
        (testing "read-only false is the token flow, never a bypass"
          (let [r (aws/invoke! c (writable) :ssm :PutParameter (assoc req :Overwrite true))]
            (is (= :confirm-required (:type r)))))
        (testing "an unknown op is gated as a mutate"
          (is (= :confirm-required (:type (aws/invoke! c e :ssm :SomethingNew {})))))
        (confirmed! c e :ssm :DeleteParameter {:Name p})))))

(deftest sqs-answers-a-missing-permission-as-a-missing-queue-and-the-hint-says-both
  ;; found with rvw-denied on real AWS: `QueueDoesNotExist ... or you do not have access to it` came with the hint "rejected by profile ..."
  (let [env {:kind :remote :name "rt-denied" :aws {:profile "rvw-denied" :region "eu-central-1" :account "000000000000"}}
        h (aws/hint env :incorrect "QueueDoesNotExist" :sqs :GetQueueUrl)]
    (is (str/includes? h "denies sqs:GetQueueUrl"))
    (is (str/includes? h "name is wrong"))
    (is (not (str/includes? h "expired")) "this is not a login problem")
    (testing "a throttled call is not blamed on credentials"
      (let [t (aws/hint env :busy "ThrottlingException" :logs :FilterLogEvents)]
        (is (str/includes? t "throttled"))
        (is (not (str/includes? t "aws sso login")))))))

(deftest a-dropped-keep-alive-connection-is-a-transient-io-error-for-reads
  ;; B34: seen on real CloudWatch: `java.io.IOException: HTTP/1.1 header parser received no bytes` ended a log tail
  (let [transient? #'aws/transient-io?
        anomaly (fn [t] {:cognitect.anomalies/category :cognitect.anomalies/fault :cognitect.aws.client.impl/throwable t})]
    (is (transient? (anomaly (java.io.IOException. "HTTP/1.1 header parser received no bytes"))))
    (is (transient? (anomaly (java.net.SocketException. "Connection reset"))))
    (is (transient? (anomaly (ex-info "x" {} (java.io.IOException. "wrapped")))) "found in the cause chain")
    (is (not (transient? (anomaly (java.net.ConnectException. "refused")))) "a refused connect is a fact, not a glitch")
    (is (not (transient? (anomaly (java.net.UnknownHostException. "nope")))))
    (is (not (transient? {:cognitect.anomalies/category :cognitect.anomalies/forbidden})) "no throwable, no retry")))

(deftest-floci one-confirm-can-carry-the-tokens-of-several-requests
  ;; B33: `dlb run order.scn --continue --confirm T1,T8,T9` approves the three mutating steps of a run at once
  (with-temp-home
    (with-profile
      (let [e (writable)
            c (assoc ctx :session (:key (session/start! "w" "T-1")))
            p1 (str "/dlbtest/" (uniq "one")) p2 (str "/dlbtest/" (uniq "two"))
            req (fn [p] {:Name p :Value "on" :Type "String"})
            t1 (:token (aws/invoke! c e :ssm :PutParameter (req p1)))
            t2 (:token (aws/invoke! c e :ssm :PutParameter (req p2)))
            both (str t2 "," t1)]
        (testing "the token of this request is found among the others and consumed alone"
          (is (not (aws/error? (aws/invoke! (assoc c :confirm both) e :ssm :PutParameter (req p1))))))
        (testing "the other token is still live for its own request, the used one is gone"
          (is (not (aws/error? (aws/invoke! (assoc c :confirm both) e :ssm :PutParameter (req p2)))))
          (is (= :confirmation (:category (aws/invoke! (assoc c :confirm both) e :ssm :PutParameter (req p1))))))
        (confirmed! c e :ssm :DeleteParameter {:Name p1})
        (confirmed! c e :ssm :DeleteParameter {:Name p2})))))

(deftest-floci tokens-expire-after-ten-minutes
  (with-temp-home
    (with-profile
      (let [e (writable)
            p (str "/dlbtest/" (uniq "exp"))
            req {:Name p :Value "v" :Type "String"}
            t0 "2026-10-16T10:00:00Z"
            r1 (binding [store/*clock* (fixed-clock t0)] (aws/invoke! ctx e :ssm :PutParameter req))
            late (fn [iso] (binding [store/*clock* (fixed-clock iso)]
                             (aws/invoke! (assoc ctx :confirm (:token r1)) e :ssm :PutParameter req)))]
        (is (= (Instant/parse "2026-10-16T10:10:00Z") (:expires r1)))
        (is (= :confirmation (:category (late "2026-10-16T10:10:01Z"))))
        (is (= :confirmation (:category (late "2026-10-16T10:09:00Z"))) "expired tokens are gone for good")))))

(deftest-floci secrets-are-redacted-from-remote-persistence
  (with-temp-home
    (with-profile
      (let [e (writable)
            n (uniq "secret")
            secret "s3cr3t-value-123"
            put-ref (aws/raw-refs (confirmed! ctx e :secretsmanager :CreateSecret {:Name n :SecretString secret}))
            get-it (fn [c] (aws/invoke! c e :secretsmanager :GetSecretValue {:SecretId n}))]
        (testing "the request that created it is redacted too"
          (is (not (str/includes? (blob (-> put-ref :request :sha)) secret)))
          (is (str/includes? (blob (-> put-ref :request :sha)) "<redacted>")))
        (testing "default: caller sees the value, disk does not"
          (let [r (get-it ctx)
                b (blob (-> (aws/raw-refs r) :response :sha))]
            (is (= secret (:SecretString r)))
            (is (not (str/includes? b secret)))
            (is (str/includes? b "<redacted>"))
            (is (= n (get (json/read-str b) "Name")) "everything else stays")))
        (testing ":persist-secrets keeps the value"
          (let [r (get-it (assoc ctx :persist-secrets true))]
            (is (str/includes? (blob (-> (aws/raw-refs r) :response :sha)) secret))))
        (testing "local: stored in full"
          (let [le local
                ln (uniq "lsecret")]
            (aws/invoke! ctx le :secretsmanager :CreateSecret {:Name ln :SecretString secret})
            (let [r (aws/invoke! ctx le :secretsmanager :GetSecretValue {:SecretId ln})]
              (is (str/includes? (blob (-> (aws/raw-refs r) :response :sha)) secret)))
            (testing ":redact-secrets (secrets-from) redacts a LOCAL environment's response too; the caller still has the value"
              (let [r (aws/invoke! (assoc ctx :redact-secrets true) le :secretsmanager :GetSecretValue {:SecretId ln})
                    b (blob (-> (aws/raw-refs r) :response :sha))]
                (is (= secret (:SecretString r)))
                (is (not (str/includes? b secret)))
                (is (str/includes? b "<redacted>"))))
            (aws/invoke! ctx le :secretsmanager :DeleteSecret {:SecretId ln :ForceDeleteWithoutRecovery true})))
        (confirmed! ctx e :secretsmanager :DeleteSecret {:SecretId n :ForceDeleteWithoutRecovery true})))))

(deftest-floci decrypted-parameters-are-redacted
  (with-temp-home
    (with-profile
      (let [e (writable)
            p (str "/dlbtest/" (uniq "param"))
            secret "p4ss-123"
            _ (confirmed! ctx e :ssm :PutParameter {:Name p :Value secret :Type "SecureString"})
            one (aws/invoke! ctx e :ssm :GetParameter {:Name p :WithDecryption true})
            many (aws/invoke! ctx e :ssm :GetParameters {:Names [p] :WithDecryption true})
            path (aws/invoke! ctx e :ssm :GetParametersByPath {:Path (subs p 0 (str/last-index-of p "/")) :WithDecryption true})
            raw-blob #(blob (-> (aws/raw-refs %) :response :sha))]
        (is (= secret (get-in one [:Parameter :Value])))
        (is (= secret (get-in many [:Parameters 0 :Value])))
        (doseq [r [one many path]]
          (is (not (str/includes? (raw-blob r) secret)))
          (is (str/includes? (raw-blob r) "<redacted>")))
        (is (some #(= secret (:Value %)) (:Parameters path)) "caller still decodes real values")
        (testing "without decryption nothing is replaced"
          (let [r (aws/invoke! ctx e :ssm :GetParameter {:Name p :WithDecryption false})]
            (is (not (str/includes? (raw-blob r) "<redacted>")))))
        (confirmed! ctx e :ssm :DeleteParameter {:Name p})))))

(deftest-floci sso-style-profiles-fall-back-to-the-cli-bridge
  (with-temp-home
    (let [dir (temp-home)
          cli (io/file dir "fake-aws")
          args (io/file dir "fake-aws.args")]
      (spit cli (str "#!/bin/sh\necho \"$@\" > " args "\n"
                     "echo '{\"Version\":1,\"AccessKeyId\":\"test\",\"SecretAccessKey\":\"test\","
                     "\"SessionToken\":\"tok\",\"Expiration\":\"2099-01-01T00:00:00Z\"}'\n"))
      (.setExecutable cli true)
      (binding [env/*credentials-file* (io/file dir "no-such-credentials")
                env/*aws-cli* (str cli)]
        (let [r (aws/check ctx (remote :aws {:profile "dlbtest-sso" :region "eu-central-1"
                                             :account "000000000000" :endpoint fu/url}))]
          (is (:ok? r) (pr-str r))
          (is (= "configure export-credentials --profile dlbtest-sso --format process"
                 (str/trim (slurp args)))))))))

(deftest a-profile-that-cannot-log-in-fails-with-the-clis-words-and-the-sso-hint
  ;; no Floci, no network: the credentials are never fetched, so nothing is sent
  (with-temp-home
    (let [dir (temp-home)
          cli (io/file dir "fake-aws")]
      (spit cli "#!/bin/sh\necho 'Error loading SSO Token: Token for corp does not exist' >&2\nexit 255\n")
      (.setExecutable cli true)
      (binding [env/*credentials-file* (io/file dir "no-such-credentials")
                env/*aws-cli* (str cli)]
        (let [e (remote :aws {:profile "dlbtest-corp" :region "eu-central-1" :account "111111111111"})
              r (aws/invoke! ctx e :sts :GetCallerIdentity {})]
          (is (aws/error? r) (pr-str r))
          (is (re-find #"credentials of profile dlbtest-corp could not be fetched" (:message r)) (:message r))
          (is (str/includes? (:message r) "Token for corp does not exist") (:message r))
          (is (str/includes? (:hint r) "aws sso login --profile dlbtest-corp") (:hint r))
          (is (str/includes? (:hint r) "AWS_CONFIG_FILE") (:hint r)))
        (testing "the check answers the same way (env.save and every op that verifies the account)"
          (let [c (aws/check ctx (remote :aws {:profile "dlbtest-corp" :region "eu-central-1" :account "111111111111"}))]
            (is (false? (:ok? c)))
            (is (str/includes? (get-in c [:error :hint]) "aws sso login --profile dlbtest-corp"))))))))

(deftest-floci check-compares-the-account
  (with-temp-home
    (is (:ok? (aws/check ctx local)))
    (let [r (aws/check ctx (assoc-in local [:aws :account] "999999999999"))]
      (is (false? (:ok? r)))
      (is (= "000000000000" (:account r))))))

;; --- the generic kind's secrets (R2), the hint (R18), sqs receive on remote (R6) -----------------------------------

(deftest redaction-rows-and-the-name-rule
  (let [js (fn [m] (.getBytes (json/write-str m) "UTF-8"))
        out (fn [svc op req body] (String. ^bytes (aws/redact-body svc op req body) "UTF-8"))]
    (testing "kms rows: Plaintext goes, the key id and ciphertext stay"
      (let [b (out :kms "Decrypt" {} (js {"KeyId" "k1" "Plaintext" "c2VjcmV0" "EncryptionAlgorithm" "SYMMETRIC_DEFAULT"}))]
        (is (not (str/includes? b "c2VjcmV0")))
        (is (= {"KeyId" "k1" "Plaintext" "<redacted>" "EncryptionAlgorithm" "SYMMETRIC_DEFAULT"} (json/read-str b))))
      (is (= "<redacted>" (get (json/read-str (out :kms "GenerateDataKeyPair" {} (js {"PrivateKeyPlaintext" "p" "PublicKey" "pub"}))) "PrivateKeyPlaintext")))
      (is (= "pub" (get (json/read-str (out :kms "GenerateDataKeyPair" {} (js {"PrivateKeyPlaintext" "p" "PublicKey" "pub"}))) "PublicKey"))))
    (testing "cognito rows (a service that is not on the classpath: the rule needs no client)"
      (let [b (json/read-str (out :cognito-idp "InitiateAuth" {} (js {"ChallengeParameters" {} "AuthenticationResult" {"AccessToken" "a.b.c" "ExpiresIn" 3600 "IdToken" "i" "RefreshToken" "r" "TokenType" "Bearer"}})))]
        (is (= {"AccessToken" "<redacted>" "ExpiresIn" 3600 "IdToken" "<redacted>" "RefreshToken" "<redacted>" "TokenType" "Bearer"}
               (get b "AuthenticationResult")))))
    (testing "sts and iam answer in XML: the name rule redacts the elements, the rest of the document stays"
      (let [xml (str "<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><AssumeRoleResult>"
                     "<Credentials><AccessKeyId>ASIAEXAMPLE</AccessKeyId><SecretAccessKey>wJalrXUtnFEMI/K7MDENG</SecretAccessKey>"
                     "<SessionToken>FwoGZXIvYXdzEBYaD</SessionToken><Expiration>2026-10-26T01:00:00Z</Expiration></Credentials>"
                     "<AssumedRoleUser><Arn>arn:aws:sts::111111111111:assumed-role/r/s</Arn></AssumedRoleUser>"
                     "</AssumeRoleResult></AssumeRoleResponse>")
            b (out :sts "AssumeRole" {} (.getBytes xml "UTF-8"))]
        (is (not (str/includes? b "wJalrXUtnFEMI")))
        (is (not (str/includes? b "FwoGZXIvYXdz")))
        (is (str/includes? b "<SecretAccessKey>&lt;redacted&gt;</SecretAccessKey>"))
        (is (str/includes? b "<AccessKeyId>ASIAEXAMPLE</AccessKeyId>") "the key id is not a secret")
        (is (str/includes? b "<Expiration>2026-10-26T01:00:00Z</Expiration>"))
        (is (str/includes? b "assumed-role/r/s")))
      (let [b (out :iam "CreateAccessKey" {} (.getBytes "<CreateAccessKeyResponse><CreateAccessKeyResult><AccessKey><UserName>u</UserName><AccessKeyId>AKIA1</AccessKeyId><Status>Active</Status><SecretAccessKey>s3cr3t</SecretAccessKey></AccessKey></CreateAccessKeyResult></CreateAccessKeyResponse>" "UTF-8"))]
        (is (not (str/includes? b "s3cr3t")))
        (is (str/includes? b "<AccessKeyId>AKIA1</AccessKeyId>"))))
    (testing "the name rule alone, on a service nobody classified: Password, *Secret, SessionToken, nested and in lists"
      (let [b (json/read-str (out :rds "ModifyDBInstance" {} (js {"DBInstance" {"MasterUserPassword" "pw" "DBName" "d"}
                                                                  "Items" [{"ClientSecret" "cs" "ClientId" "id"} {"SessionToken" "t"}]})))]
        (is (= {"DBInstance" {"MasterUserPassword" "<redacted>" "DBName" "d"}
                "Items" [{"ClientSecret" "<redacted>" "ClientId" "id"} {"SessionToken" "<redacted>"}]} b))))
    (testing "what is not a secret is not touched: NextToken, ClientToken, a Value without decryption, the ids"
      (let [body (js {"NextToken" "n" "ClientToken" "c" "Parameter" {"Name" "/x" "Value" "v" "ARN" "arn:x"}})]
        (is (identical? body (aws/redact-body :ssm "GetParameter" {:WithDecryption false} body)) "untouched bytes, not a copy")
        (is (= "<redacted>" (get-in (json/read-str (out :ssm "GetParameter" {:WithDecryption true} body)) ["Parameter" "Value"])))))
    (testing "requests: the table paths and the name rule, keys as keywords"
      (is (= {:Name "/x" :Value "<redacted>" :Type "SecureString"} (aws/redact-request :ssm "PutParameter" {:Name "/x" :Value "s" :Type "SecureString"})))
      (is (= {:KeyId "k" :Plaintext "<redacted>"} (aws/redact-request :kms "Encrypt" {:KeyId "k" :Plaintext "hello"})))
      (is (= {:UserName "u" :Password "<redacted>" :PasswordResetRequired true}
             (aws/redact-request :iam "CreateLoginProfile" {:UserName "u" :Password "pw" :PasswordResetRequired true})))
      (is (= {:QueueUrl "u" :MaxNumberOfMessages 1} (aws/redact-request :sqs "ReceiveMessage" {:QueueUrl "u" :MaxNumberOfMessages 1}))))
    (testing "the secret-bearing table names the sts, iam, kms and cognito rows"
      (doseq [[s o] [[:sts "AssumeRole"] [:sts "GetSessionToken"] [:iam "CreateAccessKey"] [:kms "Decrypt"] [:cognito-idp "InitiateAuth"]]]
        (is (ops/secret-bearing? s o) (str s " " o))))))

(deftest hints-tell-a-policy-denial-from-expired-credentials
  (let [e (remote)
        h (fn [cat code] (aws/hint e cat code :sqs "SendMessage"))]
    (testing "an IAM denial names the policy and the operation, never a login"
      (doseq [code ["AccessDenied" "AccessDeniedException" "UnauthorizedOperation"]]
        (let [t (h :forbidden code)]
          (is (str/includes? t "IAM policy") t)
          (is (str/includes? t "sqs:SendMessage") t)
          (is (not (str/includes? t "sso login")) t)))
      (is (str/includes? (h :incorrect "AccessDeniedException") "IAM policy") "SSM and DynamoDB deny with a 400"))
    (testing "bad or expired keys say log in"
      (doseq [code ["ExpiredToken" "InvalidClientTokenId" "UnrecognizedClientException" "SignatureDoesNotMatch"]]
        (is (str/includes? (h :forbidden code) "aws sso login --profile dlbtest") code)))
    (testing "an unknown code falls back to the category, both causes named"
      (let [t (h :forbidden nil)]
        (is (str/includes? t "IAM policy or expired credentials") t)))
    (testing "local records never mention a login"
      (is (not (str/includes? (aws/hint local :forbidden "AccessDenied" :sqs "SendMessage") "sso"))))))

(deftest-floci sts-and-kms-secrets-are-redacted-from-remote-persistence
  (with-temp-home
    (with-profile
      (let [e (writable)
            raw-blob #(blob (-> (aws/raw-refs %) :response :sha))]
        (testing "sts GetSessionToken (a mutate: confirmed) persists no SecretAccessKey or SessionToken"
          (let [r (confirmed! ctx e :sts :GetSessionToken {:DurationSeconds 900})]
            (is (not (aws/error? r)) (pr-str (dissoc r :raw)))
            (let [{:keys [SecretAccessKey SessionToken AccessKeyId]} (:Credentials r)
                  b (raw-blob r)]
              (is (string? SecretAccessKey) "the caller has the real credentials")
              (is (not (str/includes? b SecretAccessKey)))
              (is (not (str/includes? b SessionToken)))
              (is (str/includes? b aws/xml-redacted) b)
              (is (str/includes? b AccessKeyId) "the key id stays readable"))))
        (testing "kms Decrypt and GenerateDataKey persist no Plaintext; the caller decodes it"
          (let [k (confirmed! ctx e :kms :CreateKey {:Description "dlbtest redaction"})
                kid (get-in k [:KeyMetadata :KeyId])
                _ (is (string? kid) (pr-str (dissoc k :raw)))
                secret (.getBytes "the plaintext secret 42" "UTF-8")
                enc (confirmed! ctx e :kms :Encrypt {:KeyId kid :Plaintext secret})
                ;; aws-api answers a blob as an InputStream (one read): the caller's bytes
                rd (fn [x] (if (instance? java.io.InputStream x) (.readAllBytes ^java.io.InputStream x) x))
                dec (confirmed! ctx e :kms :Decrypt {:KeyId kid :CiphertextBlob (rd (:CiphertextBlob enc))})
                gdk (confirmed! ctx e :kms :GenerateDataKey {:KeyId kid :KeySpec "AES_256"})
                dec-plain (rd (:Plaintext dec))
                gdk-plain (rd (:Plaintext gdk)) gdk-cipher (rd (:CiphertextBlob gdk))
                b64 (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))]
            (is (not (aws/error? dec)) (pr-str (dissoc dec :raw)))
            (is (= "the plaintext secret 42" (String. ^bytes dec-plain "UTF-8")))
            (is (not (str/includes? (raw-blob dec) (b64 dec-plain))))
            (is (str/includes? (raw-blob dec) "<redacted>"))
            (is (str/includes? (blob (-> (aws/raw-refs enc) :request :sha)) "<redacted>") "the Encrypt request's Plaintext too")
            (is (not (str/includes? (blob (-> (aws/raw-refs enc) :request :sha)) (b64 secret))))
            (is (not (aws/error? gdk)) (pr-str (dissoc gdk :raw)))
            (is (not (str/includes? (raw-blob gdk) (b64 gdk-plain))))
            (is (= (b64 gdk-cipher) (get (clojure.data.json/read-str (raw-blob gdk)) "CiphertextBlob")) "the ciphertext stays")
            (confirmed! ctx e :kms :ScheduleKeyDeletion {:KeyId kid :PendingWindowInDays 7})))))))

(defn- sqs-ctx
  "A step context for the sqs kind outside the engine."
  [e sess & {:as over}]
  (merge {:ws "w" :session sess :env e :step-correlation "c-1" :step {:n 1}
          :resolve-target (fn [k] (env/target e (keyword k)))
          :deadline (.plusSeconds (Instant/now) 30)
          :attempt-cancelled? (constantly false)}
         over))

(defn- step [verb target & dirs]
  {:kind "sqs" :verb verb :target target :directives (vec (for [d dirs] {:name d}))})

(deftest-floci sqs-receive-on-a-remote-environment-is-a-peek-unless-delete-is-confirmed-first
  (with-temp-home
    (with-profile
      (let [q (uniq "peek")
            _ (is (not (aws/error? (aws/invoke! ctx local :sqs :CreateQueue {:QueueName q}))))
            url (:QueueUrl (aws/invoke! ctx local :sqs :GetQueueUrl {:QueueName q}))
            _ (aws/invoke! ctx local :sqs :SendMessage {:QueueUrl url :MessageBody "m-1"})
            sess (:key (session/start! "w" "P-1"))
            ro (remote :targets {:queue/q q})
            rw (writable :targets {:queue/q q})
            run (fn [e st & {:as over}] (sqs/run (sqs-ctx e sess over) (assoc st :directives (conj (:directives st) {:name "wait" :value "1s"}))))
            depth (fn [] (Integer/parseInt (get-in (aws/invoke! ctx local :sqs :GetQueueAttributes {:QueueUrl url :AttributeNames ["ApproximateNumberOfMessages"]})
                                                   [:Attributes :ApproximateNumberOfMessages])))]
        (testing "read-only remote: a plain receive peeks (visibility 0), the message stays, nothing is deleted or gated"
          (let [r (run ro (step "receive" "queue/q"))]
            (is (= 1 (get-in r [:facts "count"])))
            (is (= ["m-1"] (:body-json r)))
            (is (= 1 (get-in (run ro (step "receive" "queue/q")) [:facts "count"])) "still visible to the next receive")
            (is (= 1 (depth)))))
        (testing "read-only remote: @delete is refused flat before anything is received"
          (let [x (try (run ro (step "receive" "queue/q" "delete")) (catch clojure.lang.ExceptionInfo x x))]
            (is (instance? Exception x))
            (is (str/includes? (ex-message x) "read-only") (ex-message x))
            (is (= 1 (depth)) "the message is still there and visible")
            (is (= 1 (get-in (run ro (step "receive" "queue/q")) [:facts "count"])))))
        (testing "writable remote: @delete asks for a confirmation up front: no receive happened"
          (let [events (atom [])
                x (try (run rw (step "receive" "queue/q" "delete") :sink (fn [ev] (swap! events conj ev)))
                       (catch clojure.lang.ExceptionInfo x x))
                ev (first (filter #(= :confirm.required (:event %)) @events))]
            (is (= :aws/confirm-required (:type (ex-data x))) (pr-str x))
            (is (some? ev) (pr-str @events))
            (is (str/includes? (:summary ev) "DeleteMessageBatch"))
            (is (str/includes? (:summary ev) q))
            (is (= 1 (get-in (run ro (step "receive" "queue/q")) [:facts "count"])) "not received, so visible")
            (testing "the confirmed run receives and deletes on the pass"
              (let [r (run rw (step "receive" "queue/q" "delete") :confirm (:token ev))]
                (is (= 1 (get-in r [:facts "count"])) (pr-str r))
                (is (= ["m-1"] (:body-json r)))
                (is (= 0 (depth)))
                (is (= 0 (get-in (run ro (step "receive" "queue/q")) [:facts "count"])))))
            (testing "audit: the gate's confirm-required and confirmation, then the delete on the pass"
              (let [ls (filter #(= "DeleteMessageBatch" (:op %)) (aws/audit-lines {:ws "w" :session sess}))]
                (is (= [:refused :confirm-required :confirmed :ok] (map :outcome ls)) (pr-str ls))
                (is (= [nil nil :used :pass] (map :confirmation ls)))
                (is (= [:read-only nil nil nil] (map :reason ls)))))))
        (testing "a pass is single use and bound to the op: presented again it is refused"
          (aws/invoke! ctx local :sqs :SendMessage {:QueueUrl url :MessageBody "m-2"})
          (let [g (aws/gate! {:ws "w" :session sess} rw :sqs :DeleteMessageBatch {:QueueUrl url})
                g (aws/gate! {:ws "w" :session sess :confirm (:token g)} rw :sqs :DeleteMessageBatch {:QueueUrl url})
                pass (:pass g)]
            (is (string? pass) (pr-str g))
            (is (= :confirmation (:category (aws/invoke! {:ws "w" :session sess :pass pass} rw :sqs :PurgeQueue {:QueueUrl url}))) "another op")
            (let [msg (first (:Messages (aws/invoke! ctx local :sqs :ReceiveMessage {:QueueUrl url :WaitTimeSeconds 1})))
                  r (aws/invoke! {:ws "w" :session sess :pass pass} rw :sqs :DeleteMessageBatch
                                 {:QueueUrl url :Entries [{:Id "0" :ReceiptHandle (:ReceiptHandle msg)}]})]
              (is (not (aws/error? r)) (pr-str (dissoc r :raw)))
              (is (= :confirmation (:category (aws/invoke! {:ws "w" :session sess :pass pass} rw :sqs :DeleteMessageBatch
                                                           {:QueueUrl url :Entries [{:Id "0" :ReceiptHandle (:ReceiptHandle msg)}]})))
                  "used once"))))
        (testing "local: a receive deletes, as before"
          (aws/invoke! ctx local :sqs :SendMessage {:QueueUrl url :MessageBody "m-3"})
          (let [e (env/validate (assoc-in (fu/local-env) [:targets :queue/q] q))
                r (run e (step "receive" "queue/q"))]
            (is (= 1 (get-in r [:facts "count"])))
            (is (= 0 (depth)))))
        (aws/invoke! ctx local :sqs :DeleteQueue {:QueueUrl url})))))
