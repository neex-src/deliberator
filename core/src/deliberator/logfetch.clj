(ns deliberator.logfetch
  "`logs.fetch`: the `logs` kind's sources as a standalone, persisted operation. `(fetch! ws env-name
  target & {:keys [session since for filter]})` resolves `target` exactly as the kind does
  (`steps.logs/resolve-target-source` over the environment's targets: `{:container}`, `{:file}`,
  `{:cloudwatch}`; a target starting with `/` is a file path) and reads the source:

    without `for`  one shot: the whole file, the container's logs from `since` (default all), the
                   CloudWatch events from `since` (default 15 minutes back), paged;
    with `for`     the kind's live sources for that long, starting at `since` (file: byte 0), so history
                   plus whatever arrives meanwhile, through the kind's own entry loop (`drain!`).

  `since` is an ISO-8601 instant or a duration ago (`10m`); `for` and `since` durations are `500ms 2s 3m 1h`.
  Persist first: every line read is `raw-put!` as one blob (nothing is filtered at the source, not even
  for CloudWatch); with `filter` (`text` or `/regex/`, an entry is kept whole, as in the kind) the kept
  entries are a second blob. The answer's blob is hard-linked to `sessions/<session>/logs/<target>-<ts>.log`.
  Returns `{:path :lines :bytes :sha :target :source}` (`:source` = container, file or cloudwatch)."
  (:require [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.scenario.expr :as expr]
            [deliberator.session :as session]
            [deliberator.steps.logs :as logs]
            [deliberator.store :as store])
  (:import (java.time Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent LinkedBlockingQueue)))

(defn- bad [msg] (throw (ex-info msg {:type :logs/bad-arg :hint "since: an ISO-8601 instant or a duration like 10m; for: 500ms, 2s, 3m, 1h"})))

(defn parse-since
  "An ISO-8601 instant, or a duration ago (`10m`) relative to `now`."
  [s now]
  (let [s (str/trim (str s))]
    (cond (expr/duration? s) (.minus ^Instant now (Duration/ofMillis (expr/parse-duration s)))
          :else (try (Instant/parse s) (catch Exception _ (bad (str "bad since " (pr-str s))))))))

(def ^:private stamp (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (.withZone ZoneOffset/UTC)))

(defn- link-answer! [ws sess target sha]
  (let [base (str (str/replace (str target) #"[^A-Za-z0-9._-]+" "-") "-" (.format stamp (store/now)))
        dir (.resolve (session/dir ws sess) "logs")]
    (loop [n 1]
      (let [p (.resolve dir (str base (when (> n 1) (str "-" n)) ".log"))
            r (try (store/link! ws sha p)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :store/target-exists (:type (ex-data e))) nil (throw e))))]
        (if r (:path r) (recur (inc n)))))))

(defn fetch!
  [ws env-name target & {:keys [session since for filter]}]
  (let [e (env/load ws env-name)
        now (store/now)
        source (if (str/starts-with? (str target) "/")
                 {:file target}
                 (logs/resolve-target-source (fn [k] (env/target e (keyword k))) target (:name e)))
        kind (cond (:container source) "container" (:file source) "file" :else "cloudwatch")
        since-i (some-> since (parse-since now))
        since-i (or since-i (when (:cloudwatch source) (.minus ^Instant now (Duration/ofMinutes 15))))
        for-ms (when for (expr/parse-duration for))
        pred (some-> filter logs/parse-filter)
        ctx {:ws ws :env e :session session}
        lines (if (and for-ms (pos? for-ms))
                (let [q (LinkedBlockingQueue.)
                      stop (logs/start-source ctx source 0 since-i nil q)]
                  (try (:raw (logs/drain! q (+ (System/currentTimeMillis) for-ms) (constantly false) [] (fn [_])
                                          (str "check the log target " target " in environment " (:name e))))
                       (finally (stop))))
                (logs/snapshot ctx source since-i nil))
        text (fn [ls] (if (seq ls) (str (str/join "\n" ls) "\n") ""))
        put! (fn [ls op-suffix]
               (store/raw-put! ws (.getBytes ^String (text ls) "UTF-8")
                               {:source (case kind "container" :podman "file" :file :aws)
                                :op (str "logs.fetch " target op-suffix) :env (keyword (:name e)) :at now
                                :content-type "text/plain"}))
        raw (put! lines "")
        kept (if pred (vec (mapcat identity (clojure.core/filter #(pred (str/join "\n" %)) (logs/group-entries lines)))) lines)
        answer (if pred (put! kept (str " (filter " filter ")")) raw)
        path (link-answer! ws session target (:sha answer))]
    {:path (str path) :lines (count kept) :bytes (:bytes answer) :sha (:sha answer)
     :target target :source kind}))
