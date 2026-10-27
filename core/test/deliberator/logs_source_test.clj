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

(deftest a-cloudwatch-tail-waits-out-throttling-and-dropped-connections
  ;; B34: on real CloudWatch five tails polling once a second got `Rate exceeded` / `header parser received no bytes`, and the first
  ;; such error ended the tail with an ERROR 19 s into a 150 s window. Here the first three polls fail like that, the fourth answers.
  (let [calls (atom 0)
        q (LinkedBlockingQueue.)
        busy (ex-info "FilterLogEvents on /g: Rate exceeded" {:type :logs/cloudwatch :aws {:type :aws/error :category :busy :code "ThrottlingException"}})
        dropped (ex-info "FilterLogEvents on /g: x" {:type :logs/cloudwatch :aws {:type :aws/error :category :fault}})
        refused (ex-info "FilterLogEvents on /g: denied" {:type :logs/cloudwatch :aws {:type :aws/error :category :forbidden :code "AccessDeniedException"}})
        events [{:eventId "e1" :timestamp 1000 :message "order 7 stored"}]]
    (testing "throttled and dropped polls are waited out, the line still arrives"
      (with-redefs [logs/*poll-backoff-ms* [5 5]
                    logs/cw-events (fn [& _] (case (swap! calls inc) 1 (throw busy) 2 (throw dropped) 3 (throw busy) events))]
        (let [stop (logs/cw-source {} "/g" (Instant/ofEpochMilli 0) nil q)]
          (try (is (= ["order 7 stored"] (texts (drain-for q 600))))
               (finally (stop))))
        (is (>= @calls 4))))
    (testing "a refusal (a policy, not a glitch) still ends the tail with its error"
      (with-redefs [logs/cw-events (fn [& _] (throw refused))]
        (let [stop (logs/cw-source {} "/g" (Instant/ofEpochMilli 0) nil (LinkedBlockingQueue.))]
          (try (let [q2 (LinkedBlockingQueue.)
                     stop2 (logs/cw-source {} "/g" (Instant/ofEpochMilli 0) nil q2)]
                 (try (is (instance? Throwable (.poll q2 2 TimeUnit/SECONDS)))
                      (finally (stop2))))
               (finally (stop))))))))
