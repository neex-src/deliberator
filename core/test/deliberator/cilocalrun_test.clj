(ns deliberator.cilocalrun-test
  "Piece 03: the reducer over a REAL `act --json` run (fixtures/ci/act-two-jobs.json, workflow t.yml), the workflow parser, the atomic run number."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ciworkflow :as wf]
            [deliberator.cilocalrun :as lr]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def workflow (wf/parse-file "test/fixtures/ci/t.yml"))

(def final
  (let [recs (keep lr/read-jsonl-line (str/split-lines (slurp "test/fixtures/ci/act-two-jobs.json")))]
    (lr/finish-state (reduce #(lr/apply-record %1 workflow %2) {:jobs [] :lines {}} recs) workflow false)))

(deftest the-workflow-is-parsed-with-a-yaml-parser
  (is (= "t" (:name workflow)))
  (is (= ["workflow_dispatch" "push"] (:events workflow)))
  (is (= [{:name "tag" :type nil :default "x" :description "t" :options nil :required false}] (:inputs workflow)))
  (is (= [["build" 0 []] ["deploy" 1 ["build"]]] (mapv (juxt :id :stage :needs) (:jobs workflow))))
  (is (= "actions/checkout@v4" (:uses (first (:steps (first (:jobs workflow))))))))

(deftest act-json-becomes-jobs-and-steps
  (let [[build deploy] (:jobs final)]
    (is (= ["build" "deploy"] (mapv :id (:jobs final))))
    (is (= ["success" "failure"] (mapv :conclusion (:jobs final))))
    (is (= ["Set up job" "Run actions/checkout@v4" "Run echo \"img=foo:zz\" >> \"$GITHUB_OUTPUT\"" "Say hi" "Complete job"] (mapv :name (:steps build))))
    (is (= [1 2 3 4 5] (mapv :number (:steps build))))
    (is (= ["success" "success" "success" "success" "success"] (mapv :conclusion (:steps build))))
    (is (= ["Set up job" "use output" "boom" "after" "Complete job"] (mapv :name (:steps deploy))))
    (is (= ["success" "success" "failure" "success" "success"] (mapv :conclusion (:steps deploy))) "the failing step is red, `if: always()` still ran")
    (is (every? #(= "completed" (:status %)) (mapcat :steps (:jobs final))))
    (is (every? :duration-s (mapcat :steps (:jobs final))))
    (testing "a step's lines are its own output with the runner's timestamp, annotations and errors"
      (is (= ["hello" "##[group]inner" "inside" "##[endgroup]"] (map #(subs % 29) (get-in final [:lines "build" 4]))))
      (is (= ["foo:zz"] (map #(subs % 29) (get-in final [:lines "deploy" 2]))))
      (is (re-find #"##\[error\]exitcode '3': failure" (first (get-in final [:lines "deploy" 3])))))))

(deftest a-job-that-never_ran-is-skipped-and-a-run-with-a-failed-job-is-red
  (let [wf2 (update workflow :jobs conj {:id "later" :name "later" :needs ["deploy"] :steps [] :stage 2})
        st (lr/finish-state {:jobs (take 1 (:jobs final)) :lines {}} wf2 false)]
    (is (= [["build" "success"] ["deploy" "skipped"] ["later" "skipped"]] (mapv (juxt :id :conclusion) (:jobs st)))))
  (is (= "failure" (lr/run-conclusion (:jobs final))))
  (is (= "success" (lr/run-conclusion [{:conclusion "success"} {:conclusion "skipped"}]))))

(deftest run-numbers-are-claimed-atomically
  (tu/with-temp-home
    (let [ns (doall (pmap (fn [_] (:n (lr/claim! "w" "acme/widgets"))) (range 24)))]
      (is (= (map str (range 1 25)) (sort-by parse-long ns)) "24 runs started at once got 24 different numbers"))))
