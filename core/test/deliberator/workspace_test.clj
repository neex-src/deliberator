(ns deliberator.workspace-test
  (:require [clojure.test :refer [deftest is]]
            [deliberator.test-util :as t]
            [deliberator.workspace :as workspace]))

(deftest load-default-save-and-reload
  (t/with-temp-home
    (let [w (workspace/load "demo")]
      (is (= {:name "demo" :environments [] :repos [] :current-session nil}
             (dissoc w :created)))
      (is (nil? (workspace/current-session "demo")))
      (workspace/save! (assoc w :environments ["local"] :extra {:kept true}))
      (let [w2 (workspace/load "demo")]
        (is (= ["local"] (:environments w2)))
        (is (= {:kept true} (:extra w2)))
        (is (= (:created w) (:created w2)))))))

(deftest current-session-roundtrip
  (t/with-temp-home
    (workspace/set-current-session! "demo" "DEMO-1")
    (is (= "DEMO-1" (workspace/current-session "demo")))
    (is (= "DEMO-1" (workspace/current-session (workspace/load "demo"))))
    (workspace/set-current-session! "demo" nil)
    (is (nil? (workspace/current-session "demo")))
    (is (thrown? clojure.lang.ExceptionInfo (workspace/set-current-session! "demo" "../x")))))
