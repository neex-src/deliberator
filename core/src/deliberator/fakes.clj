(ns deliberator.fakes
  "The HTTP fakes of a workspace: `fakes/<name>.edn` is the declaration, the runtime role `:fake/<name>` is derived from it
  the way `:service/<name>` is derived from a service description (`runtime/declared-containers`), so a fake is reconciled, health-checked,
  logged and removed like every other container.

    {:name \"a\" :mode :record|:replay|:stopped :port 18080 :upstream \"http://deliberator-ws-service-a:8081\"
     :recording \"/abs/dir\" [:session \"T-1\"] [:image ..] [:scrub {:headers [..] :json-keys [..] :name-keys [..] :patterns [{:re .. :with ..}]}]}

  The container runs `bb /app/http_fake.bb` (the classpath resource `deliberator/fake/http_fake.bb`, staged under the workspace so it can be
  mounted) from the babashka image, listens on 8080 inside, published on `127.0.0.1:<port>`, and mounts the recording directory:
  read-write while recording (the proxy persists every exchange as `NNNNN.json` before the client gets its answer), read-only while
  replaying. Recordings live under the session: `sessions/<key>/fakes/<name>/<yyyyMMdd-HHmmss>/`."
  (:refer-clojure :exclude [list read])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.store :as store])
  (:import (java.net InetAddress InetSocketAddress ServerSocket)
           (java.time ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def default-image "docker.io/babashka/babashka:1.12.200")
(def container-port 8080)
(def first-port 18080)

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn dir [ws] (store/path ws "fakes"))
(defn path [ws name] (store/path ws "fakes" (str (store/safe-name name) ".edn")))

(defn role "The runtime role keyword of fake `name`." [name] (keyword "fake" (store/safe-name name)))
(defn role-label "The role label (\"fake-a\")." [name] (str "fake-" (store/safe-name name)))
(defn container-name [ws name] (store/container-name ws (role-label name)))

(defn read
  "The declaration of fake `name`, nil when there is none."
  [ws name]
  (let [p (path ws name)]
    (when (.exists (.toFile p)) (store/read-edn p))))

(defn names [ws]
  (let [d (.toFile (dir ws))]
    (vec (sort (for [^java.io.File f (or (.listFiles d) []) :when (str/ends-with? (.getName f) ".edn")]
                 (str/replace (.getName f) #"\.edn$" ""))))))

(defn list [ws] (vec (keep #(read ws %) (names ws))))

(defn write! [ws decl]
  (store/write-edn! (path ws (:name decl)) decl)
  decl)

(defn serving
  "`:record`, `:replay` or nil (stopped, undeclared) for fake `name`."
  [ws name]
  (let [m (:mode (read ws name))] (when (#{:record :replay} m) m)))

(defn active? [decl] (boolean (#{:record :replay} (:mode decl))))

(defn host-url "The fake as the host reaches it (scenario steps, curl)." [decl] (str "http://localhost:" (:port decl)))

(defn container-url
  "The fake as the other containers of the runtime's network reach it."
  [ws decl] (str "http://" (container-name ws (:name decl)) ":" container-port))

;; ports ---------------------------------------------------------------------------------------------

(defn- port-free? [port]
  (try (with-open [s (ServerSocket.)] (.bind s (InetSocketAddress. (InetAddress/getByName "127.0.0.1") (int port))) true)
       (catch Exception _ false)))

(defn pick-port
  "A host port for a new fake: not used by another declaration, free on the loopback now."
  [ws]
  (let [used (set (keep :port (list ws)))]
    (or (some #(when (and (not (used %)) (port-free? %)) %) (range first-port (+ first-port 200)))
        (fail :fake/no-port (str "no free host port in " first-port "-" (+ first-port 199)) :hint "stop a fake or pass port"))))

;; recordings ----------------------------------------------------------------------------------------

(def ^:private stamp (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn new-recording-dir
  "Creates `sessions/<session>/fakes/<name>/<stamp>/` -> the absolute path string."
  [ws session name]
  (let [id (.format stamp (.atOffset (store/now) ZoneOffset/UTC))
        d (store/path ws "sessions" (store/safe-name session) "fakes" (store/safe-name name) id)]
    (.mkdirs (.toFile d))
    (str d)))

(defn exchange-files [recording-dir]
  (vec (sort-by #(.getName ^java.io.File %)
                (for [^java.io.File f (or (.listFiles (io/file recording-dir)) [])
                      :when (re-matches #"\d+\.json" (.getName f))]
                  f))))

(defn recordings
  "Every recording of fake `name` in the workspace's sessions, oldest first: `[{:id :session :dir :exchanges}]`."
  [ws name]
  (let [sessions (.toFile (store/path ws "sessions"))]
    (vec (sort-by :id
                  (for [^java.io.File s (or (.listFiles sessions) [])
                        :let [fd (io/file s "fakes" (store/safe-name name))]
                        ^java.io.File r (or (.listFiles fd) [])
                        :when (.isDirectory r)]
                    {:id (.getName r) :session (.getName s) :dir (str r) :exchanges (count (exchange-files r))})))))

;; the runtime role ----------------------------------------------------------------------------------

(defn script-text [] (slurp (io/resource "deliberator/fake/http_fake.bb")))

(defn stage-script!
  "Writes the proxy script under the workspace (a container mounts a file, not a resource of the jar) when it differs -> its path."
  [ws]
  (let [f (.toFile (store/path ws "fakes" ".runtime" "http_fake.bb"))
        text (script-text)]
    (io/make-parents f)
    (when-not (and (.exists f) (= text (slurp f))) (spit f text))
    (str f)))

(defn ->spec
  "The runtime spec of an active fake: image, the published port, the script, the recording, a health probe."
  [ws {:keys [mode port upstream recording image] :as decl}]
  (when-not (active? decl) (fail :fake/not-active (str "fake " (:name decl) " is " (clojure.core/name (or mode :stopped)))))
  (let [script (stage-script! ws)]
    {:image (or image default-image)
     :ports (sorted-map container-port port)
     :command ["bb" "/app/http_fake.bb"]
     ;; FAKE_SCRIPT is the script's sha: a new proxy script changes the spec hash, so the next reconcile recreates the container
     :env (into (sorted-map "FAKE_SCRIPT" (subs (store/sha256 (.getBytes (script-text) "UTF-8")) 0 12))
                (case mode
                  :record {"FAKE_MODE" "record" "FAKE_UPSTREAM" upstream "FAKE_RECORD_DIR" "/data/rec"}
                  :replay {"FAKE_MODE" "replay" "FAKE_REPLAY_DIR" "/data/rec"}))
     :mounts [{:source script :destination "/app/http_fake.bb" :options ["ro" "z"]}
              {:source recording :destination "/data/rec" :options [(if (= mode :record) "rw" "ro") "z"]}]
     :health {:http "/__fake/health" :port container-port}}))
