(ns deliberator.follow-estate-test
  "`logs.fetch {follow true}` on service A's container against the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`,
  `DELIBERATOR_HOME`; without them a SKIP line): the follow starts, an order runs through the estate, A's log lines reach the
  `logs.line` events while the follow is still open (the ACK line within seconds), and the cancelled follow leaves exactly ONE
  file in the session's `logs/`, read-only, the same inode as its raw blob."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.steps.dynamodb] [deliberator.steps.eventbridge] [deliberator.steps.http]
            [deliberator.steps.lambda] [deliberator.steps.logs] [deliberator.steps.scheduler]
            [deliberator.steps.sql] [deliberator.steps.sqs] [deliberator.steps.wait]))

(defn- call [op args & [ctx]]
  (ops/call! op (merge {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} ctx) args))

(defn- wait-until [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 50) (recur))))))

(deftest follow-a-service-container-while-an-order-runs
  (if-let [ws (fu/estate-ws)]
    (let [sess (str "FOLLOW-" (System/currentTimeMillis))
          _ (call "session.start" {:ws ws :key sess})
          logs-dir (io/file (str (session/dir ws sess)) "logs")
          events (atom [])
          cancelled (atom false)
          fut (future (call "logs.fetch" {:ws ws :target "log/service-a" :follow true :since "1s" :session sess}
                            {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)}))]
      (try
        (Thread/sleep 1500)
        (let [t0 (System/currentTimeMillis)
              run (future (call "scenario.run" {:ws ws :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))}))]
          (testing "the ACK line of A is an event within seconds, while the follow is open"
            (is (wait-until #(some (fn [e] (str/includes? (str (:text e)) "ACK received")) @events) 15000))
            (is (not (realized? fut)) "still following"))
          (is (= :ok (:status @run))))
        (testing "one file in the session while it runs, growing"
          (is (= 1 (count (.listFiles logs-dir))))
          (is (str/includes? (slurp (first (.listFiles logs-dir))) "ACK received")))
        (finally (reset! cancelled true)))
      (let [r (deref fut 10000 nil)]
        (is (true? (:follow r)))
        (is (= 1 (count (.listFiles logs-dir))) "one file for the whole follow")
        (is (= (:lines r) (count (filter (complement :gap) @events))))
        (is (java.nio.file.Files/isSameFile (.toPath (io/file (:path r))) (store/raw-path ws (:sha r))))
        (is (not (.canWrite (io/file (:path r)))))))
    (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
