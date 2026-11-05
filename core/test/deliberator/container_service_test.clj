(ns deliberator.container-service-test
  "The `:container` service kind: any image as a workspace service. The description rules and the derived spec
  need no runtime; the lifecycle runs on the real Podman (the JDK web server on the plain JRE image, a mounted directory of a fixture
  repository) through the same ops a Spring service uses: adopt, list, start, status, logs, topology off and local, stop,
  restart. Without Podman the live test reports a :skip; nothing is mocked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- call
  ([op args] (call op args nil))
  ([op args events] (ops/call! op {:emit! (fn [e] (when events (swap! events conj e)))} args)))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(def good {:name "site" :kind :container :image "docker.io/library/alpine:3.20" :port 8080
           :cmd ["busybox" "httpd" "-f" "-p" "8080" "-h" "/site"] :env {"GREETING" "hello"} :health "/health"
           :mounts [{:from "site" :to "/site"} {:from "out" :to "/out" :rw true}] :aliases ["site.localhost"]})

(deftest the-description-rules
  (is (= good (description/validate good)))
  (is (description/runnable? good))
  (is (not (description/buildable? good)))
  (is (not (description/reloadable? good)))
  (is (and (description/buildable? {:kind :node-lambda}) (not (description/runnable? {:kind :node-lambda}))))
  (is (and (description/buildable? {:kind :spring-boot}) (description/reloadable? {:kind :spring-boot})))
  (testing "what a container cannot have, and what each key must be"
    (doseq [[why bad] [["no image" (dissoc good :image)]
                       ["no port" (dissoc good :port)]
                       ["jdwp" (assoc good :jdwp 5005)]
                       ["build" (assoc good :build {:cmd ["x"]})]
                       ["main" (assoc good :main "a.B")]
                       ["cmd not a vector" (assoc good :cmd "httpd -f")]
                       ["cmd not strings" (assoc good :cmd ["httpd" 8080])]
                       ["env value not a string" (assoc good :env {"A" 1})]
                       ["mount out of the repository" (assoc good :mounts [{:from "../etc" :to "/x"}])]
                       ["mount absolute" (assoc good :mounts [{:from "/etc" :to "/x"}])]
                       ["mount to not absolute" (assoc good :mounts [{:from "site" :to "x"}])]
                       ["mount rw not a boolean" (assoc good :mounts [{:from "site" :to "/x" :rw "yes"}])]
                       ["health without a slash" (assoc good :health "health")]
                       ["aliases not a vector" (assoc good :aliases "a")]
                       ["host-port not a number" (assoc good :host-port "80")]]]
      (is (= "service/invalid" (:type (thrown #(description/validate bad)))) why))))

(deftest the-derived-spec
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (let [root (tu/temp-home)]
      (call "repo.attach" {:ws "w" :path (str root)})
      (let [d (assoc good :repo (.getName root) :module "deploy")
            spec (description/->spec "w" d)]
        (is (= {:image "docker.io/library/alpine:3.20" :ports {8080 8080} :env {"GREETING" "hello"} :service? true
                :health {:http "/health" :port 8080} :command ["busybox" "httpd" "-f" "-p" "8080" "-h" "/site"] :aliases ["site.localhost"]
                :mounts [{:source (str (io/file root "site")) :destination "/site" :options ["ro" "z"]}
                         {:source (str (io/file root "out")) :destination "/out" :options ["rw" "z"]}]}
               spec)
            "read-only unless :rw, sources relative to the repository, the port published on itself")
        (is (= {8080 9999} (:ports (description/->spec "w" (assoc d :host-port 9999)))) "a pinned host port")
        (is (= {} (:health (description/->spec "w" (dissoc d :health)))) "no health path: no probe")
        (testing "the runtime role: a service, aliases and mounts in the hash, aliases in the libpod body"
          (let [cfg {:network "n" :containers {}}
                _ (description/write! "w" d)
                role (first (runtime/desired-all "w" cfg))
                _ (description/write! "w" (assoc d :aliases ["other.localhost"]))
                moved (first (runtime/desired-all "w" cfg))
                body (#'runtime/container-spec "w" role nil)]
            (is (= "service-site" (:role role)))
            (is (true? (:service? role)))
            (is (= {8080 8080} (:ports role)))
            (is (= {:http "/health" :port 8080} (:health role)))
            (is (= {"n" {"aliases" ["site.localhost"]}} (get body "Networks")))
            (is (not= (:hash role) (:hash moved)) "another alias recreates the container")
            (is (= {} (get-in (#'runtime/container-spec "w" (assoc role :aliases nil) nil) ["Networks" "n"])) "no aliases, no key")))
        (testing "an unattached repository does not break the status of the others: the path says what is wrong"
          (is (str/includes? (get-in (description/->spec "w" (assoc d :repo "nowhere")) [:mounts 0 :source]) "not-attached")))))))

(deftest build-and-reload-are-refused-with-the-kind-named
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (description/write! "w" (assoc good :module "/m" :image-hint "bb image in the sling repository"))
    (let [b (thrown #(call "service.build" {:ws "w" :name "site"}))
          r (thrown #(call "service.reload" {:ws "w" :name "site"}))]
      (is (= "service/not-buildable" (:type b)))
      (is (str/includes? (:message b) "container"))
      (is (= "bb image in the sling repository" (:hint b)))
      (is (= "service/not-reloadable" (:type r)))
      (is (str/includes? (:message r) "container")))
    (testing "JDWP is not a thing for a container: adopting with a jdwp port is refused"
      (let [m (io/file (tu/temp-home) "m")]
        (.mkdirs m)
        (spit (io/file m "deliberator-service.edn") (pr-str (dissoc good :name :aliases)))
        (is (= "service/invalid" (:type (thrown #(call "service.adopt" {:ws "w" :path (str m) :name "x" :jdwp 5005})))))))))

;; --- the real Podman ---------------------------------------------------------------------------

(defn- http-get [url]
  (let [r (.send (HttpClient/newHttpClient) (.build (HttpRequest/newBuilder (URI. url))) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (.body r)}))

(defn- sh-in [c container & argv] (podman/exec! c container (vec argv)))

(deftest a-container-is-a-service-like-any-other
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "cs-" id)
           port (sup/free-port)
           pin (sup/free-port)
           root (tu/temp-home)
           module (io/file root "deploy")
           desc (fn [m] (assoc m :name "site" :kind :container :image description/plain-image :port port :health "/health"
                               :cmd ["sh" "-c" (str "echo booted-$GREETING; exec jwebserver -b 0.0.0.0 -p " port " -d /site")]
                               :env {"GREETING" "hello"} :mounts [{:from "site" :to "/site"}] :aliases ["site.localhost"]
                               :image-hint "build the fixture image"))
           write-module! (fn [m] (spit (io/file module "deliberator-service.edn") (pr-str (desc m))))
           events (atom [])
           c (podman/client (sup/endpoint))]
       (.mkdirs module)
       (.mkdirs (io/file root "site"))
       (spit (io/file root "site" "health") "ok")
       (spit (io/file root "site" "data.txt") "from the repository\n")
       (write-module! {})
       (store/write-edn! (runtime/config-path ws) {:podman {:endpoint (sup/endpoint)} :network (str "deliberator-cs-" id) :containers {}})
       (call "workspace.open" {:ws ws})
       (call "repo.attach" {:ws ws :path (str root)})
       (try
         (testing "adopt: the description lands in the workspace, the repository and module are filled in"
           (let [r (call "service.adopt" {:ws ws :path (str module)})]
             (is (= {:name "site" :created true :repo (.getName root) :module "deploy" :from "file"}
                    (select-keys r [:name :created :repo :module :from])))))
         (testing "service.list shows it before anything runs"
           (let [row (first (call "service.list" {:ws ws}))]
             (is (= {:name "site" :kind :container :mode "local" :status "absent" :image description/plain-image :container-port port}
                    (select-keys row [:name :kind :mode :status :image :container-port])))
             (is (= "log/service-site" (:log-target row)))))
         (testing "a mount that is not there is said by name, before Podman is asked to create anything"
           (spit (io/file module "deliberator-service.edn") (pr-str (assoc (desc {}) :mounts [{:from "nope" :to "/site"}])))
           (call "service.adopt" {:ws ws :path (str module)})
           (let [e (thrown #(call "service.start" {:ws ws :name "site"}))]
             (is (= "runtime/missing-mount" (:type e)))
             (is (str/includes? (:message e) "nope")))
           (write-module! {})
           (call "service.adopt" {:ws ws :path (str module)}))
         (testing "start: created, healthy through its health path, ports allocated like a Spring service's, never a JDWP port"
           (let [r (call "service.start" {:ws ws :name "site"} events)
                 hp (:port r)]
             (is (= "running" (:status r)))
             (is (some #(= "service.started" (:event %)) @events))
             (is (some #(= "runtime.ready" (:event %)) @events))
             (is (= [port] (keys (get-in (runtime/read-config ws) [:services "site" :ports]))) "only the container port is allocated")
             (is (= {:status 200 :body "ok"} (http-get (str "http://127.0.0.1:" hp "/health"))))
             (testing "the mounted directory of the repository is what the container serves"
               (is (= "from the repository\n" (:body (http-get (str "http://127.0.0.1:" hp "/data.txt"))))))
             (testing "read-only unless :rw"
               (is (pos? (:exit (sh-in c (:container r) "touch" "/site/x")))))
             (testing "env, and the alias is a name of the runtime network's DNS: the container's own address, not a loopback"
               (is (= "hello" (str/trim (:stdout (sh-in c (:container r) "sh" "-c" "echo $GREETING")))))
               (let [a (sh-in c (:container r) "getent" "hosts" "site.localhost")]
                 (is (zero? (:exit a)))
                 (is (re-find #"^\d+\.\d+\.\d+\.\d+\s+site\.localhost" (:stdout a)))
                 (is (not (str/starts-with? (:stdout a) "127.")) "the network's answer; a client that special-cases *.localhost never asks")))
             (testing "status, show and list agree"
               (is (= "healthy" (:health (first (call "service.status" {:ws ws :name "site"})))))
               (is (= "running" (:status (call "service.show" {:ws ws :name "site"}))))
               (is (= {:status "running" :mode "local" :port hp} (select-keys (first (call "service.list" {:ws ws})) [:status :mode :port]))))
             (testing "the log is mirrored under logs/services/"
               (Thread/sleep 500)
               (let [lines (doall (runtime/logs ws "service-site" {:tail 50}))
                     f (first (filter #(.isFile %) (file-seq (.toFile (store/path ws "logs" "services" "service-site")))))]
                 (is (some #(str/includes? (:text %) "booted-hello") lines))
                 (is (some? f) "a log file for the role")
                 (is (str/includes? (slurp f) "booted-hello"))))))
         (testing "topology off stops it (the container is kept), start is refused; local brings it back"
           (call "topology.set" {:ws ws :env "local" :name "site" :mode "off"})
           (call "runtime.ensure" {:ws ws})
           (let [row (first (call "service.list" {:ws ws}))]
             (is (= "off" (:mode row)))
             (is (= "off" (:status row))))
           (is (= "topology/service-not-local" (:type (thrown #(call "service.start" {:ws ws :name "site"})))))
           (call "topology.set" {:ws ws :env "local" :name "site" :mode "local"})
           (is (= "running" (:status (call "service.start" {:ws ws :name "site"})))))
         (testing "stop keeps the container, start reuses it, restart recreates nothing when nothing changed"
           (let [before (:container (call "service.show" {:ws ws :name "site"}))
                 s (call "service.stop" {:ws ws :name "site"})
                 row (first (call "service.status" {:ws ws :name "site"}))]
             (is (= "stopped" (:status s)))
             (is (= "stopped" (:status row)))
             (is (= "running" (:status (call "service.start" {:ws ws :name "site"}))))
             (is (= "running" (:status (call "service.restart" {:ws ws :name "site"}))))
             (is (= before (:container (call "service.show" {:ws ws :name "site"}))))))
         (testing "a pinned :host-port is the published one, and a changed description recreates the container"
           (write-module! {:host-port pin})
           (call "service.adopt" {:ws ws :path (str module)})
           (runtime/set-config! ws [:services] {})   ; the port the runtime allocated earlier is runtime.edn's word, and it wins over the description
           (let [r (call "service.restart" {:ws ws :name "site"})]
             (is (= pin (:port r)))
             (is (= "ok" (:body (http-get (str "http://127.0.0.1:" pin "/health")))))
             (is (= {:port pin :container-port port} (select-keys (first (call "service.list" {:ws ws})) [:port :container-port])))))
         (finally
           (try (runtime/down! ws) (catch Exception _ nil))
           (try (podman/remove-network! c (str "deliberator-cs-" id)) (catch Exception _ nil))))))))
