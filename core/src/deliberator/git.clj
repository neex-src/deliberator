(ns deliberator.git
  "The one way the daemon runs `git` (M5.1): `(run dir args opts)` -> `{:exit :out :err}`, stdout and stderr kept apart (a
  `warning:` never lands in a value we parse), the process tree killed on the budget or when the request is cancelled
  (`:on-cancel!` of the protocol ctx), and never a prompt: `GIT_TERMINAL_PROMPT=0` (a private repository without a credential
  helper fails with git's own message instead of waiting for a person), `LC_ALL=C`. `(out dir & args)` -> trimmed stdout, or
  throws `{:type :git/failed :argv :exit :stderr}` on a non-zero exit; `(ok? dir & args)` -> true when the exit is 0."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.process :as process])
  (:import (java.io IOException)
           (java.util.concurrent TimeUnit)))

(def ^:private env {"GIT_TERMINAL_PROMPT" "0" "LC_ALL" "C" "GIT_OPTIONAL_LOCKS" "0"})

(def default-budget-ms "Two minutes; a clone passes more." 120000)

(defn run
  "Runs `git <args>` in `dir` (nil = the daemon's) -> `{:exit :out :err}`. Opts: `:budget-ms`, `:on-cancel!`."
  [dir args & [{:keys [budget-ms on-cancel!]}]]
  (let [argv (into ["git"] (map str) args)
        pb (doto (ProcessBuilder. ^java.util.List argv)
             (.redirectInput java.lang.ProcessBuilder$Redirect/PIPE))
        _ (when dir (.directory pb (io/file (str dir))))
        _ (.putAll (.environment pb) ^java.util.Map env)
        p (try (.start pb)
               (catch IOException e
                 (throw (ex-info (str "cannot run git: " (.getMessage e))
                                 {:type :process/cannot-start :argv argv :hint "is git on the daemon's PATH?"} e))))
        _ (.close (.getOutputStream p))
        killed (atom nil)
        kill! (fn [why] (when (compare-and-set! killed nil why) (process/kill-tree! p)))
        _ (when on-cancel! (on-cancel! #(kill! :cancelled)))
        err (future (slurp (.getErrorStream p)))
        out (future (slurp (.getInputStream p)))
        budget (long (or budget-ms default-budget-ms))
        done? (.waitFor p budget TimeUnit/MILLISECONDS)
        _ (when-not done? (kill! :timeout))
        exit (.waitFor p)]
    (case @killed
      :timeout (throw (ex-info (str "git " (str/join " " (take 2 args)) " ran longer than " (quot budget 1000) " s and was killed")
                               {:type :git/timeout :argv argv :hint "the process was killed; try again or check the remote"}))
      :cancelled (throw (ex-info "git was cancelled; the process was killed" {:type :process/cancelled :argv argv}))
      {:exit exit :out @out :err @err})))

(defn out
  "Trimmed stdout of `git <args>` in `dir`; throws `:git/failed` on a non-zero exit."
  [dir & args]
  (let [r (run dir args)]
    (if (zero? (:exit r))
      (str/trim (:out r))
      (throw (ex-info (str "git " (str/join " " (take 2 args)) " failed: "
                           (let [e (str/trim (:err r))] (if (str/blank? e) (str "exit " (:exit r)) e)))
                      {:type :git/failed :argv (vec args) :exit (:exit r) :stderr (str/trim (:err r))})))))

(defn ok?
  "True when `git <args>` exits 0."
  [dir & args]
  (zero? (:exit (run dir args))))
