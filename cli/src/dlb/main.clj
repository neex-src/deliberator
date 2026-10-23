(ns dlb.main
  "`dlb`: argv -> one request to deliberatord -> printed summary. No business logic and no state: the
  daemon is found or started (`dlb.endpoint`), `hello` checks the version, `op.describe` types the
  arguments (`dlb.args`) and drives the help (`dlb.render`), the request is sent with
  cancel-on-close (Ctrl-C cancels a run), events stream as lines and the terminal line decides
  the exit code: 0 ok; 1 the run completed with failed checks or errors, the runs differ, or a
  confirmation is required; 2 any other error (bad args, daemon unreachable, op error). `--json`
  prints only the terminal line."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [dlb.args :as args]
            [dlb.client :as client]
            [dlb.endpoint :as endpoint]
            [dlb.render :as render]))

(defn- extract-flags
  "Pulls `--json` and `--home D` out of argv. -> [{:json? :home} remaining-words]."
  [argv]
  (loop [[a & more :as xs] argv, flags {}, out []]
    (cond
      (empty? xs) [flags out]
      (= a "--json") (recur more (assoc flags :json? true) out)
      (= a "--home") (recur (rest more) (assoc flags :home (first more)) out)
      (str/starts-with? a "--home=") (recur more (assoc flags :home (subs a 7)) out)
      :else (recur more flags (conj out a)))))

(defn- local-error
  "A failure that never reached an op: printed as the daemon would, exit 2."
  [json? type message & [hint]]
  (let [err (cond-> {:type type :message message} hint (assoc :hint hint))]
    (if json?
      (println (json/generate-string {:id nil :ok false :error err}))
      (binding [*out* *err*]
        (println (str "error: " type ": " message))
        (when hint (println (str "hint: " hint)))))
    2))

(defn- exit-code [{:keys [ok result error]}]
  (cond
    (and ok (map? result) (contains? result :status) (contains? result :steps)) (if (= "ok" (:status result)) 0 1)
    (and ok (map? result) (false? (:equal result))) 1
    ok 0
    (= "confirm-required" (:type error)) 1
    :else 2))

(def ^:private path-args
  "Operations whose file argument is sent absolute when it exists relative to the shell's cwd."
  {"scenario.run" :path "scenario.parse" :path "logs.match" :path "repo.attach" :path
   "marker.add" :file "marker.remove" :file "marker.list" :file})

(defn- absolutize-path
  "A path argument that exists relative to the shell's cwd is sent absolute (the daemon's cwd is not
  ours); anything else is left for the daemon (a scenario or log path is looked up in the session)."
  [op args]
  (let [k (get path-args (:op op))
        p (get args k)]
    (if (and k p (fs/exists? p))
      (assoc args k (str (fs/absolutize p)))
      args)))

(defn- describe [ch]
  (let [r (client/request! ch "op.describe" {} (fn [_]))]
    (get-in r [:terminal :result])))

(declare run-op*)

(defn- daemon-stop
  "`dlb daemon stop`: asks the running daemon to stop (never starts one) and waits for it to be gone."
  [json? home]
  (let [r (endpoint/stop-daemon! home)]
    (if json?
      (println (json/generate-string {:id nil :ok true :result r}))
      (if (:running? r)
        (do (println (str "daemon pid " (:pid r) (if (:gone? r) " stopped" " is stopping (still running after 20 s)")
                          " (" (:via r) ")"))
            (when (seq (:runs r)) (println (str "runs cancelled: " (str/join ", " (:runs r))))))
        (println "daemon: not running")))
    (if (and (:running? r) (not (:gone? r))) 2 0)))

(defn- daemon-status
  "`dlb daemon status`: `hello` of the running daemon (never starts one); exit 1 when none runs."
  [json? home jar]
  (let [want (some-> jar endpoint/jar-version)
        c (when (endpoint/read-endpoint home) (try (endpoint/connect-existing home) (catch Exception _ nil)))
        hello (when c (try (endpoint/hello! (:channel c) want) (finally (client/close! (:channel c)))))
        st (if hello (assoc hello :running true :jar-version want) {:running false})]
    (if json?
      (println (json/generate-string st))
      (if hello
        (doseq [k [:version :pid :home :endpoint :runs :compatible :jar-version]]
          (when (some? (get st k)) (println (str (name k) ": " (get st k)))))
        (println "daemon: not running")))
    (if hello 0 1)))

(defn- run* [script-dir argv]
  (let [[{:keys [json? home]} words] (extract-flags argv)
        home (endpoint/home home)
        jar (endpoint/jar script-dir)
        want (some-> jar endpoint/jar-version)]
    (case (vec (take 2 words))
      ["daemon" "stop"] (daemon-stop json? home)
      ["daemon" "status"] (daemon-status json? home jar)
      (run-op* script-dir argv json? home jar want words))))

(defn- run-op* [script-dir argv json? home jar want words]
  (let [{:keys [channel hello]} (endpoint/connect-compatible! home jar)]
    (try
      (do
        (let [table (describe channel)
              help? (or (empty? words) (= "help" (first words)) (some #{"--help" "-h"} words))]
          (cond
            (and help? json?) (do (println (json/generate-string table)) 0)

            help? (let [ws (remove #{"help" "--help" "-h"} words)]
                    (println (render/help table (:version hello) ws)) 0)

            :else
            (let [{:keys [op rest unknown]} (args/find-op table words)]
              (if-not op
                (local-error json? "unknown-op" (str "no such operation: " unknown) "dlb help lists the operations")
                (let [call (try {:args (absolutize-path op (args/parse op rest))}
                                (catch clojure.lang.ExceptionInfo e
                                  (if (:cli (ex-data e)) {:error e} (throw e))))]
                  (if (:error call)
                    (local-error json? "bad-args" (ex-message (:error call)) (:hint (ex-data (:error call))))
                    (let [steps (atom {})
                          on-event (fn [ev]
                                     (when-not json?
                                       (when-let [l (render/event-line steps ev)]
                                         (println l) (flush))))
                          {:keys [line terminal]} (client/request! channel (:op op) (:args call) on-event)]
                      (if json?
                        (println line)
                        (if (:ok terminal)
                          (render/print-result (:op op) (:result terminal))
                          (render/print-error (:op op) (:error terminal) argv)))
                      (exit-code terminal)))))))))
      (finally (client/close! channel)))))

(defn -main
  "Entry point: `(-main script-dir argv)`; returns the exit code."
  [script-dir argv]
  (let [json? (boolean (some #{"--json"} argv))]
    (try (run* script-dir argv)
         (catch clojure.lang.ExceptionInfo e
           (local-error json? (or (:type (ex-data e)) "error") (ex-message e) (:hint (ex-data e))))
         (catch Exception e
           (local-error json? "error" (or (ex-message e) (str (class e))))))))
