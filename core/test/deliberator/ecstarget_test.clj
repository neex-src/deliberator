(ns deliberator.ecstarget-test
  "B38: an ECS service as a target is looked up when it is used. The AWS side is a small HTTP server that speaks the ECS
  (JSON) and EC2 (query/XML) wire formats of the four calls, with a task list the test changes the way a deploy does; no emulator
  has task networking, and the real run is `a sandbox AWS account/runs/deliberator-aws-20261028-ide-real.md`."
  (:require [clojure.data.json :as json]
            [deliberator.env]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.ecstarget :as ecs]
            [deliberator.remote-util :as remote-util]
            [deliberator.scenario.engine :as engine]
            [deliberator.session :as session]
            [deliberator.steps.http]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(defn- task [arn status started eni priv]
  {"taskArn" arn "lastStatus" status "startedAt" started
   "attachments" [{"type" "ElasticNetworkInterface" "status" "ATTACHED"
                   "details" [{"name" "subnetId" "value" "subnet-1"} {"name" "networkInterfaceId" "value" eni}
                              {"name" "privateIPv4Address" "value" priv}]}]})

(defn- fake-aws
  "`state` is an atom `{:tasks [..] :public {eni ip} :ec2-denied? bool :calls []}`. -> {:server :url}."
  [state]
  (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     s "/"
     (reify HttpHandler
       (handle [_ ex]
         (let [^HttpExchange ex ex
               body (String. (.readAllBytes (.getRequestBody ex)) "UTF-8")
               target (.getFirst (.getRequestHeaders ex) "X-Amz-Target")
               [code ctype out]
               (cond
                 (and target (str/ends-with? target ".ListTasks"))
                 (do (swap! state update :calls conj :ListTasks)
                     [200 "application/x-amz-json-1.1"
                      (json/write-str {"taskArns" (vec (for [t (:tasks @state) :when (not= "STOPPED" (get t "lastStatus"))] (get t "taskArn")))})])
                 (and target (str/ends-with? target ".DescribeTasks"))
                 (do (swap! state update :calls conj :DescribeTasks)
                     [200 "application/x-amz-json-1.1" (json/write-str {"tasks" (:tasks @state) "failures" []})])
                 (str/includes? body "Action=DescribeNetworkInterfaces")
                 (do (swap! state update :calls conj :DescribeNetworkInterfaces)
                     (if (:ec2-denied? @state)
                       [403 "text/xml" "<Response><Errors><Error><Code>UnauthorizedOperation</Code><Message>You are not authorized to perform this operation.</Message></Error></Errors><RequestID>r</RequestID></Response>"]
                       (let [eni (second (re-find #"NetworkInterfaceId\.1=([^&]+)" body))
                             ip (get-in @state [:public eni])]
                         [200 "text/xml"
                          (str "<DescribeNetworkInterfacesResponse xmlns=\"http://ec2.amazonaws.com/doc/2016-11-15/\"><requestId>r</requestId><networkInterfaceSet><item>"
                               "<networkInterfaceId>" eni "</networkInterfaceId><privateIpAddress>10.0.0.9</privateIpAddress>"
                               (when ip (str "<association><publicIp>" ip "</publicIp></association>"))
                               "</item></networkInterfaceSet></DescribeNetworkInterfacesResponse>")])))
                 :else [400 "text/plain" "?"])
               bs (.getBytes ^String out "UTF-8")]
           (.add (.getResponseHeaders ex) "Content-Type" ctype)
           (.sendResponseHeaders ex code (alength bs))
           (.write (.getResponseBody ex) bs)
           (.close ex)))))
    (.start s)
    {:server s :url (str "http://127.0.0.1:" (.getPort (.getAddress s)))}))

(defn- counting-http
  "A service that answers 200 `up`."
  []
  (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext s "/" (reify HttpHandler
                            (handle [_ ex]
                              (let [^HttpExchange ex ex out (.getBytes "up" "UTF-8")]
                                (.sendResponseHeaders ex 200 (alength out))
                                (.write (.getResponseBody ex) out)
                                (.close ex)))))
    (.start s)
    s))

(def ^:private spec {:ecs {:cluster "c" :service "svc-a" :port 8081}})

(deftest the-address-is-looked-up-every-time-and-follows-a-deploy
  (remote-util/with-remote-at-floci [remote _]
    (let [state (atom {:tasks [(task "arn:t1" "RUNNING" 1.79e9 "eni-1" "10.0.0.1")]
                       :public {"eni-1" "3.3.3.1" "eni-2" "3.3.3.2" "eni-3" "3.3.3.3"} :calls []})
          {:keys [server url]} (fake-aws state)
          env (assoc-in remote [:aws :endpoint] url)
          ctx {:ws "default" :session "T-1"}]
      (try
        (is (= "http://3.3.3.1:8081" (ecs/resolve-target ctx env spec)))
        (testing "a string target is returned as it is, nothing is asked"
          (is (= "http://x:1" (ecs/resolve-target ctx env "http://x:1")))
          (is (= 3 (count (:calls @state)))))
        (testing "a deploy: the new task is running while the old one still is, the newer one wins; then only it is left"
          (swap! state assoc :tasks [(task "arn:t1" "RUNNING" 1.79e9 "eni-1" "10.0.0.1")
                                     (task "arn:t2" "RUNNING" 1.795e9 "eni-2" "10.0.0.2")
                                     (task "arn:t3" "PENDING" 0 "eni-3" "10.0.0.3")])
          (is (= "http://3.3.3.2:8081" (ecs/resolve-target ctx env spec)))
          (swap! state assoc :tasks [(task "arn:t2" "RUNNING" 1.80e9 "eni-2" "10.0.0.2")])
          (is (= "http://3.3.3.2:8081" (ecs/resolve-target ctx env spec)))
          (swap! state assoc :tasks [(task "arn:t4" "RUNNING" 1.81e9 "eni-3" "10.0.0.4")])
          (is (= "http://3.3.3.3:8081" (ecs/resolve-target ctx env spec)) "no cache: the replaced task's address is not served"))
        (testing ":address :private asks ECS only, and :format builds a JDBC URL"
          (swap! state assoc :calls [])
          (is (= "jdbc:postgresql://10.0.0.4:5432/estate?user=estate"
                 (ecs/resolve-target ctx env {:ecs {:cluster "c" :service "svc-b" :port 5432 :address :private}
                                              :format "jdbc:postgresql://{host}:{port}/estate?user=estate"})))
          (is (not (some #{:DescribeNetworkInterfaces} (:calls @state)))))
        (testing "without :port the target is the host"
          (is (= "3.3.3.3" (ecs/resolve-target ctx env {:ecs {:cluster "c" :service "s"}}))))
        (testing "no running task, an unreadable interface, no public IP: errors that say what to do"
          (swap! state assoc :tasks [])
          (let [e (try (ecs/resolve-target ctx env spec) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :ecs/target (:type (ex-data e))))
            (is (str/includes? (ex-message e) "has no running task")))
          (swap! state assoc :tasks [(task "arn:t4" "RUNNING" 1.81e9 "eni-9" "10.0.0.4")])
          (let [e (try (ecs/resolve-target ctx env spec) (catch clojure.lang.ExceptionInfo e e))]
            (is (str/includes? (ex-message e) "has no public IP") (ex-message e)))
          (swap! state assoc :ec2-denied? true)
          (let [e (try (ecs/resolve-target ctx env spec) (catch clojure.lang.ExceptionInfo e e))]
            (is (str/includes? (ex-message e) "cannot read the public address") (ex-message e))
            (is (str/includes? (str (:hint (ex-data e))) "ec2:DescribeNetworkInterfaces") (pr-str (ex-data e)))))
        (finally (.stop ^HttpServer server 0))))))

(deftest a-scenario-asks-for-the-address-when-a-step-runs
  ;; the plan only checks that the target exists: a service with no task yet does not fail the plan, its step fails (and the next run works)
  (remote-util/with-remote-at-floci [remote _]
    (let [svc (counting-http)
          state (atom {:tasks [] :public {"eni-1" "127.0.0.1"} :calls []})
          {:keys [server url]} (fake-aws state)
          env (-> remote
                  (assoc-in [:aws :endpoint] url)
                  (assoc :targets {:service/a {:ecs {:cluster "c" :service "svc-a" :port (.getPort (.getAddress svc))}}}))
          scn (io/file (str (store/home)) "scn" "a.scn")
          _ (io/make-parents scn)
          _ (spit scn "### http service/a health\n# @assert status == 200\nGET /health\n\n### http again\n# @assert bytes == 2\nGET {{service/a}}/x\n")
          run! (fn [] (session/start! "default" "T-1")
                 (let [res (engine/run! "default" env (str scn) :session "T-1"
                                        :resolve-target #(ecs/resolve-target {:ws "default" :session "T-1"} env (deliberator.env/target env (keyword %)))
                                        :check-target #(deliberator.env/target env (keyword %)))]
                   (assoc res :text (slurp (get-in res [:paths :result])))))]
      (try
        (do
          (let [r1 (run!)]
            (is (str/includes? (:text r1) "has no running task") (:text r1)))
          (swap! state assoc :tasks [(task "arn:t1" "RUNNING" 1.79e9 "eni-1" "10.0.0.1")])
          (let [r2 (run!)]
            (is (not (str/includes? (:text r2) "has no running task")) (:text r2))
            (is (str/includes? (:text r2) "RUN ok") (:text r2))))
        (finally (.stop ^HttpServer server 0) (.stop ^HttpServer svc 0))))))
