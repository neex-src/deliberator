(ns deliberator.process-test
  "`deliberator.process/run!` against real processes: output streamed and returned, a cancel kills the tree (the child too),
  the budget kills it with a typed error, a command that is not there is a typed error."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.process :as process])
  (:import (java.lang ProcessHandle)))

(defn- alive? [pid] (boolean (some-> (.orElse (ProcessHandle/of (long pid)) nil) .isAlive)))

(defn- wait-dead [pid ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (not (alive? pid)) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 50) (recur))))))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))

(deftest output-is-streamed-and-returned
  (let [seen (atom [])
        r (process/run! ["sh" "-c" "echo one; echo two >&2; echo three; exit 3"] {:line! #(swap! seen conj %)})]
    (is (= 3 (:exit r)))
    (is (= ["one" "two" "three"] (:lines r)) "stderr merged, in order")
    (is (= ["one" "two" "three"] @seen))))

(deftest env-dir-and-extra-env
  (let [r (process/run! ["sh" "-c" "pwd; echo $DLB_T; echo ${HOME:-nohome}"] {:dir "/tmp" :env {"DLB_T" "x"}})]
    (is (= ["/tmp" "x" "nohome"] (:lines r)) "env replaces the inherited environment"))
  (is (= ["y"] (:lines (process/run! ["sh" "-c" "echo $DLB_T"] {:extra-env {"DLB_T" "y"}})))))

(deftest a-cancel-kills-the-process-and-its-children
  (let [cancel (promise)
        child (promise)
        ;; the script prints its sleeping child's pid, then waits for it
        fut (future (thrown #(process/run! ["sh" "-c" "sleep 60 & echo $!; wait"]
                                           {:on-cancel! (fn [f] (deliver cancel f))
                                            :line! (fn [l] (when-not (realized? child) (deliver child (parse-long (str/trim l)))))
                                            :what "the sleeper"})))
        pid (deref child 5000 nil)]
    (is (some? pid) "the child reported its pid")
    (is (alive? pid))
    ((deref cancel 1000 (fn [])))                       ; what the protocol does on cancel
    (let [e (deref fut 5000 :still-running)]
      (is (= :process/cancelled (:type e)) (pr-str e))
      (is (= "the sleeper" (:what e)))
      (is (= [(str pid)] (:lines e)) "the lines so far ride along"))
    (is (wait-dead pid 2000) (str "the child sleep " pid " should be dead"))))

(deftest the-budget-kills-the-tree-with-a-typed-error
  (let [child (promise)
        t0 (System/currentTimeMillis)
        e (thrown #(process/run! ["sh" "-c" "echo start; sleep 60 & echo $!; wait"]
                                 {:budget-ms 400 :what "slow"
                                  :line! (fn [l] (when (re-matches #"\d+" (str/trim l)) (deliver child (parse-long (str/trim l)))))}))]
    (is (= :process/timeout (:type e)) (pr-str e))
    (is (= 400 (:budget-ms e)))
    (is (< (- (System/currentTimeMillis) t0) 5000) "killed around the budget, not after the sleep")
    (is (= "start" (first (:lines e))))
    (is (str/includes? (:message e) "slow ran longer than 0 s"))
    (is (wait-dead (deref child 1000 -1) 2000) "the child is gone too")))

(deftest a-missing-command-is-typed
  (let [e (thrown #(process/run! ["dlb-no-such-command-xyz" "a"] {}))]
    (is (= :process/cannot-start (:type e)))
    (is (str/includes? (:hint e) "PATH"))))
