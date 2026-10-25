(ns deliberator.scenario.vars
  "Variables, all values text. `(snapshot header & {:keys [correlation env vars builtins]}) -> vars`
  (a map name -> string) evaluates once per run: builtins `uuid` (one value for the whole run),
  `now` (ISO-8601 instant at snapshot), `now.epoch` (epoch SECONDS), `correlation` (the run id),
  `env.name`, `env.kind` and `env.<key>` for the scalar entries of the environment record and of
  its `:aws` map (credentials, secrets, tokens and keys are never exposed), then the header's
  `@var`s top-down (each may use the ones before it), then `:vars` (seeds, e.g. captures from an
  earlier run) which win. `:builtins` overrides builtin values (determinism in tests). An unknown
  name in a `@var` throws as below, with the var's `:line`.

  `(substitute text vars & {:keys [resolve]})` replaces `{{name}}`; a name that is not a var and
  contains `/` is asked of `resolve` (a fn name -> text, the targets: `{{service/a}}`); any other
  unknown name throws `{:type :scenario/unknown-var :name :known [...]}`. `{{` not followed by a
  name is left alone. `{{{{` is the escape: the literal text `{{`, never a placeholder, so
  `{{{{end}}` renders `{{end}}`. `(substitute-bytes bytes vars opts)` does the same on raw bytes without ever
  touching other bytes (a payload without placeholders comes back identical). `(references text)`
  lists the names used (an escaped brace is no reference);`(overlay vars captures)` merges captures over a snapshot;
  `(volatile-names header)` is the set of var names whose value differs run to run (derived from
  uuid, now, now.epoch, correlation), which `render/diff` normalises."
  (:require [clojure.string :as str]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.util UUID)))

(def builtin-names ["uuid" "now" "now.epoch" "correlation"])

;; `{{{{` is tried first so it wins over a placeholder starting at the same place; group 1 is nil
;; for the escape.
(def ^:private placeholder #"\{\{\{\{|\{\{\s*([A-Za-z_][A-Za-z0-9_./-]*)\s*\}\}")

(defn references [text]
  (into [] (keep second) (re-seq placeholder (str text))))

(defn- unknown [name vars line]
  (ex-info (str "unknown variable {{" name "}}; known: " (str/join ", " (sort (keys vars))))
           (cond-> {:type :scenario/unknown-var :name name :known (vec (sort (keys vars)))}
             line (assoc :line line))))

(defn substitute
  [text vars & {:keys [resolve line]}]
  (str/replace (str text) placeholder
               (fn [[_ n]]
                 (cond
                   (nil? n) "{{"
                   (contains? vars n) (get vars n)
                   (and resolve (str/includes? n "/")) (str (resolve n))
                   :else (throw (unknown n vars line))))))

(defn substitute-bytes
  [^bytes bs vars & {:keys [resolve line]}]
  (let [s (String. bs StandardCharsets/ISO_8859_1)]
    (if-not (str/includes? s "{{")
      bs
      (let [enc (fn [v] (String. (.getBytes (str v) StandardCharsets/UTF_8) StandardCharsets/ISO_8859_1))
            vars* (update-vals vars enc)
            res (when resolve (fn [n] (enc (resolve n))))]
        (.getBytes ^String (substitute s vars* :resolve res :line line) StandardCharsets/ISO_8859_1)))))

(defn overlay [vars captures] (merge vars captures))

(defn volatile-names [header]
  (reduce (fn [acc {:keys [name value]}]
            (if (some acc (references value)) (conj acc name) acc))
          (set builtin-names)
          (:vars header)))

(def ^:private secretish #"(?i)secret|credential|token|password|key")

(defn- env-vars [env]
  (let [scalar? #(or (string? %) (number? %) (keyword? %) (boolean? %))
        entries (fn [m prefix] (for [[k v] m :when (and (scalar? v) (not (re-find secretish (name k))))]
                                 [(str prefix (name k)) (if (keyword? v) (name v) (str v))]))]
    (into {} (concat (entries (dissoc env :aws) "env.") (entries (:aws env) "env.")))))

(defn snapshot
  [header & {:keys [correlation env vars builtins]}]
  (let [now (store/now)
        base (merge {"uuid" (str (UUID/randomUUID))
                     "now" (str now)
                     "now.epoch" (str (.getEpochSecond now))
                     "correlation" (or correlation (str (UUID/randomUUID)))}
                    (env-vars env)
                    builtins)
        vs (reduce (fn [acc {:keys [name value line]}]
                     (assoc acc name (substitute value acc :line line)))
                   base (:vars header))]
    (merge vs vars)))
