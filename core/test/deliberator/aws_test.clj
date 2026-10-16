(ns deliberator.aws-test
  "Layer 2: `aws/invoke!` against the test Floci (`floci-util/url`, default :14566). Resources are prefixed `dlbtest-`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :refer [fixed-clock temp-home with-temp-home]])
  (:import (java.time Instant)
           (java.util UUID)))

(use-fixtures :once (fn [f] (f) (env/close-clients!)))

(def ctx {:ws "w"})

(def local (env/validate (fu/local-env)))

(def remote-base
  {:name "dlbdev" :kind :remote
   :aws {:profile "dlbtest" :region "eu-central-1" :account "111111111111"
         :endpoint fu/url}
   :targets {:service/a "https://a.example.test"}})

(defn remote [& {:as over}] (env/validate (merge remote-base over)))

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

(deftest-floci confirmation-flow-on-a-remote-environment
  (with-temp-home
    (with-profile
      (let [e (remote)
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
          (is (str/includes? (:summary r1) "111111111111"))
          (is (str/includes? (:summary r1) "read-only") "the default is read-only and the summary says so"))
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
        (testing "read-only false does not bypass the gate"
          (let [r (aws/invoke! c (remote :read-only false) :ssm :PutParameter (assoc req :Overwrite true))]
            (is (= :confirm-required (:type r)))
            (is (not (str/includes? (:summary r) "read-only")))))
        (testing "an unknown op is gated as a mutate"
          (is (= :confirm-required (:type (aws/invoke! c e :ssm :SomethingNew {})))))
        (confirmed! c e :ssm :DeleteParameter {:Name p})))))

(deftest-floci tokens-expire-after-ten-minutes
  (with-temp-home
    (with-profile
      (let [e (remote)
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
      (let [e (remote)
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
            (aws/invoke! ctx le :secretsmanager :DeleteSecret {:SecretId ln :ForceDeleteWithoutRecovery true})))
        (confirmed! ctx e :secretsmanager :DeleteSecret {:SecretId n :ForceDeleteWithoutRecovery true})))))

(deftest-floci decrypted-parameters-are-redacted
  (with-temp-home
    (with-profile
      (let [e (remote)
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

(deftest-floci check-compares-the-account
  (with-temp-home
    (is (:ok? (aws/check ctx local)))
    (let [r (aws/check ctx (assoc-in local [:aws :account] "999999999999"))]
      (is (false? (:ok? r)))
      (is (= "000000000000" (:account r))))))
