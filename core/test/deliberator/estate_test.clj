(ns deliberator.estate-test
  "The runtime's `:service` role and the estate behind it.

  Pure: `taskdef->service`, the desired state of a `:jar` role (command, mounts, hash, secrets kept
  out of the environment), `adopt-taskdefs!` writing `runtime.edn` and bending `local.edn`.
  Against Floci on :4566: `resolve-secrets` reads SSM parameters and secrets by ARN and by name.
  Against a running estate (`bb estate:up --ws W`): set `ESTATE_WS=W` and `DELIBERATOR_HOME`
  (the test then drives the whole chain with the targets of W's `local` environment; without
  `ESTATE_WS` it prints a SKIP line). The URL question is part of it: Floci hands back
  `http://localhost:<port>/…` queue URLs that a container cannot dial, and the services, which
  talk to `http://deliberator-floci:4566`, still work."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :refer [deftest-floci]]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util UUID)))

(def container-def
  {"name" "service-a" "image" "x"
   "portMappings" [{"containerPort" 8081}]
   "environment" [{"name" "AWS_ENDPOINT_URL" "value" "http://deliberator-floci:4566"}
                  {"name" "AWS_REGION" "value" "eu-central-1"}]
   "secrets" [{"name" "TABLE_NAME" "valueFrom" "arn:aws:ssm:eu-central-1:000000000000:parameter/estate/common/table-name"}
              {"name" "API_KEY" "valueFrom" "arn:aws:secretsmanager:eu-central-1:000000000000:secret:estate/api-key-AbCdEf"}]})

(deftest taskdef-to-service
  (let [s (runtime/taskdef->service container-def {:jar "a.jar" :debug 5005 :java-opts ["-Xmx256m"]})]
    (is (= {8081 8081 5005 5005} (:ports s)))
    (is (= 5005 (:debug s)))
    (is (= "http://deliberator-floci:4566" (get-in s [:env "AWS_ENDPOINT_URL"])))
    (is (= {"API_KEY" "arn:aws:secretsmanager:eu-central-1:000000000000:secret:estate/api-key-AbCdEf"
            "TABLE_NAME" "arn:aws:ssm:eu-central-1:000000000000:parameter/estate/common/table-name"}
           (:secrets s)))
    (is (str/includes? (:image s) "eclipse-temurin:21-jre"))))

(defn- jar-config [root]
  {:network "n" :root (str root)
   :containers {:service/a (assoc (runtime/taskdef->service container-def {:jar "svc/a.jar" :debug 5005 :java-opts ["-Xmx256m"]})
                                  :image "img:1")}})

(deftest jar-role-desired-state
  (let [root (tu/temp-home)
        jar (io/file root "svc" "a.jar")]
    (io/make-parents jar)
    (spit jar "one")
    (let [cfg (jar-config root)
          d (first (runtime/desired-all "w" cfg))]
      (testing "command, mount, health"
        (is (= ["java" "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" "-Xmx256m" "-jar" "/app/app.jar"]
               (:command d)))
        (is (some #(and (= "/app/app.jar" (:destination %)) (= (str jar) (:source %)) (some #{"ro"} (:options %))) (:mounts d)))
        (is (= {:http "/actuator/health" :port 8081} (:health d)))
        (is (= "service-a" (:role d)))
        (is (= "deliberator-service-a" (:name d))))
      (testing "secret references are in the spec, their values never in the environment"
        (is (= 2 (count (:secrets d))))
        (is (not (contains? (:env d) "API_KEY"))))
      (testing "a rebuilt jar changes the hash, an untouched one does not"
        (is (= (:hash d) (:hash (first (runtime/desired-all "w" cfg)))))
        (spit jar "two, longer")
        (is (not= (:hash d) (:hash (first (runtime/desired-all "w" cfg))))))
      (testing "a changed secret reference recreates, the status of a missing jar does not throw"
        (is (not= (:hash d) (:hash (first (runtime/desired-all "w" (assoc-in cfg [:containers :service/a :secrets "API_KEY"] "other"))))))
        (.delete jar)
        (is (some? (:hash (first (runtime/desired-all "w" cfg)))))))))

(deftest adopt-taskdefs-writes-roles-and-bends-local-env
  (tu/with-temp-home
    (store/write-edn! (runtime/config-path "w")
                      (-> (runtime/default-config)
                          (assoc-in [:containers :floci :ports] {4566 14566})
                          (assoc-in [:containers :postgres :ports] {5432 15432})))
    (let [cfg (runtime/adopt-taskdefs! "w" {:root "/repo" :postgres-init "/repo/estate/sql"
                                            :services {"a" {:taskdef (json/write-str [container-def]) :jar "x/a.jar" :debug 5005}}})
          e (env/load "w" "local")]
      (is (= "/repo" (:root cfg)))
      (is (= "/repo/estate/sql" (get-in cfg [:containers :postgres :init])))
      (is (= #{:floci :postgres :service/a} (set (keys (:containers cfg)))))
      (is (= cfg (store/read-edn (runtime/config-path "w"))))
      (is (= "http://localhost:14566" (get-in e [:aws :endpoint])))
      (is (= "jdbc:postgresql://localhost:15432/deliberator" (get-in e [:targets :db/main])))
      (is (= "http://localhost:8081" (get-in e [:targets :service/a]))))))

(deftest-floci resolve-secrets-by-arn-and-name
  (tu/with-temp-home
    (let [local (env/load "w" "local")
          id (subs (str (UUID/randomUUID)) 0 8)
          p (str "/dlbtest/" id "/param")
          s (str "dlbtest/" id "/secret")]
      (try
        (is (not (aws/error? (aws/invoke! {:ws "w"} local :ssm :PutParameter {:Name p :Value "pv" :Type "String"}))))
        (let [r (aws/invoke! {:ws "w"} local :secretsmanager :CreateSecret {:Name s :SecretString "{\"k\":\"sv\"}"})]
          (is (not (aws/error? r)))
          (is (= {"P1" "pv" "P2" "pv" "S1" "{\"k\":\"sv\"}" "S2" "{\"k\":\"sv\"}" "S3" "sv"}
                 (runtime/resolve-secrets
                  "w" {"P1" (str "arn:aws:ssm:eu-central-1:000000000000:parameter" p)
                       "P2" p
                       "S1" (:ARN r)
                       "S2" s
                       "S3" (str (:ARN r) ":k::")})))
          (let [e (try (runtime/resolve-secrets "w" {"X" "/dlbtest/nope"}) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :runtime/secret-unresolved (:type (ex-data e))))))
        (finally
          (aws/invoke! {:ws "w"} local :ssm :DeleteParameter {:Name p})
          (aws/invoke! {:ws "w"} local :secretsmanager :DeleteSecret {:SecretId s :ForceDeleteWithoutRecovery true})
          (env/close-clients!))))))

;; --- the running estate ----------------------------------------------------------------------

(def ^:private http (delay (HttpClient/newHttpClient)))

(defn- http-req [method url headers]
  (let [b (HttpRequest/newBuilder (URI. url))
        _ (doseq [[k v] headers] (.header b k v))
        _ (.method b method (HttpRequest$BodyPublishers/noBody))
        r (.send ^HttpClient @http (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (.body r)}))

(defn- until [timeout-ms f]
  (let [end (+ (System/currentTimeMillis) timeout-ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 300) (recur))))))

(deftest estate-end-to-end
  (if-let [ws (System/getenv "ESTATE_WS")]
    (let [e (env/load ws "local")
          a (env/target e :service/a)
          corr (str "corr-" (UUID/randomUUID))
          id (str "ord-" (subs corr 5 13))
          ctx {:ws ws}
          q (aws/invoke! ctx e :sqs :GetQueueUrl {:QueueName (env/target e :queue/inbox)})
          item #(let [r (aws/invoke! ctx e :dynamodb :GetItem {:TableName (env/target e :table/messages) :Key {:id {:S id}}})]
                  (:Item r))
          rt (runtime/status ws)
          pg (some #(when (= "postgres" (:role %)) (:name %)) (:containers rt))
          c (podman/client (get-in (runtime/read-config ws) [:podman :endpoint]) :ws ws)]
      (is (every? #(= :healthy (:health %)) (:containers rt)) (pr-str (map (juxt :role :health) (:containers rt))))
      (testing "Floci hands back a URL the services' containers cannot dial, and they work anyway"
        (is (re-find #"^http://localhost:\d+/" (:QueueUrl q)) (pr-str q)))
      (is (not (aws/error? (aws/invoke! ctx e :sqs :SendMessage
                                        {:QueueUrl (:QueueUrl q) :MessageBody "{\"order\":1}"
                                         :MessageAttributes {"id" {:DataType "String" :StringValue id}
                                                             "correlation" {:DataType "String" :StringValue corr}}}))))
      (is (until 20000 item) "A stored the inbox message in DynamoDB")
      (is (= corr (get-in (item) [:correlation :S])))
      (is (= 200 (:status (http-req "POST" (str a "/publish/" id) {"X-Correlation-Id" corr}))))
      (is (until 30000 #(= "ACKED" (get-in (item) [:status :S]))) "B acked to A")
      (is (= "ACKED" (get (json/read-str (:body (http-req "GET" (str a "/messages/" id) {}))) "status")))
      (testing "the ack is a row in Postgres"
        (is (until 10000 #(let [r (podman/exec! c pg ["psql" "-U" "deliberator" "-tAc"
                                                      (str "select distinct correlation from acks where message_id='" id "'")])]
                            (= corr (str/trim (:stdout r)))))))
      (testing "the Lambda logged the event, correlation included"
        (is (until 30000 #(seq (:events (aws/invoke! ctx e :logs :FilterLogEvents
                                                     {:logGroupName "/aws/lambda/estate-logger"
                                                      :filterPattern (str "\"" corr "\"")}))))))
      (testing "the correlation id is on the service log lines of the chain"
        (doseq [[role n] [[:service/a 3] [:service/b 3]]]
          (let [lines (until 10000 #(let [ls (filter (fn [l] (str/includes? (:text l) corr))
                                                     (doall (runtime/logs ws role {})))]
                                      (when (>= (count ls) n) ls)))]
            (is (>= (count lines) n) (str role " lines with " corr))
            (is (every? #(re-find #"\[corr-[0-9a-f-]+\]" (:text %)) lines))))))
    (println "SKIPPED estate-end-to-end: set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
