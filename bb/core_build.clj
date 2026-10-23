(ns core-build
  "`ensure-jar!`: builds core/target/deliberatord.jar (`bb core:build`) when it is missing or older than any file under core/src,
  core/resources, core/deps.edn or core/build.clj. Every task that can start a daemon calls it first: a daemon is never started
  from a stale jar (and a rebuilt jar's stamped version makes clients replace a daemon of an older build)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- stale? []
  (let [jar "core/target/deliberatord.jar"]
    (or (not (fs/exists? jar))
        (let [t (fs/file-time->millis (fs/last-modified-time jar))]
          (some #(> (fs/file-time->millis (fs/last-modified-time %)) t)
                (concat (fs/glob "core/src" "**") (fs/glob "core/resources" "**") ["core/deps.edn" "core/build.clj"]))))))

(defn ensure-jar!
  "Builds the jar when stale or missing; prints one line either way it acted. -> true when built."
  []
  (when (stale?)
    (println "core/target/deliberatord.jar missing or older than core/src: building it (bb core:build)")
    (p/shell {:dir "core"} "clojure" "-T:build" "uber")
    true))
