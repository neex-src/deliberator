(ns deliberator.session
  "Sessions, the unit of work; nothing is ever deleted. Contract: `(start! ws key & {:keys [title]})`
  creates `sessions/<key>/` with `session.edn` `{:key :title :started :ended nil :tags []}`, an empty
  `notes.md`, `scenarios/` and `runs/`, and makes it current (an existing session is reopened,
  `:ended` cleared, `:started` kept); `(end! ws key)` sets `:ended` and clears the workspace's
  current session if it was this one; `(list ws)` (by `:started`), `(current ws)`; `(run-dir! ws key
  scenario-name) -> Path` allocates `runs/<yyyymmdd-hhmmss>-<scenario>/` (UTC; `/` and other odd
  characters of the name become `-`), suffix `-2`, `-3` on collision. All return maps/paths."
  (:refer-clojure :exclude [list])
  (:require [clojure.string :as str]
            [deliberator.store :as store]
            [deliberator.workspace :as workspace])
  (:import (java.nio.file FileAlreadyExistsException Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.time ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defn dir ^Path [ws key] (store/path ws "sessions" (store/safe-name key)))

(defn- file [ws key] (.resolve (dir ws key) "session.edn"))

(defn- mkdirs [^Path p] (Files/createDirectories p (make-array FileAttribute 0)))

(defn- get-session [ws key]
  (or (store/read-edn (file ws key))
      (throw (ex-info (str "no such session " key) {:type :session/not-found :key key}))))

(defn start!
  "Creates or reopens session `key`, makes it current; returns the session map."
  [ws key & {:keys [title]}]
  (let [k (store/safe-name key)
        existing (store/read-edn (file ws k))
        s (if existing
            (cond-> (assoc existing :ended nil) title (assoc :title title))
            {:key k :title title :started (store/now) :ended nil :tags []})]
    (mkdirs (.resolve (dir ws k) "scenarios"))
    (mkdirs (.resolve (dir ws k) "runs"))
    (let [notes (.resolve (dir ws k) "notes.md")]
      (when-not (Files/exists notes (make-array LinkOption 0))
        (Files/write notes (byte-array 0) (make-array java.nio.file.OpenOption 0))))
    (store/write-edn! (file ws k) s)
    (workspace/set-current-session! ws k)
    s))

(defn end!
  "Marks the session ended; returns the session map."
  [ws key]
  (let [s (assoc (get-session ws key) :ended (store/now))]
    (store/write-edn! (file ws key) s)
    (when (= (workspace/current-session ws) (:key s))
      (workspace/set-current-session! ws nil))
    s))

(defn list
  "All sessions of the workspace, oldest first."
  [ws]
  (let [root (store/path ws "sessions")]
    (if (Files/isDirectory root (make-array LinkOption 0))
      (with-open [st (Files/list root)]
        (->> (iterator-seq (.iterator st))
             (keep #(store/read-edn (.resolve ^Path % "session.edn")))
             (sort-by (juxt :started :key))
             vec))
      [])))

(defn current
  "The current session map, or nil."
  [ws]
  (when-let [k (workspace/current-session ws)]
    (store/read-edn (file ws k))))

(def ^:private stamp (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC))

(defn run-dir!
  "Allocates and creates a fresh run directory in the session; returns its Path."
  [ws key scenario-name]
  (let [runs (.resolve (dir ws key) "runs")
        scn (let [s (str/replace (str scenario-name) #"[^A-Za-z0-9._-]+" "-")]
              (if (str/blank? s) "scenario" s))
        base (str (.format stamp (store/now)) "-" scn)]
    (mkdirs runs)
    (loop [n 1]
      (let [p (.resolve runs (if (= n 1) base (str base "-" n)))]
        (if (try (Files/createDirectory p (make-array FileAttribute 0)) true
                 (catch FileAlreadyExistsException _ false))
          p
          (recur (inc n)))))))
