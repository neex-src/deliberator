(ns deliberator.scenario.vars-test
  (:require [clojure.test :refer [deftest is testing]]
            [deliberator.scenario.vars :as vars]
            [deliberator.store :as store]
            [deliberator.test-util :as t])
  (:import (java.nio.charset StandardCharsets)
           (java.util Arrays)))

(def header {:env "local"
             :vars [{:name "orderId" :value "ord-{{uuid}}" :line 3}
                    {:name "again" :value "{{orderId}}/{{now.epoch}}" :line 4}
                    {:name "plain" :value "x" :line 5}]})

(deftest snapshot-is-stable-and-has-builtins
  (binding [store/*clock* (t/fixed-clock "2026-10-16T10:15:02Z")]
    (let [v (vars/snapshot header :correlation "run-1"
                           :env {:name "local" :kind :local
                                 :aws {:region "eu-central-1" :account "123456789012"
                                       :credentials {:secret-access-key "s3cr3t"} :secret-key "k"}
                                 :token "t"})]
      (is (re-matches #"[0-9a-f-]{36}" (v "uuid")))
      (is (= (str "ord-" (v "uuid")) (v "orderId")) "the same uuid everywhere")
      (is (= (str (v "orderId") "/1792145702") (v "again")))
      (is (= "2026-10-16T10:15:02Z" (v "now")))
      (is (= "1792145702" (v "now.epoch")))
      (is (= "run-1" (v "correlation")))
      (is (= ["local" "local" "eu-central-1" "123456789012"]
             (map v ["env.name" "env.kind" "env.region" "env.account"])))
      (is (not-any? #(re-find #"(?i)secret|token|key|s3cr3t" %) (keys v)) "nothing secret-looking")
      (is (not-any? #(= "s3cr3t" %) (vals v)))
      (is (not= (v "uuid") (get (vars/snapshot header) "uuid")) "a new snapshot, a new uuid"))))

(deftest seeds-and-builtin-overrides
  (let [v (vars/snapshot header :builtins {"uuid" "U"} :vars {"extra" "e" "plain" "seeded"})]
    (is (= "ord-U" (v "orderId")))
    (is (= "e" (v "extra")))
    (is (= "seeded" (v "plain")) "seeds win over header vars")))

(deftest substitution
  (let [v {"a" "1" "b.c" "x$y\\z"}]
    (is (= "1-x$y\\z-{{ }}-{{\"j\":1}}" (vars/substitute "{{a}}-{{ b.c }}-{{ }}-{{\"j\":1}}" v)))
    (is (= "no vars" (vars/substitute "no vars" v)))
    (is (= ["a" "b.c"] (vars/references "{{a}} {{ b.c }} {{ }}")))
    (is (= "http://h/x" (vars/substitute "{{service/a}}/x" v :resolve (fn [n] (when (= n "service/a") "http://h")))))
    (is (= {"a" "2" "b.c" "x$y\\z" "n" "3"} (vars/overlay v {"a" "2" "n" "3"})))))

(deftest unknown-var-lists-the-known-ones
  (let [e (try (vars/substitute "{{nope}}" {"b" "1" "a" "2"}) (catch clojure.lang.ExceptionInfo e e))]
    (is (= :scenario/unknown-var (:type (ex-data e))))
    (is (= "nope" (:name (ex-data e))))
    (is (= ["a" "b"] (:known (ex-data e))))
    (is (re-find #"known: a, b" (ex-message e))))
  (testing "a slash name without a resolver is unknown too"
    (is (= :scenario/unknown-var
           (:type (ex-data (try (vars/substitute "{{service/a}}" {}) (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "in a @var, with the line"
    (let [e (try (vars/snapshot {:vars [{:name "x" :value "{{nope}}" :line 7}]}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 7 (:line (ex-data e)))))))

(deftest bytes-are-substituted-without-touching-others
  (let [bs (byte-array [0xff 0xfe 0x7b 0x7b 0x61 0x7d 0x7d 0x0a])
        out (vars/substitute-bytes bs {"a" "é"})]
    (is (Arrays/equals ^bytes out (byte-array (concat [0xff 0xfe 0xc3 0xa9 0x0a])))))
  (let [bs (.getBytes "no placeholders é\n" StandardCharsets/UTF_8)]
    (is (identical? bs (vars/substitute-bytes bs {}))))
  (is (= "x-http://h" (String. (vars/substitute-bytes (.getBytes "{{a}}-{{service/a}}") {"a" "x"}
                                                      :resolve (constantly "http://h"))))))

(deftest volatility
  (is (= #{"uuid" "now" "now.epoch" "correlation" "orderId" "again"} (vars/volatile-names header))))
