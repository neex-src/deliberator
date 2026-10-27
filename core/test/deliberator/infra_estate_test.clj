(ns deliberator.infra-estate-test
  "The four ops no other test called (review 2026-10-25, R13), against the running estate (`bb estate:up --ws W`,
  `ESTATE_WS=W`, `DELIBERATOR_HOME`; without them a SKIP line): `aws.discover` lists what Terraform put on Floci,
  `terraform.drift` of `estate/terraform/floci` finds nothing missing, `terraform.local plan` of the same root is ok and
  persisted. `aws.describe` reads the bundled models and runs in both passes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.store :as store]))

(def ^:private floci-root (.getCanonicalPath (io/file "../estate/terraform/floci")))

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)))

(defmacro with-estate
  "Runs body with `ws` bound to `ESTATE_WS`, else reports a skip for the current test."
  [[ws] & body]
  `(if-let [~ws (fu/estate-ws)]
     (do ~@body)
     (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))

(deftest aws-discover-lists-the-estate
  (with-estate [ws]
    (let [r (call "aws.discover" {:ws ws :env "local"})
          names (fn [kind] (set (map :name (get-in r [:resources kind]))))
          table (-> (aws/invoke! {:ws ws} (env/load ws "local") :ssm :GetParameter {:Name "/estate/common/table-name"})
                    (get-in [:Parameter :Value]))]
      (is (= "local" (:env r)))
      (is (= {} (:errors r)) "every section answered")
      (testing "the stack's parameters, queues, table and bus are there"
        (is (contains? (names "ssm") "/estate/common/table-name"))
        (is (contains? (names "ssm") "/estate/common/bus-name"))
        (is (string? table))
        (is (contains? (names "dynamodb") table) "the table the parameter names exists")
        (is (<= 2 (count (names "sqs"))) "inbox and events queues")
        (is (contains? (names "ecs") "estate")))
      (testing "ECS services and task definitions, not only clusters (B31)"
        (let [svc (first (filter #(= "estate/estate-service-a" (:name %)) (get-in r [:resources "ecs-services"])))]
          (is (some? svc) (pr-str (get-in r [:resources "ecs-services"])))
          (is (str/includes? (str (:ref svc)) ":service/estate/estate-service-a") "the ref is the service ARN")
          (is (= "estate" (:cluster svc)))
          (is (= ["service-a"] (:containers svc)) "the container names of its task definition, which tie it to the workspace service a"))
        (is (contains? (names "ecs-taskdefs") "estate-service-a:1"))
        (is (contains? (names "ecs-taskdefs") "estate-service-b:1")))
      (testing "names only: no values, no secret strings"
        (is (not (str/includes? (pr-str r) "SecretString")))))))

(deftest terraform-drift-of-the-floci-root-is-clean
  (with-estate [ws]
    (let [r (call "terraform.drift" {:ws ws :env "local" :dir floci-root})]
      (is (= "local" (:env r)))
      (is (= {} (:errors r)))
      (is (pos? (:declared r)) "the root declares resources of kinds the explorer knows")
      (is (= [] (:missing r)) (pr-str (:missing r)))
      (is (= (:declared r) (:matching r)) "everything declared exists on Floci")
      (is (vector? (:undeclared r)) "hand-made things may exist (other tests' dlbtest- queues), they are listed, not failed"))))

(deftest terraform-local-plan-of-the-floci-root
  (with-estate [ws]
    (let [events (atom [])
          r (call "terraform.local" {:ws ws :env "local" :dir floci-root :action "plan"} events)
          lines (map :line (filter #(= "tf.line" (:event %)) @events))]
      (is (true? (:ok r)) (:summary r))
      (is (zero? (:exit r)))
      (is (= "plan" (:action r)))
      (is (str/starts-with? (str (:summary r)) "No changes.") "the estate was applied with the same variables the workspace derives")
      (is (= (get-in (env/load ws "local") [:aws :endpoint]) (get (:vars r) "endpoint")))
      (is (seq lines) "the output streamed as tf.line events")
      (is (= (str/split-lines (:output r)) lines) "every line was an event")
      (is (= (:output r) (String. (store/raw-get ws (:sha r)) "UTF-8")) "the output is persisted raw"))))

(deftest aws-describe-gives-the-request-shape
  (testing "a known operation"
    (let [r (call "aws.describe" {:service "ssm" :op "GetParameter"})]
      (is (= ["ssm" "GetParameter" "read"] ((juxt :service :op :class) r)))
      (is (= "structure" (get-in r [:input :type])))
      (is (= ["Name"] (get-in r [:input :required])))
      (is (= "string" (get-in r [:input :members "Name" :type])))
      (is (= "boolean" (get-in r [:input :members "WithDecryption" :type])))
      (is (= "Name" (first (get-in r [:input :order]))) "required members come first")))
  (testing "a mutating operation is classed so"
    (is (= "mutate" (:class (call "aws.describe" {:service "sqs" :op "SendMessage"})))))
  (testing "an unknown operation is a typed error, not nil"
    (let [e (try (call "aws.describe" {:service "ssm" :op "NoSuchOp"}) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :aws/unknown-operation (:type e)))))
  (testing "the request shape has no self-reference loop: a nested shape is cut at the depth limit"
    (let [r (call "aws.describe" {:service "dynamodb" :op "PutItem"})]
      (is (= "map" (get-in r [:input :members "Item" :type])))
      (is (some? (get-in r [:input :members "Item" :value]))))))
