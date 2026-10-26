(ns deliberator.queries-test
  "Named queries are a convention, not an operation family: a one-step `.scn` under the session's
  `queries/`, run with `scenario.run`, its history `runs.list` with `scenario` (`dlb runs list --scenario <name>`). No
  Floci: the query here is a `wait` step; `deployed-estate-test` and `insights-estate-test` hold the AWS ones."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.test-util :as tu]))

(defn- call
  ([op args] (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :alive? (constantly true) :cancelled? (constantly false)
                            :daemon {:runs (atom {}) :checked (atom #{})}}
                        args)))

(deftest a-queries-scn-runs-from-the-session-and-is-listed-by-name
  (tu/with-temp-home
    (call "workspace.open" {:ws "default"})
    (call "session.start" {:key "Q-1" :ws "default"})
    (let [qdir (.resolve (session/dir "default" "Q-1") "queries")
          scn "# @env local\n\n### wait pause\n# @for 100ms\n"]
      (io/make-parents (.toFile (.resolve qdir "pause.scn")))
      (spit (.toFile (.resolve qdir "pause.scn")) scn)
      (spit (.toFile (.resolve qdir "other.scn")) scn)
      (testing "a relative queries/<name>.scn is found in the session directory"
        (let [r (call "scenario.run" {:path "queries/pause.scn" :ws "default" :session "Q-1"})]
          (is (= "ok" (name (:status r))) (pr-str r))
          (is (= 1 (:steps r)))))
      (call "scenario.run" {:path "queries/pause.scn" :ws "default" :session "Q-1"})
      (call "scenario.run" {:path "queries/other.scn" :ws "default" :session "Q-1"})
      (testing "runs.list --scenario <name> is the history of that query"
        (let [by-name (:runs (call "runs.list" {:ws "default" :session "Q-1" :scenario "pause" :limit 30}))
              by-path (:runs (call "runs.list" {:ws "default" :session "Q-1" :scenario "queries/pause.scn" :limit 30}))
              all (:runs (call "runs.list" {:ws "default" :session "Q-1" :limit 30}))]
          (is (= 2 (count by-name)))
          (is (= 2 (count by-path)))
          (is (every? #(str/ends-with? (:scenario %) "queries/pause.scn") by-name))
          (is (= 3 (count all)) "the other query is a different history")))
      (testing "the scenario file is recorded with its sha, the run keeps a copy"
        (let [run (first (:runs (call "runs.list" {:ws "default" :session "Q-1" :scenario "pause" :limit 1})))]
          (is (.isFile (io/file (:path run) "scenario.scn")))))
      (testing "scenarios/ wins over the session directory, a path that leaves the session is not looked up"
        (let [sdir (.resolve (session/dir "default" "Q-1") "scenarios")]
          (io/make-parents (.toFile (.resolve sdir "pause.scn")))
          (spit (.toFile (.resolve sdir "pause.scn")) (str scn "\n### wait again\n# @for 100ms\n"))
          (is (= 2 (:steps (call "scenario.run" {:path "pause.scn" :ws "default" :session "Q-1"}))) "scenarios/pause.scn is looked up first")
          (is (= 1 (:steps (call "scenario.run" {:path "queries/pause.scn" :ws "default" :session "Q-1"}))) "queries/ is its own name")
          (spit (.toFile (.resolve (session/dir "default" "Q-1") "outside.scn")) scn)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no scenario file"
                                (call "scenario.run" {:path "queries/../../Q-1/../../../x.scn" :ws "default" :session "Q-1"}))))))))
