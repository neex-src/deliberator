(ns deliberator.env-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cognitect.aws.credentials :as creds]
            [deliberator.env :as env]
            [deliberator.env.ops]
            [deliberator.store :as store]
            [deliberator.test-util :refer [temp-home with-temp-home]]))

(use-fixtures :once (fn [f] (f) (env/close-clients!)))

(def remote {:name "dev" :kind :remote
             :aws {:profile "dlbtest-dev" :region "eu-central-1" :account "210987654321"}
             :targets {:service/a "https://a.example.test"}})

(defn invalid-problems [m]
  (try (env/validate m) nil
       (catch clojure.lang.ExceptionInfo e
         (is (= :env/invalid (:type (ex-data e))))
         (:problems (ex-data e)))))

(defn mentions? [ps s] (boolean (some #(re-find (re-pattern s) %) ps)))

(deftest default-local-written-on-first-use
  (with-temp-home
    (is (= [] (env/names "w")))
    (let [e (env/load "w" "local")]
      (is (= :local (:kind e)))
      (is (= (or (System/getenv "DELIBERATOR_LOCAL_ENDPOINT") (System/getProperty "deliberator.local.endpoint") "http://localhost:4566")
             (get-in e [:aws :endpoint])))
      (is (= env/fake-account (get-in e [:aws :account])))
      (is (= "eu-central-1" (get-in e [:aws :region])))
      (is (= ["local"] (env/names "w")))
      (is (= e (store/read-edn (store/path "w" "environments" "local.edn"))))
      (is (= e (env/load "w" "local"))))))

(deftest unknown-environment-lists-known
  (with-temp-home
    (env/load "w" "local")
    (let [e (try (env/load "w" "nope") (catch clojure.lang.ExceptionInfo e e))]
      (is (= :env/not-found (:type (ex-data e))))
      (is (= ["local"] (:known (ex-data e)))))))

(deftest local-schema-refusals
  (let [base (assoc env/default-local :name "x")]
    (is (nil? (invalid-problems base)))
    (testing "a profile is refused"
      (is (mentions? (invalid-problems (assoc-in base [:aws :profile] "p")) "cannot name a :profile")))
    (testing "an endpoint is required"
      (is (mentions? (invalid-problems (update base :aws dissoc :endpoint)) "endpoint is required")))
    (testing "credentials are required"
      (is (mentions? (invalid-problems (update base :aws dissoc :credentials)) "credentials")))
    (testing ":read-only belongs to remote"
      (is (mentions? (invalid-problems (assoc base :read-only true)) "read-only")))
    (testing "unknown keys, bad account, bad targets"
      (is (mentions? (invalid-problems (assoc base :extra 1)) "unknown keys"))
      (is (mentions? (invalid-problems (assoc-in base [:aws :account] "12")) "12-digit"))
      (is (mentions? (invalid-problems (assoc base :targets {:plain "x"})) "namespaced")))
    (testing "not a map, bad kind"
      (is (seq (invalid-problems 42)))
      (is (mentions? (invalid-problems (assoc base :kind :both)) ":kind")))))

(deftest remote-schema
  (is (nil? (invalid-problems remote)))
  (testing "a profile is required and credentials are refused (mixed shapes)"
    (is (mentions? (invalid-problems (update remote :aws dissoc :profile)) "profile is required"))
    (is (mentions? (invalid-problems (assoc-in remote [:aws :credentials] {:access-key-id "a" :secret-access-key "b"}))
                   "credentials")))
  (testing ":read-only defaults to true, an explicit false is kept"
    (is (true? (:read-only (env/validate remote))))
    (is (false? (:read-only (env/validate (assoc remote :read-only false)))))
    (is (true? (env/read-only? (env/validate remote))))
    (is (mentions? (invalid-problems (assoc remote :read-only "yes")) "boolean"))))

(deftest file-name-must-match
  (with-temp-home
    (env/save! "w" remote)
    (store/write-edn! (store/path "w" "environments" "other.edn") remote)
    (is (= remote (dissoc (env/load "w" "dev") :read-only)))
    (is (true? (:read-only (env/load "w" "dev"))))
    (is (= :env/invalid (:type (ex-data (try (env/load "w" "other") (catch clojure.lang.ExceptionInfo e e))))))
    (is (= ["dev" "other"] (env/names "w")))))

(deftest targets
  (let [e (env/validate env/default-local)]
    (is (= "estate-inbox" (env/target e :queue/inbox)))
    (is (= "estate-inbox" (env/target e "queue/inbox")))
    (is (= {:container "deliberator-default-service-a"} (env/target e :log/service-a)))
    (let [x (try (env/target e :queue/nope) (catch clojure.lang.ExceptionInfo x x))
          d (ex-data x)]
      (is (= :env/unknown-target (:type d)))
      (is (some #{:queue/inbox} (:known d)))
      (is (re-find #"queue/inbox" (ex-message x))))))

(deftest clients-are-memoised-per-record-and-service
  (let [e (env/validate env/default-local)
        a (env/aws-client e :sqs)]
    (is (identical? a (env/aws-client e :sqs)))
    (is (identical? a (env/aws-client (assoc e :targets {}) :sqs)) "targets are not part of the key")
    (is (not (identical? a (env/aws-client e :sts))))
    (is (not (identical? a (env/aws-client (assoc-in e [:aws :region] "eu-west-1") :sqs))))
    (is (identical? (env/aws-client e :eventbridge) (env/aws-client e :events)))))

(deftest op-class-is-reexported
  (is (= :read (env/op-class :sqs "ReceiveMessage")))
  (is (= :mutate (env/op-class :sqs "DeleteMessage"))))

(deftest every-table-service-has-a-client
  (let [e (env/validate env/default-local)]
    (doseq [s (keys deliberator.env.ops/table)]
      (is (some? (env/aws-client e s)) (str s)))))

(deftest a-local-endpoint-never-names-aws
  (let [base (assoc env/default-local :name "x")
        with (fn [url] (invalid-problems (assoc-in base [:aws :endpoint] url)))]
    (testing "accepted: loopback, private and link-local addresses, a container name, the local suffixes"
      (doseq [u ["http://localhost:4566" "http://127.0.0.1:4566" "http://[::1]:4566" "http://10.0.0.5:4566" "http://172.20.3.4:4566"
                 "http://192.168.1.10:4566" "http://169.254.169.254:4566" "http://100.100.1.2:4566" "http://floci:4566"
                 "http://deliberator-tour-floci:4566" "http://host.containers.internal:4566" "http://box.local:4566"
                 "http://floci.lan:4566" "http://x.home.arpa:4566" "https://emu.test"]]
        (is (nil? (with u)) u)))
    (testing "refused: AWS's own domains, with the reason"
      (doseq [u ["https://sqs.eu-central-1.amazonaws.com" "https://s3.amazonaws.com" "https://sts.cn-north-1.amazonaws.com.cn"
                 "https://sqs.eu-central-1.api.aws" "http://amazonaws.com:4566"]]
        (is (mentions? (with u) "names AWS") u)
        (is (mentions? (with u) "must never reach AWS") u)))
    (testing "refused: any public address or name"
      (is (mentions? (with "http://8.8.8.8:4566") "public address"))
      (is (mentions? (with "https://floci.example.com") "public name"))
      (is (mentions? (with "https://emulator.cloudprovider.io:4566") "public name")))
    (testing "the rule is the local record's: a remote record may name AWS or an emulator"
      (is (nil? (invalid-problems (assoc-in remote [:aws :endpoint] "https://sqs.eu-central-1.amazonaws.com"))))
      (is (nil? (invalid-problems (assoc-in remote [:aws :endpoint] "http://localhost:14566")))))
    (testing "env/load refuses such a file"
      (with-temp-home
        (store/write-edn! (store/path "w" "environments" "bad.edn")
                          (assoc-in (assoc base :name "bad") [:aws :endpoint] "https://sqs.eu-central-1.amazonaws.com"))
        (let [x (try (env/load "w" "bad") (catch clojure.lang.ExceptionInfo e e))]
          (is (= :env/invalid (:type (ex-data x))))
          (is (mentions? (:problems (ex-data x)) "names AWS")))))))

(deftest child-env-scrubs-the-daemon-s-aws-and-exports-the-local-record
  (let [base {"PATH" "/bin" "HOME" "/h" "AWS_PROFILE" "work" "AWS_ACCESS_KEY_ID" "AKIAREAL" "AWS_SECRET_ACCESS_KEY" "real"
              "AWS_SESSION_TOKEN" "tok" "AWS_SHARED_CREDENTIALS_FILE" "/h/.aws/credentials" "AWS_DEFAULT_REGION" "us-east-1"
              "AWS_ENDPOINT_URL" "https://sqs.eu-central-1.amazonaws.com" "DLB_X" "keep"}
        e (env/validate (assoc-in env/default-local [:aws :endpoint] "http://localhost:14566"))
        out (env/child-env base e)]
    (testing "nothing of the daemon's AWS_* survives; everything else does"
      (is (= "/bin" (get out "PATH")))
      (is (= "keep" (get out "DLB_X")))
      (is (not (contains? out "AWS_PROFILE")))
      (is (not (contains? out "AWS_SESSION_TOKEN")))
      (is (not-any? #(str/includes? (str %) "real") (vals out)))
      (is (not-any? #(str/includes? (str %) "amazonaws") (vals out))))
    (testing "the files are /dev/null, IMDS and the pager are off"
      (is (= "/dev/null" (get out "AWS_CONFIG_FILE")))
      (is (= "/dev/null" (get out "AWS_SHARED_CREDENTIALS_FILE")))
      (is (= "true" (get out "AWS_EC2_METADATA_DISABLED")))
      (is (= "" (get out "AWS_PAGER"))))
    (testing "the local record is what the child's aws CLI sees"
      (is (= "http://localhost:14566" (get out "AWS_ENDPOINT_URL")))
      (is (= "test" (get out "AWS_ACCESS_KEY_ID")))
      (is (= "test" (get out "AWS_SECRET_ACCESS_KEY")))
      (is (= "eu-central-1" (get out "AWS_DEFAULT_REGION")))
      (is (= "eu-central-1" (get out "AWS_REGION"))))
    (testing "without a record (or a remote one, which shell refuses anyway): the scrub only"
      (let [o (env/child-env base nil)]
        (is (= #{"AWS_CONFIG_FILE" "AWS_SHARED_CREDENTIALS_FILE" "AWS_EC2_METADATA_DISABLED" "AWS_PAGER"}
               (set (filter #(str/starts-with? % "AWS_") (keys o))))))
      (is (not (contains? (env/child-env base (env/validate remote)) "AWS_ENDPOINT_URL"))))))

;; --- the credential bridge ----------------------------------------------------------------------

(defn- stub-cli
  "A fake `aws`: appends a line per call to <dir>/calls, prints credentials expiring `exp-s` seconds from now (key id
  `AKIA<n>`, n = call number) unless <dir>/fail exists, then it says `fail-msg` on stderr and exits 255."
  [dir exp-s fail-msg]
  (let [cli (io/file dir "fake-aws")]
    (spit cli (str "#!/bin/sh\n"
                   "echo \"$@\" >> " dir "/calls\n"
                   "if [ -e " dir "/fail ]; then echo '" fail-msg "' >&2; exit 255; fi\n"
                   "n=$(wc -l < " dir "/calls)\n"
                   "exp=$(date -u -d \"+" exp-s " seconds\" +%Y-%m-%dT%H:%M:%S.%3N+00:00)\n"
                   "echo \"{\\\"Version\\\":1,\\\"AccessKeyId\\\":\\\"AKIA$n\\\",\\\"SecretAccessKey\\\":\\\"s\\\","
                   "\\\"SessionToken\\\":\\\"t\\\",\\\"Expiration\\\":\\\"$exp\\\"}\"\n"))
    (.setExecutable cli true)
    (str cli)))

(defn- calls [dir]
  (let [f (io/file dir "calls")] (if (.exists f) (count (str/split-lines (slurp f))) 0)))
(defn- key-of [p] (:aws/access-key-id (creds/fetch p)))

(deftest the-cli-bridge-caches-until-the-reported-expiry-less-the-margin
  (with-temp-home
    (let [dir (temp-home) cli (stub-cli dir 3600 "never")
          p (#'env/profile-provider "dlbtest-role" (io/file dir "no-such-credentials") cli)]
      (is (= "AKIA1" (key-of p)))
      (is (= "AKIA1" (key-of p)))
      (is (= 1 (calls dir)) "one CLI call serves the next fetches")
      (is (= "configure export-credentials --profile dlbtest-role --format process"
             (str/trim (slurp (io/file dir "calls")))))
      (is (nil? (env/credential-failure "dlbtest-role"))))))

(deftest the-cli-bridge-refreshes-by-the-wall-clock-and-keeps-valid-credentials-when-the-refresh-fails
  (with-temp-home
    (let [dir (temp-home) cli (stub-cli dir 4 "Error loading SSO Token: Token for s does not exist")
          p (#'env/profile-provider "dlbtest-sso" (io/file dir "no-such-credentials") cli)]
      (with-redefs [env/refresh-margin-s 3]       ; refresh from 1 s after the fetch, expire after 4
        (is (= "AKIA1" (key-of p)))
        (Thread/sleep 1300)
        (testing "past refresh-at the CLI is asked again, the new credentials replace the old"
          (is (= "AKIA2" (key-of p)))
          (is (= 2 (calls dir))))
        (testing "a refresh that fails keeps the still valid credentials, and says why"
          (spit (io/file dir "fail") "")
          (Thread/sleep 1300)
          (is (= "AKIA2" (key-of p)) "valid for another 2.7 s: kept")
          (is (= 3 (calls dir)))
          (is (str/includes? (:message (env/credential-failure "dlbtest-sso")) "Token for s does not exist"))
          (is (str/includes? (:message (env/credential-failure "dlbtest-sso")) "--profile dlbtest-sso exited 255")))
        (testing "expired and still failing: nothing, so the call fails with the CLI's own words"
          (Thread/sleep 3000)
          (is (nil? (key-of p))))
        (testing "the login happens (the CLI works again): credentials come back, the failure is forgotten"
          (io/delete-file (io/file dir "fail"))
          (is (some? (key-of p)))
          (is (nil? (env/credential-failure "dlbtest-sso"))))))))

(deftest the-cli-bridge-reports-a-missing-cli-and-a-hung-one
  (with-temp-home
    (let [dir (temp-home)]
      (is (nil? (key-of (#'env/profile-provider "dlbtest-nocli" (io/file dir "x") "/no/such/aws"))))
      (is (re-find #"cannot be started.*PATH" (:message (env/credential-failure "dlbtest-nocli"))))
      (let [cli (io/file dir "slow-aws")]
        (spit cli "#!/bin/sh\nexec sleep 20\n")
        (.setExecutable cli true)
        (with-redefs [env/cli-timeout-ms 300]
          (is (nil? (key-of (#'env/profile-provider "dlbtest-slow" (io/file dir "x") (str cli)))))
          (is (re-find #"timed out" (:message (env/credential-failure "dlbtest-slow"))))))
      (let [cli (io/file dir "junk-aws")]
        (spit cli "#!/bin/sh\necho 'not json'\n")
        (.setExecutable cli true)
        (is (nil? (key-of (#'env/profile-provider "dlbtest-junk" (io/file dir "x") (str cli)))))
        (is (re-find #"something else than JSON" (:message (env/credential-failure "dlbtest-junk"))))))))

;; --- B17: replaced clients are stopped, never under a running call ------------------------------------------------------

(deftest a-replaced-client-is-stopped-when-its-last-call-returns
  (with-temp-home
    (env/close-clients!)
    (let [stopped (atom 0)
          e remote
          e2 (assoc-in remote [:aws :region] "eu-west-1")]
      (with-redefs [cognitect.aws.client.api/stop (let [real cognitect.aws.client.api/stop]
                                                    (fn [c] (swap! stopped inc) (real c)))]
        (env/save! "w" e)
        (let [started (promise) release (promise)
              call (future (env/with-client "w" e :sqs (fn [c] (deliver started c) @release :done)))
              old @started]
          (is (= {:live 1 :retired 0} (env/client-stats)))
          (env/save! "w" e2)                                   ; the record changes while the call runs
          (is (= {:live 0 :retired 1} (env/client-stats)) "retired, not stopped: the call still uses it")
          (is (zero? @stopped))
          (env/with-client "w" e2 :sqs (fn [c] (is (not (identical? old c)))))
          (is (= {:live 1 :retired 1} (env/client-stats)))
          (deliver release true)
          (is (= :done @call))
          (is (= {:live 1 :retired 0} (env/client-stats)))
          (is (= 1 @stopped) "stopped when the call returned"))
        (testing "an idle client is stopped the moment the record changes"
          (reset! stopped 0)
          (env/save! "w" (assoc-in remote [:aws :region] "eu-north-1"))
          (is (= 1 @stopped))
          (is (= 0 (:retired (env/client-stats)))))
        (testing "save of the same record keeps the client; remove! forgets it"
          (reset! stopped 0)
          (env/with-client "w" (env/validate (assoc-in remote [:aws :region] "eu-north-1")) :sqs identity)
          (env/save! "w" (assoc-in remote [:aws :region] "eu-north-1"))
          (is (zero? @stopped))
          (env/remove! "w" "dev")
          (is (= 0 (:live (env/client-stats)))))))
    (env/close-clients!)))

(deftest remove-deletes-the-record-and-protects-local
  (with-temp-home
    (env/save! "w" remote)
    (is (some #{"dev"} (env/names "w")))
    (is (= "dev" (:name (env/remove! "w" "dev"))))
    (is (not-any? #{"dev"} (env/names "w")))
    (is (= :env/not-found (:type (ex-data (try (env/remove! "w" "dev") (catch Exception e e))))))
    (is (= :env/protected (:type (ex-data (try (env/remove! "w" "local") (catch Exception e e))))))
    (is (= :env/invalid (:type (ex-data (try (env/validate (assoc remote :name "local")) (catch Exception e e)))))
        "a remote record cannot take the name local")))
