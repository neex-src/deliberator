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

(deftest refuses-remote-environments-and-empty-roots
  (let [home (tmp-home)]
    (binding [store/*home* home]
      (env/save! "w" {:name "dev" :kind :remote :read-only true :aws {:profile "p" :region "eu-central-1" :account "000000000000"} :targets {}})
      (testing "a remote environment is refused before anything runs"
        (is (= :tf/local-only (:type (ex-data (try (tf/run! "w" "dev" {:dir home :action "apply"}) (catch Exception e e)))))))
      (testing "a directory without .tf files"
        (is (= :tf/no-root (:type (ex-data (try (tf/run! "w" "local" {:dir home}) (catch Exception e e))))))))))
