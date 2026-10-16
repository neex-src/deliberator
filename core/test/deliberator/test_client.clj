(ns deliberator.test-client
  "A tiny protocol client for the daemon tests: connects to an endpoint string (`unix://…` or
  `tcp://127.0.0.1:<port>`), sends request lines and collects the answer lines of a request."
  (:require [clojure.data.json :as json]
            [deliberator.protocol]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net InetSocketAddress StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels SocketChannel)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn connect
  "-> client map {:ch :queue :seq}; a reader thread queues every line, nil at EOF."
  [endpoint]
  (let [ch (cond
             (str/starts-with? endpoint "unix://")
             (doto (SocketChannel/open StandardProtocolFamily/UNIX)
               (.connect (UnixDomainSocketAddress/of ^String (subs endpoint 7))))
             (str/starts-with? endpoint "tcp://")
             (let [[h p] (str/split (subs endpoint 6) #":")]
               (doto (SocketChannel/open) (.connect (InetSocketAddress. ^String h (Integer/parseInt p)))))
             :else (throw (ex-info (str "bad endpoint " endpoint) {})))
        q (LinkedBlockingQueue.)
        rd (BufferedReader. (InputStreamReader. (deliberator.protocol/channel-in ch) "UTF-8"))]
    (doto (Thread. ^Runnable (fn [] (try (loop [] (if-let [l (.readLine rd)] (do (.put q l) (recur)) (.put q ::eof)))
                                         (catch Exception _ (.put q ::eof)))))
      (.setDaemon true) (.start))
    {:ch ch :queue q :seq (atom 0)}))

(defn close! [{:keys [^SocketChannel ch]}] (.close ch))

(defn send-line!
  "Writes a raw line."
  [{:keys [^SocketChannel ch]} ^String line]
  (let [buf (java.nio.ByteBuffer/wrap (.getBytes (str line "\n") "UTF-8"))]
    (while (.hasRemaining buf) (.write ch buf))))

(defn send!
  "Sends a request; returns its id."
  [c op args & {:keys [id cancel-on-close]}]
  (let [id (or id (str "r" (swap! (:seq c) inc)))]
    (send-line! c (json/write-str (cond-> {"id" id "op" op "args" args}
                                    cancel-on-close (assoc "cancel-on-close" true))))
    id))

(defn recv
  "The next line as a map, `::eof`, or `::timeout`."
  ([c] (recv c 10000))
  ([{:keys [^LinkedBlockingQueue queue]} ms]
   (let [l (.poll queue ms TimeUnit/MILLISECONDS)]
     (cond (nil? l) ::timeout (= ::eof l) ::eof :else (json/read-str l :key-fn keyword)))))

(defn collect
  "Lines of request `id` up to and including its terminal line: `{:events [...] :terminal m}`.
  Lines of other requests are dropped."
  ([c id] (collect c id 20000))
  ([c id ms]
   (loop [events []]
     (let [m (recv c ms)]
       (cond (not (map? m)) {:events events :terminal m}
             (not= id (:id m)) (recur events)
             (contains? m :event) (recur (conj events m))
             :else {:events events :terminal m})))))

(defn request
  "Sends and collects: `{:events :terminal}`; `(:ok terminal)`, `(:result terminal)`, `(:error terminal)`."
  [c op args & {:as opts}]
  (collect c (apply send! c op args (mapcat identity opts))))
