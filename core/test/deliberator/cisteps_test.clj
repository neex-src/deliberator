(ns deliberator.cisteps-test
  "Piece 01: cutting a job log into its steps, over five REAL job logs of GitHub Actions (hosted and self-hosted runner,
  fetched from the-user/acme-*; `test/fixtures/ci/<name>.log` and the job's steps as the API gave them)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ci :as ci]
            [deliberator.cisteps :as cisteps]))

(defn- fixture [n]
  (let [job (json/read-str (slurp (io/file "test/fixtures/ci" (str n ".job.json"))) :key-fn keyword)
        raw (.getBytes (slurp (io/file "test/fixtures/ci" (str n ".log")) :encoding "UTF-8") "UTF-8")
        steps (mapv (fn [s] {:number (:number s) :name (:name s) :status (:status s) :conclusion (:conclusion s)
                             :started-at (:started_at s) :completed-at (:completed_at s)}) (:steps job))]
    {:lines (str/split-lines (ci/log-text raw)) :steps steps}))

(def names ["billing-red" "billing-green" "identity-apply" "lambdas-test" "provisioning-verify"])

(deftest every-real-log-is-cut-by-its-markers-into-a-partition
  (doseq [n names]
    (testing n
      (let [{:keys [lines steps]} (fixture n)
            c (cisteps/cut lines steps)]
        (is (= (count steps) (count c)) "every step of these runs has lines")
        (is (every? :exact (vals c)))
        (is (= :markers (:cut (val (first c)))))
        (is (= lines (vec (mapcat #(:lines (get c (:number %))) steps))) "the steps' lines are the whole log, in order, none twice")
        (is (str/starts-with? (first (:lines (get c 1))) "2026-") "Set up job starts with the first line of the job")))))

(deftest the-maven-step-has-the-assertion-and-nothing-of-its-neighbours
  (let [{:keys [lines steps]} (fixture "billing-red")
        c (cisteps/cut lines steps)
        mvn (:lines (get c 4))
        txt (str/join "\n" mvn)]
    (is (re-find #"##\[group\]Run mvn -B verify$" (first mvn)))
    (is (str/includes? txt "expected: <26> but was: <25>"))
    (is (str/includes? txt "PricingTest.java:48"))
    (is (str/includes? txt "##[error]Process completed with exit code 1."))
    (is (not (str/includes? txt "Post job cleanup")))
    (is (not (str/includes? txt "Run actions/setup-java")))
    (testing "the post steps follow the order of the API, and Complete job ends the log"
      (is (str/includes? (first (:lines (get c 7))) "Post job cleanup."))
      (is (str/includes? (str/join "\n" (:lines (get c 8))) "git config --local --unset-all"))
      (is (str/includes? (first (:lines (get c 9))) "Cleaning up orphan processes")))
    (testing "a step's name is not the marker's text: a named step is found by order"
      (let [{:keys [lines steps]} (fixture "identity-apply")
            c (cisteps/cut lines steps)]
        (is (= "Apply (validate, import, re-export, require no difference)" (:name (cisteps/select-step steps 4))))
        (is (re-find #"##\[group\]Run bb ci/ping.bb apply$" (first (:lines (get c 4)))))))))

(deftest a-cut-by-time-says-so
  (let [{:keys [lines steps]} (fixture "billing-red")
        ;; an extra group marker, as a composite action prints: the counts no longer match
        lines (vec (concat (take 150 lines) ["2026-11-05T12:15:57.0000000Z ##[group]Run inner step of a composite"] (drop 150 lines)))
        c (cisteps/cut lines steps)]
    (is (every? #(false? (:exact %)) (vals c)))
    (is (every? #(= :time (:cut %)) (vals c)))
    (is (str/includes? (str/join "\n" (:lines (get c 4))) "expected: <26> but was: <25>") "the window still holds the assertion")
    (is (str/starts-with? cisteps/exact-note "# deliberator:"))))

(deftest step-selection-and-durations
  (let [{:keys [steps]} (fixture "billing-red")]
    (is (= 4 (:number (cisteps/select-step steps "4"))))
    (is (= 4 (:number (cisteps/select-step steps "mvn"))))
    (is (= 7 (:number (cisteps/select-step steps "Post Run actions/setup-java@v4"))))
    (is (= :ambiguous (cisteps/select-step steps "Post Run")))
    (is (nil? (cisteps/select-step steps "nope")))
    (is (nil? (cisteps/select-step steps "5")) "numbers have gaps: 5 and 6 are hidden steps")
    (is (= 20 (cisteps/duration-s "2026-11-05T12:15:56Z" "2026-11-05T12:16:16Z")))
    (is (nil? (cisteps/duration-s nil "2026-11-05T12:16:16Z")))
    (is (= "3m 05s" (cisteps/fmt-duration 185)))))
