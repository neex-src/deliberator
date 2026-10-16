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
    (and ok (map? result) (contains? result :status)) (if (= "ok" (:status result)) 0 1)
    (and ok (map? result) (false? (:equal result))) 1
    ok 0
    (= "confirm-required" (:type error)) 1
    :else 2))

(defn- absolutize-path
  "A scenario path that exists relative to the shell's cwd is sent absolute (the daemon's cwd is not
  ours); anything else is left for the daemon to look up in the session's scenarios/."
  [op args]
  (let [p (:path args)]
    (if (and (= "scenario.run" (:op op)) p (fs/exists? p))
      (assoc args :path (str (fs/absolutize p)))
      args)))

(defn- describe [ch]
  (let [r (client/request! ch "op.describe" {} (fn [_]))]
    (get-in r [:terminal :result])))

(defn- run* [script-dir argv]
  (let [[{:keys [json? home]} words] (extract-flags argv)
        home (endpoint/home home)
        jar (endpoint/jar script-dir)
        want (some-> jar endpoint/jar-version)
        {:keys [channel]} (endpoint/connect! home jar)
        hello (get-in (client/request! channel "hello" (cond-> {:client "dlb"} want (assoc :version want)) (fn [_]))
                      [:terminal :result])]
    (try
      (if (false? (:compatible hello))
        (local-error json? "daemon/incompatible"
                     (str "daemon pid " (:pid hello) " runs " (:version hello) " but this dlb ships " want)
                     "stop that daemon (kill its pid) and run dlb again")
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
           (local-error json? (or (:type (ex-data e)) "error") (ex-message e)))
         (catch Exception e
           (local-error json? "error" (or (ex-message e) (str (class e))))))))
