(ns deliberator.protocol
  "The wire protocol of `docs/architecture.md` section 6: newline-delimited JSON, UTF-8, one object
  per line, over a unix domain socket or a loopback TCP socket.

  Client -> daemon, one line per request:
    {\"id\":\"c7\",\"op\":\"scenario.run\",\"args\":{...},\"cancel-on-close\":true}
  `id` (string or number) is chosen by the client and echoed on every line of the answer; `args`
  defaults to `{}`; `cancel-on-close` (optional, default false) asks for the request to be cancelled
  when the client's connection closes (a run is cancelled, a followed log is closed).

  Daemon -> client, per request, zero or more event lines and exactly one terminal line:
    {\"id\":\"c7\",\"event\":\"step.started\",\"step\":1,...}
    {\"id\":\"c7\",\"ok\":true,\"result\":{...}}
    {\"id\":\"c7\",\"ok\":false,\"error\":{\"type\":\"bad-args\",\"message\":\"...\",\"hint\":\"...\"}}
  A line that cannot be decoded (not JSON, not an object, no string `op`, `args` not an object, longer
  than `max-line-bytes`) is answered with a terminal `bad-request` error whose `id` is null (or the
  request's own id when it could be read); the connection stays usable. Requests on one connection run
  concurrently, each on its own thread (a virtual thread on JDK 21+), and their lines interleave
  whole: every line is written atomically. Events of a request go to the connection that sent it,
  nowhere else. EOF from the client does not close the connection while requests are still running
  (so `echo '{...}' | socat - UNIX-CONNECT:...` gets its answer); requests that asked for
  `cancel-on-close` are cancelled at EOF.

  Values: keywords, instants and paths are written as strings (a namespaced keyword as `ns/name`),
  sets and sequences as arrays, map keys as strings.

  Server side: `(listen! server-socket-channel opts)` accepts connections and serves each with
  `(serve-connection! channel opts)`; `opts` is `{:dispatch (fn [request ctx]) :error-fn (fn [throwable])
  :log (fn [& parts])}`. `dispatch` returns the result (any JSON-able value) or throws; `ctx` is
  `{:emit! (fn [event-map]) :alive? :cancelled? :on-cancel! (fn [f])}`: `emit!` writes an event line
  (the map needs `:event`; it never throws), `:on-cancel!` registers a function called once when the
  request is cancelled, `alive?` is false once the connection has failed. `error-fn` turns a throwable into the wire
  error map. `(spawn! f)` starts `f` on a virtual thread when the JVM has them, a daemon thread
  otherwise, with the caller's dynamic bindings (the daemon's home) conveyed."
  (:require [clojure.data.json :as json])
  (:import (java.nio ByteBuffer)
           (java.io BufferedInputStream ByteArrayOutputStream IOException InputStream)
           (java.nio.channels ClosedChannelException ServerSocketChannel SocketChannel)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent ConcurrentHashMap ThreadFactory)
           (java.util.concurrent.locks ReentrantLock)))

(def max-line-bytes "Longest request line accepted (4 MB)." (* 4 1024 1024))

;; threads -------------------------------------------------------------------------------------

(defn- virtual-factory []
  (try (let [b (clojure.lang.Reflector/invokeStaticMethod Thread "ofVirtual" (object-array 0))]
         (clojure.lang.Reflector/invokeInstanceMethod b "factory" (object-array 0)))
       (catch Throwable _ nil)))

(def ^:private factory
  (or (virtual-factory)
      (let [n (atom 0)]
        (reify ThreadFactory
          (newThread [_ r] (doto (Thread. ^Runnable r (str "deliberatord-" (swap! n inc)))
                             (.setDaemon true)))))))

(def thread-model "`:virtual` on JDK 21+, else `:platform`." (if (virtual-factory) :virtual :platform))

(defn spawn!
  "Runs `f` on a new (virtual when possible) thread with the caller's dynamic bindings."
  [f]
  (let [t (.newThread ^ThreadFactory factory ^Runnable (bound-fn* f))]
    (.start ^Thread t)
    t))

;; JSON ----------------------------------------------------------------------------------------

(defn- key-str [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn json-ready
  "`x` with keywords, instants, paths and other objects turned into strings, sets into vectors and
  map keys into strings."
  [x]
  (cond
    (nil? x) nil
    (or (string? x) (boolean? x)) x
    (number? x) (cond (and (float? x) (or (Double/isNaN (double x)) (Double/isInfinite (double x)))) (str x)
                      (ratio? x) (double x)
                      :else x)
    (keyword? x) (key-str x)
    (map? x) (persistent! (reduce-kv (fn [m k v] (assoc! m (key-str k) (json-ready v))) (transient {}) x))
    (or (sequential? x) (set? x)) (mapv json-ready x)
    :else (str x)))

(defn encode
  "One JSON line (without the newline) for the map/value `m`."
  ^String [m]
  (json/write-str (json-ready m) :escape-unicode false :escape-js-separators false :escape-slash false))

(defn error-line
  "A terminal error map `{:id :ok false :error {:type :message}}`."
  [id type message]
  {:id id :ok false :error {:type type :message message}})

(defn decode
  "Decodes one request line. -> `{:request {:id :op :args (keyword keys) :cancel-on-close bool}}`
  or `{:error <terminal error map>}`."
  [^String line]
  (let [bad (fn [id msg] {:error (error-line id "bad-request" msg)})]
    (try
      (let [m (json/read-str line)]
        (if-not (map? m)
          (bad nil "a request must be a JSON object {\"id\",\"op\",\"args\"}")
          (let [id (get m "id") op (get m "op") args (get m "args")
                id (when (or (string? id) (number? id)) id)]
            (cond
              (nil? id) (bad nil "\"id\" is required (a string or a number)")
              (not (and (string? op) (seq op))) (bad id "\"op\" is required (a string such as \"session.list\")")
              (not (or (nil? args) (map? args))) (bad id "\"args\" must be an object")
              :else {:request {:id id :op op
                               :args (into {} (map (fn [[k v]] [(keyword k) v])) args)
                               :cancel-on-close (true? (get m "cancel-on-close"))}}))))
      (catch Exception e
        (bad nil (str "not valid JSON: " (ex-message e)))))))

;; line reading --------------------------------------------------------------------------------

(defn read-line*
  "Next non-blank line from `in` as a string, `::eof` at end of stream, `::too-long` when a line
  exceeded `max-line-bytes` (the rest of that line is discarded)."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [over? false]
      (let [b (.read in)]
        (cond
          (neg? b) (if (and (zero? (.size buf)) (not over?)) ::eof
                       (if over? ::too-long (String. (.toByteArray buf) StandardCharsets/UTF_8)))
          (= b 10) (cond over? ::too-long
                         (zero? (.size buf)) (recur false)
                         :else (let [s (String. (.toByteArray buf) StandardCharsets/UTF_8)]
                                 (if (= "" (.trim s)) (do (.reset buf) (recur false)) (.stripTrailing s))))
          over? (recur true)
          (>= (.size buf) max-line-bytes) (do (.reset buf) (recur true))
          :else (do (.write buf b) (recur false)))))))

(defn channel-in
  "An InputStream over a socket channel reading with `ch.read` directly: `Channels.newInputStream`
  holds the channel's blocking lock for the whole read on JDK 17, which would stall every write."
  ^InputStream [^SocketChannel ch]
  (let [buf (ByteBuffer/allocate 8192)]
    (.limit buf 0)
    (proxy [InputStream] []
      (read
        ([] (let [b (byte-array 1)] (if (neg? (.read ^InputStream this b 0 1)) -1 (bit-and (aget b 0) 0xff))))
        ([bs off len]
         (if (and (not (.hasRemaining buf))
                  (do (.clear buf)
                      (let [n (.read ch buf)] (.flip buf) (neg? n))))
           -1
           (let [n (min len (.remaining buf))]
             (.get buf ^bytes bs (int off) (int n))
             n))))
      (close [] nil))))

(defn write-fully!
  "Writes all of `bs` to the channel."
  [^SocketChannel ch ^bytes bs]
  (let [buf (ByteBuffer/wrap bs)]
    (while (.hasRemaining buf) (.write ch buf))))

;; connections ---------------------------------------------------------------------------------

(defn- cancel! [st]
  (when (compare-and-set! (:cancelled st) false true)
    (doseq [f @(:fns st)] (try (f) (catch Throwable _ nil)))))

(defn serve-connection!
  "Serves one client until it disconnects (see the namespace docstring). Blocks."
  [^SocketChannel ch {:keys [dispatch error-fn log]}]
  (let [in (BufferedInputStream. (channel-in ch) 8192)
        wlock (ReentrantLock.)
        alive (atom true)
        inflight (atom #{})
        write! (fn [m]
                 (when @alive
                   (let [bs (.getBytes (str (encode m) "\n") StandardCharsets/UTF_8)]
                     (.lock wlock)
                     (try (write-fully! ch bs) true
                          (catch IOException _ (reset! alive false) false)
                          (finally (.unlock wlock))))))
        run-request!
        (fn [{:keys [id op] :as request}]
          (let [st {:cancelled (atom false) :fns (atom []) :cancel-on-close (:cancel-on-close request)}
                ctx {:emit! (fn [ev] (write! (assoc (into {} ev) :id id)) nil)
                     :alive? (fn [] @alive)
                     :cancelled? (fn [] @(:cancelled st))
                     :on-cancel! (fn [f] (swap! (:fns st) conj f) (when @(:cancelled st) (f)))}]
            (swap! inflight conj st)
            (spawn!
             (fn []
               (try
                 (let [line (try {:id id :ok true :result (dispatch request ctx)}
                                 (catch Throwable e {:id id :ok false :error (error-fn e)}))]
                   (write! line))
                 (catch Throwable e (when log (log "terminal write failed" op (ex-message e))))
                 (finally (swap! inflight disj st)))))))]
    (try
      (loop []
        (let [l (read-line* in)]
          (cond
            (= l ::eof) nil
            (= l ::too-long) (do (write! (error-line nil "bad-request"
                                                     (str "request line longer than " max-line-bytes " bytes")))
                                 (recur))
            :else (let [{:keys [request error]} (decode l)]
                    (if error (write! error) (run-request! request))
                    (recur)))))
      (catch IOException _ nil)
      (finally
        (doseq [st @inflight] (when (:cancel-on-close st) (cancel! st)))
        (loop [] (when (and (seq @inflight) @alive) (Thread/sleep 25) (recur)))
        (reset! alive false)
        (try (.close ch) (catch IOException _ nil))))))

(defn cancel-request!
  "Cancels a request state (used by tests and the daemon's shutdown)."
  [st] (cancel! st))

(defn listen!
  "Accepts connections on `ssc` (a bound `ServerSocketChannel`) until `stop!` -> `{:stop! f}`."
  [^ServerSocketChannel ssc {:keys [log] :as opts}]
  (let [conns (ConcurrentHashMap/newKeySet)]
    (spawn! (fn []
              (loop []
                (let [ch (try (.accept ssc)
                              (catch ClosedChannelException _ ::closed)
                              (catch IOException e
                                (when log (log "accept failed" (ex-message e)))
                                (when (.isOpen ssc) (Thread/sleep 100))
                                (if (.isOpen ssc) nil ::closed)))]
                  (cond (= ch ::closed) nil
                        (nil? ch) (recur)
                        :else (do (.add conns ch)
                                  (spawn! (fn [] (try (serve-connection! ch opts)
                                                      (catch Throwable e (when log (log "connection failed" (ex-message e))))
                                                      (finally (.remove conns ch)))))
                                  (recur)))))))
    {:stop! (fn []
              (try (.close ssc) (catch IOException _ nil))
              (doseq [c conns] (try (.close ^SocketChannel c) (catch IOException _ nil))))}))
