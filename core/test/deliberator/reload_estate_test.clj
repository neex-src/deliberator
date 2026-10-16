(ns deliberator.reload-estate-test
  "`service.reload` against the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`, `DELIBERATOR_HOME`; without them a
  SKIP line). The test edits the REAL source of service A in this checkout (`estate/services/service-a/…/Api.java`, and
  `common`'s `SqsPoller.java`), reloads, and puts the files back with `git checkout` in a `finally` (it refuses to start when
  those files already have uncommitted changes).

  1. hotswap image (the estate's default): the ack log string changes -> `redefined`, no fallback, the same container
     (id and start time), `order.scn` green and the new string in A's log; a syntax error -> `service/compile-failed`
     with the compiler's line, container untouched; a field added -> redefined in place; a change in the
     sibling module `common` -> redefined in place; `reload-count` counts the in-place reloads.
  2. plain JRE (`:image` temurin): the same string change goes over JDWP (`mechanism` jdwp, same container); a field added
     is refused by the JVM -> `fallback` in the result and a recreated container.
  The description of A and its container are put back at the end."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.steps.dynamodb] [deliberator.steps.eventbridge] [deliberator.steps.http]
            [deliberator.steps.lambda] [deliberator.steps.logs] [deliberator.steps.scheduler]
            [deliberator.steps.sql] [deliberator.steps.sqs] [deliberator.steps.wait])
  (:import (java.time Instant)))

(def ^:private api "../estate/services/service-a/src/main/java/estate/a/Api.java")
(def ^:private poller "../estate/services/common/src/main/java/estate/common/SqsPoller.java")
(def ^:private paths ["estate/services/service-a/src" "estate/services/common/src"])

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)))

(defn- error-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (merge (ex-data e) {:message (ex-message e)}))))

(defn- git [& args]
  (let [p (-> (ProcessBuilder. ^java.util.List (into ["git" "-C" ".."] args)) (.redirectErrorStream true) .start)
        out (slurp (.getInputStream p))]
    {:exit (.waitFor p) :out out}))

(defn- restore! [] (git "checkout" "--" "estate/services"))

(defn- edit! [path from to]
  (let [s (slurp path)]
    (assert (str/includes? s from) (str from " not in " path))
    (spit path (str/replace-first s from to))))

(defn- container [ws]
  (let [c (podman/client (get-in (runtime/read-config ws) [:podman :endpoint]) :ws ws)
        x (podman/inspect c (str "deliberator-" ws "-service-a"))]
    {:id (:Id x) :started (get-in x [:State :StartedAt]) :image (get-in x [:Config :Image])}))

(defn- status-a [ws] (first (call "service.status" {:ws ws :name "a"})))

(defn- run-order! [ws sess]
  (call "scenario.run" {:ws ws :session sess :path (.getCanonicalPath (io/file "../estate/scenarios/order.scn"))}))

(defn- a-log [ws since]
  (str/join "\n" (map :text (doall (runtime/logs ws "service-a" {:since since})))))

(defn- clean? [] (zero? (:exit (apply git "diff" "--quiet" "--" paths))))

(deftest reload-story-hotswap
  (if-let [ws (fu/estate-ws)]
    (if-not (clean?)
      (fu/skip! "reload-story-hotswap" "estate/services has uncommitted changes: the test restores them with git checkout and will not touch them")
      (let [sess (str "RELOAD-" (System/currentTimeMillis))
            c0 (container ws)
            n0 (or (:reload-count (status-a ws)) 0)
            marker (str "reloaded-" (System/currentTimeMillis))
            since (Instant/now)]
        (call "session.start" {:ws ws :key sess})
        (testing "the estate runs the hotswap image"
          (is (= description/hotswap-image (:image c0))))
        (try
          (testing "a string changes: redefined in place, same container, new behaviour"
            (edit! api "\"ACK received id={}\"" (str "\"ACK received (" marker ") id={}\""))
            (let [events (atom [])
                  r (call "service.reload" {:ws ws :name "a"} events)]
              (is (nil? (:fallback r)) (pr-str r))
              (is (= "hotswap-agent" (:mechanism r)))
              (is (= ["estate/a/Api.class"] (:changed r)))
              (is (= ["estate.a.Api"] (:redefined r)))
              (is (.isFile (io/file (:log r))))
              (is (= (inc n0) (:reload-count r)))
              (is (= (inc n0) (:reload-count (status-a ws))))
              (is (= ["compiling" "changed" "staged" "redefined" "healthy"]
                     (map :step (filter #(= "service.reload.step" (:event %)) @events))))
              (is (= (select-keys c0 [:id :started]) (select-keys (container ws) [:id :started])) "same container, same start time")
              ;; a warm-up run first: right after a redefinition A runs cold code, and A's `publish` has a race of its own (it sets
              ;; PUBLISHED after the bus put, B's ack can land before it and be overwritten; reported, not fixed here)
              (run-order! ws sess)
              (let [run (run-order! ws sess)]
                (is (= :ok (:status run)) (pr-str (select-keys run [:status :failed]))))
              (is (str/includes? (a-log ws since) (str "ACK received (" marker ")")) "A's log carries the new string")))
          (testing "nothing changed: nothing happens"
            (let [r (call "service.reload" {:ws ws :name "a"})]
              (is (= [] (:changed r))) (is (nil? (:fallback r)))
              (is (= (inc n0) (:reload-count r)))))
          (testing "a syntax error is a typed error with the compiler's line; the container is untouched"
            (edit! api "private final String apiKey;" "private final String apiKey  int broken;")
            (let [e (error-of #(call "service.reload" {:ws ws :name "a"}))]
              (is (= :service/compile-failed (:type e)) (pr-str e))
              (is (some #(and (str/includes? % "Api.java") (str/includes? % "';' expected")) (:lines e)) (pr-str (:lines e)))
              (is (= "Api.java" (.getName (io/file (:file (first (:errors e)))))))
              (is (.isFile (io/file (:log e)))))
            (is (= (select-keys c0 [:id :started]) (select-keys (container ws) [:id :started])))
            (edit! api "private final String apiKey  int broken;" "private final String apiKey;"))
          (testing "a field added: redefined in place on the JBR"
            (edit! api "private final String apiKey;" "private final String apiKey;\n  private int extra = 3;")
            (let [r (call "service.reload" {:ws ws :name "a"})]
              (is (nil? (:fallback r)) (pr-str r))
              (is (= ["estate.a.Api"] (:redefined r)))
              (is (= (select-keys c0 [:id :started]) (select-keys (container ws) [:id :started])))))
          (testing "a change in the sibling module common reaches the container too"
            (edit! poller "\"queue {} not available yet: {}\"" "\"queue {} not available yet (reload test): {}\"")
            (let [r (call "service.reload" {:ws ws :name "a"})]
              (is (nil? (:fallback r)) (pr-str r))
              (is (= ["common/estate/common/SqsPoller.class"] (:changed r)))
              (is (= ["estate.common.SqsPoller"] (:redefined r)))
              (is (= (select-keys c0 [:id :started]) (select-keys (container ws) [:id :started])))))
          (finally
            (restore!)
            (try (call "service.reload" {:ws ws :name "a"}) (catch Exception _ nil))))))
    (fu/skip! "reload-story-hotswap" "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))

(deftest reload-story-plain-jre
  (if-let [ws (fu/estate-ws)]
    (if-not (clean?)
      (fu/skip! "reload-story-plain-jre" "estate/services has uncommitted changes: the test restores them with git checkout and will not touch them")
      (let [a (description/read ws "a")
            marker (str "jdwp-" (System/currentTimeMillis))]
        (try
          (description/write! ws (assoc a :image description/plain-image))
          (call "service.restart" {:ws ws :name "a"})
          (let [c0 (container ws)]
            (testing "the plain image runs the fat jar"
              (is (= description/plain-image (:image c0))))
            (testing "a string changes: redefined over JDWP, same container"
              (edit! api "\"ACK received id={}\"" (str "\"ACK received (" marker ") id={}\""))
              (let [r (call "service.reload" {:ws ws :name "a"})]
                (is (nil? (:fallback r)) (pr-str r))
                (is (= "jdwp" (:mechanism r)))
                (is (= ["estate.a.Api"] (:redefined r)))
                (is (= 1 (:reload-count r)))
                (is (= (select-keys c0 [:id :started]) (select-keys (container ws) [:id :started])))))
            (testing "a field added: the JVM refuses, the fallback rebuilds the jar and recreates the container"
              (edit! api "private final String apiKey;" "private final String apiKey;\n  private int extra = 3;")
              (let [r (call "service.reload" {:ws ws :name "a"})]
                (is (str/includes? (str (:fallback r)) "schema-change-not-implemented") (pr-str r))
                (is (not= (:id c0) (:id (container ws))) "a new container")
                (is (= "running" (:status (status-a ws)))))))
          (finally
            (restore!)
            (try (description/write! ws a)
                 (call "service.build" {:ws ws :name "a"})
                 (call "service.restart" {:ws ws :name "a"})
                 (catch Exception _ nil))))))
    (fu/skip! "reload-story-plain-jre" "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
