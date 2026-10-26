(ns deliberator.process
  "The one way the daemon runs a subprocess whose output is a transcript (review 2026-10-25, R11): `terraform` in
  `tflocal`, the build command in `service/build!`, the compile in `service/reload`. `(run! argv opts)` streams the merged
  stdout+stderr line by line, and the process tree is killed when the request is cancelled (the client left, Esc in the
  IDE: `:on-cancel!` of the protocol ctx) or when the budget runs out, so no `terraform apply` waiting on a state lock holds
  a request for ever and no orphan survives its request. The `shell` kind keeps its own byte-exact reader (its response is
  the exact bytes) and already kills its tree the same way."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File IOException)
           (java.lang ProcessHandle)
           (java.util.concurrent TimeUnit)))

(def default-budget-ms
  "Ten minutes: longer than any build or `terraform apply` of the estate, shorter than for ever."
  600000)

(defn kill-tree!
  "Destroys `p` and every descendant forcibly."
  [^Process p]
  (doseq [h (iterator-seq (.iterator (.descendants (.toHandle p))))] (.destroyForcibly ^ProcessHandle h))
  (.destroyForcibly p))

(defn run!
  "Runs `argv` (strings) to the end -> `{:exit n :lines [..]}`, stderr merged into stdout, every line handed to `:line!` as
  it arrives. `opts`: `:dir` (File or path), `:env` (a map that REPLACES the inherited environment), `:extra-env` (merged on
  top), `:budget-ms` (default `default-budget-ms`, 0 or nil = the default), `:on-cancel!` (the protocol's: registers the
  kill), `:line!`, `:what` (a label for errors, default the command). Throws `{:type :process/cannot-start}` when the
  command cannot be started (not on PATH), `{:type :process/timeout :what :budget-ms :lines}` after the tree was killed on
  the budget, `{:type :process/cancelled :what :lines}` after it was killed by a cancel."
  [argv {:keys [dir env extra-env budget-ms on-cancel! line! what]}]
  (let [argv (mapv str argv)
        what (or what (str/join " " argv))
        budget (if (and budget-ms (pos? (long budget-ms))) (long budget-ms) default-budget-ms)
        pb (doto (ProcessBuilder. ^java.util.List argv) (.redirectErrorStream true))
        _ (when dir (.directory pb (io/file dir)))
        _ (when env (doto (.environment pb) (.clear) (.putAll ^java.util.Map (into {} (map (fn [[k v]] [(str k) (str v)])) env))))
        _ (when extra-env (.putAll (.environment pb) ^java.util.Map (into {} (map (fn [[k v]] [(str k) (str v)])) extra-env)))
        p (try (.start pb)
               (catch IOException e
                 (throw (ex-info (str "cannot run " what ": " (.getMessage e))
                                 {:type :process/cannot-start :argv argv :what what
                                  :hint (str "is " (first argv) " on the daemon's PATH?")} e))))
        _ (.close (.getOutputStream p))
        killed (atom nil)                                   ; :cancelled | :timeout, set once by whoever kills first
        kill! (fn [why] (when (compare-and-set! killed nil why) (kill-tree! p)))
        _ (when on-cancel! (on-cancel! #(kill! :cancelled)))
        _ (doto (Thread. ^Runnable (fn [] (try (when-not (.waitFor p budget TimeUnit/MILLISECONDS) (kill! :timeout))
                                               (catch InterruptedException _ nil)))
                         (str "deliberator-process-budget"))
            (.setDaemon true) (.start))
        lines (java.util.ArrayList.)
        _ (try (with-open [r (io/reader (.getInputStream p))]
                 (doseq [l (line-seq r)]
                   (.add lines l)
                   (when line! (line! l))))
               (catch InterruptedException e (kill! :cancelled) (throw e)))
        exit (try (.waitFor p) (catch InterruptedException e (kill! :cancelled) (throw e)))
        lines (vec lines)]
    (case @killed
      :timeout (throw (ex-info (str what " ran longer than " (quot budget 1000) " s and was killed")
                               {:type :process/timeout :what what :budget-ms budget :lines lines
                                :hint "the process and its children were killed; the last lines of its output are in lines"}))
      :cancelled (throw (ex-info (str what " was cancelled; the process and its children were killed")
                                 {:type :process/cancelled :what what :lines lines}))
      {:exit exit :lines lines})))
