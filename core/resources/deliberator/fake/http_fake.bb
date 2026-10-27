;; The HTTP fake of Deliberator: a record/replay reverse proxy in babashka + http-kit, run by the daemon in a
;; container (docker.io/babashka/babashka) as `bb /app/http_fake.bb`. Nothing here knows the daemon; it is configured by
;; environment and writes plain files.
;;
;;   FAKE_MODE        record | replay
;;   FAKE_UPSTREAM    record: base URL the proxy forwards to (as the container sees it, e.g. http://deliberator-ws-service-a:8081)
;;   FAKE_PORT        listen port (default 8080)
;;   FAKE_RECORD_DIR  record: where exchanges are written (one NNNNN.json each, persisted before the client gets its answer)
;;   FAKE_REPLAY_DIR  replay: the recording to serve (every *.json in it)
;;
;; An exchange file is JSON: {seq at ms request{method path query headers body|body-base64} response{status headers body|body-base64}
;; match{path-template}}. `match.path-template` is the request path with id-like parts (uuids, long hex, runs of 4+ digits)
;; turned into `{}`; a hand-edited template (`/ack/{}`) works the same. Replay answers, in this order: same method+path+query,
;; same method+path, a template of the same method that matches the path; several candidates are served in recorded order,
;; round robin. No match: 404 with a JSON body, header x-fake-match: none, and a MISS line on stdout (the container's log).
;; Control: GET /__fake/health -> {mode exchanges}, GET /__fake/exchanges -> [{seq method path status}].
(require '[org.httpkit.server :as srv]
         '[org.httpkit.client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])
(import '(java.util Base64 Arrays)
        '(java.nio.charset StandardCharsets)
        '(java.time Instant))

(def mode (or (System/getenv "FAKE_MODE") "record"))
(def upstream (some-> (System/getenv "FAKE_UPSTREAM") (str/replace #"/+$" "")))
(def port (parse-long (or (System/getenv "FAKE_PORT") "8080")))
(def record-dir (System/getenv "FAKE_RECORD_DIR"))
(def replay-dir (System/getenv "FAKE_REPLAY_DIR"))

(defn log [& xs] (println (str (Instant/now)) (str/join " " xs)) (flush))

;; bodies: text when it is valid UTF-8 without control characters, else base64 (the bytes are never changed)
(defn text-bytes? [^bytes bs]
  (let [s (String. bs StandardCharsets/UTF_8)]
    (and (Arrays/equals bs (.getBytes s StandardCharsets/UTF_8))      ; a malformed sequence would not survive the round trip
         (not (re-find #"[\x00-\x08\x0B\x0C\x0E-\x1F]" s)))))

(defn body->map [^bytes bs]
  (cond (or (nil? bs) (zero? (alength bs))) {}
        (text-bytes? bs) {:body (String. bs StandardCharsets/UTF_8)}
        :else {:body-base64 (.encodeToString (Base64/getEncoder) bs)}))

(defn map->body ^bytes [{:keys [body body-base64]}]
  (cond body-base64 (.decode (Base64/getDecoder) ^String body-base64)
        body (.getBytes ^String body StandardCharsets/UTF_8)
        :else (byte-array 0)))

(defn lower-headers [h] (into (sorted-map) (for [[k v] h] [(str/lower-case (name k)) v])))

(def id-part #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}|\d{4,}")

(defn path-template [path] (str/replace path id-part "{}"))

(defn template->re [t]
  (re-pattern (str "^" (str/join "[^/]+" (map #(java.util.regex.Pattern/quote %) (str/split t #"\{\}" -1))) "$")))

;; recording -----------------------------------------------------------------------------------------------------------
(def seqn (atom 0))

(defn next-seq! []
  (swap! seqn inc))

(defn write-exchange! [ex]
  (let [f (io/file record-dir (format "%05d.json" (:seq ex)))
        tmp (io/file record-dir (format ".%05d.json.tmp" (:seq ex)))]
    (spit tmp (json/generate-string ex {:pretty true}))
    (.renameTo tmp f)))

(def hop #{"host" "content-length" "connection" "accept-encoding" "transfer-encoding" "keep-alive" "upgrade" "te" "trailer"
           "proxy-authorization" "proxy-authenticate"})

(defn record-handler [{:keys [request-method uri query-string headers body]}]
  (let [t0 (System/currentTimeMillis)
        req-bytes (if body (.readAllBytes ^java.io.InputStream body) (byte-array 0))
        method (str/upper-case (name request-method))
        url (str upstream uri (when (seq query-string) (str "?" query-string)))
        resp @(http/request {:method request-method :url url :as :byte-array :follow-redirects false :timeout 30000
                             :headers (into {} (remove (fn [[k _]] (hop (str/lower-case (name k))))) headers)
                             :body (when (pos? (alength req-bytes)) req-bytes)})]
    (if (or (:error resp) (nil? (:status resp)))
      (do (log "UPSTREAM-ERROR" method url (str (:error resp)))
          {:status 502 :headers {"content-type" "application/json" "x-fake-match" "none"}
           :body (json/generate-string {:error "fake: the upstream did not answer" :upstream url :cause (str (:error resp))})})
      (let [ex {:seq (next-seq!) :at (str (Instant/now)) :ms (- (System/currentTimeMillis) t0)
                :request (merge {:method method :path uri :query (or query-string "") :headers (lower-headers headers)}
                                (body->map req-bytes))
                :response (merge {:status (:status resp) :headers (lower-headers (:headers resp))}
                                 (body->map (:body resp)))
                :match {:path-template (path-template uri)}}]
        (write-exchange! ex)                                  ; persisted before the client sees the answer
        (log "RECORD" (:seq ex) method uri (:status resp))
        {:status (:status resp)
         :headers (into {} (comp (remove (fn [[k _]] (hop (name k)))) (map (fn [[k v]] [(name k) v]))) (:headers resp))
         :body (java.io.ByteArrayInputStream. ^bytes (:body resp))}))))

;; replay --------------------------------------------------------------------------------------------------------------
(def exchanges
  (delay (vec (for [^java.io.File f (sort-by #(.getName ^java.io.File %) (.listFiles (io/file replay-dir)))
                    :when (re-matches #"\d+\.json" (.getName f))]
                (let [ex (json/parse-string (slurp f) true)]
                  (assoc-in ex [:match :re] (template->re (or (get-in ex [:match :path-template]) (path-template (get-in ex [:request :path]))))))))))

(def counters (atom {}))

(defn pick [k candidates]
  (when (seq candidates)
    (let [n (get (swap! counters update k (fnil inc -1)) k)]
      (nth candidates (mod n (count candidates))))))

(defn find-exchange [method path query]
  (let [exs @exchanges
        same (fn [pred] (filterv #(and (= method (get-in % [:request :method])) (pred %)) exs))]
    (or (when-let [e (pick [:q method path query] (same #(and (= path (get-in % [:request :path])) (= (or query "") (get-in % [:request :query])))))] [:exact e])
        (when-let [e (pick [:p method path] (same #(= path (get-in % [:request :path]))))] [:path e])
        (when-let [e (pick [:t method (path-template path)] (same #(re-matches (get-in % [:match :re]) path)))] [:template e]))))

(def drop-resp #{"content-length" "transfer-encoding" "connection" "date" "server" "content-encoding" "keep-alive"})

(defn replay-handler [{:keys [request-method uri query-string]}]
  (let [method (str/upper-case (name request-method))]
    (if-let [[how ex] (find-exchange method uri (or query-string ""))]
      (do (log "REPLAY" (:seq ex) method uri (name how))
          {:status (get-in ex [:response :status])
           :headers (assoc (into {} (comp (map (fn [[k v]] [(name k) v])) (remove (fn [[k _]] (drop-resp k)))) (get-in ex [:response :headers]))
                           "x-fake-match" (name how) "x-fake-recording" (str (:seq ex)))
           :body (java.io.ByteArrayInputStream. (map->body (:response ex)))})
      (do (log "MISS" method uri)
          {:status 404 :headers {"content-type" "application/json" "x-fake-match" "none"}
           :body (json/generate-string {:error "fake: no recording matches this request" :method method :path uri :query (or query-string "")
                                        :hint "record it (fake.record) or edit a recorded exchange's match.path-template"})}))))

;; control + main ------------------------------------------------------------------------------------------------------
(defn handler [{:keys [uri] :as req}]
  (cond
    (= uri "/__fake/health")
    {:status 200 :headers {"content-type" "application/json"}
     :body (json/generate-string {:mode mode :exchanges (if (= mode "replay") (count @exchanges) @seqn)})}
    (= uri "/__fake/exchanges")
    {:status 200 :headers {"content-type" "application/json"}
     :body (json/generate-string (for [e (if (= mode "replay") @exchanges [])]
                                   {:seq (:seq e) :method (get-in e [:request :method]) :path (get-in e [:request :path]) :status (get-in e [:response :status])}))}
    (= mode "replay") (replay-handler req)
    :else (record-handler req)))

(when-not (#{"record" "replay"} mode) (log "FAKE_MODE must be record or replay, not" mode) (System/exit 2))
(when (and (= mode "record") (or (str/blank? upstream) (str/blank? record-dir))) (log "record needs FAKE_UPSTREAM and FAKE_RECORD_DIR") (System/exit 2))
(when (and (= mode "replay") (or (str/blank? replay-dir) (not (.isDirectory (io/file replay-dir))))) (log "replay needs FAKE_REPLAY_DIR (a directory)") (System/exit 2))
(when (= mode "record")
  (.mkdirs (io/file record-dir))
  (reset! seqn (count (filter #(re-matches #"\d+\.json" (.getName ^java.io.File %)) (.listFiles (io/file record-dir))))))
(when (= mode "replay") (force exchanges))
(srv/run-server handler {:port port :ip "0.0.0.0"})
(log "FAKE" mode "listening on" port (if (= mode "record") (str "-> " upstream) (str "from " replay-dir " (" (count @exchanges) " exchanges)")))
@(promise)
