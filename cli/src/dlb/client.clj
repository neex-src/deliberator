(ns dlb.client
  "The wire client of `dlb`: connects to an endpoint string (`unix:///…` or `tcp://127.0.0.1:p`) with
  a plain blocking `SocketChannel`, sends NDJSON request lines and reads the answer lines of a
  request (zero or more events, one terminal line). No state beyond the connection."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net InetSocketAddress StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio ByteBuffer)
           (java.nio.channels SocketChannel)
           (java.nio.charset StandardCharsets)))

(defn connect
  "Opens a connection to `endpoint`; throws when it is unusable or refused."
  [^String endpoint]
  (cond
    (str/starts-with? endpoint "unix://")
    (doto (SocketChannel/open StandardProtocolFamily/UNIX)
      (.connect (UnixDomainSocketAddress/of ^String (subs endpoint 7))))

    (str/starts-with? endpoint "tcp://")
    (let [[h p] (str/split (subs endpoint 6) #":")]
      (doto (SocketChannel/open)
        (.connect (InetSocketAddress. ^String h (Integer/parseInt p)))))

    :else (throw (ex-info (str "unknown endpoint " endpoint) {}))))

(defn close! [^SocketChannel ch] (try (.close ch) (catch Exception _ nil)))

(defn- write-line! [^SocketChannel ch ^String line]
  (let [buf (ByteBuffer/wrap (.getBytes (str line "\n") StandardCharsets/UTF_8))]
    (while (.hasRemaining buf) (.write ch buf))))

(defn- reader
  "A function returning the next line of the channel (a string) or nil at EOF."
  [^SocketChannel ch]
  (let [buf (ByteBuffer/allocate 65536)
        acc (java.io.ByteArrayOutputStream.)]
    (fn next-line []
      (loop []
        (let [bs (.toByteArray acc)
              i (loop [i 0] (cond (>= i (alength bs)) -1 (= 10 (aget bs i)) i :else (recur (inc i))))]
          (if (>= i 0)
            (let [line (String. bs 0 (int i) StandardCharsets/UTF_8)]
              (.reset acc)
              (.write acc bs (inc i) (- (alength bs) (inc i)))
              line)
            (do (.clear buf)
                (if (neg? (.read ch buf))
                  nil
                  (do (.flip buf)
                      (.write acc (.array buf) 0 (.limit buf))
                      (recur))))))))))

(defn request!
  "Sends one request and calls `on-event` with each event map as it arrives. Returns
  `{:line <raw terminal line> :terminal <parsed>}`; throws when the connection ends first."
  [^SocketChannel ch op args on-event]
  (let [id "dlb-1"
        next-line (reader ch)]
    (write-line! ch (json/generate-string {"id" id "op" op "args" args "cancel-on-close" true}))
    (loop []
      (if-let [line (next-line)]
        (let [m (json/parse-string line true)]
          (cond
            (contains? m :event) (do (on-event m) (recur))
            (contains? m :ok) {:line line :terminal m}
            :else (recur)))
        (throw (ex-info "the daemon closed the connection" {:type "daemon/closed"}))))))
