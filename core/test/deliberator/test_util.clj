(ns deliberator.test-util
  "Test helpers: a temporary `$DELIBERATOR_HOME` under `target/test-homes/` (gitignored; never the
  real home) and a fixed clock."
  (:require [clojure.java.io :as io]
            [deliberator.store :as store])
  (:import (java.time Instant)
           (java.util UUID)))

(defn temp-home
  "Creates and returns a fresh temp home directory (a File)."
  []
  (let [d (io/file (System/getProperty "user.dir") "target" "test-homes" (str (UUID/randomUUID)))]
    (.mkdirs d)
    d))

(defmacro with-temp-home
  "Runs body with `store/*home*` bound to a fresh temporary home."
  [& body]
  `(binding [store/*home* (temp-home)] ~@body))

(defn fixed-clock [^String iso] (let [i (Instant/parse iso)] (constantly i)))

(defn meta-for
  "A valid raw meta map."
  ([] (meta-for {}))
  ([m] (merge {:source :http :op "GET /x" :env :local :at (Instant/parse "2026-10-16T10:00:00Z")
               :content-type "text/plain"} m)))

(defn bytes-of [^String s] (.getBytes s "UTF-8"))
