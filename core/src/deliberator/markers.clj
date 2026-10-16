(ns deliberator.markers
  "Source markers and their match against logs. A marker is a source line the user cares about:
  `{:id :file :line :logger}` in `workspaces/<ws>/markers.edn` (`{:next n :markers [...]}`); `:logger` is
  the class the IDE derives (`com.acme.ServiceA`). `(add! ws file line & {:keys [logger]})` is idempotent
  per file+line (a given logger updates it, the id stays), `(remove! ws file line)`, `(list ws & {:keys
  [file]})`; each answers the whole list sorted by file and line (list: filtered).

  `(match-lines markers lines)` -> `[{:n :marker-id}]` (1-based line numbers; two markers on one line give
  two entries). Pure. A log line carries a position in one of two shapes:
    logback, the estate's pattern `%d %-5level APP [%X{correlation}] %logger{36}:%line - %msg`:
      a token `logger:line` surrounded by whitespace and followed by ` - ` (`... [c1] c.a.ServiceA:42 - msg`);
    a stack frame `at pkg.Class.method(File.java:42)` (the class is the part before the method).
  The marker matches when the lines are equal and the loggers agree: `%logger{N}` abbreviates package
  segments from the left (`com.acme.svc.ServiceA` -> `c.a.s.ServiceA`), so segments are compared right
  aligned, the class name exactly and every abbreviated segment as a prefix of the marker's; a log logger
  of one segment (`%logger{0}`) matches by class name alone. A different class with the same tail
  (`com.other.ServiceA` against `c.a.ServiceA`) never matches. `$Inner` suffixes are ignored. A marker
  without logger is compared by the simple class name taken from its file's base name."
  (:refer-clojure :exclude [list remove])
  (:require [clojure.string :as str]
            [deliberator.store :as store]))

(def ^:private lock (Object.))

(defn- path-of [ws] (store/path ws "markers.edn"))

(defn- load* [ws] (or (store/read-edn (path-of ws)) {:next 1 :markers []}))

(defn- view [ms] (mapv #(select-keys % [:id :file :line :logger]) (sort-by (juxt :file :line) ms)))

(defn list
  [ws & {:keys [file]}]
  (view (cond->> (:markers (load* ws)) file (filter #(= file (:file %))))))

(defn add!
  [ws file line & {:keys [logger]}]
  (locking lock
    (let [{:keys [next markers] :as m} (load* ws)
          old (first (filter #(and (= file (:file %)) (= line (:line %))) markers))
          markers' (if old
                     (mapv #(if (identical? % old) (cond-> % logger (assoc :logger logger)) %) markers)
                     (conj markers (cond-> {:id (str "m" next) :file file :line line :created (store/now)}
                                     logger (assoc :logger logger))))]
      (store/write-edn! (path-of ws) (assoc m :next (if old next (inc next)) :markers markers'))
      (view markers'))))

(defn remove!
  [ws file line]
  (locking lock
    (let [m (load* ws)
          markers' (clojure.core/remove #(and (= file (:file %)) (= line (:line %))) (:markers m))]
      (store/write-edn! (path-of ws) (assoc m :markers (vec markers')))
      (view markers'))))

;; matching ------------------------------------------------------------------------------------

(def ^:private logback-re #"(?:^|\s)((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*):(\d+)\s+-\s")
(def ^:private frame-re #"\bat\s+((?:[\w$]+\.)*[\w$]+)\.(?:[\w$<>]+)\([^():]*:(\d+)\)")

(defn position
  "`[logger line]` of a log line, or nil."
  [^String s]
  (or (when-let [[_ l n] (re-find logback-re s)] [l (Long/parseLong n)])
      (when-let [[_ l n] (re-find frame-re s)] [l (Long/parseLong n)])))

(defn- segments [^String s] (str/split (first (str/split s #"\$")) #"\."))

(defn- base-name [f]
  (let [n (last (str/split (str f) #"[/\\]"))] (first (str/split n #"\."))))

(defn logger-matches?
  "Does the log's `logger` (possibly logback-abbreviated) denote the marker's class `mlogger`?"
  [mlogger logger]
  (let [m (segments mlogger) l (segments logger)]
    (cond
      (= 1 (count l)) (= (first l) (last m))
      (= 1 (count m)) (= (last l) (first m))
      :else (and (= (count l) (count m))
                 (= (last l) (last m))
                 (every? true? (map #(str/starts-with? %2 %1) (butlast l) (butlast m)))))))

(defn match-lines
  "Marker matches over `lines` (see the ns docstring)."
  [markers lines]
  (vec (for [[i text] (map-indexed vector lines)
             :let [[logger n] (position text)]
             :when logger
             m markers
             :when (and (= n (:line m)) (logger-matches? (or (:logger m) (base-name (:file m))) logger))]
         {:n (inc i) :marker-id (:id m)})))
