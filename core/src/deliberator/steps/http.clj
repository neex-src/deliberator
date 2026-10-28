(ns deliberator.steps.http
  "Kind `http`: one request, written like a `.http` file in the step body:

      ### http [base-target] [name]
      POST /orders HTTP/1.1          <- METHOD URL [HTTP/x]; a first line without a method is a GET
      Content-Type: application/json <- `Name: value` lines
                                     <- blank line, then the body, sent byte-exact
      {\"id\": 1}

  The URL is absolute, or relative (`/orders`) to the step's base target: `http://host:port` or an
  environment key (`service/a`, resolved with `:resolve-target`). `X-Correlation-Id:
  <step correlation>` is added unless the request has one; `Content-Length` is dropped (computed).
  JDK `HttpClient`, HTTP/1.1, redirects are NOT followed (the 3xx is the answer), the attempt's
  deadline bounds the call.

  Persisted: `request.http` = the request as sent (request line, headers incl. the added one, blank
  line, body); `response.<ext>` = the raw response body alone (json/xml/html/txt/bin by content
  type and content). Status and headers live in the step's facts: `status`, `bytes`, and every
  response header as `header.<lowercase-name>` (repeated headers joined with `, `). `$` addresses a
  JSON body. Errors keep the request bytes and carry a hint built from the URL and environment.

  B40: on a REMOTE environment a request whose method is not GET, HEAD or OPTIONS is a mutation like any
  AWS write: `:read-only true` refuses it flat (no token, nothing is sent), `:read-only false` makes it a confirmation
  (the token is bound to the method, the URL and the body). A local environment is not gated."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c]
            [deliberator.store :as store])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio ByteBuffer)
           (java.time Duration)))

(def ^:private http-methods #{"GET" "POST" "PUT" "PATCH" "DELETE" "HEAD" "OPTIONS" "TRACE"})

(defn- fail [msg hint & [data]]
  (throw (ex-info msg (merge {:type :http/bad-request :hint hint} data))))

(defn parse-request
  "The body bytes of a step -> `{:method :url :headers [[name value]..] :body <bytes or nil>}`."
  [^bytes bs]
  (let [bs (or bs (byte-array 0))
        s (String. bs StandardCharsets/ISO_8859_1)
        m (re-matcher #"\r?\n\r?\n" s)
        [head body] (if (.find m)
                      [(subs s 0 (.start m)) (java.util.Arrays/copyOfRange bs (.end m) (alength bs))]
                      [s nil])
        lines (->> (str/split head #"\r?\n") (drop-while str/blank?))
        _ (when (empty? lines) (fail "empty request" "write `GET http://host/path` as the step body"))
        [w1 w2] (str/split (str/trim (first lines)) #"\s+")
        [method url] (if (http-methods (str/upper-case w1)) [(str/upper-case w1) w2] ["GET" w1])
        _ (when (str/blank? url) (fail (str "request line has no URL: " (first lines)) (str method " http://host/path")))
        headers (mapv (fn [l]
                        (let [i (str/index-of l ":")]
                          (when-not (and i (pos? i))
                            (fail (str "bad header line " (pr-str l)) "Name: value, or a blank line before the body"))
                          [(str/trim (subs l 0 i)) (str/trim (subs l (inc i)))]))
                      (remove str/blank? (rest lines)))]
    {:method method :url url :headers headers
     :body (when (and body (pos? (alength ^bytes body))) body)}))

(defn- base-of [ctx step]
  (when-let [t (:target step)]
    (if (re-find #"^https?://" t)
      t
      (let [v ((:resolve-target ctx) t)]
        (if (string? v) v
            (fail (str "target " t " is not a URL") (str "environment " (c/env-name ctx) " maps it to " (pr-str v))))))))

(defn- full-url [ctx step url]
  (cond
    (re-find #"^https?://" url) url
    (str/starts-with? url "/")
    (if-let [b (base-of ctx step)]
      (str (str/replace b #"/+$" "") url)
      (fail (str "relative URL " url " and no base target")
            (str "name a base on the step line (### http service/a) or write the absolute URL; environment "
                 (c/env-name ctx))))
    :else (fail (str "URL " url " is neither absolute (http://..) nor a path (/..)") "use http://host:port/path")))

(defn- serialise [method url headers ^bytes body]
  (let [head (str method " " url " HTTP/1.1\n"
                  (apply str (map (fn [[n v]] (str n ": " v "\n")) headers)))]
    (if body
      (let [h (.getBytes (str head "\n") StandardCharsets/ISO_8859_1)
            out (byte-array (+ (alength h) (alength body)))]
        (System/arraycopy h 0 out 0 (alength h))
        (System/arraycopy body 0 out (alength h) (alength body))
        out)
      (.getBytes head StandardCharsets/ISO_8859_1))))

(defn- text-bytes? [^bytes bs]
  (let [dec (-> StandardCharsets/UTF_8 .newDecoder
                (.onMalformedInput CodingErrorAction/REPORT)
                (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try (.decode dec (ByteBuffer/wrap bs)) (not-any? zero? bs)
         (catch CharacterCodingException _ false))))

(defn- json-body [^bytes bs]
  (when (pos? (alength bs))
    (try (let [v (json/read-str (c/text bs))] (when (coll? v) v))
         (catch Exception _ nil))))

(defn- extension [ct ^bytes bs jsn]
  (let [ct (str/lower-case (or ct ""))]
    (cond (or (str/includes? ct "json") (some? jsn)) "json"
          (str/includes? ct "xml") "xml"
          (str/includes? ct "html") "html"
          (or (str/starts-with? ct "text/") (zero? (alength bs)) (text-bytes? bs)) "txt"
          :else "bin")))

(defn- header-facts [^java.net.http.HttpHeaders hs]
  (into (sorted-map)
        (for [[n vs] (.map hs) :when (not (str/starts-with? n ":"))]
          [(str "header." (str/lower-case n)) (str/join ", " vs)])))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.version HttpClient$Version/HTTP_1_1)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofSeconds 10))
             .build)))

(def safe-methods
  "The methods that only read (RFC 9110 section 9.2.1 minus TRACE): everything else is a mutation on a remote environment."
  #{"GET" "HEAD" "OPTIONS"})

(defn gate!
  "B40: the gate of a request on a remote environment. A safe method passes. Any other is one mutation: read-only -> refused,
  no token; else it needs the confirmation token bound to [method url body sha]. Throws the step ERROR (`confirm.required` event
  on the run's sink); returns nil when the request may be sent. `rq-bytes` is kept in the error, the user sees what was not sent."
  [ctx method url ^bytes body rq-bytes]
  (when (and (= :remote (some-> ctx :env :kind)) (not (safe-methods method)))
    (let [g (aws/gate! (select-keys ctx [:ws :session :confirm]) (:env ctx) :http (keyword method)
                       {:Url url :BodySha256 (store/sha256 (or body (byte-array 0)))})]
      (when (aws/error? g)
        (let [e (sa/failure ctx g)]
          (throw (ex-info (ex-message e) (merge (ex-data e) {:request-bytes rq-bytes :request-ext "http"}) e)))))))

(defn run [ctx step]
  (let [{:keys [method url headers body]} (parse-request (:body step))
        url (full-url ctx step url)
        headers (cond-> (vec (remove #(= "content-length" (str/lower-case (first %))) headers))
                  (not-any? #(= "x-correlation-id" (str/lower-case (first %))) headers)
                  (conj ["X-Correlation-Id" (:step-correlation ctx)]))
        rq-bytes (serialise method url headers body)
        _ (gate! ctx method url body rq-bytes)
        host (try (let [u (URI. url)] (str (.getHost u) (when (pos? (.getPort u)) (str ":" (.getPort u)))))
                  (catch Exception _ url))
        ex (fn [msg hint cause]
             (ex-info msg {:type :http/failed :hint hint :request-bytes rq-bytes :request-ext "http"} cause))]
    (let [req (try
                (let [b (HttpRequest/newBuilder (URI. url))]
                  (doseq [[n v] headers] (.header b n v))
                  (-> b
                      (.timeout (Duration/ofMillis (max 1 (kinds/remaining-ms ctx))))
                      (.method method (if body (HttpRequest$BodyPublishers/ofByteArray body)
                                          (HttpRequest$BodyPublishers/noBody)))
                      .build))
                (catch IllegalArgumentException e
                  (throw (ex-info (str "cannot build the request: " (ex-message e))
                                  {:type :http/bad-request :request-bytes rq-bytes :request-ext "http"
                                   :hint "check the URL and that no header is one the JDK HTTP client reserves (Host, Connection, Upgrade)"}
                                  e))))
          resp (try (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofByteArray))
                    (catch java.net.ConnectException e
                      (throw (ex (str "cannot connect to " host)
                                       (str "nothing answers at " host " (environment " (c/env-name ctx)
                                            (when (:target step) (str ", target " (:target step))) "): is it up?")
                                       e)))
                    (catch java.net.http.HttpTimeoutException e
                      (throw (ex (str "no answer from " host " before the deadline")
                                 (str "the step timeout was reached; raise it with # @timeout") e)))
                    (catch java.io.IOException e
                      (throw (ex (str "request to " host " failed: " (or (ex-message e) (class e)))
                                 (str "check " host " (environment " (c/env-name ctx) ")") e))))
          rs ^bytes (.body resp)
          hs (header-facts (.headers resp))
          jsn (json-body rs)
          ext (extension (get hs "header.content-type") rs jsn)]
      (cond-> {:facts (into {"status" (.statusCode resp) "bytes" (alength rs)} hs)
               :request-bytes rq-bytes :request-ext "http"
               :response-bytes rs :response-ext ext
               :source :http :op (str method " " url)
               :lines (if (zero? (alength rs)) 0 (count (str/split-lines (String. rs StandardCharsets/UTF_8))))}
        true (assoc :body-json jsn)
        (get hs "header.content-type") (assoc :content-type (get hs "header.content-type"))))))

(def kind
  {:kind "http" :verbs #{nil} :target :optional
   :directives []
   :facts [{:name "status" :type :number} {:name "bytes" :type :number}
           {:name "header.*" :type :string}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
