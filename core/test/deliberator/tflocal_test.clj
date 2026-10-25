(ns deliberator.tflocal-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
