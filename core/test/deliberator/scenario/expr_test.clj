(ns deliberator.scenario.expr-test
  (:require [clojure.test :refer [deftest is testing]]
            [deliberator.scenario.expr :as expr])
  (:import (java.time Instant)))

(defn- ev
  ([text actual] (ev text actual true nil))
  ([text actual found? type] (:ok (expr/evaluate (expr/parse-assert text) actual found? type))))

(defn- bad? [f & args]
  (try (apply f args) false
       (catch clojure.lang.ExceptionInfo e (= :scenario/expr (:type (ex-data e))))))

(deftest durations
  (is (= [500 2000 1500 180000 3600000 42] (map expr/parse-duration ["500ms" "2s" "1.5s" "3m" "1h" "42"])))
  (is (bad? expr/parse-duration "soon"))
  (is (bad? expr/parse-duration "-1s")))

(deftest json-path-lite
  (is (= [] (expr/parse-path "$")))
  (is (= ["Item" "status" "S"] (expr/parse-path "$.Item.status.S")))
  (is (= ["items" 0 "qty"] (expr/parse-path "$.items[0].qty")))
  (is (= [0 1] (expr/parse-path "$[0][1]")))
  (doseq [p ["$.a[-1]" "$.a[*]" "$..a" "$.a[?(@.b)]" "a.b" "$.a['b']" "$.a["]]
    (is (bad? expr/parse-path p) p))
  (let [j {"a" [{"b" 1} {"b" nil}] "c" {"d" false}}]
    (is (= [true 1] (expr/path-get j ["a" 0 "b"])))
    (is (= [true nil] (expr/path-get j ["a" 1 "b"])) "null is found")
    (is (= [true false] (expr/path-get j ["c" "d"])))
    (is (= [false nil] (expr/path-get j ["a" 2 "b"])))
    (is (= [false nil] (expr/path-get j ["a" "b"])))
    (is (= [true 5] (expr/path-get {:k 5} ["k"])) "keyword keys too")
    (is (= [true j] (expr/path-get j [])))))

(deftest parsing-assertions
  (is (= {:operand "status" :op "==" :value "201" :quoted? false :text "status == 201"}
         (expr/parse-assert "status == 201")))
  (is (= ["$.a.b" "ACKED"] ((juxt :operand :value) (expr/parse-assert "$.a.b == \"ACKED\""))))
  (is (= ["a b  c" true] ((juxt :value :quoted?) (expr/parse-assert "x == \"a b  c\""))))
  (is (= "a == b" (:value (expr/parse-assert "x == a == b"))) "value is the rest of the line")
  (is (= ["lines" ">=" "2"] ((juxt :operand :op :value) (expr/parse-assert "  lines   >=   2 "))))
  (doseq [t ["status" "status ==" "status === 1" "status ~ 1" "== 1" "x matches ("]]
    (is (bad? expr/parse-assert t) t))
  (is (= {:name "id" :source "$.orderId" :path ["orderId"]} (expr/parse-capture "id = $.orderId")))
  (is (= {:name "s" :source "status"} (expr/parse-capture "s=status")))
  (is (bad? expr/parse-capture "= x"))
  (let [u (expr/parse-until "$.Item.status.S == \"ACKED\" within 10s")]
    (is (= 10000 (:within-ms u)))
    (is (= "ACKED" (:value (:assert u)))))
  (is (bad? expr/parse-until "x == 1")))

(deftest operators
  (testing "numeric when both sides are numbers"
    (is (ev "n == 2" 2)) (is (ev "n == 2.0" 2)) (is (ev "n == 2" "2")) (is (ev "n < 10" 9))
    (is (ev "n < 10" "9") "text 9 < 10 numerically, not as text")
    (is (not (ev "n < 10" "x9")))
    (is (ev "n >= 2" 2)) (is (ev "n <= 2" 2)) (is (ev "n > 1.5" 2)) (is (ev "n != 3" 2))
    (is (not (ev "n == \"2\"" 3))))
  (testing "text otherwise"
    (is (ev "s == ACKED" "ACKED")) (is (ev "s == \"ACKED\"" "ACKED")) (is (not (ev "s == acked" "ACKED")))
    (is (ev "s < b" "a")) (is (ev "s != x" "y"))
    (is (ev "b == true" true)) (is (ev "x == null" nil true nil)))
  (testing "quoted literal is always text"
    (is (not (ev "n == \"2.0\"" 2))))
  (testing "contains"
    (is (ev "s contains lo w" "hello w" true nil) "rest of line")
    (is (ev "a contains 2" [1 2 3])) (is (not (ev "a contains 4" [1 2 3])))
    (is (ev "a contains k" {"k" 1})) (is (not (ev "a contains z" {"k" 1})))
    (is (ev "a contains ell" "hello")))
  (testing "matches"
    (is (ev "s matches ^ord-\\d+$" "ord-12")) (is (not (ev "s matches ^ord-\\d+$" "ord-x")))
    (is (ev "s matches /b+/" "abbbc")) (is (ev "s matches b" "abc") "find, not full match"))
  (testing "types"
    (is (ev "took < 2s" 1500 true :duration)) (is (not (ev "took < 2s" 2500 true :duration)))
    (is (ev "took < 500" 400 true :duration))
    (is (ev "at > 2026-10-16T10:00:00Z" (Instant/parse "2026-10-16T10:00:01Z") true :instant))
    (is (ev "at == 2026-10-16T10:00:00Z" (Instant/parse "2026-10-16T10:00:00Z") true :instant)))
  (testing "missing operands"
    (is (not (ev "x == 1" nil false nil))) (is (not (ev "x < 1" nil false nil)))
    (is (ev "x != 1" nil false nil)) (is (not (ev "x contains a" nil false nil)))
    (is (nil? (:was (expr/evaluate (expr/parse-assert "x == 1") 5 false nil))))))

(deftest capture-text
  (is (= ["1" "a" "true" "null" "[1,2]" "{\"a\":1}" "2"]
         (map expr/capture-text [1 "a" true nil [1 2] {"a" 1} 2.0]))))
