(ns deliberator.floci-util
  "Gate for tests that need the local Floci on :4566. `deftest-floci` runs its body only when
  `GET /_floci/health` answers; otherwise it prints a SKIP line and reports a `:skip`, it never
  passes silently."
  (:require [clojure.test :as t])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

(def url "http://localhost:4566")

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

(defmacro deftest-floci [name & body]
  `(t/deftest ~name
     (if (floci-up?)
       (do ~@body)
       (t/do-report {:type :skip :message (str '~name ": runtime down: bb runtime:up")}))))
