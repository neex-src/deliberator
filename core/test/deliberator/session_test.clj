(ns deliberator.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as t]
            [deliberator.workspace :as workspace]))

(deftest start-creates-layout-and-becomes-current
  (t/with-temp-home
    (let [s (session/start! "w" "DEMO-1" :title "first")]
      (is (= {:key "DEMO-1" :title "first" :ended nil :tags []} (dissoc s :started)))
      (is (inst? (java.util.Date/from (:started s))))
      (doseq [f ["session.edn" "notes.md" "scenarios" "runs"]]
        (is (.exists (.toFile (.resolve (session/dir "w" "DEMO-1") f))) f))
      (is (= "DEMO-1" (workspace/current-session "w")))
      (is (= s (session/current "w")))
      (is (= [s] (session/list "w"))))))

(deftest reopen-keeps-everything
  (t/with-temp-home
    (let [s1 (session/start! "w" "K-1" :title "t")
          note (.resolve (session/dir "w" "K-1") "notes.md")
          _ (spit (.toFile note) "my notes")
          ended (session/end! "w" "K-1")]
      (is (some? (:ended ended)))
      (is (nil? (session/current "w")) "ending the current session clears current")
      (let [s2 (session/start! "w" "K-1")]
        (is (nil? (:ended s2)))
        (is (= (:started s1) (:started s2)))
        (is (= "t" (:title s2)) "title kept when not given")
        (is (= "my notes" (slurp (.toFile note))) "files untouched")
        (is (= 1 (count (session/list "w"))))
        (is (= "K-1" (:key (session/current "w"))))))))

(deftest end-of-other-session-keeps-current
  (t/with-temp-home
    (session/start! "w" "A-1")
    (session/start! "w" "B-2")
    (session/end! "w" "A-1")
    (is (= "B-2" (:key (session/current "w"))))
    (is (= ["A-1" "B-2"] (map :key (session/list "w"))))
    (is (thrown? clojure.lang.ExceptionInfo (session/end! "w" "NOPE-1")))
    (is (= [] (session/list "other")))))

(deftest run-dir-collision-suffixes
  (t/with-temp-home
    (binding [store/*clock* (t/fixed-clock "2026-10-16T09:08:07Z")]
      (session/start! "w" "K-1")
      (let [a (session/run-dir! "w" "K-1" "order")
            b (session/run-dir! "w" "K-1" "order")
            c (session/run-dir! "w" "K-1" "order")
            d (session/run-dir! "w" "K-1" "estate/order scn")]
        (is (= ["20261016-090807-order" "20261016-090807-order-2" "20261016-090807-order-3"
                "20261016-090807-estate-order-scn"]
               (map #(str (.getFileName %)) [a b c d])))
        (is (every? #(.isDirectory (.toFile ^java.nio.file.Path %)) [a b c d]))
        (is (= (session/dir "w" "K-1") (.getParent (.getParent a))))))))

(deftest nothing-is-deleted
  (t/with-temp-home
    (binding [store/*clock* (t/fixed-clock "2026-10-16T09:08:07Z")]
      (session/start! "w" "K-1")
      (let [run (session/run-dir! "w" "K-1" "x")
            f (io/file (.toFile run) "run.edn")]
        (spit f "{}")
        (session/end! "w" "K-1")
        (session/start! "w" "K-1")
        (session/start! "w" "K-2")
        (is (.exists f))
        (is (= 2 (count (session/list "w"))))))))
