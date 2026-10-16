(ns deliberator.scenario.render-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.fake-kind :as fake]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.render :as render]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as t]))

(use-fixtures :each (fn [f] (fake/reset-state!) (f)))

(def env {:name "local" :kind :local})
(def fixed-uuid "11111111-2222-3333-4444-555555555555")

(defn- run-fixture
  "Runs test/fixtures/<name> deterministically (fixed clock, uuid, elapsed) in a fresh home."
  [name clock & {:keys [uuid]}]
  (binding [store/*clock* (t/fixed-clock clock)
            engine/*elapsed* (fn [_ _] 12)]
    (engine/run! "w" env (str (io/file "test/fixtures" name)) :session "T-1"
                 :builtins {"uuid" (or uuid fixed-uuid)}
                 :resolve-target (fn [k] (str "http://" k)))))

(defmacro in-home [& body]
  `(t/with-temp-home (session/start! "w" "T-1") ~@body))

(deftest golden-result
  (in-home
   (let [res (run-fixture "order.scn" "2026-10-16T10:15:02Z")
         txt (slurp (get-in res [:paths :result]))
         golden (slurp (io/file "test/fixtures/order.result.txt"))]
     (is (= :failed (:status res)))
     (is (= golden txt))
     (testing "render-text is the same text without writing"
       (is (= golden (render/render-text (get-in res [:paths :run]))))))))

(deftest diff-of-two-runs-with-different-ids-is-empty
  (in-home
   (let [a (run-fixture "order.scn" "2026-10-16T10:15:02Z")
         b (run-fixture "order.scn" "2026-10-17T23:59:58Z" :uuid "99999999-8888-7777-6666-555555555555")]
     (is (not= (:run-id a) (:run-id b)))
     (is (not= (slurp (get-in a [:paths :result])) (slurp (get-in b [:paths :result])))
         "the raw texts do differ (ids)")
     (is (= "" (render/diff (get-in a [:paths :run]) (get-in b [:paths :run])))))))

(deftest diff-shows-real-differences
  (in-home
   (let [a (run-fixture "order.scn" "2026-10-16T10:15:02Z")
         f (io/file (str (store/home)) "changed.scn")
         _ (spit f (str/replace (slurp (io/file "test/fixtures/order.scn"))
                                "# @assert status == 201" "# @assert status == 202"))
         b (binding [store/*clock* (t/fixed-clock "2026-10-16T11:00:00Z")]
             (engine/run! "w" env (str f) :session "T-1" :resolve-target str))
         d (render/diff (get-in a [:paths :run]) (get-in b [:paths :run]))]
     (is (str/includes? d "--- 20261016-101502-order\n+++ 20261016-110000-changed\n"))
     (is (str/includes? d "-✓ status == 201\n"))
     (is (str/includes? d "+✗ status == 202 (was 201)\n") "a number is not volatile: it shows")
     (is (not (re-find #"ord-[0-9a-f]" d)) "ids stay normalised")
     (is (re-find #"(?m)^@@ -\d+,\d+ \+\d+,\d+ @@$" d)))))

(deftest unified-diff-hunks
  (let [a (mapv str (range 1 21))
        b (-> a (assoc 4 "five") (assoc 15 "sixteen"))
        d (render/unified a b "a" "b")]
    (is (= (str "--- a\n+++ b\n"
                "@@ -2,7 +2,7 @@\n 2\n 3\n 4\n-5\n+five\n 6\n 7\n 8\n"
                "@@ -13,7 +13,7 @@\n 13\n 14\n 15\n-16\n+sixteen\n 17\n 18\n 19\n")
           d))
    (is (= "" (render/unified a a "a" "a")))
    (is (= "--- a\n+++ b\n@@ -0,0 +1,2 @@\n+x\n+y\n" (render/unified [] ["x" "y"] "a" "b")))
    (is (= "--- a\n+++ b\n@@ -1,2 +0,0 @@\n-x\n-y\n" (render/unified ["x" "y"] [] "a" "b")))))

(deftest normalisation-rules
  (in-home
   (let [mk (fn [clock facts-id out]
              (binding [store/*clock* (t/fixed-clock clock)]
                (let [f (io/file (str (store/home)) (str "n" (System/nanoTime) ".scn"))]
                  (spit f (str "### fake s\n# @set id=" facts-id "\n# @set at=" clock "\n# @set took=5\n"
                               "# @assert id == nothing\n# @assert took > 100\n# @assert at < 2000-01-01T00:00:00Z\n"
                               out))
                  (engine/run! "w" env (str f) :session "T-1"))))
         a (mk "2026-10-16T10:15:02Z" "req-aaaa-1" "line req-aaaa-1 at 2026-10-16T10:15:02.123Z 3f2b8e1a-0000-4000-8000-000000000001 {\"MD5OfMessageAttributes\":\"72e86fdd5ea5a2ed84557b26bde7def6\"}\n")
         b (mk "2026-10-17T11:16:03Z" "req-bbbb-2" "line req-bbbb-2 at 2026-10-17T11:16:03.456Z 7c1d9a2b-0000-4000-8000-000000000002 {\"MD5OfMessageAttributes\":\"16e9df01f1cfb77bd86df980bcb7200f\"}\n")
         ta (render/render-text (get-in a [:paths :run]) :normalise? true)]
     (is (= "" (render/diff (get-in a [:paths :run]) (get-in b [:paths :run]))))
     (is (str/includes? ta "line <id>"))
     (is (not (str/includes? ta "req-aaaa")))
     (is (str/includes? ta "<instant>") "instant-typed fact and iso text")
     (is (str/includes? ta "<uuid>"))
     (is (str/includes? ta "\"MD5OfMessageAttributes\":\"<md5>\"") "SQS attribute hash follows the ids in it")
     (is (str/includes? ta "✗ id == nothing (was <id>)"))
     (is (str/includes? ta "✗ took > 100 (was <duration>)"))
     (is (str/includes? ta "✗ at < <instant> (was <instant>)"))
     (is (str/includes? ta "· <ms> ms")))))

(deftest sections-and-trailer-shapes
  (in-home
   (let [f (io/file (str (store/home)) "s.scn")
         _ (spit f "### fake ok n1\n# @display one\n\ttabbed\n### fake n2\n")
         res (engine/run! "w" env (str f) :session "T-1")
         txt (slurp (get-in res [:paths :result]))]
     (is (re-find #"(?s)\A### fake ok n1 · ok · \d+ ms\none\n\n### fake n2 · ok · \d+ ms\nok\n\nRUN ok steps 2 failed 0\n\z" txt))
     (is (not (str/includes? txt "FAILED")) "no FAILED line when nothing failed"))))
