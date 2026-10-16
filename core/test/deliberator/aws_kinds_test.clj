(ns deliberator.aws-kinds-test
  "The AWS kinds (sqs, eventbridge, lambda, dynamodb, scheduler) end to end through the engine, against
  the REAL runtime: a Floci on a free host port brought up by the fixture in a throwaway workspace
  (and removed afterwards); skips loudly when Podman is not reachable. Resources are prefixed
  `dlbtest-`. Pure parts first."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.podman :as podman]
            [deliberator.floci-util :as fu]
            [deliberator.podman-test-support :as sup]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.engine :as engine]
            [deliberator.session :as session]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.dynamodb :as ddb]
            [deliberator.steps.eventbridge :as eb]
            [deliberator.steps.lambda]
            [deliberator.steps.scheduler]
            [deliberator.steps.sqs :as sqs]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def ^:dynamic *rt* nil)
(def floci-port (sup/free-port))

(defn- runtime-config [id]
  (-> (runtime/default-config)
      (assoc-in [:podman :endpoint] (sup/endpoint))
      (assoc :network (str "deliberator-test-" id))
      (assoc-in [:containers :floci :ports] {4566 floci-port})
      (assoc-in [:containers :floci :name] (str "deliberator-test-" id "-floci"))
      (update :containers dissoc :postgres)))

(defn- test-env [targets]
  (-> env/default-local
      (assoc-in [:aws :endpoint] (str "http://localhost:" floci-port))
      (update :targets merge targets)))

(defn- zip-bytes ^bytes [entry content]
  (let [bo (java.io.ByteArrayOutputStream.)]
    (with-open [z (java.util.zip.ZipOutputStream. bo)]
      (.putNextEntry z (java.util.zip.ZipEntry. ^String entry))
      (.write z (.getBytes ^String content "UTF-8"))
      (.closeEntry z))
    (.toByteArray bo)))

(def function-code
  "exports.handler = async (e) => { console.log('LOGLINE ' + JSON.stringify(e)); if (e.boom) throw new Error('kaboom'); return {echo: e, ok: true}; };")

(defn- ok! [r] (is (not (aws/error? r)) (pr-str (dissoc r :raw))) r)

(defn- build-resources!
  "Creates the dlbtest- queues, bus + rule, table with one item, function and schedule; returns the
  environment targets."
  [ws env]
  (let [ctx {:ws ws}
        call! (fn [s o r] (ok! (aws/invoke! ctx env s o r)))
        q (fn [n] (call! :sqs :CreateQueue {:QueueName n}) n)
        [qin qrouted] [(q "dlbtest-in") (q "dlbtest-routed")]]
    (call! :eventbridge :CreateEventBus {:Name "dlbtest-bus"})
    (call! :eventbridge :PutRule {:Name "dlbtest-rule" :EventBusName "dlbtest-bus"
                                  :EventPattern "{\"source\":[\"dlbtest.fixture\"]}"})
    (call! :eventbridge :PutTargets {:Rule "dlbtest-rule" :EventBusName "dlbtest-bus"
                                     :Targets [{:Id "1" :Arn (str "arn:aws:sqs:eu-central-1:000000000000:" qrouted)}]})
    (call! :dynamodb :CreateTable {:TableName "dlbtest-table"
                                   :AttributeDefinitions [{:AttributeName "id" :AttributeType "S"}]
                                   :KeySchema [{:AttributeName "id" :KeyType "HASH"}] :BillingMode "PAY_PER_REQUEST"})
    (call! :dynamodb :PutItem {:TableName "dlbtest-table" :Item {"id" {:S "item-1"} "n" {:N "5"}}})
    (let [fn-arn (:FunctionArn (call! :lambda :CreateFunction
                                      {:FunctionName "dlbtest-fn" :Runtime "nodejs20.x"
                                       :Role "arn:aws:iam::000000000000:role/dlbtest" :Handler "index.handler"
                                       :Code {:ZipFile (zip-bytes "index.js" function-code)}}))]
      (call! :scheduler :CreateSchedule {:Name "dlbtest-sched" :ScheduleExpression "at(2031-01-01T00:00:00)"
                                         :FlexibleTimeWindow {:Mode "OFF"}
                                         :Target {:Arn fn-arn :RoleArn "arn:aws:iam::000000000000:role/dlbtest"}}))
    {:queue/in qin :queue/routed qrouted :bus/test "dlbtest-bus" :table/t "dlbtest-table"
     :function/fn "dlbtest-fn" :schedule/s "dlbtest-sched"}))

(use-fixtures :once
  (fn [f]
    (binding [store/*home* (tu/temp-home)]
      (if-not (sup/up?)
        (do (fu/skip! "runtime tests of deliberator.aws-kinds-test" "runtime down: bb runtime:up")
            (f))
        (let [id (sup/rand-id)
              ws (str "awsk-" id)
              cfg (runtime-config id)
              c (podman/client (sup/endpoint) :ws ws)]
          (store/write-edn! (runtime/config-path ws) cfg)
          (try
            (runtime/ensure! ws)
            (let [env0 (test-env {})
                  targets (build-resources! ws env0)]
              (binding [*rt* {:ws ws :env (test-env targets)}] (f)))
            (finally
              (try (runtime/down! ws) (catch Exception e (println "down!" (ex-message e))))
              (try (podman/remove-network! c (:network cfg)) (catch Exception _)))))))))

(defmacro when-runtime [& body]
  `(if *rt* (do ~@body)
       (fu/skip! (fu/current-test) "runtime down: bb runtime:up")))

(defn- run-scn [ws env text]
  (let [f (io/file (str (store/home)) "scn" (str (random-uuid) ".scn"))]
    (io/make-parents f) (spit f text)
    (session/start! ws "T-1")
    (let [res (engine/run! ws env (str f) :session "T-1" :resolve-target #(env/target env (keyword %)))]
      (assoc res :text (slurp (get-in res [:paths :result]))))))

(defn- statuses [res] (mapv :status (:steps (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))))

(defn- step-edn [res n]
  (let [dir (io/file (get-in res [:paths :run]) "steps")
        d (first (filter #(str/starts-with? (.getName %) (format "%03d-" n)) (.listFiles dir)))]
    (assoc (store/read-edn (io/file d "step.edn")) :dir d)))

(defn- step-file [res n fname] (slurp (io/file (:dir (step-edn res n)) fname)))

(defn- blob-meta
  "The raw-store sidecar of the blob the step file is a link of."
  [ws res n fname]
  (store/raw-meta ws (store/sha256 (java.nio.file.Files/readAllBytes (.toPath (io/file (:dir (step-edn res n)) fname))))))

;; --- pure ------------------------------------------------------------------------------------------

(deftest correlation-in-detail
  (is (= "{\"correlation\":\"c-1\"}" (eb/with-correlation nil "c-1")))
  (is (= "{\"correlation\":\"c-1\"}" (eb/with-correlation "  {}\n" "c-1")))
  (is (= "{\"a\": [1, 2],\n \"b\": {\"x\":1},\"correlation\":\"c-1\"}" (eb/with-correlation "{\"a\": [1, 2],\n \"b\": {\"x\":1}}\n" "c-1")))
  (is (= "{\"correlation\":\"mine\",\"a\":1}" (eb/with-correlation "{\"correlation\":\"mine\",\"a\":1}" "c-1")) "the author's wins")
  (is (thrown? clojure.lang.ExceptionInfo (eb/with-correlation "[1]" "c")))
  (is (thrown? clojure.lang.ExceptionInfo (eb/with-correlation "nope" "c"))))

(deftest dynamodb-body
  (let [r (ddb/request-from-body (.getBytes "{\"TableName\":\"ignored\",\"Key\":{\"k\":{\"B\":\"aGV5\"}},\"Limit\":3}" "UTF-8"))]
    (is (= "hey" (String. ^bytes (get-in r [:Key :k :B]))))
    (is (= 3 (:Limit r)))
    (is (not (contains? r :TableName))))
  (is (= {} (ddb/request-from-body nil)))
  (is (thrown? clojure.lang.ExceptionInfo (ddb/request-from-body (.getBytes "[]" "UTF-8")))))

(deftest sqs-pure
  (is (= ["k" "a=b"] (sqs/parse-attr "k=a=b")))
  (is (thrown? clojure.lang.ExceptionInfo (sqs/parse-attr "novalue")))
  (let [ctx {:deadline (.plusMillis (java.time.Instant/now) 30000)}]
    (is (= 20 (sqs/wait-seconds ctx {:directives []})))
    (is (= 2 (sqs/wait-seconds ctx {:directives [{:name "wait" :value "1500ms"}]})) "rounded up")
    (is (= 0 (sqs/wait-seconds ctx {:directives [{:name "wait" :value "0s"}]}))))
  (is (<= (sqs/wait-seconds {:deadline (.plusMillis (java.time.Instant/now) 5000)} {:directives []}) 4)
      "never beyond the attempt's deadline"))

(deftest target-forms
  (let [ctx {:resolve-target (fn [k] (case k "queue/a" "real-name" "queue/m" {:x 1}))}
        t (fn [s] (sa/target-value ctx {:target s}))]
    (is (= "real-name" (t "queue/a")))
    (is (= "bare" (t "bare")))
    (is (= "http://h:1/000/q" (t "http://h:1/000/q")))
    (is (= "arn:aws:sqs:r:1:q" (t "arn:aws:sqs:r:1:q")))
    (is (nil? (t nil)))
    (is (thrown? clojure.lang.ExceptionInfo (t "queue/m")))))

;; --- against the runtime -------------------------------------------------------------------------

(deftest aws-scenario-fixture
  (when-runtime
   (let [{:keys [ws env]} *rt*
         res (run-scn ws env (slurp (io/file "test/fixtures/aws.scn")))]
     (spit (io/file "target" "aws.result.txt") (:text res))
     (is (= :ok (:status res)) (:text res))
     (is (= 12 (:steps res)))
     (is (= 0 (:failed-checks res)))
     (testing "every call keeps a request and a response, and the step file IS the invoke! blob"
       (doseq [n (range 1 13)]
         (let [st (step-edn res n)]
           (is (= :ok (:status st)) (str n))
           (is (some? (:request st)) (str "request of step " n))
           (is (some? (:response st)) (str "response of step " n))
           (let [m (blob-meta ws res n (:response st))]
             (is (= :aws (:source m)) (str n (pr-str m)))
             (is (= :response (:role m)) (str "invoke!'s meta won the dedup: step " n))))))
     (testing "the correlation reaches the wire"
       (is (str/includes? (step-file res 1 "request.edn") (:correlation (step-edn res 1))))
       (is (str/includes? (step-file res 5 "response.json") (:correlation (step-edn res 4))) (step-file res 5 "response.json")))
     (testing "lambda: request is the payload, log tail section present"
       (is (= "{\"hello\": \"world\"}\n" (step-file res 6 "request.json")))
       (is (str/includes? (step-file res 7 "display.txt") "--- log tail ---"))))))

(deftest peek-and-delete
  (when-runtime
   (let [{:keys [ws env]} *rt*
         res (run-scn ws env
                      (str "### sqs send queue/in\n# @attr a=1\n# @attr b=two words\nmsg-1\n"
                           "### sqs receive queue/in peek1\n# @keep\n# @wait 1s\n# @assert count == 1\n# @assert $[0] contains msg-1\n"
                           "### sqs receive queue/in peek2\n# @keep\n# @wait 1s\n# @assert count == 1\n"
                           "### sqs receive queue/in take\n# @wait 1s\n# @assert count == 1\n"
                           "### sqs receive queue/in empty\n# @wait 1s\n# @assert count == 0\n"
                           "### sqs send http://localhost:" floci-port "/000000000000/dlbtest-in by-url\nmsg-2\n"
                           "### sqs receive arn:aws:sqs:eu-central-1:000000000000:dlbtest-in by-arn\n# @wait 1s\n# @assert count == 1\n"))]
     (is (= (repeat 7 :ok) (statuses res)) (:text res))
     (let [body (json/read-str (step-file res 2 "response.json"))
           m (first (get body "Messages"))]
       (is (= "msg-1\n" (get m "Body")) "the body is byte-exact, trailing newline included")
       (is (= "1" (get-in m ["MessageAttributes" "a" "StringValue"])))
       (is (= "two words" (get-in m ["MessageAttributes" "b" "StringValue"])))
       (is (re-find #"-001$" (get-in m ["MessageAttributes" "correlation" "StringValue"])))))))

(deftest errors-are-step-errors-with-bytes-and-hints
  (when-runtime
   (let [{:keys [ws env]} *rt*
         res (run-scn ws env
                      (str "### sqs send nosuch-queue\nx\n"
                           "### lambda invoke nosuch-fn\n{}\n"
                           "### dynamodb get nosuch-table\n{\"Key\": {\"id\": {\"S\": \"1\"}}}\n"
                           "### scheduler get nosuch-sched\n"
                           "### eventbridge put bus/test\n[1]\n"
                           "### sqs send queue/in\n# @attr novalue\nx\n"
                           "### dynamodb get table/t\n{\"Key\": {\"id\": {\"S\": \"1\"}}\n"))
         err (fn [n] (:error (step-edn res n)))]
     (is (= (repeat 7 :error) (statuses res)) (:text res))
     (is (str/includes? (:message (err 1)) "QueueDoesNotExist"))
     (is (str/includes? (:hint (err 1)) (str "endpoint http://localhost:" floci-port)) (pr-str (err 1)))
     (is (str/includes? (:message (err 2)) "Function not found"))
     (is (str/includes? (:message (err 3)) "not found"))
     (is (str/includes? (:message (err 4)) "nosuch-sched"))
     (is (str/includes? (:message (err 5)) "JSON object"))
     (is (str/includes? (:message (err 6)) "bad @attr"))
     (testing "the failed call's bytes are on disk"
       (is (some? (step-file res 1 "request.edn")))
       (is (str/includes? (step-file res 1 "response.json") "QueueDoesNotExist"))
       (is (str/includes? (step-file res 2 "request.edn") "nosuch-fn"))))))

(deftest confirm-required-is-an-error
  (let [e (sa/failure {:env {:name "dev"}}
                      (with-meta {:type :confirm-required :summary "SendMessage on dev" :token "t"} {:deliberator.aws/error true}))]
    (is (str/includes? (ex-message e) "needs confirmation"))
    (is (str/includes? (:hint (ex-data e)) "environment dev is remote"))
    (is (not (str/includes? (pr-str (ex-data e)) "\"t\"")) "the token is not echoed")))

(deftest every-op-the-kinds-use-is-classified
  (doseq [[svc op cls] [[:sqs "GetQueueUrl" :read] [:sqs "SendMessage" :mutate] [:sqs "ReceiveMessage" :read]
                        [:sqs "DeleteMessageBatch" :mutate] [:eventbridge "PutEvents" :mutate]
                        [:lambda "Invoke" :mutate] [:dynamodb "GetItem" :read] [:dynamodb "Query" :read]
                        [:dynamodb "Scan" :read] [:scheduler "ListSchedules" :read] [:scheduler "GetSchedule" :read]]]
    (is (= cls (deliberator.env.ops/op-class svc op)) (str svc " " op))
    (is (deliberator.env.ops/known? svc op) (str svc " " op))))
