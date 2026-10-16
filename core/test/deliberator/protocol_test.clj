(ns deliberator.protocol-test
  "Layer 3, the pure half: framing, JSON values, argument validation."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.protocol :as p])
  (:import (java.io ByteArrayInputStream)
           (java.nio.file Paths)
           (java.time Instant)))

(deftest encode-makes-one-line-of-plain-json
  (let [s (p/encode {:id "a" :ok true
                     :result {:at (Instant/parse "2026-10-16T10:00:00Z") :kind :aws/error :set #{1}
                              :path (Paths/get "/x/y" (make-array String 0)) (keyword "queue/inbox") "q"
                              :text "line1\nline2 é  " :ratio 1/2 :nan Double/NaN}})]
    (is (not (.contains s "\n")))
    (is (= {"id" "a" "ok" true
            "result" {"at" "2026-10-16T10:00:00Z" "kind" "aws/error" "set" [1] "path" "/x/y"
                      "queue/inbox" "q" "text" "line1\nline2 é  " "ratio" 0.5 "nan" "NaN"}}
           (json/read-str s)))))

(deftest decode-requests
  (testing "a good request"
    (is (= {:request {:id "c1" :op "session.start" :args {:key "T-1" :title "x"} :cancel-on-close false}}
           (p/decode "{\"id\":\"c1\",\"op\":\"session.start\",\"args\":{\"key\":\"T-1\",\"title\":\"x\"}}")))
    (is (= {:id 7 :op "hello" :args {} :cancel-on-close true}
           (:request (p/decode "{\"id\":7,\"op\":\"hello\",\"cancel-on-close\":true}")))))
  (testing "bad ones are terminal errors"
    (doseq [[line id] [["nope" nil] ["3" nil] ["{}" nil] ["{\"id\":\"a\"}" "a"] ["{\"id\":\"a\",\"op\":\"\"}" "a"]
                       ["{\"id\":[1],\"op\":\"x\"}" nil] ["{\"id\":\"a\",\"op\":\"x\",\"args\":3}" "a"]]]
      (let [{:keys [error]} (p/decode line)]
        (is (false? (:ok error)) line)
        (is (= id (:id error)) line)
        (is (= "bad-request" (get-in error [:error :type])) line)))))

(deftest lines-are-read-whole-and-capped
  (let [rd #(p/read-line* (ByteArrayInputStream. (.getBytes ^String % "UTF-8")))]
    (is (= "abc" (rd "abc\n")))
    (is (= "abc" (rd "\n\r\n  \nabc\r\n")))
    (is (= "tail" (rd "tail")) "an unterminated last line is still a line")
    (is (= ::p/eof (rd "")))
    (is (= ::p/eof (rd "\n\n")))
    (is (= ::p/too-long (rd (str (apply str (repeat (inc p/max-line-bytes) "x")) "\n"))))))

(deftest every-op-validates-its-arguments
  (let [schema {:key {:type :string :required true :doc "k"} :n {:type :int :default 3 :doc "n"}
                :xs {:type :ints :doc "x"}}]
    (is (= {:key "a" :n 3} (ops/validate-args "t" schema {:key "a"})))
    (is (= {:key "a" :n 4 :xs [1 2]} (ops/validate-args "t" schema {:key "a" :n 4 :xs [1 2]})))
    (let [e (try (ops/validate-args "t" schema {:n "x" :zzz 1 :xs [1 "b"]}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bad-args (:type (ex-data e))))
      (is (= ["unknown argument zzz" "missing required argument key" "argument n must be int" "argument xs must be int[]"]
             (:problems (ex-data e)))))))

(deftest every-op-in-the-table-describes-itself
  (let [d (ops/describe)]
    (is (= (count ops/table) (count d)))
    (is (apply distinct? (map :op d)))
    (is (= "mutate" (:class (first (filter #(= "scenario.run" (:op %)) d)))))))

(deftest error-maps
  (is (= {:type "env/unknown-target" :message "m" :hint "h" :known [:a]}
         (ops/error-map (ex-info "m" {:type :env/unknown-target :hint "h" :known [:a] :secret 1}))))
  (is (= {:type "internal" :message "boom"} (ops/error-map (RuntimeException. "boom")))))
