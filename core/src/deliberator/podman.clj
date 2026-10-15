(ns deliberator.podman
  "Podman REST client (libpod API, `/v5.0.0/libpod/...`), no client library.

  Endpoints: `unix:///path/to/podman.sock` (JDK `UnixDomainSocketAddress` + hand-written HTTP/1.1
  framing: `Connection: close`, chunked, content-length and read-to-EOF bodies) and
  `tcp://host:port` (`java.net.http`, HTTP/1.1). `ssh://` is recognised and refused until the Mac
  profile (M3); it is only another endpoint, nothing above this namespace changes.

  A client is a plain map `(client endpoint :ws ws)`. With a `:ws`, every response body of the
  operations below is persisted first through `store/raw-put!` (`:source :podman`, `:op \"METHOD
  /path\"`, `:env nil`), error responses included, before anything parses it; without one nothing
  is persisted (probes, tests). Log streams are the exception: they are text and land in
  `logs/services/<name>/<date>.log` through `mirror-sink`.

  Operations: `available?`, `info`, `socket-path`, `ensure-network!`, `remove-network!`,
  `image-exists?`, `pull!` (progress events to `:on-event`), `list-containers`, `inspect`,
  `create!`, `start!`, `stop!`, `remove!`, `logs` (lazy seq of `{:stream :stdout|:stderr :at
  Instant :text}`, `:since`/`:follow`; the seq's metadata carries `:close`), `exec!`.

  Failures throw `ex-info` with `:type` `:podman/unreachable`, `:podman/timeout`, `:podman/error`
  (`:status :message :op`) or `:podman/unsupported-endpoint`. Responses are decoded with keyword
  keys exactly as Podman spells them (`:Id`, `:State`, `:Labels`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.store :as store])
  (:import (java.io BufferedReader ByteArrayInputStream ByteArrayOutputStream EOFException
                    IOException InputStream InputStreamReader RandomAccessFile)
           (java.net ConnectException StandardProtocolFamily URI URLEncoder UnixDomainSocketAddress)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers HttpTimeoutException)
           (java.nio ByteBuffer)
           (java.nio.channels Channels SocketChannel)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption)
           (java.time Duration Instant OffsetDateTime ZoneOffset)
           (java.util.concurrent ExecutionException)))

(def api-prefix "/v5.0.0/libpod")

(def default-timeout-ms 30000)

;; ---------------------------------------------------------------------------------------------
;; Endpoints

(defn default-endpoint
  "The user's rootless Podman socket: `$XDG_RUNTIME_DIR/podman/podman.sock`, else
  `/run/user/<uid>/podman/podman.sock`."
  []
  (let [rt (or (System/getenv "XDG_RUNTIME_DIR")
               (str "/run/user/" (Files/getAttribute (.toPath (io/file "/proc/self")) "unix:uid"
                                                     (make-array LinkOption 0))))]
    (str "unix://" rt "/podman/podman.sock")))

(defn parse-endpoint
  "`unix:///p` -> `{:scheme :unix :path \"/p\"}`; `tcp://h:p` -> `{:scheme :tcp :host :port}`.
  `ssh://` and anything else throw `:podman/unsupported-endpoint`."
  [endpoint]
  (let [[_ scheme rest] (re-matches #"(?s)([a-z]+)://(.*)" (str endpoint))]
    (case scheme
      "unix" {:scheme :unix :path rest}
      "tcp" (let [[_ host port] (re-matches #"(.+):(\d+)" rest)]
              (when-not host
                (throw (ex-info (str "tcp endpoint needs host:port: " endpoint)
                                {:type :podman/unsupported-endpoint :endpoint endpoint})))
              {:scheme :tcp :host host :port (Long/parseLong port)})
      "ssh" (throw (ex-info "ssh:// endpoints arrive with the Mac profile (M3)"
                            {:type :podman/unsupported-endpoint :endpoint endpoint}))
      (throw (ex-info (str "unsupported podman endpoint: " (pr-str endpoint))
                      {:type :podman/unsupported-endpoint :endpoint endpoint})))))

(defn client
  "A client map for `endpoint`; `:ws` turns response persistence on."
  [endpoint & {:keys [ws]}]
  (parse-endpoint endpoint)
  {:endpoint endpoint :ws ws})

;; ---------------------------------------------------------------------------------------------
;; HTTP/1.1 framing over a byte stream (unix sockets)

(defn- read-line-bytes
  "Reads up to LF from `in` (CR stripped); nil at EOF before any byte."
  ^String [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop [first? true]
      (let [b (.read in)]
        (cond
          (neg? b) (when-not first? (str sb))
          (= b 10) (str sb)
          (= b 13) (recur false)
          :else (do (.append sb (char b)) (recur false)))))))

(defn read-head
  "Reads status line and headers from `in`: `{:status n :headers {\"lowercase-name\" value}}`."
  [^InputStream in]
  (let [status-line (or (read-line-bytes in) (throw (EOFException. "connection closed before a response")))
        [_ status] (re-matches #"HTTP/\d\.\d (\d{3}).*" status-line)]
    (when-not status
      (throw (ex-info (str "bad status line: " status-line) {:type :podman/error :message status-line})))
    {:status (Long/parseLong status)
     :headers (loop [hs {}]
                (let [l (read-line-bytes in)]
                  (if (or (nil? l) (str/blank? l))
                    hs
                    (let [i (str/index-of l ":")]
                      (recur (if i
                               (assoc hs (str/lower-case (subs l 0 i)) (str/trim (subs l (inc i))))
                               hs))))))}))

(defn- stream
  "An InputStream from a read-into-array fn and a close fn."
  ^InputStream [read-fn close-fn]
  (proxy [InputStream] []
    (read
      ([] (let [b (byte-array 1)
                n (read-fn b 0 1)]
            (if (neg? n) -1 (bit-and 0xff (aget b 0)))))
      ([b off len] (if (zero? len) 0 (read-fn b off len))))
    (close [] (close-fn))))

(defn chunked-stream
  "Decodes an HTTP/1.1 `Transfer-Encoding: chunked` body read from `in`."
  ^InputStream [^InputStream in]
  (let [left (atom 0) done (atom false)]
    (stream
     (fn [^bytes b off len]
       (if @done
         -1
         (do
           (when (zero? @left)
             (let [l (read-line-bytes in)
                   size (when l (Long/parseLong (first (str/split (str/trim l) #";")) 16))]
               (cond
                 (nil? size) (throw (EOFException. "chunked body ended without the last chunk"))
                 (zero? size) (do (loop [t (read-line-bytes in)]
                                    (when-not (or (nil? t) (str/blank? t)) (recur (read-line-bytes in))))
                                  (reset! done true))
                 :else (reset! left size))))
           (if @done
             -1
             (let [n (.read in b (int off) (int (min len @left)))]
               (when (neg? n) (throw (EOFException. "chunk cut short")))
               (swap! left - n)
               (when (zero? @left) (read-line-bytes in))   ; the CRLF after the chunk data
               n)))))
     #(.close in))))

(defn- bounded-stream ^InputStream [^InputStream in n]
  (let [left (atom n)]
    (stream (fn [^bytes b off len]
              (if (<= @left 0)
                -1
                (let [k (.read in b (int off) (int (min len @left)))]
                  (when (pos? k) (swap! left - k))
                  k)))
            #(.close in))))

(defn body-stream
  "The body of a response with `head` as a plain InputStream: chunked, content-length, or until the
  server closes the connection."
  ^InputStream [^InputStream in {:keys [status headers]}]
  (cond
    (or (#{204 304} status) (< status 200)) (bounded-stream in 0)
    (= "chunked" (some-> (get headers "transfer-encoding") str/lower-case)) (chunked-stream in)
    (get headers "content-length") (bounded-stream in (Long/parseLong (get headers "content-length")))
    :else in))

;; ---------------------------------------------------------------------------------------------
;; Exchange: one request, response head parsed, body as a stream

(defn- unwrap [^Throwable t]
  (if (instance? ExecutionException t) (or (.getCause t) t) t))

(defn- bounded
  "Runs `f` on another thread; after `ms` calls `close-fn` and throws `:podman/timeout`. `ms` nil
  runs `f` here without a bound. Bindings convey (`future`); exceptions come out unwrapped."
  [ms close-fn what f]
  (if-not ms
    (f)
    (let [fut (future (f))
          r (try (deref fut ms ::timeout) (catch ExecutionException e (throw (unwrap e))))]
      (if (= r ::timeout)
        (do (future-cancel fut) (close-fn)
            (throw (ex-info (str "podman timed out after " ms " ms: " what)
                            {:type :podman/timeout :what what :timeout-ms ms})))
        r))))

(defn- encode [s]
  (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn- query-string [query]
  (let [q (remove (comp nil? val) query)]
    (when (seq q)
      (->> q
           (map (fn [[k v]] (str (encode (name k)) "=" (encode v))))
           (str/join "&")))))

(defn- unreachable [endpoint ^Throwable cause]
  (ex-info (str "podman unreachable at " endpoint ": " (.getMessage cause))
           {:type :podman/unreachable :endpoint endpoint}
           cause))

(defn- unix-open [{:keys [endpoint]} {:keys [path]} method target ^bytes body headers timeout-ms]
  (let [ch (try
             (doto (SocketChannel/open StandardProtocolFamily/UNIX)
               (.connect (UnixDomainSocketAddress/of ^String path)))
             (catch IOException e (throw (unreachable endpoint e))))
        close #(try (.close ch) (catch IOException _))]
    (try
      (let [head (str method " " target " HTTP/1.1\r\nHost: d\r\nConnection: close\r\nAccept: */*\r\n"
                      (apply str (for [[k v] headers] (str k ": " v "\r\n")))
                      (when body (str "Content-Length: " (alength body) "\r\n"))
                      "\r\n")
            _ (let [bb (ByteBuffer/wrap (.getBytes head StandardCharsets/UTF_8))]
                (while (.hasRemaining bb) (.write ch bb)))
            _ (when body (let [bb (ByteBuffer/wrap body)] (while (.hasRemaining bb) (.write ch bb))))
            in (java.io.BufferedInputStream. (Channels/newInputStream ch) 8192)
            hd (bounded timeout-ms close (str method " " target) #(read-head in))]
        (assoc hd :in (body-stream in hd) :close close))
      (catch Throwable t (close) (throw t)))))

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.version HttpClient$Version/HTTP_1_1)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(defn- tcp-open [{:keys [endpoint]} {:keys [host port]} method target ^bytes body headers timeout-ms]
  (let [rb (-> (HttpRequest/newBuilder (URI. (str "http://" host ":" port target)))
               (.method method (if body (HttpRequest$BodyPublishers/ofByteArray body)
                                   (HttpRequest$BodyPublishers/noBody))))
        rb (if timeout-ms (.timeout rb (Duration/ofMillis timeout-ms)) rb)]
    (doseq [[k v] headers] (.header rb k v))
    (try
      (let [resp (.send ^HttpClient @http-client (.build rb) (HttpResponse$BodyHandlers/ofInputStream))
            ^InputStream in (.body resp)
            hs (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)]) (.map (.headers resp))))]
        {:status (.statusCode resp) :headers hs :in in :close #(try (.close in) (catch IOException _))})
      (catch HttpTimeoutException e
        (throw (ex-info (str "podman timed out after " timeout-ms " ms: " method " " target)
                        {:type :podman/timeout :what (str method " " target)} e)))
      (catch ConnectException e (throw (unreachable endpoint e)))
      (catch IOException e (throw (unreachable endpoint e))))))

(defn open
  "Sends one request and returns `{:status :headers :in :close}` once the response head arrived;
  the body is `:in`, to be read and closed by the caller. `opts`: `:query` map, `:body` (bytes),
  `:content-type`, `:timeout-ms` for the head (default 30 s, nil none). `path` is relative to the
  libpod prefix."
  [c method path {:keys [query body content-type timeout-ms] :or {timeout-ms default-timeout-ms}}]
  (let [ep (parse-endpoint (:endpoint c))
        qs (query-string query)
        target (str api-prefix path (when qs (str "?" qs)))
        headers (cond-> {} content-type (assoc "Content-Type" content-type))
        f (case (:scheme ep) :unix unix-open :tcp tcp-open)]
    (f c ep method target body headers timeout-ms)))

(defn request
  "Like `open` but reads the whole body (bounded by `:timeout-ms`, default 30 s): `{:status :headers
  :body bytes}`. Nothing is persisted here; operations go through `call`."
  [c method path {:keys [timeout-ms] :or {timeout-ms default-timeout-ms} :as opts}]
  (let [{:keys [in close] :as r} (open c method path opts)]
    (try
      (let [bs (bounded timeout-ms close (str method " " path) #(.readAllBytes ^InputStream in))]
        (-> r (dissoc :in :close) (assoc :body bs)))
      (finally (close)))))

;; ---------------------------------------------------------------------------------------------
;; Persistence and the generic call

(defn persist!
  "Writes `bs` to the raw store of the client's workspace (no-op without `:ws` or for an empty
  body)."
  [c op ^bytes bs content-type]
  (when (and (:ws c) (pos? (alength bs)))
    (store/raw-put! (:ws c) bs {:source :podman :op op :env nil :at (store/now)
                                :content-type (or content-type "application/octet-stream")})))

(defn- decode [^bytes bs]
  (when (pos? (alength bs))
    (json/read-str (String. bs StandardCharsets/UTF_8) :key-fn keyword)))

(defn- error-ex [op status ^bytes body]
  (let [m (try (decode body) (catch Exception _ nil))
        msg (or (when (map? m) (:message m)) (String. body StandardCharsets/UTF_8))]
    (ex-info (str "podman " op " -> " status ": " msg)
             {:type :podman/error :status status :message msg :op op :cause (when (map? m) (:cause m))})))

(defn call
  "One persisted request. Returns `{:status :headers :body bytes :json decoded-or-nil}`; throws
  `:podman/error` unless the status is in `:ok` (default any 2xx). The body is persisted before the
  status is looked at. `:json-body` is encoded and sent as the request body."
  [c method path {:keys [ok json-body] :as opts}]
  (let [opts (cond-> (dissoc opts :ok :json-body)
               (some? json-body) (assoc :body (.getBytes ^String (json/write-str json-body) StandardCharsets/UTF_8)
                                        :content-type "application/json"))
        op (str method " " api-prefix path)
        r (request c method path opts)
        ct (get-in r [:headers "content-type"])]
    (persist! c op (:body r) ct)
    (if (if ok (contains? ok (:status r)) (<= 200 (:status r) 299))
      (assoc r :json (when (some-> ct (str/includes? "json")) (decode (:body r))))
      (throw (error-ex op (:status r) (:body r))))))

;; ---------------------------------------------------------------------------------------------
;; System, networks, images

(defn available?
  "True when the endpoint answers `_ping` within 2 s. Never throws, never persists."
  [c]
  (try (= 200 (:status (request c "GET" "/_ping" {:timeout-ms 2000})))
       (catch Throwable _ false)))

(defn info
  "`GET /info`: host, version, store."
  [c]
  (:json (call c "GET" "/info" {})))

(defn socket-path
  "Filesystem path of the Podman socket as seen by the machine Podman runs on: the endpoint's own
  path for `unix://`, else `host.remoteSocket.path` from `info`."
  [c]
  (let [ep (parse-endpoint (:endpoint c))]
    (if (= :unix (:scheme ep))
      (:path ep)
      (some-> (get-in (info c) [:host :remoteSocket :path]) (str/replace #"^unix://" "")))))

(defn ensure-network!
  "Creates the DNS-enabled bridge network `name` unless it exists. -> `{:name :created? bool}`."
  [c name]
  (let [r (call c "GET" (str "/networks/" (encode name) "/exists") {:ok #{204 404}})]
    (if (= 204 (:status r))
      {:name name :created? false}
      (do (call c "POST" "/networks/create" {:json-body {:name name :dns_enabled true}})
          {:name name :created? true}))))

(defn remove-network!
  "Removes network `name`; true when it is gone afterwards (absent counts)."
  [c name]
  (boolean (call c "DELETE" (str "/networks/" (encode name)) {:ok #{200 204 404}})))

(defn image-exists? [c image]
  (= 204 (:status (call c "GET" (str "/images/" image "/exists") {:ok #{204 404}}))))

(defn pull!
  "Pulls `image`, streaming Podman's JSON progress objects (`:status`, `:stream`, `:id`, `:images`,
  `:error`) to `:on-event`. The whole stream is persisted when it ends. Throws `:podman/pull-failed`
  on an `:error` event. -> `{:image :id}`."
  [c image & {:keys [on-event timeout-ms] :or {timeout-ms 1200000}}]
  (let [op (str "POST " api-prefix "/images/pull")
        {:keys [status in close headers]} (open c "POST" "/images/pull"
                                                {:query {:reference image} :timeout-ms 60000})
        tee (ByteArrayOutputStream.)
        reader (BufferedReader.
                (InputStreamReader.
                 (stream (fn [^bytes b off len]
                           (let [n (.read ^InputStream in b (int off) (int len))]
                             (when (pos? n) (.write tee b (int off) (int n)))
                             n))
                         close)
                 StandardCharsets/UTF_8))]
    (try
      (let [events (bounded timeout-ms close (str "pull " image)
                            (fn [] (loop [acc []]
                                     (if-let [l (.readLine reader)]
                                       (let [e (when-not (str/blank? l)
                                                 (json/read-str l :key-fn keyword))]
                                         (when (and e on-event) (on-event e))
                                         (recur (cond-> acc e (conj e))))
                                       acc))))]
        (persist! c op (.toByteArray tee) (get headers "content-type"))
        (when-not (<= 200 status 299)
          (throw (error-ex op status (.toByteArray tee))))
        (when-let [err (some :error events)]
          (throw (ex-info (str "pull of " image " failed: " err)
                          {:type :podman/pull-failed :image image :message err})))
        {:image image :id (some :id (reverse events))})
      (finally (close)))))

;; ---------------------------------------------------------------------------------------------
;; Containers

(defn list-containers
  "`GET /containers/json`: `:all` includes stopped ones, `:labels` a map of exact label matches."
  [c & {:keys [all labels]}]
  (:json (call c "GET" "/containers/json"
               {:query {:all (boolean all)
                        :filters (when (seq labels)
                                   (json/write-str {:label (mapv (fn [[k v]] (str (name k) "=" v)) labels)}))}})))

(defn inspect
  "Container `id-or-name` as Podman describes it, nil when it does not exist."
  [c id-or-name]
  (let [r (call c "GET" (str "/containers/" (encode id-or-name) "/json") {:ok #{200 404}})]
    (when (= 200 (:status r)) (:json r))))

(defn create!
  "Creates a container from a libpod spec map (string keys as in Podman's SpecGenerator:
  \"name\" \"image\" \"labels\" \"env\" \"command\" \"portmappings\" \"mounts\" \"netns\" \"Networks\"
  ...). -> `{:id :warnings}`."
  [c spec]
  (let [j (:json (call c "POST" "/containers/create" {:json-body spec}))]
    {:id (:Id j) :warnings (:Warnings j)}))

(defn start!
  "Starts a container; already running is fine. -> true."
  [c id]
  (call c "POST" (str "/containers/" (encode id) "/start") {:ok #{204 304}})
  true)

(defn stop!
  "Stops a container (SIGTERM, then SIGKILL after `:timeout` seconds, default 10). -> true."
  [c id & {:keys [timeout] :or {timeout 10}}]
  (call c "POST" (str "/containers/" (encode id) "/stop") {:query {:timeout timeout} :ok #{204 304 404}})
  true)

(defn remove!
  "Removes a container; `:force` kills it first (after `:timeout` seconds of grace), volumes go with
  it. Absent is fine. -> true when it existed."
  [c id & {:keys [force timeout] :or {force true timeout 5}}]
  (let [r (call c "DELETE" (str "/containers/" (encode id))
                {:query {:force force :timeout timeout :v true} :ok #{200 204 404}})]
    (not= 404 (:status r))))

;; --- logs ------------------------------------------------------------------------------------

(defn- read-fully
  "Reads exactly `n` bytes into a fresh array; nil on clean EOF at 0, throws when cut short."
  ^bytes [^InputStream in n]
  (let [b (byte-array n)]
    (loop [off 0]
      (if (= off n)
        b
        (let [k (.read in b (int off) (int (- n off)))]
          (if (neg? k)
            (if (zero? off) nil (throw (EOFException. "log frame cut short")))
            (recur (+ off k))))))))

(defn frames
  "Lazy seq of `{:stream :stdout|:stderr|:stdin :payload bytes}` decoded from Podman's multiplexed
  stream (8-byte header: type, 3 zero bytes, big-endian length)."
  [^InputStream in]
  (lazy-seq
   (when-let [^bytes h (read-fully in 8)]
     (let [n (bit-or (bit-shift-left (bit-and 0xff (aget h 4)) 24) (bit-shift-left (bit-and 0xff (aget h 5)) 16)
                     (bit-shift-left (bit-and 0xff (aget h 6)) 8) (bit-and 0xff (aget h 7)))
           payload (if (zero? n) (byte-array 0) (read-fully in n))]
       (cons {:stream (case (int (aget h 0)) 0 :stdin 2 :stderr :stdout) :payload payload}
             (frames in))))))

(defn- parse-at [s]
  (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil)))

(defn frame->event
  "A `timestamps=true` log frame -> `{:stream :at :text}` (`:at` nil when unparsable)."
  [{:keys [stream payload]}]
  (let [s (String. ^bytes payload StandardCharsets/UTF_8)
        s (str/replace s #"\r?\n$" "")
        i (str/index-of s " ")
        at (when i (parse-at (subs s 0 i)))]
    {:stream stream :at at :text (if at (subs s (inc i)) s)}))

(defn- tail-instant
  "The instant at the start of the last line of `file` (nil if empty or absent)."
  [^java.io.File file]
  (when (.exists file)
    (with-open [raf (RandomAccessFile. file "r")]
      (let [len (.length raf)
            n (int (min len 8192))
            b (byte-array n)]
        (.seek raf (- len n))
        (.readFully raf b)
        (some-> (last (str/split-lines (String. b StandardCharsets/UTF_8)))
                (str/split #" " 2) first parse-at)))))

(defn mirror-sink
  "A fn taking a seq of log events and appending them to `logs/services/<service>/<date>.log` of
  `ws` (date of the event, UTC), one line `<instant> out|err <text>`. Events not newer than the
  last line already in that file are skipped, so mirroring the same stream twice adds nothing."
  [ws service]
  (let [last-at (atom {})]
    (fn [events]
      (doseq [[date evs] (group-by #(str (.toLocalDate (.atOffset ^Instant (or (:at %) (Instant/now)) ZoneOffset/UTC)))
                                  events)]
        (let [file (.toFile (store/path ws "logs" "services" service (str date ".log")))
              known (if (contains? @last-at file) (@last-at file) (tail-instant file))
              fresh (filter #(or (nil? known) (nil? (:at %)) (.isAfter ^Instant (:at %) ^Instant known)) evs)]
          (when (seq fresh)
            (.mkdirs (.getParentFile file))
            (spit file (apply str (for [{:keys [stream at text]} fresh]
                                    (str (or at (Instant/now)) " " (if (= stream :stderr) "err" "out") " " text "\n")))
                  :append true)
            (swap! last-at assoc file (or (:at (last fresh)) known))))))))

(defn logs
  "Log lines of a container as a lazy seq of `{:stream :at :text}`. `:since`/`:until` Instants
  (lines before `:since` are also dropped client side), `:follow` keeps the connection open and
  the seq blocks for new lines, `:tail` last n lines. `:mirror [ws service]` appends the lines to
  `logs/services/<service>/<date>.log` as they are read. Close early with `((:close (meta s)))`."
  [c id & {:keys [since until follow tail mirror]}]
  (let [{:keys [status in close]} (open c "GET" (str "/containers/" (encode id) "/logs")
                                        {:query {:stdout true :stderr true :timestamps true
                                                 :follow (boolean follow)
                                                 :since (some-> since str) :until (some-> until str)
                                                 :tail tail}})]
    (when-not (<= 200 status 299)
      (let [bs (try (.readAllBytes ^InputStream in) (finally (close)))]
        (throw (error-ex (str "GET " api-prefix "/containers/" id "/logs") status bs))))
    (let [sink (when mirror (mirror-sink (first mirror) (second mirror)))
          evs (->> (frames in)
                   (map frame->event)
                   (filter #(or (nil? since) (nil? (:at %)) (not (.isBefore ^Instant (:at %) ^Instant since)))))
          evs (cond
                (nil? sink) evs
                follow (map (fn [e] (sink [e]) e) evs)
                :else (mapcat (fn [chunk] (sink chunk) chunk) (partition-all 200 evs)))
          guarded (fn step [s]
                    (lazy-seq
                     (if-let [s (try (seq s) (catch Throwable t (close) (throw t)))]
                       (cons (first s) (step (rest s)))
                       (do (close) nil))))]
      (with-meta (guarded evs) {:close close}))))

;; --- exec ------------------------------------------------------------------------------------

(defn exec!
  "Runs `cmd` (vector of strings) inside a running container and waits. Create, start (the output
  stream) and inspect responses are all persisted. -> `{:exit n :stdout \"…\" :stderr \"…\"}`."
  [c id cmd & {:keys [timeout-ms env user] :or {timeout-ms default-timeout-ms}}]
  (let [eid (:Id (:json (call c "POST" (str "/containers/" (encode id) "/exec")
                              {:json-body (cond-> {:Cmd (vec cmd) :AttachStdout true :AttachStderr true}
                                            env (assoc :Env (mapv (fn [[k v]] (str (name k) "=" v)) env))
                                            user (assoc :User user))})))
        started (call c "POST" (str "/exec/" eid "/start") {:json-body {:Detach false} :timeout-ms timeout-ms})
        out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)
        _ (doseq [{:keys [stream payload]} (frames (ByteArrayInputStream. ^bytes (:body started)))]
            (.write (if (= stream :stderr) err out) ^bytes payload))
        done (:json (call c "GET" (str "/exec/" eid "/json") {}))]
    {:exit (:ExitCode done)
     :stdout (.toString out "UTF-8")
     :stderr (.toString err "UTF-8")}))
