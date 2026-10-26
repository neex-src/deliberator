(ns deliberator.logs-source-test
  "The `logs` kind's container source against a real container (pass A: Podman only, no Floci; a loud SKIP without it).
  Review 2026-10-25 R3b: conmon stamps a write burst with ONE timestamp, and `container-source` kept one line per distinct
  timestamp (30 lines printed, 2 delivered). Here an alpine container prints a 30-line burst: every line arrives; after a
  stop/start the restart's burst arrives too and the lines from before the gap are not replayed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.steps.logs :as logs])
  (:import (java.time Instant)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- drain-for
  "Everything on `q` within `ms` (polls stop after a 300 ms silence once `ms` has passed)."
  [^LinkedBlockingQueue q ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [acc []]
      (if-let [x (.poll q 300 TimeUnit/MILLISECONDS)]
        (recur (conj acc x))
        (if (< (System/currentTimeMillis) end) (recur acc) acc)))))

(defn- texts [items] (vec (remove map? items)))
(defn- gaps [items] (vec (filter map? items)))

(deftest a-burst-sharing-one-timestamp-arrives-whole-and-a-restart-replays-nothing
  (when-podman
   (let [c (podman/client (sup/endpoint))
         cname (str "deliberator-test-burst-" (sup/rand-id))
         burst "for j in $(seq 1 30); do echo \"boot $j\"; done; sleep 60"
         _ (when-not (podman/image-exists? c sup/alpine-image) (podman/pull! c sup/alpine-image))
         {:keys [id]} (podman/create! c {"name" cname "image" sup/alpine-image
                                         "command" ["sh" "-c" burst]
                                         "labels" {"deliberator.workspace" "logs-source-test" "deliberator.role" "probe"}
                                         "log_configuration" {"driver" "k8s-file"}})
         q (LinkedBlockingQueue.)
         stop (atom nil)]
     (try
       (podman/start! c id)
       (Thread/sleep 1200)
       (let [raw (doall (podman/logs c id))
             stamps (distinct (keep :at raw))]
         (is (= 30 (count raw)) "podman delivers the whole burst")
         (is (< (count stamps) 30) (str "the burst shares timestamps (conmon): " (count stamps) " distinct of 30")))
       (reset! stop (with-redefs [logs/podman-client (constantly c)]
                      (logs/container-source {:ws "logs-source-test"} cname (.minusSeconds (Instant/now) 30) q)))
       (testing "the first attach delivers every line of the burst, timestamps shared or not"
         (let [items (drain-for q 2500)]
           (is (= (mapv #(str "boot " %) (range 1 31)) (texts items)) (pr-str (texts items)))
           (is (empty? (gaps items)))))
       (testing "after a stop and a start the restart's burst arrives once and nothing from before the gap is replayed"
         (podman/stop! c id)
         (let [stopped (drain-for q 2000)]
           (is (= 1 (count (gaps stopped))) (pr-str stopped))
           (is (true? (:stop? (first (gaps stopped)))))
           (is (empty? (texts stopped)) "stopping replays nothing"))
         (podman/start! c id)
         (let [items (drain-for q 4000)
               gs (gaps items)]
           (is (= 1 (count gs)) (pr-str gs))
           (is (str/includes? (str (:gap (first gs))) "running again"))
           (is (= (mapv #(str "boot " %) (range 1 31)) (texts items))
               (str "exactly the restart's 30 lines, got " (count (texts items)) ": " (pr-str (texts items))))))
       (finally
         (when-let [f @stop] (f))
         (podman/remove! c id))))))
