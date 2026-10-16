(ns deliberator.floci-util
  "Gate for tests that need a local Floci. The URL is `DLB_TEST_FLOCI_URL`, default
  `http://localhost:14566` (the estate's port; 4566 belongs to whatever else runs on the machine and
  the suite never touches it unless told to). `deftest-floci` runs its body only when
  `GET /_floci/health` answers; otherwise it prints a SKIP line and reports a `:skip`, it never
  passes silently. `local-env` is the default local environment record pointed at that URL."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [deliberator.env :as env])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def url (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:14566"))

(def hostport (str/replace url #"^https?://" ""))

(defn local-env
  "The default local environment record with its endpoint at the test Floci."
  ([] (local-env nil))
  ([over] (merge-with (fn [a b] (if (map? a) (merge a b) b))
                      (assoc-in env/default-local [:aws :endpoint] url) over)))

(defn floci-up? []
  (try
    (let [c (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 2)) .build)
          r (.send c (-> (HttpRequest/newBuilder (URI. (str url "/_floci/health")))
                         (.timeout (Duration/ofSeconds 2)) .build)
                   (HttpResponse$BodyHandlers/discarding))]
      (= 200 (.statusCode r)))
    (catch Exception _ false)))

(defonce skipped (atom 0))

(defmethod t/report :skip [m]
  (swap! skipped inc)
  (println "SKIP" (:message m)))

(defn skip!
  "Reports a `:skip` (counted and printed, never a silent pass); `what` names the test or fixture."
  [what reason]
  (t/do-report {:type :skip :message (str what ": " reason)}))

(defn current-test [] (or (some-> t/*testing-vars* first meta :name) "test"))

(defn estate-ws
  "`ESTATE_WS` when set and not blank, else nil."
  []
  (let [v (System/getenv "ESTATE_WS")] (when-not (str/blank? v) v)))

(defmacro deftest-floci [name & body]
  `(t/deftest ~name
     (if (floci-up?)
       (do ~@body)
       (t/do-report {:type :skip :message (str '~name ": runtime down: bb estate:up (Floci at " url ")")}))))
