(ns deliberator.deployed-image-test
  "A revision whose image is in the runtime's Podman store runs that image (cicd-aws piece 02b), against the real thing:
  Floci 2.3.0 (ECS mock) on host port 14566 and Podman (run under the suite lock). Images are built with `podman build` into the same
  store the runtime uses and told apart by what they print. A service bound to ECS service `c/s` runs as the dev container; a revision
  naming a built image replaces it within seconds (proved by the container's output and its image id), `service.dev` brings the dev
  container back and the hold survives the next polls, a further revision with another image switches again, a revision naming an
  image that is not in the store leaves the dev container and says why; a deployed image that does not answer on the service's port
  says so in its row."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.ecsfollow :as follow]
            [deliberator.ecsrecord :as rec]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.service :as service]
            [deliberator.service.description :as description]
            [deliberator.service.reload :as reload]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def ^:private floci-port 14566)

(defn- await-true [ms f]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (try (f) (catch Throwable _ false)) (- (System/currentTimeMillis) t0)
            (> (- (System/currentTimeMillis) t0) ms) nil
            :else (do (Thread/sleep 100) (recur))))))

(defn- ecs! [ws op req]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :ecs op req)]
    (when (aws/error? r) (throw (ex-info (str op " failed: " (pr-str (dissoc r :raw))) {:type :test/ecs})))
    r))

(defn- register! [ws family image]
  (ecs! ws :RegisterTaskDefinition {:family family
                                    :containerDefinitions [{:name "web" :image image :essential true
                                                            :environment [{:name "V" :value image}]}]}))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(defn- build-image!
  "`podman build` of an image that prints `word` and sleeps; -> its name. Into the store the runtime's Podman answers for."
  [dir tag word]
  (let [ctx (io/file dir (str "ctx-" word))]
    (.mkdirs ctx)
    (spit (io/file ctx "Containerfile") (str "FROM " sup/alpine-image "\nCMD [\"sh\", \"-c\", \"echo " word "; sleep 600\"]\n"))
    (let [r (sh/sh "podman" "build" "-q" "-t" tag (str ctx))]
      (when-not (zero? (:exit r)) (throw (ex-info (str "podman build failed: " (:err r)) {:type :test/build})))
      tag)))

(defn- log-text [c ctr] (str/join "\n" (map :text (podman/logs c (:Id ctr) :tail 20))))

(defn- test-config [id net]
  (-> (runtime/default-config)
      (assoc-in [:podman :endpoint] (sup/endpoint))
      (assoc :network net)
      (update :containers dissoc :postgres)
      (assoc-in [:containers :floci :ports] {4566 floci-port})
      (assoc-in [:containers :floci :name] (str net "-floci"))))

(deftest a-revision-image-in-the-store-runs
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "di-" id)
           net (str "deliberator-test-" id)
           c (podman/client (sup/endpoint) :ws ws)
           tag #(str "localhost/p02b-" id ":" %)
           role "service-web"
           ctr #(first (podman/list-containers c :all true :labels {"deliberator.workspace" ws "deliberator.role" role}))
           running? #(= "running" (:State (ctr)))
           row #(first (service/status ws "web"))
           text #(:text (:ecs (row)))
           ran? (fn [word] #(and (running?) (str/includes? (log-text c (ctr)) word)))
           watch (atom nil)
           dir (.toFile (store/path ws "build"))
           absent "registry.invalid/p02b/absent:1"]
       (store/write-edn! (runtime/config-path ws) (test-config id net))
       (try
         (runtime/ensure! ws {:infra-only true})
         (runtime/sync-env! ws)
         (description/write! ws {:name "web" :kind :container :image sup/alpine-image :port 8080 :host-port 14578
                                 :cmd ["sh" "-c" "echo dev-container; sleep 600"] :taskdef {:container "web"}
                                 :ecs {:cluster "c" :service "s"}})
         (ecs! ws :CreateCluster {:clusterName "c"})
         (register! ws "fam" absent)
         (ecs! ws :CreateService {:cluster "c" :serviceName "s" :taskDefinition "fam:1" :desiredCount 1})
         (service/start! ws "web" {})
         (reset! watch (follow/start! {:home (store/home) :log (fn [& _] nil)}))
         (follow/poke!)
         (testing "a revision whose image is not in the store: the dev container, and the row says why"
           (is (some? (await-true 4000 #(= "fam:1" (:revision (rec/read-record ws "web"))))))
           (is ((ran? "dev-container")))
           (is (= (str "revision 1 · " absent " (not used: not in the runtime's store, the description's image runs)") (text))))
         (testing "a revision naming a built image: within seconds the container runs THAT image"
           (build-image! dir (tag "a") "deployed-A")
           (register! ws "fam" (tag "a"))
           (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:2"})
           (let [ms (await-true 8000 (ran? "deployed-A"))]
             (is (some? ms))
             (is (< (or ms 99999) 6000) (str "took " ms " ms")))
           (is (= (podman/image-id c (tag "a")) (:Image (podman/inspect c (:Id (ctr))))) "podman inspect names the built image")
           (is (= (str "revision 2 · " (tag "a") " (deployed)") (text)))
           (is (str/includes? (str (:hint (:ecs (row)))) "dlb service dev web"))
           (is (nil? (:jdwp (first (filter #(= "web" (:name %)) (service/list ws))))))
           (is (:debug-refused (first (filter #(= "web" (:name %)) (service/list ws)))))
           (is (= "service/deployed-image" (:type (thrown #(reload/reload! ws "web" {})))) "service.reload says why it cannot"))
         (testing "the one action back: the dev container, and it holds across polls"
           (is (= "dev" (:mode (service/dev! ws "web" {}))))
           (is (some? (await-true 8000 (ran? "dev-container"))))
           (is (= (podman/image-id c sup/alpine-image) (:Image (podman/inspect c (:Id (ctr))))))
           (is (= (str "revision 2 · " (tag "a") " (in the runtime's store, held on the dev container until the next revision)") (text)))
           (Thread/sleep 3500)
           (is ((ran? "dev-container")) "the watch did not flip it back"))
         (testing "a further revision with another local image switches again, and clears the hold"
           (build-image! dir (tag "b") "deployed-B")
           (register! ws "fam" (tag "b"))
           (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:3"})
           (is (some? (await-true 8000 (ran? "deployed-B"))))
           (is (= (str "revision 3 · " (tag "b") " (deployed)") (text))))
         (testing "a revision whose image is absent leaves the deployed one for the dev container and says why"
           (register! ws "fam" absent)
           (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:4"})
           (is (some? (await-true 8000 (ran? "dev-container"))))
           (is (str/includes? (text) "not in the runtime's store")))
         (testing "an older revision registered earlier is deployed again by UpdateService"
           (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:2"})
           (is (some? (await-true 8000 (ran? "deployed-A"))))
           (is (rec/deployed? (rec/read-record ws "web"))))
         (finally
           (when-let [f @watch] (f))
           (try (runtime/down! ws) (catch Exception _))
           (podman/remove-network! c net)
           (doseq [t ["a" "b"]] (sh/sh "podman" "rmi" "-f" (tag t)))))))))

(deftest a-deployed-image-that-does-not-answer-says-which-port
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "dp-" id)
           net (str "deliberator-test-" id)
           c (podman/client (sup/endpoint) :ws ws)
           img (str "localhost/p02b-port-" id ":a")
           role "service-web"
           ctr #(first (podman/list-containers c :all true :labels {"deliberator.workspace" ws "deliberator.role" role}))
           watch (atom nil)
           dir (.toFile (store/path ws "build"))]
       (store/write-edn! (runtime/config-path ws) (test-config id net))
       (try
         (runtime/ensure! ws {:infra-only true})
         (runtime/sync-env! ws)
         (description/write! ws {:name "web" :kind :container :image sup/alpine-image :port 8080 :host-port 14579 :health "/"
                                 :cmd ["sh" "-c" "while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok' | nc -l -p 8080 >/dev/null; done"] :taskdef {:container "web"}
                                 :ecs {:cluster "c" :service "s"}})
         (ecs! ws :CreateCluster {:clusterName "c"})
         (register! ws "fam" "registry.invalid/p02b/absent:1")
         (ecs! ws :CreateService {:cluster "c" :serviceName "s" :taskDefinition "fam:1" :desiredCount 1})
         (service/start! ws "web" {})
         (is (= "running" (:status (first (service/status ws "web")))))
         (reset! watch (follow/start! {:home (store/home) :log (fn [& _] nil)}))
         (follow/poke!)
         (Thread/sleep 2500)
         (build-image! dir img "deployed-A")   ; sleeps, listens on nothing
         (register! ws "fam" img)
         (ecs! ws :UpdateService {:cluster "c" :service "s" :taskDefinition "fam:2"})
         (is (some? (await-true 45000 #(str/includes? (str (:error (rec/read-record ws "web"))) "must listen on 8080"))))
         (let [r (first (service/status ws "web"))]
           (is (= "unhealthy" (:status r)))
           (is (str/includes? (:detail r) "the image must listen on 8080")))
         (is (= (podman/image-id c img) (:Image (podman/inspect c (:Id (ctr))))))
         (finally
           (when-let [f @watch] (f))
           (try (runtime/down! ws) (catch Exception _))
           (podman/remove-network! c net)
           (sh/sh "podman" "rmi" "-f" img)))))))

(deftest every-kind-runs-a-deployed-image-as-a-plain-container
  (tu/with-temp-home
    (let [ws "w"
          cfg {:network "n" :containers {} :services {"api" {:ports {8082 14590}}}}]
      (ops/call! "workspace.open" {} {:ws ws})
      (description/write! ws {:name "api" :kind :spring-boot :module "/nowhere" :port 8082 :jdwp 5006 :image sup/alpine-image :image-kind :plain
                              :health "/actuator/health" :taskdef {:container "api" :environment {"A" "dev"}} :ecs {:cluster "c" :service "api"}})
      (is (= (description/read ws "api") (rec/overlay ws (description/read ws "api"))) "no record: the description as it is")
      (rec/write-record! ws "api" {:environment {"A" "rev"} :secrets {"S" "/x/y"} :revision "fam:7" :image "localhost/api:1" :image-id "sha256:abc"
                                   :command ["run"] :entrypoint ["/bin/app"] :deployed true})
      (let [d (first (runtime/desired-all ws cfg))]
        (is (= "localhost/api:1" (:image d)) "a Spring service too runs the revision's image")
        (is (= {8082 14590} (:ports d)) "the service's port on its allotted host port, no JDWP port")
        (is (= {"A" "rev"} (:env d)))
        (is (= {"S" "/x/y"} (:secrets d)))
        (is (= ["run"] (:command d)))
        (is (= ["/bin/app"] (:entrypoint d)))
        (is (= {:http "/actuator/health" :port 8082} (:health d)))
        (is (nil? (:jar-path d)))
        (testing "another image id recreates (a rebuilt tag)"
          (rec/write-record! ws "api" (assoc (rec/read-record ws "api") :image-id "sha256:def"))
          (is (not= (:hash d) (:hash (first (runtime/desired-all ws cfg))))))))))
