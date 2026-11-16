(ns deliberator.eventmatch-test
  "The matcher is held to the REAL `TestEventPattern`: `eventmatch_table.edn` has 67 patterns and the answer the real endpoint (account 000000000000, profile rvw-deploy,
  2026-11-16) gave for each; two of them are refused by AWS itself (`$or` of one object, an empty pattern: `InvalidEventPatternException`), which `valid` knows."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [deliberator.eventmatch :as em]))

(def table (edn/read-string (slurp (io/resource "deliberator/eventmatch_table.edn"))))

(deftest the-matcher-agrees-with-the-real-test-event-pattern-on-every-case
  (let [bool-cases (filter #(boolean? (:real %)) (:cases table))]
    (is (>= (count bool-cases) 60))
    (doseq [{:keys [name pattern detail real]} bool-cases]
      (is (= real (em/matches? pattern (assoc (:event table) "detail" detail))) name))
    (testing "the cases the real endpoint refused are the ones `valid` refuses"
      (doseq [{:keys [name pattern real]} (:cases table) :when (string? real)]
        (is (nil? (em/valid pattern)) name))
      (is (every? #(some? (em/valid (:pattern %))) (filter #(boolean? (:real %)) (:cases table)))))))

(deftest a-sample-of-a-pattern-matches-the-pattern
  (doseq [{:keys [name pattern]} (:cases table) :when (and (em/valid pattern) (not (re-find #"exists false|anything-but missing|nested missing|null literal on string|number as string|numeric on string|nested on scalar|prefix on number|boolean literal wrong|detail absent" name)))
          :let [s (em/sample pattern)]]
    (is (em/matches? pattern (merge {"source" "x" "detail-type" "y" "detail" {}} s)) (str name " " (json/write-str s)))))

(deftest what-a-sample-picks
  (is (= {"source" "p05.orders" "detail-type" "Ordersample"} (em/sample {"source" ["p05.orders"] "detail-type" [{"prefix" "Order"}]})))
  (is (= {"detail" {"qty" 4 "state" "sample"}} (em/sample {"detail" {"qty" [{"numeric" [">" 3]}] "state" [{"exists" true}]}})))
  (is (= {"source" "b"} (em/sample {"$or" [{"source" ["b"]} {"source" ["c"]}]})))
  (is (= {"detail" {}} (em/sample {"detail" {}})) "an object with nothing asked is an empty detail"))
