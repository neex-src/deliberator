(ns deliberator.config-test
  "The configuration chain: the parsers on fixtures and on the estate's own files (pass A), the chain over a fixture
  repository with hand-written snapshots (pass A, offline), and `config.refresh` against a real Floci
  (`deftest-floci`, pass B: `DLB_TEST_FLOCI_URL`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.config :as config]
            [deliberator.config.sources :as src]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.ops :as ops]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.time Instant)
           (java.util UUID)))

(defn- call [op args] (ops/call! op {:emit! (fn [_])} args))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(defn- by [k xs] (into {} (map (juxt k identity)) xs))

;; parsers ---------------------------------------------------------------------------------------

(deftest properties-placeholders
  (let [ps (src/placeholders (str "# ${COMMENTED}\n"
                                  "! ${ALSO}\n"
                                  "app.table=${TABLE_NAME}\n"
                                  "app.flag = ${FLAG:false}\n"
                                  "app.url: http://${HOST:localhost}:${PORT:${DEFAULT_PORT:8080}}/x\n"
                                  "plain=value\n")
                             :properties)]
    (is (= [["TABLE_NAME" nil 3 "app.table"] ["FLAG" "false" 4 "app.flag"]
            ["HOST" "localhost" 5 "app.url"] ["PORT" "${DEFAULT_PORT:8080}" 5 "app.url"] ["DEFAULT_PORT" "8080" 5 "app.url"]]
           (map (juxt :placeholder :default :line :property) ps)))
    (is (= #{"app.table" "app.flag" "app.url" "plain"}
           (src/defined-keys "app.table=${A}\napp.flag = 1\napp.url: x\n# c=1\nplain=value\n" :properties)))))

(deftest yaml-placeholders
  (let [text (str "spring:\n"
                  "  datasource:\n"
                  "    url: ${DB_URL}   # trailing\n"
                  "    username: ${DB_USER:sa}\n"
                  "  # ${NOPE}\n"
                  "app:\n"
                  "  hosts:\n"
                  "    - ${HOST_A}\n"
                  "    - plain\n"
                  "  quoted: \"${Q:x y}\"\n")
        ps (src/placeholders text :yaml)]
    (is (= [["DB_URL" nil 3 "spring.datasource.url"] ["DB_USER" "sa" 4 "spring.datasource.username"]
            ["HOST_A" nil 8 "app.hosts"] ["Q" "x y" 10 "app.quoted"]]
           (map (juxt :placeholder :default :line :property) ps)))
    (is (contains? (src/defined-keys text :yaml) "spring.datasource.url"))))

(def ^:private estate (io/file "../estate/terraform/modules/stack"))

(deftest tftpl-and-main-tf-of-the-estate
  (testing "the estate's template renders from maps: the line is the loop's entry line, no literal names"
    (let [t (src/tftpl (slurp (io/file estate "taskdef.json.tftpl")))]
      (is (= {} (:literals t)))
      (is (= {:env 9 :secret 14} (:loops t)))))
  (testing "a template with literal entries gives their lines"
    (let [t (src/tftpl "{\n \"environment\": [\n  { \"name\": \"REGION\", \"value\": \"eu\" }\n ],\n \"secrets\": [\n  { \"name\": \"KEY\", \"valueFrom\": \"/p/key\" }\n ]\n}")]
      (is (= {"REGION" {:kind :env :line 3 :value "eu"} "KEY" {:kind :secret :line 6 :value-from "/p/key"}} (:literals t)))))
  (testing "main.tf: the service's block, inline maps, raw expressions"
    (let [text (slurp (io/file estate "main.tf"))
          a (src/main-tf text "a")
          b (src/main-tf text "b")]
      (is (= {:kind :secret :line 282 :expr "local.ssm_arn[\"common/table-name\"]" :literal? false} (get a "TABLE_NAME")))
      (is (= {:kind :env :line 280 :expr "var.auto_publish" :literal? false} (get a "APP_AUTO_PUBLISH")))
      (is (= "aws_secretsmanager_secret.api_key.arn" (:expr (get b "API_KEY"))))
      (is (= #{"DB_URL" "DB_USER" "DB_PASSWORD" "EVENTS_QUEUE" "SERVICE_A_URL" "API_KEY"} (set (keys b))))
      (is (= 273 (:line (src/main-tf-name text "AWS_REGION"))))))
  (testing "literals"
    (let [m (src/main-tf "locals {\n  svc = {\n    x = {\n      secrets = {\n        K = \"/p/k\"\n        N = 3\n        E = var.e\n      }\n    }\n  }\n}\n" "x")]
      (is (= {"K" {:kind :secret :line 5 :expr "\"/p/k\"" :literal? true :value "/p/k"}
              "N" {:kind :secret :line 6 :expr "3" :literal? true :value "3"}
              "E" {:kind :secret :line 7 :expr "var.e" :literal? false}}
             m)))))

(deftest value-from-references
  (is (= {:kind :ssm :name "/estate/x/y" :hierarchical? true} (src/parse-ref "arn:aws:ssm:eu-central-1:000000000000:parameter/estate/x/y")))
  (is (= {:kind :ssm :name "/p" :hierarchical? true} (src/parse-ref "/p")))
  (is (= {:kind :ssm :name "bare" :hierarchical? false} (src/parse-ref "bare")))
  (is (= {:kind :secret :arn "arn:aws:secretsmanager:r:1:secret:estate/api-key-ZAASKE" :id "estate/api-key-ZAASKE" :json-key "k"}
         (src/parse-ref "arn:aws:secretsmanager:r:1:secret:estate/api-key-ZAASKE:k::")))
  (is (nil? (src/parse-ref "local.ssm_arn[\"x\"]")))
  (is (nil? (src/parse-ref "${var.x}")))
  (is (nil? (src/parse-ref nil))))

;; chain -------------------------------------------------------------------------------------------

(def ^:private props
  (str "# comment ${NOT}\n"
       "app.table=${TABLE_NAME}\n"
       "app.key=${API_KEY}\n"
       "app.gone=${GONE_PARAM}\n"
       "app.tpl=${TPL_ONLY}\n"
       "app.noentry=${NO_ENTRY}\n"
       "app.default=${WITH_DEFAULT:7}\n"
       "app.auto=${APP_AUTO:false}\n"
       "app.ref=${app.table}\n"
       "app.relaxed=${app.relaxed-name}\n"))

(def ^:private tf
  (str "locals {\n  services = {\n    q = {\n      environment = { APP_AUTO = var.auto }\n      secrets = {\n"
       "        TPL_ONLY = module.x.arn\n        TABLE_NAME = local.p[\"t\"]\n      }\n    }\n  }\n}\n"))

(def ^:private tftpl
  (str "[{\n  \"environment\": [\n%{ for k, v in environment ~}\n    { \"name\": ${jsonencode(k)} }\n%{ endfor ~}\n  ],\n"
       "  \"secrets\": [\n%{ for k, v in secrets ~}\n    { \"name\": ${jsonencode(k)} }\n%{ endfor ~}\n  ]\n}]\n"))

(defn- fixture!
  "Workspace `w`, repo `checkout` with module `mod` and a Terraform module `tf`, service `q` adopted by hand."
  []
  (let [root (tu/temp-home)
        repo (io/file root "checkout")
        mod (io/file repo "mod")
        res (io/file mod "src/main/resources")
        tfd (io/file repo "tf")]
    (.mkdirs res) (.mkdirs tfd)
    (spit (io/file res "application.properties") props)
    (spit (io/file tfd "main.tf") tf)
    (spit (io/file tfd "taskdef.json.tftpl") tftpl)
    (call "workspace.open" {:ws "w"})
    (call "repo.attach" {:ws "w" :path (str repo)})
    (description/write! "w" {:name "q" :kind :spring-boot :main "m.Main" :port 9000 :repo "checkout" :module "mod"
                             :taskdef {:module "tf" :container "service-q" :key "q"
                                       :environment {"APP_AUTO" "false" "AWS_REGION" "eu"}
                                       :secrets {"TABLE_NAME" "arn:aws:ssm:eu:0:parameter/p/table"
                                                 "API_KEY" "arn:aws:secretsmanager:eu:0:secret:p/api-key-AbCdEf"
                                                 "GONE_PARAM" "/p/gone"
                                                 "APP_RELAXED_NAME" "arn:aws:secretsmanager:eu:0:secret:p/other-ZZZZZZ"
                                                 "UNUSED" "/p/unused"}}})
    {:repo repo :mod mod}))

(defn- write-snapshot! [m]
  (store/write-edn! (config/snapshot-path "w" "local")
                    (merge {:env "local" :fetched-at (Instant/parse "2026-01-01T00:00:00Z") :path "/"
                            :parameters {"/p/table" {:name "/p/table" :type "String" :value "tbl"}}
                            :secrets {"p/api-key" {:name "p/api-key" :arn "arn:aws:secretsmanager:eu:0:secret:p/api-key-AbCdEf"
                                                   :last-changed (Instant/parse "2026-01-01T00:00:00Z")}}}
                           m)))

(deftest chain-without-a-snapshot-is-unknown-and-offline
  (tu/with-temp-home
    (fixture!)
    (let [r (call "config.chain" {:ws "w" :name "q"})
          es (by :placeholder (:entries r))]
      (is (false? (get-in r [:snapshot :present])))
      (is (str/includes? (:hint r) "dlb config refresh --env local"))
      (is (= :unknown (get-in es ["TABLE_NAME" :parameter :status])))
      (is (= :unknown (get-in es ["API_KEY" :parameter :status])))
      (is (nil? (get-in es ["TABLE_NAME" :parameter :exists])))
      (is (= [] (map :kind (filter #(= :parameter-missing (:kind %)) (:broken r))))
          "nothing is missing without a snapshot")
      (is (not (config/snapshot "w" "local")) "chain never wrote one"))))

(deftest chain-with-a-snapshot
  (tu/with-temp-home
    (let [{:keys [mod]} (fixture!)]
      (write-snapshot! {})
      (let [r (call "config.chain" {:ws "w" :name "q"})
            es (by :placeholder (:entries r))
            props-file (str (io/file mod "src/main/resources/application.properties"))
            tf-file (str (io/file (.getParentFile mod) "tf/taskdef.json.tftpl"))]
        (testing "every file is absolute and under the attached repository"
          (is (every? #(.isAbsolute (io/file %)) (:files r)))
          (is (= [props-file] (:files r)))
          (is (= props-file (get-in es ["TABLE_NAME" :file])))
          (is (= tf-file (get-in es ["TABLE_NAME" :taskdef :file])))
          (is (= (str (io/file (.getParentFile mod) "tf/main.tf")) (get-in es ["TABLE_NAME" :taskdef :source :file]))))
        (testing "placeholder, entry and parameter"
          (is (= 2 (get-in es ["TABLE_NAME" :line])))
          (is (= {:kind :secret :name "TABLE_NAME" :via :loop :line 9 :adopted true :match :exact
                  :value-from "arn:aws:ssm:eu:0:parameter/p/table" :expr "local.p[\"t\"]" :literal false}
                 (select-keys (get-in es ["TABLE_NAME" :taskdef]) [:kind :name :via :line :adopted :match :value-from :expr :literal])))
          (is (= 7 (get-in es ["TABLE_NAME" :taskdef :source :line])))
          (is (= {:name "/p/table" :kind :ssm :exists true :status :present :value "tbl"}
                 (select-keys (get-in es ["TABLE_NAME" :parameter]) [:name :kind :exists :status :value])))
          (is (= (Instant/parse "2026-01-01T00:00:00Z") (get-in es ["TABLE_NAME" :parameter :fetched-at]))))
        (testing "a secret: metadata only, matched by ARN"
          (let [p (get-in es ["API_KEY" :parameter])]
            (is (= [true :present :secret "p/api-key"] ((juxt :exists :status :kind :name) p)))
            (is (nil? (:value p)))
            (is (some? (get-in p [:metadata :last-changed])))))
        (testing "environment entries have no parameter; the default placeholder with an entry"
          (is (= {:kind :env :name "APP_AUTO" :value "false"} (select-keys (get-in es ["APP_AUTO" :taskdef]) [:kind :name :value])))
          (is (nil? (get-in es ["APP_AUTO" :parameter]))))
        (testing "internal references and relaxed names"
          (is (true? (get-in es ["app.table" :internal])))
          (is (nil? (get-in es ["app.table" :taskdef])))
          (is (= :relaxed (get-in es ["app.relaxed-name" :taskdef :match])))
          (is (= :missing (get-in es ["app.relaxed-name" :parameter :status])) "p/other is not in the snapshot"))
        (testing "broken: no entry (without default), missing parameters (referenced and not)"
          (let [kinds (group-by :kind (:broken r))]
            (is (= ["NO_ENTRY"] (map #(get-in % [:entry :placeholder]) (:placeholder-without-entry kinds))))
            (is (= #{"GONE_PARAM" "app.relaxed-name"} (set (map #(get-in % [:entry :placeholder]) (filter #(get-in % [:entry :placeholder]) (:parameter-missing kinds))))))
            (is (contains? (set (map #(get-in % [:entry :taskdef :name]) (:parameter-missing kinds))) "UNUSED") "unreferenced entry")
            (is (not-any? #(= "WITH_DEFAULT" (get-in % [:entry :placeholder])) (:broken r)) "a default resolves it")))
        (is (true? (get-in es ["WITH_DEFAULT" :uses-default])))
        (testing "a template expression is unresolved, not broken, never evaluated"
          (let [u (:unresolved r)]
            (is (= 1 (count u)))
            (is (= "TPL_ONLY" (get-in (first u) [:entry :placeholder])))
            (is (= "module.x.arn" (get-in (first u) [:entry :taskdef :expr])))
            (is (not-any? #(= "TPL_ONLY" (get-in % [:entry :placeholder])) (:broken r)))))
        (testing "extra: task definition entries no placeholder uses"
          (is (= #{"AWS_REGION" "UNUSED"} (set (map #(get-in % [:taskdef :name]) (:extra r))))))
        (testing "age"
          (is (true? (get-in r [:snapshot :present])))
          (is (pos? (get-in r [:snapshot :age-ms]))))))))

(deftest chain-strictness-and-errors
  (tu/with-temp-home
    (doseq [[op args] [["config.chain" {:name "q"}] ["config.refresh" {}]]]
      (is (= "workspace/not-found" (:type (thrown #(call op (assoc args :ws "nope"))))) op))
    (call "workspace.open" {:ws "w"})
    (is (= "service/not-found" (:type (thrown #(call "config.chain" {:ws "w" :name "zzz"})))))
    (fixture!)
    (is (= "env/not-found" (:type (thrown #(call "config.chain" {:ws "w" :name "q" :env "nope"})))))))

(deftest yaml-module-and-profile-files
  (tu/with-temp-home
    (let [{:keys [mod]} (fixture!)
          res (io/file mod "src/main/resources")]
      (spit (io/file res "application-prod.yml") "app:\n  extra: ${EXTRA:1}\n")
      (let [r (call "config.chain" {:ws "w" :name "q"})
            e (first (filter #(= "EXTRA" (:placeholder %)) (:entries r)))]
        (is (= 2 (count (:files r))))
        (is (= ["app.extra" 2 "1"] ((juxt :property :line :default) e)))
        (is (str/ends-with? (:file e) "application-prod.yml"))))))

;; refresh on a real Floci -------------------------------------------------------------------------

(deftest-floci refresh-snapshots-parameters-and-secret-metadata
  (tu/with-temp-home
    (env/save! "w" (fu/local-env))
    (call "workspace.open" {:ws "w"})
    (let [local (env/load "w" "local")
          id (subs (str (UUID/randomUUID)) 0 8)
          p (str "/dlbtest/" id "/param")
          s (str "dlbtest/" id "/secret")
          inv (fn [svc op req] (aws/invoke! {:ws "w"} local svc op req))]
      (try
        (is (not (aws/error? (inv :ssm :PutParameter {:Name p :Value "pv" :Type "String"}))))
        (is (not (aws/error? (inv :secretsmanager :CreateSecret {:Name s :SecretString "topsecret"}))))
        (call "session.start" {:ws "w" :key "S1"})
        (let [r (call "config.refresh" {:ws "w" :env "local"})
              snap (config/snapshot "w" "local")]
          (is (<= 1 (:parameters r)))
          (is (<= 1 (:secrets r)))
          (is (= "pv" (get-in snap [:parameters p :value])))
          (is (= s (get-in snap [:secrets s :name])))
          (is (not (str/includes? (pr-str snap) "topsecret")) "secret values are never fetched")
          (is (inst? (:fetched-at snap)))
          (is (.isFile (io/file (:copy r))) "a copy under the open session")
          (is (str/includes? (:copy r) "/sessions/S1/config/local-"))
          (testing "every response is raw-persisted before it is read"
            (is (seq (:raw snap)))
            (doseq [{:keys [response request]} (:raw snap)]
              (is (some? (store/raw-get "w" response)))
              (is (some? (store/raw-get "w" request)))))
          (testing "the chain sees the parameter, then a deletion after the next refresh"
            (description/write! "w" {:name "q" :kind :spring-boot :main "m.Main" :port 9000 :module (str (io/file (tu/temp-home)))
                                     :taskdef {:secrets {"P" p}}}))
          (is (= :present (get-in (config/chain "w" "q" "local") [:extra 0 :parameter :status])))
          (inv :ssm :DeleteParameter {:Name p})
          (call "config.refresh" {:ws "w" :env "local"})
          (let [c (config/chain "w" "q" "local")]
            (is (= :missing (get-in c [:extra 0 :parameter :status])))
            (is (= :parameter-missing (:kind (first (:broken c)))))))
        (finally
          (inv :ssm :DeleteParameter {:Name p})
          (inv :secretsmanager :DeleteSecret {:SecretId s :ForceDeleteWithoutRecovery true}))))))
