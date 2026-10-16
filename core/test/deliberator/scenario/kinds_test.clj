(ns deliberator.scenario.kinds-test
  (:require [clojure.test :refer [deftest is]]
            [deliberator.fake-kind :as fake]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.kinds :as kinds]))

(defn- invalid? [m]
  (try (kinds/register! m) false
       (catch clojure.lang.ExceptionInfo e (= :kinds/invalid (:type (ex-data e))))))

(deftest registry-validates-kind-maps
  (is (invalid? {:kind "bad kind" :run identity}))
  (is (invalid? {:kind "x"}))
  (is (invalid? {:kind "x" :run identity :arm identity}))
  (is (invalid? {:kind "x" :run identity :facts [{:name "a" :type :bogus}]}))
  (is (invalid? {:kind "x" :run identity :verbs [nil]}))
  (is (nil? (kinds/get-kind "x")))
  (let [k {:kind "tmp" :verbs #{nil} :run identity :facts [{:name "a" :type :id}]}]
    (is (= k (kinds/register! k)))
    (is (= k (kinds/get-kind "tmp")))
    (kinds/unregister! "tmp")
    (is (nil? (kinds/get-kind "tmp")))))

(deftest facts-are-typed-with-families-and-an-implicit-elapsed
  (let [k fake/fake]
    (is (= :number (kinds/fact-type k "status")))
    (is (= :duration (kinds/fact-type k "elapsed")))
    (is (= :string (kinds/fact-type k "header.content-type")))
    (is (nil? (kinds/fact-type k "headers")))
    (is (nil? (kinds/fact-type k "nope")))
    (is (= "elapsed" (first (kinds/fact-names k))))))

(deftest directives-are-engine-owned-plus-the-kinds
  (is (= ["assert" "capture" "until" "timeout" "body" "name"] (map :name kinds/engine-directives)))
  (is (= (concat (map :name kinds/engine-directives) ["set" "sleep" "fail" "log" "display"])
         (map :name (kinds/directive-specs fake/fake)))))

(deftest thread-model-matches-the-jvm
  (is (= (if (>= (.feature (Runtime/version)) 21) :virtual :platform) engine/thread-model)))
