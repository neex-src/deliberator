(ns deliberator.secrets-test
  "Unit tests of the pieces behind `secrets-from`: logical names of references, the hash marker, the
  inspect scrub, the redaction table. The live walk (two tokens, `podman inspect`, `grep` of the whole home) is
  `secrets-estate-test`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.test-util :as tu]))

(deftest logical-names
  (testing "a secret ARN loses account, region and Secrets Manager's random suffix"
    (is (= {:service :secretsmanager :id "estate/api-key" :json-key nil}
           (runtime/logical-ref "arn:aws:secretsmanager:eu-central-1:000000000000:secret:estate/api-key-LAREMM"))))
  (testing "a json key after the name is kept"
    (is (= {:service :secretsmanager :id "estate/db" :json-key "password"}
           (runtime/logical-ref "arn:aws:secretsmanager:eu-central-1:000000000000:secret:estate/db-AbC123:password"))))
  (testing "an SSM ARN, a path and a bare secret name"
    (is (= {:service :ssm :id "/estate/common/bus-name"}
           (runtime/logical-ref "arn:aws:ssm:eu-central-1:000000000000:parameter/estate/common/bus-name")))
    (is (= {:service :ssm :id "/x/y"} (runtime/logical-ref "/x/y")))
    (is (= {:service :secretsmanager :id "estate/api-key" :json-key nil} (runtime/logical-ref "estate/api-key")))))

(deftest every-redacted-response-is-marked-secret-bearing
  (testing "the value reads of Secrets Manager and SSM are :read and listed in env.ops/secret-bearing"
    (doseq [[s o] [[:secretsmanager "GetSecretValue"] [:ssm "GetParameter"] [:ssm "GetParameters"] [:ssm "GetParametersByPath"]]]
      (is (= :read (ops/op-class s o)) (str s " " o " stays a read"))
      (is (ops/secret-bearing? s o)))
    (is (not (ops/secret-bearing? :ssm "PutParameter")))
    (is (ops/secret-bearing? :secretsmanager :GetSecretValue)))
  (testing "the credential-minting rows (sts, iam, kms, cognito) are mutations: gated on a remote environment, redacted too"
    (doseq [[s o] ops/secret-bearing :when (not (#{:ssm :secretsmanager} s))
            ;; the one exception: a short session of the SAME identity narrowed by a read-only policy, asked for by the record's `:console {:signin true}`
            :when (not= [:sts "GetFederationToken"] [s o])]
      (is (= :mutate (ops/op-class s o)) (str s " " o))
      (is (ops/secret-bearing? s o))))
  (testing "the name rule"
    (doseq [k ["SecretAccessKey" "SessionToken" "Plaintext" "PrivateKeyPlaintext" "Password" "MasterUserPassword"
               "SecretString" "SecretBinary" "AccessToken" "IdToken" "RefreshToken" "ClientSecret" "SharedSecret" :Plaintext]]
      (is (ops/secret-member? k) (str k)))
    (doseq [k ["NextToken" "ClientToken" "ContinuationToken" "Value" "AccessKeyId" "Credentials" "KeyId" "Name" "TokenType" "SecretId" "SecretList"]]
      (is (not (ops/secret-member? k)) (str k)))))

(deftest inspect-body-of-a-secret-container-is-scrubbed
  (let [body (fn [labels] (.getBytes (json/write-str {"Id" "abc" "Config" {"Labels" labels "Env" ["PATH=/bin" "API_KEY=the-real-value" "EMPTY="]}}) "UTF-8"))
        out (fn [bs] (json/read-str (String. ^bytes bs "UTF-8")))]
    (testing "with the label every Env value goes, names stay"
      (let [bs (podman/scrub-secrets (body {"deliberator.secrets-from" "local2"}))]
        (is (not (str/includes? (String. ^bytes bs "UTF-8") "the-real-value")))
        (is (= ["PATH=<redacted>" "API_KEY=<redacted>" "EMPTY=<redacted>"] (get-in (out bs) ["Config" "Env"])))))
    (testing "a list of descriptions"
      (let [bs (podman/scrub-secrets (.getBytes (json/write-str [(out (body {"deliberator.secrets-from" "local2"}))]) "UTF-8"))]
        (is (not (str/includes? (String. ^bytes bs "UTF-8") "the-real-value")))))
    (testing "without the label the body is untouched, byte for byte"
      (let [b (body {"deliberator.role" "service-a"})]
        (is (identical? b (podman/scrub-secrets b)))))
    (testing "garbage that mentions the label is replaced, never persisted as is"
      (let [bs (podman/scrub-secrets (.getBytes "deliberator.secrets-from {not json API_KEY=the-real-value" "UTF-8"))]
        (is (not (str/includes? (String. ^bytes bs "UTF-8") "the-real-value")))))))

(deftest the-spec-hash-holds-a-marker-never-a-value
  (tu/with-temp-home
    (let [d {:role "service-a" :image "i" :ports {} :env {} :secrets {"K" "/k"}}]
      (is (string? (:hash (#'runtime/rehash d))))
      (is (= (:hash (#'runtime/rehash d)) (:hash (#'runtime/rehash (assoc d :secret-values {"K" "v1"}))))
          "values are not hashed")
      (is (not= (:hash (#'runtime/rehash d))
                (:hash (#'runtime/rehash (assoc d :secrets-from {:env "local2" :start "aa"}))))
          "the marker is")
      (is (not= (:hash (#'runtime/rehash (assoc d :secrets-from {:env "local2" :start "aa"})))
                (:hash (#'runtime/rehash (assoc d :secrets-from {:env "local2" :start "bb"}))))
          "and differs per start"))))
