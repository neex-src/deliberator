(ns deliberator.ci-local-estate-test
  "M5.4 end to end on the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`, `DELIBERATOR_HOME`; a SKIP line without them): the estate's own
  workflow `estate/.github/workflows/deploy.yml`, job `deploy`, runs with `act` on the sandbox's Podman against the estate's Floci. The `build` job
  it needs builds service A's image through the Podman socket; `deploy` registers a new task definition revision of `estate-service-a` with
  that image and drives a CodeDeploy deployment of the service (controller CODE_DEPLOY) to the end. Checked from the outside with
  `deployed.refresh` / `deployed.show` of the `local` environment: a new PRIMARY task set on a new revision, a Succeeded deployment, and
  the image tag of the new revision is the commit the workflow ran at."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.deployed :as deployed]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]))

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :cancelled? (constantly false) :daemon {:runs (atom {}) :checked (atom #{}) :follows (atom 0)}}
              args)))

(defn- service-of [snap]
  (first (filter #(and (= :ecs (:kind %)) (= "estate-service-a" (:name %))) (:services snap))))

(defn- primary [svc] (first (filter #(= "PRIMARY" (:status %)) (:task-sets svc))))

(deftest the-estates-workflow-deploys-service-a-through-codedeploy
  (if-let [ws (fu/estate-ws)]
    (let [root (.getCanonicalPath (io/file ".."))
          sha (str/trim (:out (sh/sh "git" "rev-parse" "HEAD" :dir root)))
          tag (subs sha 0 12)
          sess (str "CILOCAL-" (System/currentTimeMillis))
          _ (call "session.start" {:ws ws :key sess})
          _ (call "deployed.refresh" {:ws ws :env "local"})
          before (service-of (deployed/cached ws "local"))
          events (atom [])
          r (call "ci.local" {:ws ws :repo root :workflow "estate/.github/workflows/deploy.yml" :session sess} events)
          text (slurp (:path r))]
      (is (some? before) "the estate's service A is in the local environment (terraform applied)")
      (testing "the pipeline ran green, both jobs, output kept as text and streamed"
        (is (= "success" (:conclusion r)) (str (:path r) "\n" (str/join "\n" (take-last 25 (str/split-lines text)))))
        (is (re-find #"deployment d-\w+: Succeeded" text))
        (is (str/includes? text (str "estate/service-a:" tag)))
        (is (= (count (str/split-lines text)) (count (filter #(= "logs.line" (:event %)) @events))))
        (is (.isFile (io/file (str (deliberator.store/raw-path ws (:sha r)))))))
      (testing "the image was built through Podman's socket"
        (is (podman/image-exists? (podman/client (podman/default-endpoint)) (str "estate/service-a:" tag))))
      (testing "deployed.refresh/show see a new PRIMARY task set on a new task definition revision"
        (call "deployed.refresh" {:ws ws :env "local"})
        (let [shown (call "deployed.show" {:ws ws :env "local"})
              svc (first (filter #(= "estate-service-a" (:name %)) (:services shown)))
              ts (primary svc)
              ts0 (primary before)]
          (is (= "CODE_DEPLOY" (:controller svc)))
          (is (some? ts) (pr-str (:task-sets svc)))
          (is (= 1 (count (filter #(= "PRIMARY" (:status %)) (:task-sets svc)))))
          (is (not= (:id ts0) (:id ts)) "a new task set")
          (is (> (:revision ts) (or (:revision ts0) 0)) "on a new revision")
          (is (some #(and (= "Succeeded" (:status %)) (= "estate" (:application %))) (:deployments shown)))
          (testing "the new revision runs the image the workflow built"
            (let [e (env/load ws "local")
                  td (:taskDefinition (aws/invoke! {:ws ws} e :ecs :DescribeTaskDefinition {:taskDefinition (:task-definition ts)}))]
              (is (= (str "estate/service-a:" tag) (:image (first (:containerDefinitions td)))))
              (is (= "estate-service-a" (:family td)))))))
      (testing "act left nothing behind"
        (is (empty? (filter #(get (:Labels %) (keyword "deliberator.ci-run"))
                            (podman/list-containers (podman/client (podman/default-endpoint)) :all true))))))
    (fu/skip! "the-estates-workflow-deploys-service-a-through-codedeploy" "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
