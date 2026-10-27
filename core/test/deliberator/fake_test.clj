(ns deliberator.fake-test
  "M6.6 against real containers (no estate): an upstream API (babashka) and the HTTP fake (babashka) on a throwaway network, reconciled by the
  real runtime. Record through the fake, scrub-commit into a directory standing for a repository, remove the upstream, replay the committed
  copy: a request with a NEW id is answered by the template match, a request nobody recorded is a 404; then the topology mode fake."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.fake :as fake]
            [deliberator.ops :as ops]
            [deliberator.fakes :as fakes]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.topology :as topology]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def upstream-script (.getCanonicalPath (io/file (System/getProperty "user.dir") "test" "deliberator" "fake_upstream.bb")))

(defn- http [method url & {:keys [body headers]}]
  (let [b (-> (HttpRequest/newBuilder (URI. url)) (.timeout (Duration/ofSeconds 10)))
        _ (doseq [[k v] headers] (.header b k v))
        r (.build (.method b method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody))))
        resp (.send (HttpClient/newHttpClient) r (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)
     :headers (into {} (for [[k vs] (.map (.headers resp))] [(str/lower-case k) (first vs)]))}))

(defn- config [id upstream?]
  {:podman {:endpoint (sup/endpoint)} :network (str "deliberator-test-" id)
   :containers (if upstream?
                 {:upstream {:image fakes/default-image :command ["bb" "/app/up.bb"] :ports {8080 (sup/free-port)}
                             :mounts [{:source upstream-script :destination "/app/up.bb" :options ["ro" "z"]}]
                             :health {:http "/health" :port 8080}}}
                 {})})

(defn- cleanup! [ws cfg]
  (try (runtime/down! ws) (catch Throwable _ nil))
  (try (podman/remove-network! (runtime/podman-client ws) (:network cfg)) (catch Throwable _ nil)))

(deftest record-scrub-commit-replay-and-the-topology-mode
  (when-podman
   (tu/with-temp-home
     (let [id (sup/rand-id)
           ws (str "fk" id)
           cfg (config id true)
           repo (io/file (str (store/home)) "some-repo")
           events (atom [])]
       (.mkdirs repo)
       (store/write-edn! (runtime/config-path ws) cfg)
       (runtime/sync-env! ws)
       (try
         (let [up (str "http://" (store/container-name ws "upstream") ":8080")
               rec (fake/record! ws "a" up {:session "T-1" :sink #(swap! events conj %)})
               url (:url rec)
               uuid "8d4c2f10-1111-4222-8333-444455556666"]
           (testing "record: the fake is a container on the network, its health answers, the recording dir is under the session"
             (is (= "record" (:mode rec)))
             (is (str/includes? (:recording rec) "/sessions/T-1/fakes/a/"))
             (is (some #(= "fake.started" (:event %)) @events))
             (is (= "record" (:mode (json/read-str (:body (http "GET" (str url "/__fake/health"))) :key-fn keyword)))))
           (testing "through the fake: the upstream answers, every exchange is on disk raw before the answer is used"
             (let [r (http "POST" (str url "/ack/ord-" uuid) :body "{\"who\":\"jane.doe@example.com\"}"
                           :headers {"X-Api-Key" "secret-123" "Content-Type" "application/json"})
                   r2 (http "GET" (str url "/messages/m-12345?x=1"))]
               (is (= 200 (:status r)))
               (is (str/includes? (:body r) "ACKED"))
               (is (= 200 (:status r2)))
               (let [files (fakes/exchange-files (:recording rec))
                     ex (json/read-str (slurp (first files)) :key-fn keyword)]
                 (is (= 2 (count files)))
                 (is (= "secret-123" (get-in ex [:request :headers :x-api-key])) "the recording is raw: scrubbing is for the copy")
                 (is (= "/ack/ord-{}" (get-in ex [:match :path-template])))
                 (is (= 200 (get-in ex [:response :status])))
                 (is (= "sid=abc; Path=/" (get-in ex [:response :headers :set-cookie]))))))
           (testing "fake.list shows the recording with its exchange count"
             (let [l (first (fake/list ws))]
               (is (= "a" (:name l)))
               (is (= 2 (:exchanges l)))
               (is (= 1 (count (:recordings l))))))
           (testing "commit: a scrubbed copy in the repository, the raw one untouched"
             (let [c (fake/commit! ws "a" (str repo) {})
                   copy (slurp (io/file repo "fakes" "a" "00001.json"))]
               (is (= 2 (:exchanges c)))
               (is (not-any? #(str/includes? copy %) ["secret-123" "jane.doe@example.com" "Jane Doe" "sid=abc"]))
               (is (str/includes? copy "user-1@example.invalid"))
               (is (str/includes? (slurp (io/file repo "fakes" "a" "scrub-report.txt")) "email:"))
               (is (not (str/includes? (slurp (io/file repo "fakes" "a" "scrub-report.txt")) "jane.doe@example.com")))
               (is (str/includes? (slurp (first (fakes/exchange-files (:recording rec)))) "secret-123"))))
           (testing "replay of the committed copy while the upstream is gone"
             (store/write-edn! (runtime/config-path ws) (config id false))
             (runtime/remove-role! ws "upstream")
             (let [rp (fake/replay! ws "a" {:from (str (io/file repo "fakes" "a"))})
                   new-id "aaaaaaaa-2222-4333-8444-555566667777"
                   r (http "POST" (str (:url rp) "/ack/ord-" new-id) :body "{}")]
               (is (= "replay" (:mode rp)))
               (is (= 2 (:exchanges rp)))
               (is (= 200 (:status r)) "a new id is answered by the template of the recorded path")
               (is (= "template" (get-in r [:headers "x-fake-match"])))
               (is (str/includes? (:body r) "ACKED"))
               (is (= "exact" (get-in (http "POST" (str (:url rp) "/ack/ord-" uuid)) [:headers "x-fake-match"])))
               (let [miss (http "GET" (str (:url rp) "/never-recorded"))]
                 (is (= 404 (:status miss)))
                 (is (= "none" (get-in miss [:headers "x-fake-match"])))
                 (is (str/includes? (:body miss) "no recording matches")))))
           (testing "stop removes the container and keeps the declaration"
             (let [r (fake/stop! ws "a" {})]
               (is (true? (:stopped r)))
               (is (= :stopped (:mode (fakes/read ws "a"))))
               (is (nil? (try (http "GET" (str url "/__fake/health")) (catch Exception _ nil)))))))
         (finally (cleanup! ws cfg)))))))

;; the topology mode fake, without containers -----------------------------------------------------------

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(deftest topology-mode-fake
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (description/write! "w" {:name "a" :kind :spring-boot :main "m.Main" :port 8081 :module "/m/a" :build {:artifact "a.jar"}})
    (description/write! "w" {:name "b" :kind :spring-boot :main "m.Main" :port 8082 :module "/m/b" :build {:artifact "b.jar"}
                             :depends {"SERVICE_A_URL" "a"}
                             :taskdef {:secrets {"SERVICE_A_URL" "/p/a-url" "API_KEY" "/p/key"}}})
    (testing "an undeclared fake is a typed error with the way out"
      (let [e (try (call "topology.set" {:ws "w" :env "local" :name "a" :mode "fake"}) (catch clojure.lang.ExceptionInfo e e))]
        (is (= :fake/not-found (:type (ex-data e))))
        (is (str/includes? (:hint (ex-data e)) "dlb fake record a"))))
    (fakes/write! "w" {:name "a" :mode :record :port 18090 :upstream "http://u:1" :recording "/r"})
    (let [t (call "topology.set" {:ws "w" :env "local" :name "a" :mode "fake"})
          ent (get-in (env/load "w" "local") [:topology "a"])]
      (testing "the entry keeps the fake's urls; show and resolution follow it"
        (is (= {:mode :fake :fake "a" :url "http://localhost:18090" :container-url "http://deliberator-w-fake-a:8080"} ent))
        (is (= "fake" (get-in t [:services "a" :mode])))
        (is (= "http://localhost:18090" (env/target (env/load "w" "local") :service/a))))
      (testing "while the fake records the real service stays; the dependent calls the fake, not the secret"
        (is (empty? (topology/not-local "w")))
        (is (= :local (topology/container-mode "w" "a")))
        (let [b (get (runtime/declared-containers "w" (runtime/default-config)) :service/b)]
          (is (= "http://deliberator-w-fake-a:8080" (get-in b [:env "SERVICE_A_URL"])))
          (is (not (contains? (:secrets b) "SERVICE_A_URL")))
          (is (contains? (:secrets b) "API_KEY"))))
      (testing "while the fake replays the real service is not desired"
        (fakes/write! "w" {:name "a" :mode :replay :port 18090 :recording "/r"})
        (is (= #{"a"} (topology/not-local "w")))
        (is (= :fake (topology/container-mode "w" "a")))
        (is (not (contains? (runtime/effective-containers "w" (runtime/default-config)) :service/a)))
        (is (contains? (runtime/effective-containers "w" (runtime/default-config)) :fake/a))
        (is (= :replay (get-in (topology/snapshot "w" (env/load "w" "local")) [:services "a" :serving]))))
      (testing "a hand-written entry is validated"
        (is (seq (env/problems (assoc (env/load "w" "local") :topology {"a" {:mode :fake}}))))
        (is (empty? (env/problems (env/load "w" "local"))))))))

(defn- thrown-type [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest typed-errors-before-anything-is-touched
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (is (= :fake/bad-upstream (thrown-type #(fake/record! "w" "a" "ftp://x" {:session "S"}))))
    (is (= :service/not-found (thrown-type #(fake/record! "w" "a" "service/ghost" {:session "S"}))))
    (is (= :fake/no-recording (thrown-type #(fake/replay! "w" "a" {}))) "nothing recorded yet")
    (is (= :fake/no-recording (thrown-type #(fake/replay! "w" "a" {:from "20200101-000000"}))))
    (let [empty-dir (io/file (str (store/home)) "empty-recording")]
      (.mkdirs empty-dir)
      (is (= :fake/empty-recording (thrown-type #(fake/replay! "w" "a" {:from (str empty-dir)})))))
    (is (= :fake/not-found (thrown-type #(fake/stop! "w" "ghost" {}))))
    (is (= :fake/no-recording (thrown-type #(fake/commit! "w" "a" "/tmp" {}))))
    (is (= :session/none (:type (try (call "fake.record" {:ws "w" :name "a" :upstream "http://u:1"}) (catch clojure.lang.ExceptionInfo e (ex-data e)))))
        "the op needs a session: the recording lives under it")
    (is (empty? (fakes/names "w")) "none of it declared a fake")))
