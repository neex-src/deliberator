(ns deliberator.podman-test
  "Framing units (byte streams, no Podman) and integration tests against the real Podman: unix
  socket and a `tcp://` endpoint served by a self-terminating `podman system service` child."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util.concurrent TimeUnit)))

(defn- bytes-of [^String s] (.getBytes s StandardCharsets/ISO_8859_1))
(defn- in-of [^String s] (ByteArrayInputStream. (bytes-of s)))
(defn- slurp-in [in] (String. (.readAllBytes ^java.io.InputStream in) StandardCharsets/UTF_8))

;; --- framing, no Podman ----------------------------------------------------------------------

(deftest chunked-body
  (let [raw "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n5\r\nhello\r\n7;ext=1\r\n, world\r\n0\r\nTrailer: x\r\n\r\ntrailing garbage"
        in (in-of raw)
        head (podman/read-head in)]
    (is (= 200 (:status head)))
    (is (= "text/plain" (get-in head [:headers "content-type"])))
    (is (= "hello, world" (slurp-in (podman/body-stream in head))))))

(deftest content-length-and-eof-bodies
  (let [in (in-of "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabcdEXTRA")
        head (podman/read-head in)]
    (is (= "abcd" (slurp-in (podman/body-stream in head)))))
  (let [in (in-of "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nuntil the end")
        head (podman/read-head in)]
    (is (= "until the end" (slurp-in (podman/body-stream in head)))))
  (let [in (in-of "HTTP/1.1 204 No Content\r\nApi-Version: 1.44\r\n\r\n")
        head (podman/read-head in)]
    (is (= "" (slurp-in (podman/body-stream in head))))))

(deftest truncated-chunk-throws
  (let [in (in-of "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nA\r\nshort")
        head (podman/read-head in)]
    (is (thrown? java.io.EOFException (slurp-in (podman/body-stream in head))))))

(deftest multiplexed-frames
  (let [frame (fn [t ^String s] (let [b (bytes-of s) n (count b)]
                                  (concat [t 0 0 0 0 0 (quot n 256) (rem n 256)] b)))
        bs (byte-array (map unchecked-byte (concat (frame 1 "2026-10-16T02:51:58.475447177+03:00 one\n")
                                                   (frame 2 "2026-10-16T02:51:58.5+03:00 err\n"))))
        evs (map podman/frame->event (podman/frames (ByteArrayInputStream. bs)))]
    (is (= [:stdout :stderr] (map :stream evs)))
    (is (= ["one" "err"] (map :text evs)))
    (is (= (Instant/parse "2026-10-15T23:51:58.475447177Z") (:at (first evs))))))

(deftest endpoints
  (is (= {:scheme :unix :path "/run/user/1000/podman/podman.sock"}
         (podman/parse-endpoint "unix:///run/user/1000/podman/podman.sock")))
  (is (= {:scheme :tcp :host "127.0.0.1" :port 8080} (podman/parse-endpoint "tcp://127.0.0.1:8080")))
  (is (= :podman/unsupported-endpoint (:type (ex-data (try (podman/parse-endpoint "ssh://u@h") (catch Exception e e))))))
  (is (= :podman/unsupported-endpoint (:type (ex-data (try (podman/parse-endpoint "ftp://x") (catch Exception e e)))))))

(deftest unreachable-socket
  (let [c (podman/client "unix:///nonexistent/podman.sock")]
    (is (false? (podman/available? c)))
    (is (= :podman/unreachable (:type (ex-data (try (podman/info c) (catch Exception e e))))))))

;; --- against the real Podman -----------------------------------------------------------------

(def alpine "docker.io/library/alpine:3.20")

(defn- lifecycle
  "Network, pull, container, logs with `since`, exec, persistence, removal over client `c`."
  [c ws]
  (let [id (sup/rand-id)
        net (str "deliberator-test-" id)
        cname (str "deliberator-test-ctr-" id)
        cid (atom nil)]
    (try
      (testing "info, network idempotence"
        (is (string? (get-in (podman/info c) [:version :Version])))
        (is (= {:name net :created? true} (podman/ensure-network! c net)))
        (is (= {:name net :created? false} (podman/ensure-network! c net))))
      (testing "pull streams progress events and ends with an id"
        (let [evs (atom [])
              r (podman/pull! c alpine :on-event #(swap! evs conj %))]
          (is (seq @evs))
          (is (re-matches #"[0-9a-f]{64}" (:id r)))))
      (is (true? (podman/image-exists? c alpine)))
      (testing "container: labels, logs since, exec"
        (let [{:keys [id]} (podman/create! c {"name" cname "image" alpine
                                              "command" ["sh" "-c" "echo one; echo err >&2; sleep 2; echo two; sleep 60"]
                                              "labels" {"deliberator.workspace" ws "deliberator.role" "probe"}
                                              "netns" {"nsmode" "bridge"} "Networks" {net {}}
                                              "log_configuration" {"driver" "k8s-file"}})]
          (reset! cid id)
          (podman/start! c id)
          (podman/start! c id)                        ; already running is fine
          (Thread/sleep 800)
          (let [l1 (doall (podman/logs c id))
                first-at (:at (first l1))]
            (is (= ["one" "err"] (map :text l1)))
            (is (= #{:stdout :stderr} (set (map :stream l1))))
            (Thread/sleep 2500)
            (let [l2 (doall (podman/logs c id :since (.plusNanos ^Instant (:at (second l1)) 1)))]
              (is (= ["two"] (map :text l2)) "since excludes the earlier lines"))
            (let [l3 (doall (podman/logs c id :since first-at))]
              (is (not-any? #(= "one" (:text %)) l3)))
            (testing "follow yields new lines as they come, then closes"
              (let [s (podman/logs c id :follow true :since (.plusNanos ^Instant (:at (second l1)) 1))]
                (is (= "two" (:text (first s))))
                ((:close (meta s))))))
          (is (= [{:exit 3 :stdout "hi\n" :stderr "bad\n"}]
                 [(podman/exec! c id ["sh" "-c" "echo hi; echo bad >&2; exit 3"])]))
          (let [ctrs (podman/list-containers c :all true :labels {"deliberator.workspace" ws})]
            (is (= [cname] (mapcat :Names ctrs)))
            (is (= "probe" (get-in (first ctrs) [:Labels :deliberator.role]))))
          (is (= cname (get-in (podman/inspect c cname) [:Name])))
          (is (nil? (podman/inspect c (str cname "-missing"))))))
      (testing "responses landed in the raw store as :podman"
        (let [metas (for [f (file-seq (.toFile (store/path ws "raw")))
                          :when (str/ends-with? (.getName f) ".meta.edn")]
                      (store/read-edn f))]
          (is (seq metas))
          (is (every? #(= :podman (:source %)) metas))
          (is (some #(str/includes? (:op %) "/info") metas))
          (is (some #(str/includes? (:op %) "/exec/") metas))
          (is (every? #(and (:at %) (:content-type %) (:sha %) (contains? % :env)) metas))))
      (finally
        (when @cid (podman/remove! c @cid))
        (is (false? (podman/remove! c cname)) "removing the absent is fine")
        (podman/remove-network! c net)))))

(deftest unix-socket-lifecycle
  (when-podman
   (tu/with-temp-home
     (lifecycle (podman/client (sup/endpoint) :ws "podman-test") "podman-test"))))

(deftest tcp-endpoint
  (when-podman
   (tu/with-temp-home
     (let [port (sup/free-port)
           pb (doto (ProcessBuilder. ["podman" "system" "service" "--time=10" (str "tcp://127.0.0.1:" port)])
                (.redirectErrorStream true)
                (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD))
           proc (.start pb)
           c (podman/client (str "tcp://127.0.0.1:" port) :ws "podman-test-tcp")]
       (try
         (is (loop [n 0] (or (podman/available? c) (and (< n 50) (do (Thread/sleep 200) (recur (inc n)))))) "the tcp service came up")
         (lifecycle c "podman-test-tcp")
         (is (nil? (podman/socket-path c))
             "a service listening on tcp only has no socket path to offer")
         (finally
           (is (.waitFor proc 40 TimeUnit/SECONDS) "the --time=10 service ended on its own")
           (when (.isAlive proc) (.destroy proc))))))))
