(ns deliberator.steps.sql
  "Kind `sql`: `### sql db/main [name]` runs the body as ONE statement. The target resolves (through
  `:resolve-target`) to a JDBC URL string or a map `{:url :user :password}`; credentials may also ride
  in the URL as `?user=..&password=..`. Only `jdbc:postgresql:` URLs are accepted. The connection is
  opened for the step and closed after it.

  SELECT -> the rows as the monospace table that greps and diffs: columns joined by ` | `, a `-+-`
  rule, `NULL` spelled out, cells cut at 40 chars with an ellipsis, newlines as `\\n`. `# @limit N`
  (default 50) rows are shown; the driver is asked for N+1 so `… more rows` appears exactly when
  there are more. DML -> `(n rows affected)`. `# @timeout` bounds the statement (the attempt's
  deadline becomes the statement timeout).

  Facts: `rows` (shown), `updated` (the update count of a DML, else 0), `elapsed`, `col.<label>` (the
  first row's value, a number stays a number). Persisted: `request.sql` = the statement,
  `response.txt` = the rendered table. Errors carry the SQLSTATE and, for connection failures, the
  URL with credentials removed and the environment/target names."
  (:require [clojure.string :as str]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.common :as c]
            [next.jdbc :as jdbc])
  (:import (java.sql Connection ResultSet SQLException Statement)))

(def max-cell 40)

(defn cell
  "One value as table text."
  [v]
  (if (nil? v)
    "NULL"
    (let [flat (-> (str v) (str/replace "\r\n" "\n") (str/replace "\n" "\\n") (str/replace \tab \space))]
      (if (> (count flat) max-cell) (str (subs flat 0 (dec max-cell)) "…") flat))))

(defn render-table
  "Columns (labels) and rows (vectors of strings or nil) -> table text, no trailing newline.
  `more?` adds the trailer."
  [columns rows more? limit]
  (let [cells (mapv #(mapv cell %) rows)
        widths (vec (map-indexed (fn [i col] (reduce max (count col) (map #(count (nth % i "")) cells))) columns))
        pad (fn [s w] (str s (apply str (repeat (- w (count s)) \space))))
        line (fn [xs sep] (str/trimr (str/join sep (map-indexed (fn [i x] (pad x (nth widths i))) xs))))]
    (str/join "\n"
              (concat [(line columns " | ")
                       (str/join "-+-" (map #(apply str (repeat % \-)) widths))]
                      (map #(line % " | ") cells)
                      (when more? [(str "… more rows (limit " limit ", # @limit N to raise)")])))))

(defn rows-affected [n] (str "(" n " row" (when (not= n 1) "s") " affected)"))

(defn- redact-url
  "The URL without user/password query parameters."
  [url]
  (-> url (str/replace #"(?<=[?&])(user|password)=[^&]*&?" "") (str/replace #"[?&]+$" "")))

(defn- db-spec [target]
  (let [{:keys [url user password]} (if (map? target) target {:url target})]
    (when-not (and (string? url) (str/starts-with? url "jdbc:postgresql:"))
      (throw (ex-info (str "only jdbc:postgresql: URLs are supported, got " (pr-str (some-> url redact-url)))
                      {:type :sql/unsupported :hint "the db/ target must be a jdbc:postgresql: URL (or {:url .. :user .. :password ..})"})))
    (cond-> {:jdbcUrl url :connectTimeout "5" :ApplicationName "deliberator"}
      user (assoc :user user)
      password (assoc :password password))))

(defn- value [^ResultSet rs i]
  (let [o (.getObject rs (int i))]
    (cond (nil? o) nil
          (bytes? o) (str "bytea(" (alength ^bytes o) ")")
          (number? o) o
          (instance? Boolean o) (str o)
          :else (.getString rs (int i)))))

(defn- read-rows [^ResultSet rs limit]
  (let [md (.getMetaData rs)
        n (.getColumnCount md)
        labels (mapv #(.getColumnLabel md (int %)) (range 1 (inc n)))]
    (loop [rows []]
      (if (and (<= (count rows) limit) (.next rs))
        (recur (conj rows (mapv #(value rs %) (range 1 (inc n)))))
        [labels rows]))))

(defn- sql-ex [^SQLException e target-key ctx url]
  (let [state (.getSQLState e)
        first-line (first (str/split-lines (or (ex-message e) "SQL error")))
        conn? (and state (str/starts-with? state "08"))]
    (ex-info first-line
             {:type :sql/failed
              :hint (if conn?
                      (str "cannot reach " (redact-url url) " (environment " (c/env-name ctx) ", target " target-key
                           "): is the database up, are the credentials in the target right?")
                      (str "SQLSTATE " state))})))

(defn run [ctx step]
  (let [target-key (:target step)
        target ((:resolve-target ctx) target-key)
        spec (db-spec target)
        url (:jdbcUrl spec)
        sql (-> (c/text (or (:body step) (byte-array 0))) str/trim (str/replace #";+\s*$" ""))
        _ (when (str/blank? sql)
            (throw (ex-info "empty statement" {:type :sql/empty :hint "put the SQL under the ### sql line"})))
        limit (if-let [v (c/dir step "limit")]
                (or (parse-long v) (throw (ex-info (str "bad @limit " (pr-str v)) {:type :sql/bad-limit :hint "a whole number"})))
                50)
        rq (c/utf8 (str sql "\n"))
        fail (fn [e] (let [ex (sql-ex e target-key ctx url)]
                       (ex-info (ex-message ex) (assoc (ex-data ex) :request-bytes rq :request-ext "sql") e)))]
    (try
      (with-open [^Connection conn (jdbc/get-connection spec)
                  ^Statement st (.createStatement conn)]
        (.setQueryTimeout st (int (max 1 (long (Math/ceil (/ (kinds/remaining-ms ctx) 1000.0))))))
        (.setMaxRows st (int (inc limit)))
        (let [rs? (.execute st sql)
              base {:request-bytes rq :request-ext "sql" :response-ext "txt"
                    :source :sql :op (str "sql " target-key) :content-type "text/plain"}]
          (if-not rs?
            (let [n (.getUpdateCount st)]
              (assoc base :facts {"rows" 0 "updated" n}
                     :response-bytes (c/utf8 (str (rows-affected n) "\n")) :lines 1))
            (with-open [rs (.getResultSet st)]
              (let [[labels rows] (read-rows rs limit)
                    more? (> (count rows) limit)
                    shown (vec (take limit rows))
                    table (render-table labels (mapv (fn [r] (mapv #(cond (nil? %) nil (decimal? %) (.toPlainString ^BigDecimal %) :else (str %)) r)) shown) more? limit)]
                (assoc base
                       :facts (into {"rows" (count shown) "updated" 0}
                                    (when-let [r (first shown)]
                                      (map (fn [l v] [(str "col." l) v]) labels r)))
                       :response-bytes (c/utf8 (str table "\n"))
                       :lines (+ 2 (count shown) (if more? 1 0))))))))
      (catch SQLException e (throw (fail e))))))

(def kind
  {:kind "sql" :verbs #{nil} :target :required
   :directives [{:name "limit" :hint "<rows>" :values ["20" "50" "200" "1000"] :doc "rows shown (default 50)"}]
   :facts [{:name "rows" :type :number} {:name "updated" :type :number} {:name "col.*" :type :string}]
   :capture-roots #{}
   :run run})

(kinds/register! kind)
