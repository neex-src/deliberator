(ns deliberator.tflocal-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.env :as env]
            [deliberator.store :as store]
            [deliberator.tflocal :as tf]
            [clojure.java.io :as io])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-home []
  (str (Files/createTempDirectory "dlb-tf" (make-array FileAttribute 0))))

(defn- fake-root [home]
  (let [d (io/file home "root")]
    (.mkdirs d)
    (spit (io/file d "main.tf")
          (str "variable \"endpoint\" {\n  default = \"x\"\n}\nvariable \"container_endpoint\" {}\nvariable \"service_a_url\" {}\n"
               "variable \"db_url\" {}\nvariable \"auto_publish\" {\n  default = \"false\"\n}\n"))
    (str d)))

(deftest declared-variables-are-filled-from-the-workspace
  (let [home (tmp-home)
        root (fake-root home)]
    (binding [store/*home* home]
      (env/save! "w" (assoc-in env/default-local [:aws :endpoint] "http://localhost:4566"))
      (testing "action vars returns the final values and runs nothing"
        (let [r (tf/run! "w" "local" {:dir root :action "vars"})]
          (is (= {"endpoint" "http://localhost:4566"
                  "container_endpoint" (str "http://" (store/container-name "w" "floci") ":4566")
                  "service_a_url" (str "http://" (store/container-name "w" "service-a") ":8081")
                  "db_url" (str "jdbc:postgresql://" (store/container-name "w" "postgres") ":5432/deliberator")}
                 (into {} (:vars r))))
          (is (not (contains? (:vars r) "auto_publish")) "a declared variable the workspace cannot fill keeps its default")))
      (testing "an explicit vars map overrides, keyword keys too"
        (let [r (tf/run! "w" "local" {:dir root :action "vars" :vars {:db_url "jdbc:x" "auto_publish" "true"}})]
          (is (= "jdbc:x" (get (:vars r) "db_url")))
          (is (= "true" (get (:vars r) "auto_publish")))))
      (testing "a root that declares none of them gets none"
        (spit (io/file root "main.tf") "variable \"other\" {}\n")
        (is (= {} (into {} (:vars (tf/run! "w" "local" {:dir root :action "vars"})))))))))

(deftest refuses-remote-environments-and-empty-roots
  (let [home (tmp-home)]
    (binding [store/*home* home]
      (env/save! "w" {:name "dev" :kind :remote :read-only true :aws {:profile "p" :region "eu-central-1" :account "000000000000"} :targets {}})
      (testing "a remote environment is refused before anything runs"
        (is (= :tf/local-only (:type (ex-data (try (tf/run! "w" "dev" {:dir home :action "apply"}) (catch Exception e e)))))))
      (testing "a directory without .tf files"
        (is (= :tf/no-root (:type (ex-data (try (tf/run! "w" "local" {:dir home}) (catch Exception e e))))))))))

;; --- M6.1: the override file ----------------------------------------------------------------------

(defn- sh! [dir & args]
  (let [r (apply clojure.java.shell/sh (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r)) (throw (ex-info (str args (:err r)) r)))))

(defn- git-root!
  "A root `r` in a fresh git repository (no .gitignore yet) with the given main.tf."
  [home main-tf]
  (let [repo (io/file home "repo") d (io/file repo "r")]
    (.mkdirs d)
    (sh! repo "git" "init" "-q")
    (spit (io/file d "main.tf") main-tf)
    (str d)))

(def ^:private aws-root "provider \"aws\" {\n  region = \"eu-central-1\"\n  profile = \"prod\"\n}\nresource \"aws_sqs_queue\" \"q\" {\n  name = \"q\"\n}\n")

(deftest the-override-file-is-written-checked-and-never-removed
  (let [home (tmp-home)
        root (git-root! home aws-root)
        f (io/file root "deliberator_override.tf.json")]
    (binding [store/*home* home]
      (env/save! "w" (assoc-in env/default-local [:aws :endpoint] "http://localhost:4566"))
      (let [r (tf/run! "w" "local" {:dir root :action "override"})
            doc (json/read-str (slurp f))]
        (testing "written next to the .tf files, with the endpoints, the fake keys and a local state under the workspace"
          (is (true? (get-in r [:override :written?])))
          (is (= (.getPath f) (get-in r [:override :file])))
          (is (= "http://localhost:4566" (get-in doc ["provider" "aws" 0 "endpoints" 0 "sqs"])))
          (is (= ["test" "test"] ((juxt #(get % "access_key") #(get % "secret_key")) (first (get-in doc ["provider" "aws"])))))
          (is (nil? (get-in doc ["provider" "aws" 0 "profile"])) "the profile of the real root is nulled")
          (is (contains? (first (get-in doc ["provider" "aws"])) "profile"))
          (is (str/starts-with? (get-in doc ["terraform" "backend" "local" "path"]) (str (store/path "w" "terraform")))))
        (testing "the missing gitignore entry is a loud warning"
          (is (some #(re-find #"NOT gitignored.*deliberator_override\.tf\.json" %) (:warnings r)) (pr-str (:warnings r))))
        (testing "a second call changes nothing; with the gitignore line there is no warning"
          (spit (io/file (io/file root "..") ".gitignore") "deliberator_override.tf.json\n")
          (let [r2 (tf/run! "w" "local" {:dir root :action "override"})]
            (is (false? (get-in r2 [:override :written?])))
            (is (nil? (:warnings r2)))))
        (testing "the file is the user's: it stays after the run"
          (is (.exists f)))))))

(deftest a-file-without-the-marker-is-theirs-and-must-cover-the-root
  (let [home (tmp-home)
        root (git-root! home aws-root)
        f (io/file root "deliberator_override.tf.json")]
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (testing "covers the provider: used as it is, never rewritten"
        (let [mine "{\"provider\":{\"aws\":[{\"endpoints\":[{\"sqs\":\"http://localhost:1\"}]}]}}"]
          (spit f mine)
          (let [r (tf/run! "w" "local" {:dir root :action "override"})]
            (is (true? (get-in r [:override :user-owned?])))
            (is (false? (get-in r [:override :written?])))
            (is (= mine (slurp f))))))
      (testing "misses a provider block (an alias the root has): refused, with the reason"
        (spit (io/file root "main.tf") (str aws-root "provider \"aws\" {\n  alias = \"b\"\n  region = \"eu-west-1\"\n}\n"))
        (let [e (try (tf/run! "w" "local" {:dir root :action "plan"}) (catch Exception e e))]
          (is (= :tf/no-override (:type (ex-data e))))
          (is (some #(re-find #"alias b" %) (:problems (ex-data e))) (pr-str (:problems (ex-data e))))))
      (testing "an override that points at AWS is refused"
        (spit f "{\"provider\":{\"aws\":[{\"endpoints\":[{\"sqs\":\"https://sqs.eu-central-1.amazonaws.com\"}]},{\"alias\":\"b\"}]}}")
        (is (= :tf/no-override (:type (ex-data (try (tf/run! "w" "local" {:dir root :action "plan"}) (catch Exception e e)))))))
      (testing "not JSON, no marker: left alone and refused"
        (spit f "not json")
        (is (= :tf/no-override (:type (ex-data (try (tf/run! "w" "local" {:dir root :action "override"}) (catch Exception e e))))))
        (is (= "not json" (slurp f)))))))

(deftest a-root-that-points-its-own-providers-at-an-emulator-gets-no-file
  (let [home (tmp-home)
        d (io/file home "floci")]
    (.mkdirs d)
    (spit (io/file d "main.tf") "provider \"aws\" {\n  access_key = \"test\"\n  skip_requesting_account_id = true\n  endpoints {\n    sqs = \"http://localhost:4566\"\n  }\n}\n")
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (let [r (tf/run! "w" "local" {:dir (str d) :action "override"})]
        (is (true? (:exempt r)))
        (is (not (.exists (io/file d "deliberator_override.tf.json"))))))))

(deftest a-root-runs-with-its-state-and-terraform-data-under-the-workspace
  ;; terraform's built-in provider: no download, so this runs offline in both passes
  (let [home (tmp-home)
        root (git-root! home "resource \"terraform_data\" \"x\" {\n  input = \"hi\"\n}\n")]
    (spit (io/file home "repo" ".gitignore") "deliberator_override.tf.json\n.terraform/\n")
    (binding [store/*home* home]
      (env/save! "w" env/default-local)
      (let [r (tf/run! "w" "local" {:dir root :action "apply"})]
        (is (true? (:ok r)) (:output r))
        (is (str/starts-with? (:summary r) "Apply complete!") (:summary r))
        (is (.exists (io/file (get-in r [:override :state]))) "the state is in the workspace")
        (is (not (.exists (io/file root "terraform.tfstate"))) "not in the user's checkout")
        (is (not (.exists (io/file root ".terraform"))) "nor the data dir: the checkout's own .terraform stays as it is")
        (is (.exists (io/file root "deliberator_override.tf.json")) "the override stays"))
      (let [r (tf/run! "w" "local" {:dir root :action "destroy"})]
        (is (true? (:ok r)) (:output r))
        (is (str/starts-with? (:summary r) "Destroy complete!") (:summary r))))))
