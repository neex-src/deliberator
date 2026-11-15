(ns deliberator.deploy-estate-test
  "`deploy.list`, `deploy.show` and `deploy.follow` against Floci's ECS mock: a cluster and a service made through a local record, an
  `UpdateService` to a new task definition revision, a log group written with `PutLogEvents`; then the three operations as the daemon calls
  them, on a remote-shaped environment (gated, audited, redacted) at the emulator. Needs a running Floci (`DLB_TEST_FLOCI_URL`, SKIP otherwise)."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [deliberator.aws :as aws]
            [deliberator.deploy :as d]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.remote-util :as ru]
            [deliberator.ops :as ops]))

(def ^:private cluster "p04t-deploy")
(def ^:private family "p04t-app")
(def ^:private group "/p04t/app")

(defn- ok! [r what]
  (when (aws/error? r) (throw (ex-info (str what ": " (or (:message r) (:code r))) {:aws r})))
  r)

(defn- container [image]
  [{:name "app" :image image :memory 128 :essential true
    :logConfiguration {:logDriver "awslogs" :options {"awslogs-group" group "awslogs-region" "eu-central-1" "awslogs-stream-prefix" "app"}}}])

(defn- wait-for [f what]
  (loop [n 0]
    (or (f) (if (< n 60) (do (Thread/sleep 500) (recur (inc n))) (throw (ex-info (str "timed out waiting for " what) {}))))))

(deftest-floci a-new-revision-is-shown-with-its-change_its_commit_and_the_new_containers_lines
  (ru/with-remote-at-floci [remote local]
    (let [inv (fn [svc op req] (ok! (aws/invoke! {:ws "default"} local svc op req) (name op)))
          ecs (fn [op req] (inv :ecs op req))
          td (fn [image sha run]
               (:taskDefinition (ecs :RegisterTaskDefinition {:family family :containerDefinitions (container image)
                                                                                  :tags [{:key "git-sha" :value sha} {:key "run-id" :value run}]})))
          rem-name (:name remote)]
      (aws/invoke! {:ws "default"} local :ecs :DeleteService {:cluster cluster :service "app" :force true})
      (aws/invoke! {:ws "default"} local :ecs :DeleteCluster {:cluster cluster})
      (ecs :CreateCluster {:clusterName cluster})
      (try
        (let [t1 (td "registry.example/app:aaaaaaaaaaaa" "aaaaaaaaaaaa1111" "local-1")
              td1 (:taskDefinitionArn t1)
              _ (ecs :CreateService {:cluster cluster :serviceName "app" :taskDefinition td1 :desiredCount 1 :launchType "FARGATE"
                                     :networkConfiguration {:awsvpcConfiguration {:subnets ["s-1"]}}})
              _ (wait-for #(seq (:serviceDeployments (inv :ecs :ListServiceDeployments {:cluster cluster :service "app"}))) "the first deployment")
              _ (Thread/sleep 1200)
              t2 (td "registry.example/app:bbbbbbbbbbbb" "bbbbbbbbbbbb2222" "local-2")
              td2 (:taskDefinitionArn t2)
              [r1 r2] [(:revision t1) (:revision t2)]
              _ (ecs :UpdateService {:cluster cluster :service "app" :taskDefinition td2})
              listed (wait-for #(let [r (d/list! "default" rem-name (str cluster "/app") {})]
                                  (when (and (>= (:count r) 2) (= "bbbbbbbbbbbb2222" (:git-sha (first (:rows r))))) r))
                               "the second deployment in the list")
              newest (first (:rows listed))
              tasks (wait-for #(let [ts (:tasks (ecs :DescribeTasks {:cluster cluster
                                                                      :tasks (:taskArns (ecs :ListTasks {:cluster cluster :serviceName "app"}))}))
                                     mine (filter (fn [t] (= (:id newest) (:startedBy t))) ts)]
                                 (when (seq mine) ts))
                              "the new task")
              new-task (some #(when (= (:id newest) (:startedBy %)) %) tasks)
              old-task (some #(when (not= (:id newest) (:startedBy %)) %) tasks)
              put (fn [t msg] (let [stream (str "app/app/" (last (str/split (:taskArn t) #"/")))]
                                (aws/invoke! {:ws "default"} local :logs :CreateLogGroup {:logGroupName group})
                                (aws/invoke! {:ws "default"} local :logs :CreateLogStream {:logGroupName group :logStreamName stream})
                                (ok! (aws/invoke! {:ws "default"} local :logs :PutLogEvents
                                                  {:logGroupName group :logStreamName stream
                                                   :logEvents [{:timestamp (System/currentTimeMillis) :message msg}]}) "PutLogEvents")))]
          (put new-task "app v2 started in 1.2 seconds")
          (when old-task (put old-task "app v1 line that is not this deployment's"))
          (testing "list: newest first, the change, the commit and the run"
            (is (= {:revision-before r1 :revision-after r2 :git-sha "bbbbbbbbbbbb2222" :run-id "local-2" :source-in? true}
                   (assoc (select-keys newest [:revision-before :revision-after :git-sha :run-id]) :source-in? (= :service-deployments (:source listed)))))
            (is (str/includes? (:image-before newest) "app:aaaaaaaaaaaa"))
            (is (str/includes? (:image-after newest) "app:bbbbbbbbbbbb"))
            (is (.isFile (java.io.File. ^String (:path listed)))))
          (testing "show: ONE text with the change, the events, the tasks and the new container's line, not the old task's"
            (let [r (d/show! "default" rem-name (str cluster "/app") {})
                  body (:body r)]
              (is (= (:id newest) (:deployment r)))
              (is (str/includes? body (str r1 " -> " r2)))
              (is (str/includes? body "bbbbbbbbbbbb2222   (task definition tag git-sha)"))
              (is (str/includes? body "ci run       local-2"))
              (is (str/includes? body "== events"))
              (is (str/includes? body "== tasks (started by this deployment: 1"))
              (is (re-find #"\[app/[0-9a-f]{8}\] app v2 started in 1.2 seconds" body) "the line names its container")
              (is (not (str/includes? body "v1 line that is not")) "a replaced task's lines are not this deployment's")
              (is (= body (slurp (:path r))))
              (testing "cached: the same file with its age, no AWS"
                (let [c (d/show! "default" rem-name (str cluster "/app") {:cached true})]
                  (is (= body (:body c))) (is (:age c))))))
          (testing "follow of a finished rollout shows it and ends"
            (let [lines (atom [])
                  r (d/follow! "default" rem-name (str cluster "/app") {:emit! #(swap! lines conj (:text %)) :poll-ms 200 :timeout-ms 20000 :wait-ms 0})]
              (is (= :completed (:ended r)))
              (is (some #(str/includes? % "app v2 started") @lines))
              (is (some #(str/includes? % "== rollout COMPLETED") @lines))))
          (testing "the commit lookup finds the deployment from the cache, with no call"
            (is (= [(:id newest)] (map :deployment (d/by-commit "default" "bbbbbbbbbbbb"))))
            (is (= [(str "deployed to dlbremote: app revision " r2)] (d/commit-notes "default" "bbbbbbbbbbbb2222"))))
          (testing "through the operations, as the daemon calls them"
            (let [call (fn [op args] (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{}) :follows (atom 0)}} (assoc args :ws "default")))]
              (is (= 2 (:count (call "deploy.list" {:service (str cluster "/app") :env rem-name}))))
              (is (str/includes? (:body (call "deploy.show" {:service "app" :env rem-name :deployment (:id newest)})) "== tasks")))))
        (finally
          (aws/invoke! {:ws "default"} local :ecs :DeleteService {:cluster cluster :service "app" :force true})
          (aws/invoke! {:ws "default"} local :ecs :DeleteCluster {:cluster cluster})
          (aws/invoke! {:ws "default"} local :logs :DeleteLogGroup {:logGroupName group}))))))
