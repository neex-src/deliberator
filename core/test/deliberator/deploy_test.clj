(ns deliberator.deploy-test
  "`deliberator.deploy`, the parts that need no AWS: the rows of a deployment, which tasks and events are its own, where a container's log
  lines are, the text, the failing rule, the fallback without the service-deployment calls, and the lookup from a commit (the seam
  `ci.*` calls). The calls against the ECS mock are `deploy-estate-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.deploy :as d]
            [deliberator.deployed :as deployed]
            [deliberator.remote-util :as ru]
            [deliberator.store :as store])
  (:import (java.time Instant)))

(defn- i [s] (Instant/parse s))

(def dep {:id "ecs-svc/2" :source :service-deployments :status "SUCCESSFUL" :rollout-state "COMPLETED"
          :started (i "2026-11-15T10:00:00Z") :finished (i "2026-11-15T10:00:30Z")})

(defn- task [id by & {:as m}]
  (merge {:id id :started-by by :last-status "RUNNING" :created (i "2026-11-15T10:00:05Z") :started (i "2026-11-15T10:00:05Z")
          :task-definition (str "arn:aws:ecs:eu-central-1:1:task-definition/svc:2") :revision 2 :containers [{:name "svc"}]} m))

(deftest a-service-deployment-status-stands-for-a-rollout-state
  (is (= "COMPLETED" (d/rollout-of-status "SUCCESSFUL")))
  (is (= "IN_PROGRESS" (d/rollout-of-status "IN_PROGRESS")))
  (is (= "FAILED" (d/rollout-of-status "ROLLBACK_SUCCESSFUL")))
  (is (= "FAILED" (d/rollout-of-status "STOPPED")))
  (is (nil? (d/rollout-of-status "SOMETHING_NEW")))
  (is (= "ecs-svc/8081" (d/deployment-id "arn:aws:ecs:eu-central-1:1:service-revision/p04/p04-service-a/8081"))))

(deftest the-commit-comes-from-the-tag_else_from_a_commit_like_image_tag
  (is (= {:git-sha "abc1234" :commit-source "task definition tag git-sha" :run-id "99"}
         (d/commit-of {"git-sha" "abc1234" "run-id" "99"} [{:tag "latest"}])))
  (is (= {:git-sha "0123456789ab" :commit-source "image tag"} (d/commit-of {} [{:tag "0123456789ab"}])))
  (is (= {} (d/commit-of {} [{:tag "latest"}])) "a tag that is not a commit says nothing"))

(deftest the-tasks-of-a-deployment-are-the-ones-it-started-and-the-ones-it-replaced
  (let [now (i "2026-11-15T10:10:00Z")
        mine (task "t-new" "ecs-svc/2")
        other (task "t-other" "ecs-svc/3" :created (i "2026-11-15T10:05:00Z"))
        old-in (task "t-old" "ecs-svc/1" :last-status "STOPPED" :stopped (i "2026-11-15T10:00:20Z") :stop-code "ServiceSchedulerInitiated"
                     :stopped-reason "Scaling activity initiated by deployment ecs-svc/2")
        old-out (task "t-old2" "ecs-svc/1" :last-status "STOPPED" :stopped (i "2026-11-15T09:00:00Z") :stop-code "ServiceSchedulerInitiated"
                      :stopped-reason "Scaling activity initiated by deployment ecs-svc/1")
        by-window (task "t-old3" "ecs-svc/1" :last-status "STOPPED" :stopped (i "2026-11-15T10:00:10Z") :stop-code "ServiceSchedulerInitiated"
                        :stopped-reason "Service deployment replaced task definition x")
        crashed (task "t-crash" "ecs-svc/1" :last-status "STOPPED" :stopped (i "2026-11-15T10:00:10Z") :stop-code "EssentialContainerExited")
        {:keys [started replaced]} (d/tasks-of dep [mine other old-in old-out by-window crashed] now)]
    (is (= ["t-new"] (map :id started)) "startedBy decides; a later deployment's task is not ours")
    (is (= #{"t-old" "t-old3"} (set (map :id replaced))) "stopped by the scheduler inside the window; one that names another deployment, or crashed, is not")))

(deftest the-events-of-a-deployment-are-the-service-events-inside-its-window
  (let [ev (fn [at msg] {:createdAt (i at) :message msg :id msg})
        evs [(ev "2026-11-15T09:59:00Z" "before") (ev "2026-11-15T10:00:01Z" "(service svc) has started 1 tasks")
             (ev "2026-11-15T10:00:33Z" "(service svc) has reached a steady state.") (ev "2026-11-15T10:05:00Z" "after")
             (ev "2026-11-15T10:20:00Z" "late: deployment ecs-svc/2 completed")]]
    (is (= ["(service svc) has started 1 tasks" "(service svc) has reached a steady state." "late: deployment ecs-svc/2 completed"]
           (map :message (d/events-in evs dep (i "2026-11-15T10:30:00Z")))))
    (testing "a running rollout's window is open"
      (is (= ["after" "late: deployment ecs-svc/2 completed"] (map :message (d/events-in evs (assoc dep :started (i "2026-11-15T10:04:00Z") :finished nil) (i "2026-11-15T10:05:05Z"))))))))

(deftest a-container-log_is_found_through_the_task_definitions_log_configuration
  (let [t (task "abcdef0123456789" "ecs-svc/2")
        tds {(:task-definition t) {:containers [{:name "svc" :log {:driver "awslogs" :options {:awslogs-group "/p/svc" :awslogs-stream-prefix "ecs"}}}
                                                {:name "side" :log {:driver "json-file"}} {:name "bare"}]}}
        ss (d/log-sources [t] tds false nil)]
    (is (= {:kind :cloudwatch :group "/p/svc" :stream "ecs/svc/abcdef0123456789"} (select-keys (first ss) [:kind :group :stream])))
    (is (= [:none :none] (map :kind (rest ss))))
    (is (str/includes? (:reason (second ss)) "json-file"))
    (testing "options keyed by strings work too, and a local environment prefers the runtime container of the bound service"
      (is (= "ecs/svc/abcdef0123456789"
             (:stream (first (d/log-sources [t] {(:task-definition t) {:containers [{:name "svc" :log {:driver "awslogs" :options {"awslogs-group" "/p/svc" "awslogs-stream-prefix" "ecs"}}}]}} false nil)))))
      (is (= {:kind :container :container "deliberator-w-billing"}
             (select-keys (first (d/log-sources [t] tds true {"svc" "deliberator-w-billing"} )) [:kind :container])) "(a map is callable like the resolver)"))))

(deftest a-rollout-that-keeps-starting-tasks-that-die-is-failing
  (let [dead (fn [n] (task (str "d" n) "ecs-svc/2" :last-status "STOPPED"))]
    (is (not (d/failing? [(dead 1) (dead 2)])))
    (is (d/failing? [(dead 1) (dead 2) (dead 3)]))
    (is (not (d/failing? [(dead 1) (dead 2) (dead 3) (task "up" "ecs-svc/2")])) "a task that runs is not failing")))

(deftest the-deploy-text-has-the-header_events_tasks_and_the_log_lines_with_their_container
  (let [t (task "abcdef0123456789" "ecs-svc/2" :containers [{:name "svc" :last-status "RUNNING"}])
        old (task "00000000ffffffff" "ecs-svc/1" :last-status "STOPPED" :stopped (i "2026-11-15T10:00:20Z") :stop-code "ServiceSchedulerInitiated"
                  :stopped-reason "replaced" :revision 1 :containers [{:name "svc" :last-status "STOPPED" :exit-code 143}])
        text (d/render-show {:env "aws" :cluster "c" :service "svc" :source :service-deployments :notes ["a note"] :event-count 100
                             :row (assoc dep :revision-before 1 :revision-after 2 :image-before "svc:v1" :image-after "svc:v2" :family "svc"
                                         :git-sha "0123456789ab" :commit-source "task definition tag git-sha" :run-id "42")
                             :events [{:at (i "2026-11-15T10:00:01Z") :message "(service svc) has started 1 tasks: (task abcdef01)."}]
                             :started [t] :replaced [old] :fetched-at (i "2026-11-15T10:01:00Z")
                             :log {:entries [{:at (i "2026-11-15T10:00:06Z") :container "svc" :task "abcdef01" :text "Started App in 3.1 seconds"}] :notes []}})]
    (doseq [want ["deploy       c/svc   ecs-svc/2" "status       SUCCESSFUL   rollout COMPLETED" "revision     1 -> 2   (svc:1 -> svc:2)"
                  "image        svc:v1 -> svc:v2" "commit       0123456789ab   (task definition tag git-sha)" "ci run       42"
                  "note         a note" "== events (1 inside the window; the service keeps its last 100)"
                  "(service svc) has started 1 tasks" "== tasks (started by this deployment: 1; replaced by it: 1)"
                  "replaced 00000000" "exit 143" "== log (1 lines" "[svc/abcdef01] Started App in 3.1 seconds"]]
      (is (str/includes? text want) want))
    (is (not (str/includes? text "old task line")))))

(deftest without-the-service-deployment-calls-the-services-own-deployments-are-the-rows
  (let [td (fn [arn] (when arn {:arn arn :family "svc" :tags {"git-sha" (str "sha-of-" (peek (str/split arn #":")))} :containers [{:name "svc" :tag (peek (str/split arn #":"))}]}))]
    (with-redefs [d/td-info! (fn [_ arn] (td arn))]
      (let [rows (#'d/fallback-rows! {}
                                      {:deployments [{:id "ecs-svc/1" :status "INACTIVE" :taskDefinition "x:svc:1" :rolloutState "COMPLETED" :createdAt (i "2026-11-15T09:00:00Z") :updatedAt (i "2026-11-15T09:05:00Z")}
                                                     {:id "ecs-svc/2" :status "PRIMARY" :taskDefinition "x:svc:2" :rolloutState "IN_PROGRESS" :createdAt (i "2026-11-15T10:00:00Z") :updatedAt (i "2026-11-15T10:00:10Z")
                                                      :rolloutStateReason "ECS deployment ecs-svc/2 in progress."}]})]
        (is (= ["ecs-svc/2" "ecs-svc/1"] (map :id rows)) "newest first")
        (is (= {:source :describe-services :rollout-state "IN_PROGRESS" :revision-before 1 :revision-after 2 :image-before "svc:1" :image-after "svc:2"}
               (select-keys (first rows) [:source :rollout-state :revision-before :revision-after :image-before :image-after])))
        (is (nil? (:finished (first rows))) "a rollout in progress has no end yet")
        (is (= (i "2026-11-15T09:05:00Z") (:finished (second rows))))
        (is (= "2" (str (:revision-after (first rows)))))))))

(deftest the-commit-lookup-reads-the-caches_without_a_call
  (ru/with-remote-at-floci [remote local]
    (let [ws "default" env-name (:name remote)]
      (store/write-edn! (deployed/cache-path ws env-name)
                        {:env env-name :fetched-at (i "2026-11-15T10:00:00Z")
                         :services [{:kind :ecs :cluster "acme" :name "acme-billing" :revision 12 :status "ACTIVE" :git-sha "0123456789abcdef" :run-id "77"
                                     :deployments [{:updated-at (i "2026-11-15T10:00:00Z")}]}
                                    {:kind :ecs :cluster "acme" :name "acme-audit" :revision 3 :status "ACTIVE" :git-sha "ffffffffffff"}
                                    {:kind :lambda :name "fn"}]})
      (store/write-edn! (.resolveSibling (d/list-path ws env-name "acme" "acme-billing") "list.edn")
                        {:rows [{:env env-name :cluster "acme" :service "acme-billing" :id "ecs-svc/9" :status "SUCCESSFUL" :revision-after 11
                                 :started (i "2026-11-14T10:00:00Z") :git-sha "0123456789abcdef" :run-id "70"}
                                {:env env-name :cluster "acme" :service "acme-billing" :id "ecs-svc/10" :status "SUCCESSFUL" :revision-after 12
                                 :started (i "2026-11-15T10:00:00Z") :git-sha "0123456789abcdef" :run-id "77"}
                                {:env env-name :cluster "acme" :service "acme-billing" :id "ecs-svc/8" :revision-after 10 :git-sha "eeeeeeeeeeee"}]})
      (testing "by the full commit, a prefix of it, or the commit being the prefix of the tag"
        (is (= ["ecs-svc/10" "ecs-svc/9"] (map :deployment (d/by-commit ws "0123456789abcdef"))) "the current revision is the deployment already cached, not a second row")
        (is (= 2 (count (d/by-commit ws "0123456"))))
        (is (= 2 (count (d/by-commit ws "0123456789abcdef0000")))))
      (testing "too short a sha matches nothing; another commit finds its own"
        (is (empty? (d/by-commit ws "012345")))
        (is (= ["acme-audit"] (map :service (d/by-commit ws "ffffffffffff")))))
      (is (= ["ecs-svc/10"] (map :deployment (d/by-run-id ws "77"))))
      (is (= ["deployed to dlbremote: acme-billing revision 12" "deployed to dlbremote: acme-billing revision 11"] (d/commit-notes ws "0123456789ab"))))))
