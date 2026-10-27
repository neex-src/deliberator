(ns deliberator.deployed-test
  "Deployed versions (M4.2), the parts that need no service: image references, the rows derived from ECS, Lambda and
  CodeDeploy answers, the text view, `show` from the cache with its age, and the redaction of environment variable
  values in the raw responses of a remote environment. The calls against the ECS mock are `deployed-estate-test`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.deployed :as d]
            [deliberator.env]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.time Instant)))

(deftest image-references-split-into-tag-and-digest
  (is (= {:image "estate/service-a:local" :tag "local"} (d/image-parts "estate/service-a:local")))
  (is (= {:image "123.dkr.ecr.eu-central-1.amazonaws.com/app:v1.2" :tag "v1.2"}
         (d/image-parts "123.dkr.ecr.eu-central-1.amazonaws.com/app:v1.2")))
  (is (= {:image "registry:5000/app" } (d/image-parts "registry:5000/app")) "a port is not a tag")
  (is (= {:image "app@sha256:abc" :digest "sha256:abc"} (d/image-parts "app@sha256:abc")))
  (is (= {:image "app:1@sha256:abc" :tag "1" :digest "sha256:abc"} (d/image-parts "app:1@sha256:abc"))))

(def td-answer
  {:taskDefinition {:taskDefinitionArn "arn:aws:ecs:eu-central-1:1:task-definition/svc:7" :family "svc" :revision 7 :status "ACTIVE"
                    :cpu "256" :memory "512"
                    :containerDefinitions [{:name "app" :image "repo/app:2026-10-26" :environment [{:name "K" :value "v"}]}]}})

(deftest an-ecs-service-row-carries-identity-and-image-never-environment
  (let [td (d/task-definition-row td-answer)
        row (d/ecs-row "prod" {:serviceName "svc" :serviceArn "arn:aws:ecs:eu-central-1:1:service/prod/svc" :status "ACTIVE"
                               :desiredCount 2 :runningCount 2 :pendingCount 0 :launchType "FARGATE"
                               :taskDefinition "arn:aws:ecs:eu-central-1:1:task-definition/svc:7"
                               :deployments [{:id "ecs-svc/1" :status "PRIMARY" :rolloutState "COMPLETED" :desiredCount 2 :runningCount 2
                                              :taskDefinition "arn:aws:ecs:eu-central-1:1:task-definition/svc:7"}]}
                       td)]
    (is (= {:kind :ecs :cluster "prod" :name "svc" :status "ACTIVE" :desired 2 :running 2 :pending 0 :revision 7 :family "svc"
            :launch-type "FARGATE"}
           (select-keys row [:kind :cluster :name :status :desired :running :pending :revision :family :launch-type])))
    (is (= [{:name "app" :image "repo/app:2026-10-26" :tag "2026-10-26"}] (:containers row)))
    (is (not (str/includes? (pr-str row) "environment")))
    (testing "without a readable task definition the revision still comes from the ARN"
      (is (= 7 (:revision (d/ecs-row "prod" {:serviceName "svc" :taskDefinition "arn:aws:ecs:eu-central-1:1:task-definition/svc:7"} nil)))))))

(deftest a-code-deploy-service-shows-its-task-sets
  (let [arn #(str "arn:aws:ecs:eu-central-1:1:task-definition/svc:" %)
        row (d/ecs-row "prod" {:serviceName "svc" :taskDefinition (arn 1) :deploymentController {:type "CODE_DEPLOY"}
                               :taskSets [{:id "ecs-svc/1" :status "PRIMARY" :taskDefinition (arn 2) :stabilityStatus "STEADY_STATE" :runningCount 0}]}
                       nil)]
    (testing "the service keeps the revision it was made with; the PRIMARY task set is the deployed one"
      (is (= 1 (:revision row)))
      (is (= [{:id "ecs-svc/1" :status "PRIMARY" :task-definition (arn 2) :revision 2 :stability "STEADY_STATE" :running 0}] (:task-sets row))))
    (testing "the text view says so in a last column"
      (is (str/includes? (d/text-view {:env "e" :fetched-at "t" :services [row]}) "\ttask sets: PRIMARY rev 2 ecs-svc/1\n")))
    (is (nil? (:task-sets (d/ecs-row "prod" {:serviceName "svc" :taskDefinition (arn 1)} nil))) "a service without task sets has no key")))

(deftest a-lambda-row-is-version-and-code-identity
  (let [row (d/lambda-row {:FunctionName "a sandbox AWS account-logger" :FunctionArn "arn:aws:lambda:eu-central-1:1:function:a sandbox AWS account-logger" :Version "$LATEST"
                           :Runtime "nodejs20.x" :Handler "index.handler" :MemorySize 128 :Timeout 10 :CodeSha256 "sha=" :CodeSize 321
                           :PackageType "Zip" :LastModified "2026-10-15T18:47:31.056+0000" :State "Active"
                           :Environment {:Variables {:SECRET "x"}}})]
    (is (= {:kind :lambda :name "a sandbox AWS account-logger" :version "$LATEST" :runtime "nodejs20.x" :code-sha256 "sha=" :state "Active"
            :last-modified "2026-10-15T18:47:31.056+0000"}
           (select-keys row [:kind :name :version :runtime :code-sha256 :state :last-modified])))
    (is (not (str/includes? (pr-str row) "SECRET")))))

(deftest ages-read-like-people-say-them
  (is (= "5 s" (d/age-text 5000)))
  (is (= "3 min" (d/age-text (* 3 60 1000))))
  (is (= "2 h 5 min" (d/age-text (* 125 60 1000))))
  (is (= "4 days" (d/age-text (* 4 86400 1000))))
  (is (= "0 s" (d/age-text -10))))

(def snap
  {:env "dev" :fetched-at (Instant/parse "2026-10-26T12:00:00Z")
   :services [{:kind :ecs :cluster "prod" :name "svc" :revision 7 :desired 2 :running 2
               :containers [{:name "app" :image "repo/app:v1" :tag "v1"}]
               :deployments [{:status "PRIMARY" :updated-at (Instant/parse "2026-10-26T11:00:00Z")}]}
              {:kind :ecs :cluster "stage" :name "svc" :revision 3 :desired 0 :running 0 :containers [] :deployments []}
              {:kind :lambda :name "fn" :version "$LATEST" :code-sha256 "abc=" :last-modified "2026-10-15T18:47:31.056+0000"}]
   :deployments [{:id "d-1" :application "app" :group "g" :status "Succeeded" :created-at (Instant/parse "2026-10-25T00:00:00Z")}]
   :errors {:codedeploy [{:op "ListDeployments" :code "AccessDenied" :message "no"}]}
   :absent {:ecs "SubscriptionRequiredException"}})

(deftest the-text-view-is-one-row-per-service
  (let [lines (str/split-lines (d/text-view snap))]
    (is (some #(= "ecs\tprod\tsvc\trev 7\tapp:v1\t2/2\t2026-10-26T11:00:00Z" %) lines))
    (is (some #(= "lambda\t-\tfn\t$LATEST\tabc=\t\t2026-10-15T18:47:31.056+0000" %) lines))
    (is (some #(str/includes? % "deployment\tapp/g\td-1\tSucceeded") lines))
    (is (some #(str/includes? % "codedeploy\tListDeployments\tAccessDenied no") lines))
    (is (some #(= "# absent: ecs (SubscriptionRequiredException)" %) lines))))

(defn- thrown-data [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest show-answers-from-the-cache-with-its-age
  (tu/with-temp-home
    (testing "nothing fetched: a hint to refresh, no call"
      (let [e (thrown-data #(d/show "w" "dev" {}))]
        (is (= :deployed/no-cache (:type e)))
        (is (str/includes? (:hint e) "dlb deployed refresh --env dev"))))
    (store/write-edn! (d/cache-path "w" "dev") snap)
    (let [now (Instant/parse "2026-10-26T12:07:00Z")
          all (d/show "w" "dev" {:now now})]
      (is (= "dev" (:env all)))
      (is (= 3 (:count all)))
      (is (= 420000 (:age-ms all)))
      (is (= "7 min" (:age all)))
      (is (= (Instant/parse "2026-10-26T12:00:00Z") (:fetched-at all)))
      (is (= 1 (count (:deployments all))))
      (is (= {:codedeploy 1} (update-vals (:errors all) count)))
      (testing "one service by name, by cluster/name"
        (is (= ["lambda"] (map (comp name :kind) (:services (d/show "w" "dev" {:service "fn" :now now})))))
        (is (= 2 (:count (d/show "w" "dev" {:service "svc" :now now}))))
        (is (= [3] (map :revision (:services (d/show "w" "dev" {:service "stage/svc" :now now}))))))
      (testing "an unknown name lists the known ones"
        (let [e (thrown-data #(d/show "w" "dev" {:service "nope" :now now}))]
          (is (= :deployed/not-found (:type e)))
          (is (= ["fn" "svc"] (:names e))))))))

(deftest raw-responses-of-a-remote-environment-lose-environment-variable-values-only
  (let [fn-body (.getBytes (json/write-str {"Functions" [{"FunctionName" "f" "Environment" {"Variables" {"DB_URL" "jdbc:x" "TOKEN" "t0k"}}}
                                                         {"FunctionName" "g"}]}) "UTF-8")
        out (json/read-str (String. (aws/redact-body :lambda "ListFunctions" {} fn-body) "UTF-8"))]
    (is (= {"DB_URL" "<redacted>" "TOKEN" "<redacted>"} (get-in out ["Functions" 0 "Environment" "Variables"])) "names stay, values go")
    (is (= "f" (get-in out ["Functions" 0 "FunctionName"])))
    (is (= {"FunctionName" "g"} (get-in out ["Functions" 1]))))
  (let [one (json/read-str (String. (aws/redact-body :lambda "GetFunctionConfiguration" {}
                                                     (.getBytes (json/write-str {"FunctionName" "f" "Environment" {"Variables" {"A" "b"}}}) "UTF-8")) "UTF-8"))]
    (is (= {"A" "<redacted>"} (get-in one ["Environment" "Variables"]))))
  (let [td (json/read-str (String. (aws/redact-body :ecs "DescribeTaskDefinition" {}
                                                    (.getBytes (json/write-str {"taskDefinition" {"family" "svc" "revision" 7
                                                                                                  "containerDefinitions" [{"name" "app" "image" "r/app:1"
                                                                                                                           "environment" [{"name" "K" "value" "v"} {"name" "L" "value" "w"}]
                                                                                                                           "secrets" [{"name" "S" "valueFrom" "arn:aws:ssm:x"}]}]}}) "UTF-8")) "UTF-8"))
        c (get-in td ["taskDefinition" "containerDefinitions" 0])]
    (is (= [{"name" "K" "value" "<redacted>"} {"name" "L" "value" "<redacted>"}] (get c "environment")))
    (is (= [{"name" "S" "valueFrom" "arn:aws:ssm:x"}] (get c "secrets")) "references are not secrets")
    (is (= "r/app:1" (get c "image")))))

(deftest a-refresh-that-nothing-answered-keeps-the-cache-it-found
  ;; a remote-shaped record whose endpoint is a closed port: every call of every section fails with a connection error
  (tu/with-temp-home
    (let [creds (doto (java.io.File/createTempFile "dlb-creds" ".ini") (.deleteOnExit))]
      (spit creds "[dead]\naws_access_key_id = test\naws_secret_access_key = test\n")
      (binding [deliberator.env/*credentials-file* creds]
        (deliberator.env/save! "default" (deliberator.env/validate
                                          {:name "dead" :kind :remote :read-only true
                                           :aws {:profile "dead" :region "eu-central-1" :account "000000000000" :endpoint "http://localhost:19"} :targets {}}))
        (store/write-edn! (d/cache-path "default" "dead") snap)
        (let [e (thrown-data #(d/refresh! "default" "dead"))]
          (is (= :deployed/refresh-failed (:type e)))
          (is (= #{:ecs :lambda :codedeploy} (set (keys (:errors e)))) "every section says why")
          (is (some? (:hint (first (first (vals (:errors e)))))) "with the environment's hint"))
        (is (= "dev" (:env (d/cached "default" "dead"))) "the old cache is untouched")))))
