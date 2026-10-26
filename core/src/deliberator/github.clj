(ns deliberator.github
  "The GitHub REST client of M5.2: read-only GETs, every response persisted raw before anything parses it (principle 1), the
  token never persisted.

  Token (`(resolve-token ws)` -> `{:token :source}`; the token lives in this process's memory for one request and nowhere else).
  The workspace's `github.edn` (hand-written, optional) names WHERE the token comes from, it never holds one:
  `{:token-source :gh|:env|:file|:none :env \"VAR\" :file \"/path\" :api \"https://api.github.com\"}`. Without the record:
  `GITHUB_TOKEN`, then `GH_TOKEN`, then `gh auth token`, then no token (public repositories answer listings anonymously;
  a job's log download needs a token, `ci/auth-required` says so). The API base is the record's `:api`, else
  `DELIBERATOR_GITHUB_API`, else `https://api.github.com`.

  Redaction (the way decision 55 treats AWS secrets, here at the persistence boundary): the persisted request record has its
  `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and `X-GitHub-Token` headers replaced by `<redacted>` (names,
  case-insensitively), the signed query string of any URL on another host than the API (the log blob redirect carries a
  short-lived signature) replaced by `?<redacted>`, and the exact token value, and any string shaped like a GitHub token
  (`ghp_…`, `gho_…`, `ghs_…`, `ghu_…`, `ghr_…`, `github_pat_…`), replaced by `<redacted>` in the sidecar and in a persisted body
  (a body that echoed the token back is the only case where a body differs from the bytes received).

  Calls never follow a redirect by themselves: the job-log endpoint answers 302 to a signed blob URL, which must be fetched
  WITHOUT the Authorization header. Each call is one raw blob (`:source :http`, `:op \"github GET <path>\"`) and one line in
  `sessions/<k>/ci/requests.log` (time, status, bytes, sha, rate-limit remaining), whatever the status."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption StandardOpenOption)
           (java.time Duration Instant)
           (java.util.concurrent TimeUnit)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def redacted "<redacted>")

(def default-api "https://api.github.com")

;; token -------------------------------------------------------------------------------------------

(defn record
  "`github.edn` of the workspace, nil when absent."
  [ws]
  (store/read-edn (store/path ws "github.edn")))

(defn api-base
  [ws]
  (str/replace (str (or (:api (record ws)) (System/getenv "DELIBERATOR_GITHUB_API") default-api)) #"/+$" ""))

(defn- gh-token
  "`gh auth token`'s stdout, nil when gh is absent, not logged in or slow (10 s)."
  []
  (try
    (let [p (.start (doto (ProcessBuilder. ^java.util.List ["gh" "auth" "token"]) (.redirectErrorStream false)))
          _ (.close (.getOutputStream p))
          err (future (slurp (.getErrorStream p)))
          out (future (slurp (.getInputStream p)))]
      (if (.waitFor p 10 TimeUnit/SECONDS)
        (let [t (str/trim @out)] (when (and (zero? (.exitValue p)) (seq t)) t))
        (do (.destroyForcibly p) nil)))
    (catch Exception _ nil)))

(defn- env-token [k] (some-> (System/getenv k) str/trim not-empty))

(defn resolve-token
  "-> `{:token str-or-nil :source \"env:GITHUB_TOKEN\"|\"env:GH_TOKEN\"|\"gh\"|\"file\"|\"none\"}`."
  [ws]
  (let [rec (record ws)
        ok (fn [src t] (when t {:token t :source src}))]
    (or (case (:token-source rec)
          :gh (ok "gh" (gh-token))
          :env (let [v (or (:env rec) "GITHUB_TOKEN")] (ok (str "env:" v) (env-token v)))
          :file (ok "file" (some-> (:file rec) io/file (#(when (.isFile ^java.io.File %) (str/trim (slurp %)))) not-empty))
          :none {:token nil :source "none"}
          nil)
        (when-not (:token-source rec)
          (or (ok "env:GITHUB_TOKEN" (env-token "GITHUB_TOKEN"))
              (ok "env:GH_TOKEN" (env-token "GH_TOKEN"))
              (ok "gh" (gh-token))))
        {:token nil :source "none"})))

;; redaction ---------------------------------------------------------------------------------------

(def ^:private secret-headers #{"authorization" "proxy-authorization" "cookie" "set-cookie" "x-github-token"})

(def ^:private token-shape #"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")

(defn redact-text
  "`s` with `token` (when given) and anything token-shaped replaced by `<redacted>`."
  [s token]
  (let [s (str s)
        s (if (seq token) (str/replace s token redacted) s)]
    (str/replace s token-shape redacted)))

(declare redact-url)

(defn redact-headers
  "A header map (names to string or vector) with the secret headers' values replaced, token-shaped strings scrubbed and the
  signed query of a `Location` on another host than `api` removed."
  [headers token api]
  (into (sorted-map)
        (map (fn [[k v]]
               (let [k (str/lower-case (str k))
                     f (cond (secret-headers k) (constantly redacted)
                             (= "location" k) #(redact-url % api token)
                             :else #(redact-text % token))]
                 [k (if (sequential? v) (mapv f v) (f v))])))
        headers))

(defn redact-url
  "`url` for the sidecar: on the API's own host the query is kept (it holds only filters), on any other host (a signed blob
  URL) it becomes `?<redacted>`."
  [url api token]
  (let [u (URI. (str url))
        same? (= (.getHost (URI. (str api))) (.getHost u))
        base (str (.getScheme u) "://" (.getRawAuthority u) (.getRawPath u))]
    (cond (nil? (.getRawQuery u)) (redact-text base token)
          same? (redact-text (str base "?" (.getRawQuery u)) token)
          :else (str (redact-text base token) "?" redacted))))

(defn- op-label
  "`github GET <path>` for the sidecar: the query only on the API's own host."
  [url api token]
  (let [u (URI. (str url))
        same? (= (.getHost (URI. (str api))) (.getHost u))]
    (redact-text (str "github GET " (.getRawPath u) (when (and same? (.getRawQuery u)) (str "?" (.getRawQuery u)))) token)))

(defn redact-body
  "The bytes to persist for a response body: unchanged unless it contains the token verbatim or a token-shaped string."
  ^bytes [^bytes body token]
  (let [s (String. body StandardCharsets/ISO_8859_1)]            ; 1 byte = 1 char: lossless both ways
    (if (or (and (seq token) (str/includes? s token)) (re-find token-shape s))
      (.getBytes ^String (redact-text s token) StandardCharsets/ISO_8859_1)
      body)))

;; one call ----------------------------------------------------------------------------------------

(def ^:private client
  (delay (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.connectTimeout (Duration/ofSeconds 15)) (.build))))

(defn- headers-map [^HttpResponse resp]
  (into {} (map (fn [[k vs]] [k (if (= 1 (count vs)) (first vs) (vec vs))])) (.map (.headers resp))))

(defn- log-request!
  [ws session line]
  (when session
    (let [f (.resolve (.resolve (session/dir ws session) "ci") "requests.log")]
      (Files/createDirectories (.getParent f) (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/write f (.getBytes (str line "\n") StandardCharsets/UTF_8)
                   (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND])))))

(defn get!
  "One GET of `url` (absolute). `ctx`: `{:ws :session :token :auth? (send the Authorization header, default true) :accept :op}`.
  Persisted raw whatever the status. -> `{:status :headers :body :sha :bytes :location :rate-remaining}` (`:headers` as
  received, lower-case names; `:body` the bytes that were persisted)."
  [{:keys [ws session token auth? accept op] :or {auth? true}} url]
  (let [api (api-base ws)
        now (store/now)
        b (doto ^HttpRequest$Builder (HttpRequest/newBuilder (URI. (str url)))
            (.timeout (Duration/ofSeconds 60))
            (.header "User-Agent" "deliberator")
            (.header "Accept" (or accept "application/vnd.github+json"))
            (.header "X-GitHub-Api-Version" "2022-11-28"))
        _ (when (and auth? token) (.header b "Authorization" (str "Bearer " token)))
        req-headers (cond-> {"user-agent" "deliberator" "accept" (or accept "application/vnd.github+json")
                             "x-github-api-version" "2022-11-28"}
                      (and auth? token) (assoc "authorization" (str "Bearer " token)))
        resp (try (.send ^HttpClient @client (.build b) (HttpResponse$BodyHandlers/ofByteArray))
                  (catch java.io.IOException e
                    (fail :ci/unreachable (str "cannot reach " (redact-url url api token) ": " (redact-text (ex-message e) token))
                          :hint "check the network, or the api base (github.edn :api, DELIBERATOR_GITHUB_API)")))
        status (.statusCode resp)
        hdrs (headers-map resp)
        body (redact-body (.body resp) token)
        ctype (or (get hdrs "content-type") "application/octet-stream")
        ctype (if (sequential? ctype) (first ctype) ctype)
        safe-url (redact-url url api token)
        meta {:source :http :op (op-label url api token) :env nil :at now
              :content-type ctype :status status
              :request {:method "GET" :url safe-url :headers (redact-headers req-headers token api)}
              :response-headers (redact-headers hdrs token api)}
        {:keys [sha bytes]} (store/raw-put! ws body meta)
        remaining (get hdrs "x-ratelimit-remaining")
        location (some-> (get hdrs "location") (#(if (sequential? %) (first %) %)))]
    (log-request! ws session (str now " GET " safe-url " -> " status " " bytes "B sha=" (subs sha 0 12)
                                  (when remaining (str " ratelimit-remaining=" remaining))))
    {:status status :headers hdrs :body body :sha sha :bytes bytes :location location
     :rate-remaining (some-> remaining parse-long)}))

(defn api-url
  "`<api>/<path>` with `query` (a map; nil values dropped) url-encoded."
  [ws path query]
  (let [enc #(URLEncoder/encode (str %) "UTF-8")
        q (str/join "&" (for [[k v] query :when (some? v)] (str (enc (name k)) "=" (enc v))))]
    (str (api-base ws) path (when (seq q) (str "?" q)))))

(defn error-for
  "The typed error for a non-2xx `resp` of an API call to `what`."
  [{:keys [status headers body]} what token-source]
  (let [msg (try (get (json/read-str (String. ^bytes body StandardCharsets/UTF_8)) "message") (catch Exception _ nil))
        remaining (get headers "x-ratelimit-remaining")
        reset (get headers "x-ratelimit-reset")]
    (cond
      (and (#{403 429} status) (= "0" remaining))
      (fail :ci/rate-limited (str "GitHub rate limit used up" (when reset (str ", resets at " (Instant/ofEpochSecond (parse-long reset)))))
            :hint (if (= "none" token-source) "an anonymous client gets 60 requests an hour; log in with gh auth login or set GITHUB_TOKEN" "wait for the reset"))
      (and (#{401 403} status) (= "none" token-source))
      (fail :ci/auth-required (str what " needs a GitHub token (" status (when msg (str ": " msg)) ")")
            :hint "gh auth login, or export GITHUB_TOKEN; github.edn names a token source")
      (#{401 403} status)
      (fail :ci/forbidden (str what " was refused (" status (when msg (str ": " msg)) ")")
            :hint (str "the token (" token-source ") may lack the repo or actions:read scope"))
      (= 404 status)
      (fail :ci/not-found (str what " was not found" (when msg (str ": " msg)))
            :hint "check owner/name, the run and job ids, and that the logs have not expired (90 days)")
      :else (fail :ci/http-error (str what " answered " status (when msg (str ": " msg)))))))
