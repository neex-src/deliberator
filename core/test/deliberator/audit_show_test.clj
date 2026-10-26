(ns deliberator.audit-show-test
  "`audit.show` (M4.6): where a session's audit.log is and one summary per line, so the IDE opens the file and marks the confirmations
  without knowing the store layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.test-util :as tu]))

(defn- call [op args] (ops/call! op {} args))

(deftest audit-show-of-a-session-and-of-the-workspace
  (tu/with-temp-home
    (call "workspace.open" {:ws "w"})
    (testing "no audit file yet: exists false, nothing listed, the path still named"
      (let [r (call "audit.show" {:ws "w"})]
        (is (false? (:exists r)))
        (is (= "workspace" (:scope r)))
        (is (= 0 (:count r)))
        (is (= [] (:lines r)))))
    (let [k (:key (session/start! "w" "T-1"))
          p (io/file (:path (call "audit.show" {:ws "w"})))]
      (testing "the current session's audit.log, not the workspace's"
        (is (= "session" (:scope (call "audit.show" {:ws "w"}))))
        (is (= k (:session (call "audit.show" {:ws "w"})))))
      (io/make-parents p)
      (spit p (str "{:at #inst \"2026-10-26T10:00:00Z\" :env \"dev\" :service :ssm :op \"GetParameter\" :outcome :ok}\n"
                   "{:at #inst \"2026-10-26T10:00:01Z\" :env \"dev\" :service :ssm :op \"PutParameter\" :outcome :confirm-required}\n"
                   "{:at #inst \"2026-10-26T10:00:02Z\" :env \"dev\" :service :ssm :op \"PutParameter\" :outcome :ok :confirmation :used}\n"
                   "{:at #inst \"2026-10-26T10:00:03Z\" :env \"dev\" :service :sqs :op \"DeleteQueue\" :outcome :refused :reason :read-only}\n"))
      (let [r (call "audit.show" {:ws "w" :session k})]
        (is (true? (:exists r)))
        (is (= 4 (:count r)))
        (is (= {"ok" 2 "confirm-required" 1 "refused" 1} (:outcomes r)))
        (is (= 1 (:confirmed r)) "the call that went through with a confirmation")
        (is (= "used" (:confirmation (nth (:lines r) 2))))
        (is (= "read-only" (:reason (nth (:lines r) 3))))
        (is (= [1 2 3 4] (map :n (:lines r))))
        (is (= ["confirm-required" "ok"] (map :outcome (subvec (:lines r) 1 3))))
        (is (= "PutParameter" (:op (second (:lines r)))))
        (is (= (str p) (:path r)))))))
