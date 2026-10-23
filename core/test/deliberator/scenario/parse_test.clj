(ns deliberator.scenario.parse-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.scenario.parse :as parse])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.util Arrays)))

(defn- fixture [n] (io/file "test/fixtures" n))
(defn- fx ^bytes [n] (java.nio.file.Files/readAllBytes (.toPath (fixture n))))
(defn- bytes-of ^bytes [s] (.getBytes ^String s StandardCharsets/UTF_8))
(defn- body-str [step] (some-> (:body step) (String. StandardCharsets/UTF_8)))
(defn- thrown [& args]
  (try (apply parse/parse args) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def kinds
  {"http" {:kind "http" :verbs #{nil} :target :none
           :directives []}
   "sqs" {:kind "sqs" :verbs #{"send" "receive"} :target :required
          :directives [{:name "attr" :repeatable? true}]}
   "dynamodb" {:kind "dynamodb" :verbs #{"get" "query" "scan"} :target :required}
   "logs" {:kind "logs" :verbs #{nil} :target :required
           :directives [{:name "filter"} {:name "for"}]}
   "wait" {:kind "wait" :verbs #{nil} :target :none :directives [{:name "for"}]}
   "sql" {:kind "sql" :verbs #{nil} :target :required}})

(deftest header-forms
  (let [{:keys [header steps]} (parse/parse (fx "shapes.scn") :path "test/fixtures/shapes.scn")]
    (is (= "local" (:env header)))
    (is (= [{:name "orderId" :value "ord-{{uuid}}" :line 3}
            {:name "topic" :value "orders" :line 4}] (:vars header)))
    (is (= 7 (count steps)))))

(deftest header-forms-from-text
  (let [{:keys [header steps]} (parse/parse "\n// a\n# plain comment\n# @env dev\n# @var a = 1 2 3\n# @var b =\n")]
    (is (= "dev" (:env header)))
    (is (= ["1 2 3" ""] (map :value (:vars header))))
    (is (= [] steps)))
  (is (= {:type :scenario/parse :line 2 :message "unexpected text before the first ### step: \"oops\""}
         (thrown "# @env x\noops\n### a\n")))
  (is (= 2 (:line (thrown "# @env a\n# @env b\n"))))
  (is (= 1 (:line (thrown "# @var 9x = 1\n"))))
  (is (= 1 (:line (thrown "# @assert x == 1\n")))))

(deftest every-step-shape
  (let [{:keys [steps]} (parse/parse (String. (fx "shapes.scn") StandardCharsets/UTF_8) :kinds kinds)
        brief (map #(select-keys % [:n :kind :verb :target :name]) steps)]
    (is (= [{:n 1 :kind "http" :verb nil :target nil :name "create-order"}
            {:n 2 :kind "sqs" :verb "send" :target "queue/inbox" :name nil}
            {:n 3 :kind "sqs" :verb "receive" :target "queue/outbox" :name "got-it"}
            {:n 4 :kind "dynamodb" :verb "get" :target "table/messages" :name nil}
            {:n 5 :kind "logs" :verb nil :target "log/service-a" :name nil}
            {:n 6 :kind "wait" :verb nil :target nil :name "pause"}
            {:n 7 :kind "sql" :verb nil :target "db/main" :name "q1"}]
           brief))))

(deftest step-lines-and-directives
  (let [{:keys [steps]} (parse/parse (String. (fx "shapes.scn") StandardCharsets/UTF_8))
        s1 (first steps)]
    (is (= [6 17 21 24 26 30 33] (map :line steps))
        "line numbers are of the ### lines")
    (is (= [{:name "assert" :value "status == 201" :line 7}
            {:name "capture" :value "id = $.orderId" :line 8}]
           (:directives s1)))
    (is (= "until" (:name (first (:directives (nth steps 2))))))
    (is (= "$.Item.status.S == \"ACKED\" within 10s" (:value (first (:directives (nth steps 2))))))))

(deftest lexical-split-without-kinds
  (let [t "### x a b\n### x queue/q\n### x name\n### x {{service/a}}\n"
        steps (:steps (parse/parse t))]
    (is (= [["a" "b"] ["queue/q" nil] [nil "name"] ["{{service/a}}" nil]]
           (map (fn [s] [(:target s) (:name s)]) steps))
        "two words are target and name; a lone word is a target only if it looks like one")))

(deftest bodies-are-exact
  (let [{:keys [steps]} (parse/parse (String. (fx "shapes.scn") StandardCharsets/UTF_8))
        s1 (first steps)]
    (testing "quoted ### , indented ### and #### stay body"
      (is (str/includes? (body-str s1) "### this line starts with hashes but sits inside a JSON string"))
      (is (str/includes? (body-str s1) "   ### indented is body too"))
      (is (str/ends-with? (body-str s1) "#### four hashes are body\n")))
    (testing "body without directives, then next header"
      (is (= "{ \"id\": {\"S\": \"{{orderId}}\"} }\n" (body-str (nth steps 3)))))
    (testing "byte ranges point at the original bytes"
      (let [src (bytes-of (String. (fx "shapes.scn") StandardCharsets/UTF_8))
            [a b] (:body-range s1)]
        (is (Arrays/equals ^bytes (:body s1) (Arrays/copyOfRange src (int a) (int b))))))))

(deftest trailing-blank-lines-stripped-final-newline-kept
  (is (= "{\"a\":1}\n" (body-str (first (:steps (parse/parse "### x\n{\"a\":1}\n\n\n### y\n"))))))
  (is (= "{\"a\":1}\n" (body-str (first (:steps (parse/parse "### x\n{\"a\":1}\n\n"))))))
  (is (= "{\"a\":1}\n" (body-str (first (:steps (parse/parse "### x\n{\"a\":1}\n"))))))
  (is (= "{\"a\":1}" (body-str (first (:steps (parse/parse "### x\n{\"a\":1}"))))))
  (is (= "a\n\nb\n" (body-str (first (:steps (parse/parse "### x\na\n\nb\n\n### y\n"))))))
  (is (nil? (:body (first (:steps (parse/parse "### x\n# @set a=1\n\n\n### y\n"))))))
  (is (nil? (:body (first (:steps (parse/parse "### x"))))))
  (testing "leading blank lines after the last directive belong to the body"
    (is (= "\nGET /\n" (body-str (first (:steps (parse/parse "### x\n# @a 1\n\nGET /\n"))))))))

(deftest crlf-and-utf8-round-trip
  (let [{:keys [steps]} (parse/parse (fx "crlf.scn"))
        [a b] steps]
    (is (= "a" (:name a)))
    (is (= [{:name "set" :value "x=1" :line 2}] (:directives a)))
    (is (= "{\"k\": \"é\"}\r\n" (body-str a)) "CRLF kept inside, trailing blank CRLF line stripped")
    (is (= "last no newline" (body-str b)))))

(deftest invalid-utf8-bodies-survive
  (let [src (byte-array (concat (bytes-of "### x\n") (byte-array [0xff 0xfe 0x00 0x41 0x0a])))
        s (first (:steps (parse/parse src)))]
    (is (Arrays/equals ^bytes (:body s) (byte-array [0xff 0xfe 0x00 0x41 0x0a])))))

(deftest body-from-file
  (let [path (str (.getAbsolutePath (fixture "shapes.scn")))
        {:keys [steps]} (parse/parse (String. (fx "shapes.scn") StandardCharsets/UTF_8) :path path)
        s2 (nth steps 1)]
    (is (= "./payloads/no-newline.json" (:body-file s2)))
    (is (= "{\"sku\":\"a-1\",\"qty\":2}" (body-str s2)) "file bytes, no newline added")
    (is (nil? (:body-range s2))))
  (testing "trailing blank lines of a file body are kept: it is byte-exact"
    (let [p (str (.getAbsolutePath (fixture "shapes.scn")))
          src "### x\n# @body ./payloads/trailing-blanks.json\n\n### y\n"
          s (first (:steps (parse/parse src :path p)))]
      (is (= "{\"sku\":\"b-2\",\n \"qty\":3}\n\n\n" (body-str s)))))
  (testing "without :path only the name is recorded"
    (let [s (second (:steps (parse/parse (String. (fx "shapes.scn") StandardCharsets/UTF_8))))]
      (is (nil? (:body s))) (is (= "./payloads/no-newline.json" (:body-file s))))))

(deftest errors-carry-line-numbers
  (let [p (str (.getAbsolutePath (fixture "shapes.scn")))]
    (is (= 2 (:line (thrown "### x\n# @body ./nope.json\n" :path p))))
    (is (= 2 (:line (thrown "### x\n# @body ./payloads/no-newline.json\ninline\n" :path p))))
    (is (= 4 (:line (thrown "### x\n# @set a=1\nbody\n# @assert a == 1\n"))) "directive after the body")
    (is (= 3 (:line (thrown "\n\n### sqs send q/a b c\n" :kinds kinds))) "too many words")
    (is (= 1 (:line (thrown "### nosuch\n" :kinds kinds))))
    (is (= 1 (:line (thrown "### sqs queue/inbox\n" :kinds kinds))) "sqs needs a verb")
    (is (= 1 (:line (thrown "### sqs send\n" :kinds kinds))) "sqs needs a target")
    (is (= 3 (:line (thrown "### http a\n\n# @bogus x\n" :kinds kinds))))
    (is (str/starts-with? (ex-message (try (parse/parse "### nosuch\n" :kinds kinds) (catch Exception e e)))
                          "line 1: unknown step kind"))))

(deftest name-directive-overrides
  (is (= "z" (:name (first (:steps (parse/parse "### x y\n# @name z\n")))))))

(deftest header-inside-json-string-is-body-unless-string-never-closes
  (let [t "### x\n{\"a\": \"\n### y\n\"}\n### z\n"]
    (is (= ["x" "z"] (map :kind (:steps (parse/parse t))))))
  (testing "an unclosed quote does not swallow the rest of the file"
    (let [t "### x\n{\"a\": \"oops\n### y\n### z\n"]
      (is (= ["x" "y" "z"] (map :kind (:steps (parse/parse t))))))))

(deftest prose-among-the-directives-of-a-step
  ;;: the tour explains every step right under its ### line
  (let [{:keys [steps]} (parse/parse (str "### op marker.add mark\n# What this step does,\n# over two lines.\n# @assert ok == true\n\n"
                                          "# More prose after a blank line.\n# @capture id = $[0].id\n{\"line\": 2}\n\n"
                                          "### shell\n# prose\necho one\n# a bash comment in the script stays script\necho two\n"))
        [a b] steps]
    (is (= [["assert" "ok == true"] ["capture" "id = $[0].id"]] (map (juxt :name :value) (:directives a))))
    (is (= "{\"line\": 2}\n" (body-str a)) "prose is not body")
    (is (= "echo one\n# a bash comment in the script stays script\necho two\n" (body-str b)) "a # line after the body started is body")))
