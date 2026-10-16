(ns core-build
  "`ensure-jar!`: builds core/target/deliberatord.jar (`bb core:build`) when it is missing or older
  than any file under core/src, so the jar tests of `bb core:test` never fail for a missing build."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- stale? []
  (let [jar "core/target/deliberatord.jar"]
    (or (not (fs/exists? jar))
        (let [t (fs/file-time->millis (fs/last-modified-time jar))]
          (some #(> (fs/file-time->millis (fs/last-modified-time %)) t)
                (concat (fs/glob "core/src" "**") ["core/deps.edn"]))))))

(defn ensure-jar!
  "Builds the jar when stale or missing; prints one line either way it acted. -> true when built."
  []
  (when (stale?)
    (println "core:test: core/target/deliberatord.jar missing or older than core/src, building it (bb core:build)")
    (p/shell {:dir "core"} "clojure" "-T:build" "uber")
    true))
