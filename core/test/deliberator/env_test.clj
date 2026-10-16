(ns deliberator.env-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.env :as env]
            [deliberator.env.ops]
            [deliberator.store :as store]
            [deliberator.test-util :refer [with-temp-home]]))

(use-fixtures :once (fn [f] (f) (env/close-clients!)))

(def remote {:name "dev" :kind :remote
             :aws {:profile "dlbtest-dev" :region "eu-central-1" :account "123456789012"}
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
      (is (= "http://localhost:4566" (get-in e [:aws :endpoint])))
      (is (= "000000000000" (get-in e [:aws :account])))
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
