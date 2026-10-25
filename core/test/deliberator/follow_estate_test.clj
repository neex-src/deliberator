(ns deliberator.follow-estate-test
  "`logs.fetch {follow true}` on service A's container against the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`,
  `DELIBERATOR_HOME`; without them a SKIP line): the follow starts, an order runs through the estate, A's log lines reach the
  `logs.line` events while the follow is still open (the ACK line within seconds), and the cancelled follow leaves exactly ONE
  file in the session's `logs/`, read-only, the same inode as its raw blob. A follow without `since` replays none of the
  log written before it started (B6): after the warm-up order neither A's startup lines nor the earlier ACK line come back."
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
          order! #(call "scenario.run" {:ws ws :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))})
          _ (order!)   ; warm-up: the first order after `estate:up` runs cold code and may not finish in the scenario's windows
          logs-dir (io/file (str (session/dir ws sess)) "logs")
          events (atom [])
          cancelled (atom false)
          fut (future (call "logs.fetch" {:ws ws :target "log/service-a" :follow true :since "1s" :session sess}
                            {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)}))]
      (try
        (Thread/sleep 1500)
        (let [t0 (System/currentTimeMillis)
              run (future (order!))]
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

(deftest follow-without-since-starts-at-the-end-of-the-log
  (if-let [ws (fu/estate-ws)]
    (let [sess (str "FOLLOW-NOW-" (System/currentTimeMillis))
          _ (call "session.start" {:ws ws :key sess})
          order! #(call "scenario.run" {:ws ws :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))})
          _ (order!)   ; warm-up, as above; its lines, and A's startup lines, are the history the follow must not replay
          whole (call "logs.fetch" {:ws ws :target "log/service-a" :session sess})   ; one shot: the whole log so far
          old-lines (str/split-lines (slurp (:path whole)))
          first-old (first (remove str/blank? old-lines))
          events (atom [])
          cancelled (atom false)
          fut (future (call "logs.fetch" {:ws ws :target "log/service-a" :follow true :session sess}
                            {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)}))]
      (is (some? first-old) "A has history to not replay")
      (is (<= 1 (count (filter #(str/includes? % "ACK received") old-lines))) "...among it the warm-up order's ACK")
      (try
        (Thread/sleep 2000)
        (testing "a follow without since replays nothing: no ACK line and no startup line before the next order"
          (is (not-any? #(str/includes? (str (:text %)) "ACK received") @events) (pr-str (map :text @events)))
          (is (not-any? #(= first-old (:text %)) @events))
          (is (not (realized? fut)) "still following"))
        (let [run (future (order!))]
          (testing "the next order's ACK line arrives as an event"
            (is (wait-until #(some (fn [e] (str/includes? (str (:text e)) "ACK received")) @events) 15000)))
          (is (= :ok (:status @run))))
        (finally (reset! cancelled true)))
      (let [r (deref fut 10000 nil)
            followed (slurp (:path r))]
        (is (true? (:follow r)))
        (is (< (:lines r) (count old-lines)) "only what was written from now on")
        (is (not-any? #(= first-old %) (str/split-lines followed)) "the persisted follow holds none of the history either")
        (is (str/includes? followed "ACK received"))
        (is (= (:lines r) (count (filter (complement :gap) @events))))))
    (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
