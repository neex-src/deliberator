(ns deliberator.service-test
  "Service descriptions and the `service.*` ops without a container: the description file, adopting from a
  module's deliberator-service.edn and from a Terraform task definition, the derived role, strictness about
  an unknown workspace, the two-step secrets gate. The container lifecycle is exercised against the real
  estate (`estate_test`, `bb core:test:all`, `bb acceptance`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.runtime :as runtime]
            [deliberator.service :as service]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def taskdef-json
  (json/write-str [{"name" "service-q" "image" "x" "portMappings" [{"containerPort" 9000}]
                    "environment" [{"name" "REGION" "value" "eu"}]
                    "secrets" [{"name" "KEY" "valueFrom" "/p/key"}]}]))

(defn- call [op args] (ops/call! op {:emit! (fn [_])} args))

(defn- thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(defn- module! [root name edn]
  (let [m (io/file root name)]
    (.mkdirs m)
    (when edn (spit (io/file m "deliberator-service.edn") (pr-str edn)))
    m))

(deftest unknown-workspace-is-strict-for-the-whole-family
  (tu/with-temp-home
    (doseq [[op args] [["service.list" {}] ["service.show" {:name "a"}] ["service.status" {}]
                       ["service.adopt" {:path "/x"}] ["service.build" {:name "a"}]
                       ["service.start" {:name "a"}] ["service.stop" {:name "a"}] ["service.restart" {:name "a"}] ["service.reload" {:name "a"}]]]
      (is (= "workspace/not-found" (:type (thrown-type #(call op (assoc args :ws "nope"))))) op))
    (is (not (ops/workspace-exists? "nope")))))

(deftest adopt-from-the-module-file-and-from-a-task-definition
  (tu/with-temp-home
    (let [root (tu/temp-home)
          repo-dir (io/file root "checkout")
          m1 (module! repo-dir "mod-a" {:name "a" :kind :spring-boot :main "m.Main" :port 8081 :jdwp 5005
                                         :build {:artifact "target/*.jar"}
                                         :taskdef {:module "tf" :container "service-a" :key "a"}})
          m2 (module! root "service-q" nil)
          td (io/file root "td.json")]
      (spit (io/file m2 "pom.xml") "<project/>")
      (spit td taskdef-json)
      (call "workspace.open" {:ws "w"})
      (call "repo.attach" {:ws "w" :path (str repo-dir)})
      (testing "from deliberator-service.edn: the repository and module are filled in, taskdef entries added"
        (let [r (call "service.adopt" {:ws "w" :path (str m1) :taskdef (str td)})
              d (description/read "w" "a")]
          (is (= {:name "a" :created true :repo "checkout" :module "mod-a" :from "file"} (select-keys r [:name :created :repo :module :from])))
          (is (= {"REGION" "eu"} (get-in d [:taskdef :environment])))
          (is (= {"KEY" "/p/key"} (get-in d [:taskdef :secrets])))
          (is (false? (:created (call "service.adopt" {:ws "w" :path (str m1)}))) "adopting again is an update")
          (is (= {"REGION" "eu"} (get-in (description/read "w" "a") [:taskdef :environment]))
              "adopting again without taskdef keeps the entries as last adopted")))
      (testing "no file: derived from the Terraform entry, no repository attached"
        (let [r (call "service.adopt" {:ws "w" :path (str m2) :taskdef (str td) :jdwp 5009})
              d (description/read "w" "q")]
          (is (= "terraform" (:from r)))
          (is (= {:name "q" :kind :spring-boot :port 9000 :jdwp 5009 :repo nil :module (str m2)}
                 (select-keys d [:name :kind :port :jdwp :repo :module])))))
      (testing "neither file nor task definition"
        (is (= "service/no-description" (:type (thrown-type #(call "service.adopt" {:ws "w" :path (str (module! root "empty" nil))}))))))
      (testing "list and show without a runtime answer"
        (is (= ["a" "q"] (mapv :name (call "service.list" {:ws "w"}))))
        (is (= "local" (:mode (first (call "service.list" {:ws "w"})))))
        (is (= "a" (:name (call "service.show" {:ws "w" :name "a"}))))
        (is (= "service/not-found" (:type (thrown-type #(call "service.show" {:ws "w" :name "zz"}))))))
      (testing "adopting wrote no role into runtime.edn"
        (is (empty? (filter #(= "service" (namespace %)) (keys (:containers (runtime/read-config "w"))))))))))

(deftest derived-spec
  (tu/with-temp-home
    (let [root (tu/temp-home)
          jar (io/file root "mod" "target" "x-1.jar")]
      (io/make-parents jar) (spit jar "j")
      (let [d {:name "a" :kind :spring-boot :image description/plain-image :module (str (io/file root "mod")) :port 8081 :jdwp 5005 :java-opts ["-Xmx64m"]
               :build {:artifact "target/x-*.jar"} :taskdef {:environment {"A" "1"} :secrets {"S" "/s"}}}
            spec (description/->spec "w" d)]
        (is (= (str jar) (:jar spec)) "a glob picks the built file")
        (is (= {8081 8081 5005 5005} (:ports spec)))
        (is (= {:http "/actuator/health" :port 8081} (:health spec)))
        (is (= 5005 (:debug spec)))
        (is (= {"S" "/s"} (:secrets spec)))))))

(deftest secrets-gate-needs-two-distinct-confirmations
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (description/write! "w" {:name "a" :kind :spring-boot :main "m.Main" :port 8081 :module "/m" :build {:artifact "t.jar"}
                             :taskdef {:secrets {"API_KEY" "arn:aws:secretsmanager:eu-central-1:000000000000:secret:estate/api-key-LAREMM"
                                                 "BUS" "arn:aws:ssm:eu-central-1:000000000000:parameter/estate/common/bus-name"}}})
    (let [go (fn [& {:as m}] (thrown-type #(call "service.start" (merge {:ws "w" :name "a" :secrets-from "local"} m))))
          e1 (go)
          e2 (go :confirm (:token e1))
          e3 (go :confirm (:token e2))]
      (testing "token 1 names the environment and every secret, token 2 says where the values go"
        (is (= "confirm-required" (:type e1)))
        (is (= 1 (:step e1)))
        (is (= 2 (:of e1)))
        (is (= ["API_KEY" "BUS"] (:names e1)))
        (is (re-find #"REAL secret values of environment local" (:summary e1)))
        (is (re-find #"API_KEY <- secretsmanager estate/api-key" (:summary e1)) "the logical name, not the ARN suffix")
        (is (re-find #"BUS <- ssm /estate/common/bus-name" (:summary e1))))
      (is (= "confirm-required" (:type e2)))
      (is (= 2 (:step e2)))
      (is (not= (:token e1) (:token e2)))
      (is (not= (:summary e1) (:summary e2)))
      (is (re-find #"ONLY" (:summary e2)) "the values go into the container's environment only")
      (is (re-find #"podman inspect" (:summary e2)))
      (testing "token 2 passes the gate (the start then fails further on: the module does not exist here)"
        (is (not (contains? #{"confirm-required" "service/confirmation-refused"} (:type e3)))))
      (testing "a token is single use, bound to its stage and to the request"
        (is (= "service/confirmation-refused" (:type (go :confirm (:token e1)))) "token 1 was spent")
        (let [f1 (go)]
          (is (= "service/confirmation-refused" (:type (go :confirm (str (:token f1) "x")))) "a token with a character changed"))
        (let [g1 (go) g2 (go :confirm (:token g1))]
          (is (= "service/confirmation-refused" (:type (go :confirm (:token g1)))))
          (is (not= "service/confirmation-refused" (:type (go :confirm (:token g2)))) "g2 is still good")))
      (testing "only token 1 given twice, or token 2 without token 1, never passes"
        (let [h1 (go)]
          (is (= "confirm-required" (:type (go :confirm (:token h1)))))
          (is (= "service/confirmation-refused" (:type (go :confirm (:token h1)))))))
      (testing "an expired token is refused"
        (binding [service/*token-ttl* (java.time.Duration/ofMillis -1)]
          (let [x1 (go)]
            (is (= "service/confirmation-refused" (:type (go :confirm (:token x1))))))))
      (testing "an unknown environment is refused before any token"
        (is (= "env/not-found" (:type (go :secrets-from "nowhere")))))
      (testing "a description changed between the confirmations voids the token (names are part of what was confirmed)"
        (let [k1 (go)]
          (description/write! "w" (assoc-in (description/read "w" "a") [:taskdef :secrets "EXTRA"] "/extra"))
          (is (= "service/confirmation-refused" (:type (go :confirm (:token k1))))))))))

(deftest secrets-from-needs-secret-references
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (description/write! "w" {:name "a" :kind :spring-boot :main "m.Main" :port 8081 :module "/m" :build {:artifact "t.jar"}})
    (is (= "service/no-secrets" (:type (thrown-type #(call "service.start" {:ws "w" :name "a" :secrets-from "local"})))))))

(deftest node-lambda-does-not-start-as-a-container
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (description/write! "w" {:name "fn" :kind :node-lambda :module "/m"})
    (is (= "service/not-runnable" (:type (thrown-type #(call "service.start" {:ws "w" :name "fn"})))))))

(deftest build-streams-and-persists-raw
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (let [m (module! (tu/temp-home) "m" nil)
          events (atom [])]
      (spit (io/file m "out.txt") "x")
      (description/write! "w" {:name "b" :kind :spring-boot :main "m.Main" :port 1 :module (str m)
                               :build {:cmd ["sh" "-c" "echo one; echo two; touch built.jar"] :artifact "built.jar"}})
      (let [r (ops/call! "service.build" {:emit! #(swap! events conj %)} {:ws "w" :name "b"})]
        (is (= ["one" "two"] (map :text (filter #(= "service.build.line" (:event %)) @events))))
        (is (= (str (io/file m "built.jar")) (:artifact r)))
        (is (= "one\ntwo\n" (slurp (:log r))))
        (is (= "one\ntwo\n" (String. ^bytes (store/raw-get "w" (:sha r)) "UTF-8"))))
      (description/write! "w" {:name "bad" :kind :spring-boot :main "m.Main" :port 1 :module (str m)
                               :build {:cmd ["sh" "-c" "echo boom; exit 3"] :artifact "none.jar"}})
      (let [e (thrown-type #(call "service.build" {:ws "w" :name "bad"}))]
        (is (= "service/build-failed" (:type e)))
        (is (= 3 (:exit e)))
        (is (= "boom\n" (slurp (:log e))))))))
