(ns deliberator.events-test
  "`deliberator.events` and `deliberator.console`: the pure parts, and `events.map|who|feeds|sample` against Floci through a remote-shaped environment (needs a
  running Floci, `DLB_TEST_FLOCI_URL`, SKIP otherwise): a bus with three rules (one DISABLED, one a schedule) and a queue target, the map and its text,
  who listens for an event (matched here, and asked of the service), the other way round, a sample run as a PutEvents that the queue receives."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.console :as console]
            [deliberator.env :as env]
            [deliberator.events :as ev]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.remote-util :as ru]
            [deliberator.store :as store]))

(deftest target-kinds-and-names-come-from-the-arn
  (is (= ["lambda" "orders-fn"] ((juxt ev/target-kind ev/target-name) "arn:aws:lambda:eu-central-1:1:function:orders-fn")))
  (is (= ["sqs" "inbox"] ((juxt ev/target-kind ev/target-name) "arn:aws:sqs:eu-central-1:1:inbox")))
  (is (= ["states" "flow"] ((juxt ev/target-kind ev/target-name) "arn:aws:states:eu-central-1:1:stateMachine:flow")))
  (is (= ["events" "other-bus"] ((juxt ev/target-kind ev/target-name) "arn:aws:events:eu-central-1:1:event-bus/other-bus")))
  (is (= ["api-destination" "hook"] ((juxt ev/target-kind ev/target-name) "arn:aws:events:eu-central-1:1:api-destination/hook/abc"))))

(deftest a-pattern-is-one-readable-line
  (is (= "detail-type: MaintenanceRequested · source: acme.ops" (ev/pattern-line "{\"detail-type\":[\"MaintenanceRequested\"],\"source\":[\"acme.ops\"]}" nil))
      "the line the IDE shows, in the order of the pattern")
  (is (= "source: a | b · detail-type: Order*" (ev/pattern-line "{\"source\":[\"a\",\"b\"],\"detail-type\":[{\"prefix\":\"Order\"}]}" nil)))
  (is (= "detail.{state: not stopped · qty: numeric > 3}" (ev/pattern-line "{\"detail\":{\"state\":[{\"anything-but\":\"stopped\"}],\"qty\":[{\"numeric\":[\">\",3]}]}}" nil)))
  (is (= "rate(5 minutes)" (ev/pattern-line nil "rate(5 minutes)")))
  (is (= "any of 2" (ev/pattern-line "{\"$or\":[{\"a\":[1]},{\"b\":[2]}]}" nil))))

(deftest the-event-of-a-question-is-json-or-a-detail-type-and-a-source
  (is (= {"source" "s" "detail-type" "d" "detail" {"k" 1}} (ev/event-of {:source "s" :detail-type "d" :detail "{\"k\":1}"})))
  (is (= {"source" "s" "detail-type" "d" "detail" {}} (ev/event-of {:event "{\"source\":\"s\",\"detail-type\":\"d\"}"})))
  (is (= :events/no-event (:type (try (ev/event-of {}) (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(deftest console-links-per-kind
  (let [aws {:region "eu-central-1" :account "000000000000"}
        l #(console/link aws %1 %2)]
    (is (= "https://eu-central-1.console.aws.amazon.com/ecs/v2/clusters/acme/services/acme-billing/health?region=eu-central-1" (l "ecs-service" "acme/acme-billing")))
    (is (= "https://eu-central-1.console.aws.amazon.com/ecs/v2/task-definitions/acme-billing/12/containers?region=eu-central-1" (l "task-definition" "acme-billing:12")))
    (is (= "https://eu-central-1.console.aws.amazon.com/lambda/home?region=eu-central-1#/functions/acme-maintenance-down" (l "lambda" "acme-maintenance-down")))
    (is (= "https://eu-central-1.console.aws.amazon.com/cloudwatch/home?region=eu-central-1#logsV2:log-groups/log-group/$252Facme$252Fbilling" (l "log-group" "/acme/billing")))
    (is (= "https://eu-central-1.console.aws.amazon.com/events/home?region=eu-central-1#/eventbus/acme-bus" (l "bus" "acme-bus")))
    (is (= "https://eu-central-1.console.aws.amazon.com/events/home?region=eu-central-1#/eventbus/acme-bus/rules/acme-maintenance-requested" (l "rule" "acme-bus/acme-maintenance-requested")))
    (is (= "https://eu-central-1.console.aws.amazon.com/events/home?region=eu-central-1#/eventbus/default/rules/r" (l "rule" "r")))
    (is (str/includes? (l "queue" "q") "#/queues/https%3A%2F%2Fsqs.eu-central-1.amazonaws.com%2F000000000000%2Fq"))
    (is (= "https://eu-central-1.console.aws.amazon.com/dynamodbv2/home?region=eu-central-1#table?name=t" (l "table" "t")))
    (is (str/includes? (l "parameter" "/neex/x") "/systems-manager/parameters/%2Fneex%2Fx/description?region=eu-central-1"))
    (is (= :console/bad-kind (:type (try (l "nope" "x") (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest the-sign-in-session-can-only-read
  (let [stmts (get console/read-only-policy "Statement")
        allowed (set (get (first stmts) "Action")) denied (set (get (second stmts) "Action"))]
    (is (= "Allow" (get (first stmts) "Effect")))
    (is (every? #(not (re-find #"Put|Create|Delete|Update|Invoke|Send|Start[A-Z]|Run|Stop|Tag[A-Z]" %)) (remove #{"logs:StartLiveTail"} allowed)) "no allowed action writes")
    (is (contains? denied "secretsmanager:GetSecretValue"))
    (is (contains? denied "ssm:GetParameter*"))
    (is (= console/session-seconds 900))))

(deftest a-local-environment-has-no-console-and-a-record-says-how-it-signs-in
  (ru/with-remote-at-floci [remote local]
    (is (= :console/no-console (:type (try (console/url "default" "local" {:kind "lambda" :name "x"}) (catch clojure.lang.ExceptionInfo e (ex-data e))))))
    (is (= :remote (:kind (env/validate (assoc remote :console {:signin true})))))
    (is (seq (env/problems (assoc remote :console {:signin "yes"}))) "signin is a boolean")
    (is (seq (env/problems (assoc (fu/local-env) :console {:signin true}))) "the emulator has no console")
    (testing "the plain link without a sign-in key, and with one a note until reveal asks"
      (let [r (console/url "default" (:name remote) {:kind "bus" :name "b"})]
        (is (str/starts-with? (:url r) "https://eu-central-1.console.aws.amazon.com/events/home")) (is (false? (:signin r)))
        (is (nil? (:signin-url r))))
      (env/save! "default" (assoc remote :name "signs" :console {:signin true}))
      (let [r (console/url "default" "signs" {:kind "bus" :name "b"})]
        (is (true? (:signin r))) (is (nil? (:signin-url r))) (is (str/includes? (:note r) "reveal"))))))

;; --- against Floci ------------------------------------------------------------------------------------------------

(def ^:private bus "p05t-bus")

(defn- ok! [r what] (when (aws/error? r) (throw (ex-info (str what ": " (or (:message r) (:code r))) {:aws r}))) r)

(deftest-floci map-who-feeds-and-sample-against-floci
  (ru/with-remote-at-floci [remote local]
    (let [inv (fn [svc op req] (aws/invoke! {:ws "default"} local svc op req))
          rn (:name remote)]
      (doseq [r ["p05t-orders" "p05t-off" "p05t-tick"]] (inv :eventbridge :RemoveTargets {:Rule r :EventBusName bus :Ids ["q1"]}) (inv :eventbridge :DeleteRule {:Name r :EventBusName bus}))
      (inv :eventbridge :DeleteEventBus {:Name bus})
      (ok! (inv :eventbridge :CreateEventBus {:Name bus}) "CreateEventBus")
      (let [q (:QueueUrl (ok! (inv :sqs :CreateQueue {:QueueName "p05t-inbox"}) "CreateQueue"))
            qarn (get-in (ok! (inv :sqs :GetQueueAttributes {:QueueUrl q :AttributeNames ["QueueArn"]}) "attrs") [:Attributes :QueueArn])]
        (try
          (ok! (inv :eventbridge :PutRule {:Name "p05t-orders" :EventBusName bus :EventPattern "{\"source\":[\"p05.orders\"],\"detail-type\":[{\"prefix\":\"Order\"}]}" :Description "orders"}) "PutRule")
          (ok! (inv :eventbridge :PutRule {:Name "p05t-off" :EventBusName bus :EventPattern "{\"source\":[\"p05.orders\"]}" :State "DISABLED"}) "PutRule")
          (ok! (inv :eventbridge :PutRule {:Name "p05t-tick" :EventBusName bus :ScheduleExpression "rate(5 minutes)"}) "PutRule")
          (ok! (inv :eventbridge :PutTargets {:Rule "p05t-orders" :EventBusName bus :Targets [{:Id "q1" :Arn qarn}]}) "PutTargets")
          (testing "map: the bus, three rules, the target, the text"
            (let [m (ev/map! "default" rn {:bus bus})]
              (is (= {:buses 1 :rules 3 :targets 1} (select-keys m [:buses :rules :targets])))
              (let [t (slurp (str (ev/text-path "default" rn)))]
                (is (str/includes? t (str "rule\t" bus "\tp05t-orders\tENABLED\tsource: p05.orders · detail-type: Order*")))
                (is (str/includes? t (str "rule\t" bus "\tp05t-off\tDISABLED")))
                (is (str/includes? t (str "target\t" bus "\tp05t-orders\tsqs\tp05t-inbox"))))
              (is (= "ENABLED" (get-in (ev/show "default" rn) [:buses 0 :rules 1 :state])))))
          (testing "who: from the cached map with its age; the DISABLED rule is not listening; the schedule rule never matches"
            (let [w (ev/who "default" rn {:detail-type "OrderPlaced" :source "p05.orders" :bus bus})]
              (is (true? (:cached w))) (is (string? (:age w)))
              (is (= [["p05t-off" false] ["p05t-orders" true]] (map (juxt :rule :listening) (:matches w))))
              (is (= "p05t-inbox" (-> w :matches second :targets first :name))))
            (is (= ["p05t-off"] (map :rule (:matches (ev/who "default" rn {:detail-type "Other" :source "p05.orders" :bus bus})))) "only the DISABLED rule that matches any p05.orders event"))
          (testing "who asked of the service agrees with matching here"
            (let [w (ev/who "default" rn {:detail-type "OrderPlaced" :source "p05.orders" :bus bus :ask true})]
              (is (true? (:asked w))) (is (= ["p05t-off" "p05t-orders"] (map :rule (:matches w)))) (is (nil? (:notes w)))))
          (testing "feeds: the rules that deliver to the queue, by name and by ARN"
            (is (= ["p05t-orders"] (map :rule (:matches (ev/feeds "default" rn {:target "p05t-inbox"})))))
            (is (= ["p05t-orders"] (map :rule (:matches (ev/feeds "default" rn {:target qarn}))))))
          (testing "sample: a step that matches its rule, and the event it puts reaches the queue"
            (let [s (ev/sample "default" rn {:rule "p05t-orders" :bus bus})]
              (is (true? (:self-check s)))
              (is (str/starts-with? (:step s) (str "### eventbridge put " bus "\n# @source p05.orders\n# @detail-type Ordersample\n")))
              (is (.isFile (java.io.File. ^String (:path s))))
              (ok! (inv :eventbridge :PutEvents {:Entries [{:EventBusName bus :Source (:source s) :DetailType (:detail-type s) :Detail (json/write-str (:detail s))}]}) "PutEvents")
              (let [msgs (loop [n 0] (let [r (:Messages (inv :sqs :ReceiveMessage {:QueueUrl q :WaitTimeSeconds 1}))] (if (or (seq r) (> n 8)) r (recur (inc n)))))]
                (is (seq msgs) "the sampled event was delivered to the target")
                (is (str/includes? (str (:Body (first msgs))) "Ordersample")))))
          (finally
            (inv :eventbridge :RemoveTargets {:Rule "p05t-orders" :EventBusName bus :Ids ["q1"]})
            (doseq [r ["p05t-orders" "p05t-off" "p05t-tick"]] (inv :eventbridge :DeleteRule {:Name r :EventBusName bus}))
            (inv :eventbridge :DeleteEventBus {:Name bus})
            (inv :sqs :DeleteQueue {:QueueUrl q})))))))
