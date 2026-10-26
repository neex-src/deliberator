(ns deliberator.github-test
  "The GitHub client's persistence boundary (M5.0 spike 3): the token is sent to the API and nowhere else,
  and it never lands in `raw/`, the sidecars or the request log, however it comes back. Against a local stand-in server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.github :as gh]
            [deliberator.github-fake :as fake]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def token "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")

(defn- all-text
  "Every file under the workspace as one string (latin-1: lossless), to look for what must not be there."
  []
  (->> (file-seq (.toFile (store/path "w")))
       (filter #(.isFile ^java.io.File %))
       (map #(String. (java.nio.file.Files/readAllBytes (.toPath ^java.io.File %)) "ISO-8859-1"))
       (str/join "\n")))

(deftest redaction-rules
  (testing "headers: the secret names by name, case-insensitively; token-shaped values anywhere"
    (is (= {"authorization" "<redacted>" "set-cookie" "<redacted>" "accept" "x"}
           (gh/redact-headers {"Authorization" (str "Bearer " token) "Set-Cookie" "a=b" "Accept" "x"} token "https://api.github.com")))
    (is (= {"x-note" "<redacted> and <redacted>"}
           (gh/redact-headers {"x-note" (str token " and github_pat_11ABCDEFG0123456789_abcdefghijklmnop") } token "https://api.github.com")))
    (is (= {"location" "https://blob.example.net/x?<redacted>"}
           (gh/redact-headers {"Location" "https://blob.example.net/x?sig=SECRET"} token "https://api.github.com"))))
  (testing "urls: the query of another host is a signature and goes; the API's own query stays"
    (is (= "https://blob.example.net/a/b?<redacted>" (gh/redact-url "https://blob.example.net/a/b?sig=SECRET&se=2026" "https://api.github.com" nil)))
    (is (= "https://api.github.com/repos/o/r/actions/runs?per_page=5" (gh/redact-url "https://api.github.com/repos/o/r/actions/runs?per_page=5" "https://api.github.com" nil))))
  (testing "a body that echoes the token is the one body that changes; any other body is the received bytes"
    (let [b (.getBytes (str "{\"echo\":\"" token "\"}") "UTF-8")]
      (is (= "{\"echo\":\"<redacted>\"}" (String. ^bytes (gh/redact-body b token) "UTF-8"))))
    (let [b (byte-array [0 -1 -2 65 10 -128])]
      (is (identical? b (gh/redact-body b token)) "binary bytes pass through untouched"))))

(deftest the-token-goes-to-the-api-and-nowhere-else
  (tu/with-temp-home
    (let [tf (io/file store/*home* "token.txt")
          srv (atom nil)]
      (io/make-parents tf)
      (spit tf (str token "\n"))
      (reset! srv (fake/start!
                   (fn [{:keys [path]}]
                     (case path
                       "/echo" {:status 200 :headers {"Content-Type" "application/json" "X-RateLimit-Remaining" "4999"}
                                :body (str "{\"you_sent\":\"Bearer " token "\"}")}
                       "/jump" {:status 302 :headers {"Location" (str (fake/blob-base @srv) "/blob?sig=SECRETSIGNATURE&se=1")} :body ""}
                       "/blob" {:status 200 :headers {"Content-Type" "text/plain"} :body "log line\n"}
                       {:status 404 :body "{\"message\":\"Not Found\"}"}))))
      (try
        (store/write-edn! (store/path "w" "github.edn") {:token-source :file :file (str tf) :api (fake/base @srv)})
        (session/start! "w" "S1")
        (let [{t :token src :source} (gh/resolve-token "w")
              c {:ws "w" :session "S1" :token t}]
          (is (= token t))
          (is (= "file" src))
          (let [echo (gh/get! c (str (fake/base @srv) "/echo"))
                jump (gh/get! c (str (fake/base @srv) "/jump"))
                blob (gh/get! (assoc c :auth? false) (:location jump))]
            (is (= 200 (:status echo)))
            (is (= 302 (:status jump)))
            (is (= "log line\n" (String. ^bytes (:body blob) "UTF-8")))
            (testing "what the server received"
              (let [by (group-by :path @(:requests @srv))]
                (is (= (str "Bearer " token) (get-in (first (by "/echo")) [:headers "authorization"])))
                (is (= (str "Bearer " token) (get-in (first (by "/jump")) [:headers "authorization"])))
                (is (nil? (get-in (first (by "/blob")) [:headers "authorization"])) "the signed blob never sees the token")))
            (testing "what was persisted"
              (let [meta (store/raw-meta "w" (:sha jump))
                    echo-meta (store/raw-meta "w" (:sha echo))]
                (is (= "<redacted>" (get-in meta [:request :headers "authorization"])))
                (is (= "github GET /jump" (:op meta)))
                (is (= (str (fake/base @srv) "/jump") (get-in meta [:request :url])))
                (is (= "<redacted>" (get-in echo-meta [:request :headers "authorization"])))
                (is (= "{\"you_sent\":\"Bearer <redacted>\"}" (String. ^bytes (store/raw-get "w" (:sha echo)) "UTF-8"))
                    "the echoed token is scrubbed from the body")
                (is (str/includes? (get-in (store/raw-meta "w" (:sha blob)) [:request :url]) "/blob?<redacted>"))))
            (testing "nothing under the workspace holds the token or a signature"
              (let [txt (all-text)]
                (is (not (str/includes? txt token)))
                (is (not (str/includes? txt "AbCdEfGhIjKlMnOpQrStUvWxYz")))
                (is (not (str/includes? txt "SECRETSIGNATURE")))
                (is (str/includes? txt "\"authorization\" \"<redacted>\"") "...while the scan did see the sidecars")))
            (testing "the request log has one line per call, status and sha"
              (let [log (slurp (.toFile (store/path "w" "sessions" "S1" "ci" "requests.log")))]
                (is (= 3 (count (str/split-lines log))))
                (is (str/includes? log "/jump -> 302"))
                (is (str/includes? log "ratelimit-remaining=4999"))))))
        (finally (fake/stop! @srv))))))

(deftest token-sources
  (tu/with-temp-home
    (testing "a record that says none: no token, source none"
      (store/write-edn! (store/path "w" "github.edn") {:token-source :none})
      (is (= {:token nil :source "none"} (gh/resolve-token "w"))))
    (testing "a file that does not exist: no token (the record is a pointer, not a fallback chain)"
      (store/write-edn! (store/path "w" "github.edn") {:token-source :file :file "/no/such/token"})
      (is (nil? (:token (gh/resolve-token "w")))))
    (testing "the record's :api and the default"
      (store/write-edn! (store/path "w" "github.edn") {:api "http://127.0.0.1:1/"})
      (is (= "http://127.0.0.1:1" (gh/api-base "w"))))))
