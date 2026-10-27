(ns deliberator.fake-scrub-test
  "M6.6: the scrub rules, pure."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.fake.scrub :as scrub]))

(deftest text-rules
  (let [sc (scrub/scrubber nil)
        s (partial scrub/scrub-text sc)]
    (testing "e-mails are pseudonymised, the same address the same way, case-insensitively"
      (is (= "{\"a\":\"user-1@example.invalid\",\"b\":\"user-1@example.invalid\",\"c\":\"user-2@example.invalid\"}"
             (s "{\"a\":\"Jane.Doe@Example.com\",\"b\":\"jane.doe@example.com\",\"c\":\"bob+x@mail.example.org\"}"))))
    (testing "token fields of JSON bodies keep the formatting around them"
      (is (= "{ \"password\" : \"<scrubbed>\",\n  \"x\": 1, \"refresh_token\":\"<scrubbed>\" }"
             (s "{ \"password\" : \"hunter2\",\n  \"x\": 1, \"refresh_token\":\"abc.def\" }"))))
    (testing "key=value pairs of queries and forms"
      (is (= "/cb?access_token=<scrubbed>&x=1" (s "/cb?access_token=abc123&x=1")))
      (is (= "grant=x&client_secret=<scrubbed>" (s "grant=x&client_secret=zzz"))))
    (testing "person-name fields become stable pseudonyms; a bare name is left alone"
      (is (= "{\"firstName\":\"Name-1\",\"last_name\":\"Name-2\",\"name\":\"billing\",\"fullName\":\"Name-1\"}"
             (s "{\"firstName\":\"Jane\",\"last_name\":\"Doe\",\"name\":\"billing\",\"fullName\":\"Jane\"}"))))
    (testing "bearer tokens, JWTs and AWS key ids"
      (is (= "Bearer <scrubbed>" (s "Bearer abcdefghijklmnop")))
      (is (= "t=<jwt>" (s "t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk")))
      (is (= "k=<aws-key>" (s "k=AKIAABCDEFGHIJKLMNOP"))))
    (testing "the report counts, it never holds a value"
      (let [rep (scrub/report sc)]
        (is (pos? (get rep "email")))
        (is (not (str/includes? (pr-str rep) "hunter2")))))))

(deftest declared-extensions
  (let [sc (scrub/scrubber {:headers ["X-Tenant"] :json-keys ["pin"] :name-keys ["name"]
                            :patterns [{:re "ACME-\\d+" :with "ACME-0"}]})]
    (is (= "{\"pin\":\"<scrubbed>\",\"name\":\"Name-1\"} ACME-0" (scrub/scrub-text sc "{\"pin\":\"1234\",\"name\":\"Jane\"} ACME-98765")))
    (is (= {"x-tenant" "<scrubbed>" "accept" "*/*"}
           (into {} (map (fn [[k v]] [(str/lower-case k) v])) (:headers (:request (scrub/scrub-exchange sc {:request {:headers {"X-Tenant" "t1" "Accept" "*/*"}} :response {:headers {}}}))))))))

(deftest an-exchange-is-scrubbed-everywhere
  (let [sc (scrub/scrubber nil)
        ex {:seq 1
            :request {:method "POST" :path "/users/jane@example.com/ack" :query "token=abc"
                      :headers {"authorization" "Bearer abcdefghijkl" "x-api-key" "k1" "content-type" "application/json" "x-note" "hi bob@example.net"}
                      :body "{\"email\":\"jane@example.com\"}"}
            :response {:status 200 :headers {"set-cookie" "sid=1" "content-type" "application/json"}
                       :body "{\"owner\":\"jane@example.com\",\"firstName\":\"Jane\"}"}
            :match {:path-template "/users/jane@example.com/ack"}}
        out (scrub/scrub-exchange sc ex)
        text (pr-str out)]
    (is (= "/users/user-1@example.invalid/ack" (get-in out [:request :path])))
    (is (= "token=<scrubbed>" (get-in out [:request :query])))
    (is (= "<scrubbed>" (get-in out [:request :headers "authorization"])))
    (is (= "<scrubbed>" (get-in out [:response :headers "set-cookie"])))
    (is (= "application/json" (get-in out [:request :headers "content-type"])))
    (is (= "hi user-2@example.invalid" (get-in out [:request :headers "x-note"])))
    (is (not-any? #(str/includes? text %) ["jane@" "bob@" "abcdefghijkl" "\"k1\"" "Jane"]))
    (is (= {:status 200} (select-keys (:response out) [:status])))))
