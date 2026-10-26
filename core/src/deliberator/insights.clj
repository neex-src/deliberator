(ns deliberator.insights
  "`logs.insights` (M4.3): a CloudWatch Logs Insights query, with the bytes it scanned in front of you.

  `(query! ctx env opts)`:
    1. the query text is saved first when the query is named (`sessions/<k>/queries/<name>.insights`);
    2. `StartQuery` (log group names or the environment's `{:cloudwatch g}` targets, `since` .. `until`, epoch seconds);
    3. `GetQueryResults` is polled until a final status (`Complete` | `Failed` | `Cancelled` | `Timeout`) within `budget-ms`;
       over the budget, or when `cancelled?` turns true, the query is stopped (`StopQuery`, a read: it changes no resource);
    4. a `Complete` answer that carries a `nextToken` is paged to the end;
    5. every request and every response, polls and pages included, is raw-persisted by `aws/invoke!` before anything is read
       from it (principle 1); the derived view is the result text, hard-linked into the session as
       `logs/insights-<name|query>-<ts>.txt`: a `#` header (environment, groups, window, query, status) with the bytes scanned
       line (`# bytes scanned: 737 B`, the money line: Insights bills per GB scanned), then the fields as a tab separated table
       (`@ptr` dropped, it is a handle into the raw response).

  `statistics` is what the service sends, the shape measured on a sandbox AWS account (2026-10-26): `{recordsMatched recordsScanned
  estimatedRecordsSkipped bytesScanned estimatedBytesSkipped logGroupsScanned resultCount}`, all JSON numbers read as
  doubles (`737.0`); here they are longs when whole. The result map has `:bytes-scanned` beside `:statistics`.

  Opts: `:groups` (vector, 1..50), `:query`, `:since` (ISO-8601 instant or a duration ago, default `1h`), `:until` (default
  now), `:name`, `:budget-ms` (default 120 000), `:poll-ms` (default 1000), `:limit`, `:progress!` (a fn of one line of
  text), `:throw?` (default true: a final status other than `Complete` throws after the files are written).
  `ctx` keys: `:ws`, `:session` (required), `:cancelled?` (a fn). The remote environment's account was checked by the caller."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.scenario.expr :as expr]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def default-since "1h")
(def default-budget-ms 120000)
(def max-groups "StartQuery takes at most 50 log groups." 50)

(def ^:private stamp (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (.withZone ZoneOffset/UTC)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn- bad [msg] (fail :insights/bad-arg msg :hint "since/until: an ISO-8601 instant or a duration ago like 30m or 1h"))

(defn parse-instant
  "An ISO-8601 instant, or a duration ago (`30m`) relative to `now`."
  [s now]
  (let [s (str/trim (str s))]
    (if (expr/duration? s)
      (.minus ^Instant now (Duration/ofMillis (expr/parse-duration s)))
      (try (Instant/parse s) (catch Exception _ (bad (str "bad time " (pr-str s))))))))

(defn human-bytes
  "737 -> \"737 B\", 1536 -> \"1.5 KB\"."
  [n]
  (let [n (double (or n 0))]
    (cond (< n 1024) (str (long n) " B")
          (< n 1048576) (format "%.1f KB" (/ n 1024.0))
          (< n 1073741824) (format "%.1f MB" (/ n 1048576.0))
          :else (format "%.2f GB" (/ n 1073741824.0)))))

(defn- whole [x] (if (and (number? x) (== x (Math/rint (double x)))) (long x) x))

(defn statistics
  "The `statistics` of a GetQueryResults answer, keys as the service names them, whole numbers as longs."
  [r]
  (into (sorted-map) (for [[k v] (:statistics r)] [(name k) (whole v)])))

(defn resolve-group
  "A log group name as given, or the group of the environment target of that name (`log/service-a` -> `{:cloudwatch g}`)."
  [e g]
  (let [t (get-in e [:targets (keyword g)])]
    (cond (and (map? t) (string? (:cloudwatch t))) (:cloudwatch t)
          :else g)))

;; --- the derived text (pure) ----------------------------------------------------------------------

(defn rows
  "`GetQueryResults` `:results` ([[{:field :value}]]) -> vector of maps field -> value, `@ptr` dropped."
  [results]
  (vec (for [row results]
         (into (array-map) (for [{:keys [field value]} row :when (not= "@ptr" field)] [field value])))))

(defn fields
  "The distinct field names of `rows`, in order of first appearance."
  [rows]
  (vec (distinct (mapcat keys rows))))

(defn- cell [v] (-> (str v) (str/replace "\\" "\\\\") (str/replace "\t" "\\t") (str/replace "\r" "\\r") (str/replace "\n" "\\n")))

(defn result-text
  "The text a person (or `grep`) reads: the `#` header with the bytes scanned, then a tab separated table."
  [{:keys [env account groups since until query status statistics rows elapsed-ms name query-id]}]
  (let [fs (fields rows)
        st (fn [k] (get statistics k))]
    (str "# logs insights on " env (when account (str " (account " account ")")) (when name (str ", query " name)) "\n"
         "# groups: " (str/join ", " groups) "\n"
         "# window: " since " .. " until "\n"
         "# query: " (str/replace (str/trim query) #"\s*\n\s*" " ") "\n"
         "# status: " status (when query-id (str "   query id: " query-id)) (when elapsed-ms (str "   took: " elapsed-ms " ms")) "\n"
         "# bytes scanned: " (human-bytes (st "bytesScanned")) " (" (or (st "bytesScanned") 0) ")"
         "   records matched: " (or (st "recordsMatched") 0) "   scanned: " (or (st "recordsScanned") 0)
         "   rows: " (count rows) "\n"
         (when (seq fs) (str (str/join "\t" fs) "\n"))
         (apply str (for [r rows] (str (str/join "\t" (map #(cell (get r %)) fs)) "\n"))))))

;; --- persisted files ----------------------------------------------------------------------------

(defn queries-dir [ws sess] (.resolve (session/dir ws sess) "queries"))

(defn query-file
  "`sessions/<k>/queries/<name>.insights`."
  [ws sess name]
  (.resolve (queries-dir ws sess) (str (store/safe-name name) ".insights")))

(defn- save-query! [ws sess name query]
  (let [p (query-file ws sess name)]
    (store/write-bytes-atomic! p (.getBytes (str (str/trimr query) "\n") StandardCharsets/UTF_8))
    p))

(defn- link-text! [ws sess label sha]
  (let [base (str "insights-" (str/replace (str label) #"[^A-Za-z0-9._-]+" "-") "-" (.format stamp (store/now)))
        dir (.resolve (session/dir ws sess) "logs")]
    (loop [n 1]
      (let [p (.resolve dir (str base (when (> n 1) (str "-" n)) ".txt"))
            r (try (store/link! ws sha p)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :store/target-exists (:type (ex-data e))) nil (throw e))))]
        (if r (:path r) (recur (inc n)))))))

;; --- the query -----------------------------------------------------------------------------------

(defn- ref-of [op r]
  (let [raw (aws/raw-refs r)]
    {:op op :request (get-in raw [:request :sha]) :response (get-in raw [:response :sha])}))

(defn- aws-failure [what r]
  (fail :insights/aws-error (str what ": " (or (:message r) (:code r) (str (:category r))))
        :hint (:hint r) :aws r))

(def ^:private final-statuses #{"Complete" "Failed" "Cancelled" "Timeout" "Unknown"})

(defn query!
  "See the namespace docstring. -> `{:env :query-id :status :groups :since :until :bytes-scanned :statistics :rows :path :sha
  [:query-file] :polls :took-ms :raw [{:op :request :response}]  :result-rows [maps] :text}`."
  [ctx e {:keys [groups query since until name budget-ms poll-ms limit progress! throw?]
          :or {budget-ms default-budget-ms poll-ms 1000 throw? true}}]
  (let [ws (or (:ws ctx) (fail :insights/bad-arg "ctx needs :ws"))
        sess (or (:session ctx) (fail :session/none "no session: pass session or start one (session.start)"))
        cancelled? (or (:cancelled? ctx) (constantly false))
        say (or progress! (fn [_]))
        now (store/now)
        groups (mapv #(resolve-group e %) groups)
        _ (when (empty? groups) (fail :insights/bad-arg "logs insights needs at least one log group" :hint "--groups /aws/lambda/fn (a log group name or a log/ target of the environment)"))
        _ (when (> (count groups) max-groups) (fail :insights/bad-arg (str "at most " max-groups " log groups per query")))
        _ (when (str/blank? query) (fail :insights/bad-arg "logs insights needs a query" :hint "e.g. fields @timestamp, @message | sort @timestamp desc | limit 20"))
        whole-s #(.truncatedTo ^Instant % java.time.temporal.ChronoUnit/SECONDS)   ; StartQuery takes epoch seconds
        t1 (whole-s (if (str/blank? until) now (parse-instant until now)))
        t0 (whole-s (parse-instant (if (str/blank? since) default-since since) now))
        _ (when-not (.isBefore t0 t1) (bad (str "the window is empty: since " t0 " is not before until " t1)))
        qfile (when name (save-query! ws sess name query))   ; persist first: the text is on disk before the service sees it
        started-ms (System/currentTimeMillis)
        actx {:ws ws :session sess}
        refs (volatile! [])
        stop! (fn [id] (try (let [r (aws/invoke! actx e :logs :StopQuery {:queryId id})]
                              (vswap! refs conj (ref-of "StopQuery" r)))
                            (catch Throwable _ nil)))
        start (aws/invoke! actx e :logs :StartQuery
                           (cond-> {:logGroupNames groups :queryString query
                                    :startTime (.getEpochSecond t0) :endTime (.getEpochSecond t1)}
                             limit (assoc :limit limit)))
        _ (when (aws/error? start) (aws-failure "StartQuery" start))
        _ (vswap! refs conj (ref-of "StartQuery" start))
        id (:queryId start)
        _ (say (str "query " id " started on " (count groups) " log group" (when (> (count groups) 1) "s")))
        deadline (+ started-ms budget-ms)
        poll! (fn [token]
                (let [r (aws/invoke! actx e :logs :GetQueryResults (cond-> {:queryId id} token (assoc :nextToken token)))]
                  (vswap! refs conj (ref-of "GetQueryResults" r))
                  (when (aws/error? r) (stop! id) (aws-failure "GetQueryResults" r))
                  r))
        ;; poll until a final status, the budget or a cancel
        [last-r status polls]
        (try
          (loop [polls 0 prev nil]
            (let [r (poll! nil)
                  st (:status r) polls (inc polls)
                  b (get (statistics r) "bytesScanned")]
              (when (not= [st b] prev)
                (say (str "query " id ": " st (when b (str ", " (human-bytes b) " scanned")))))
              (cond
                (final-statuses st) [r st polls]
                (cancelled?) (do (stop! id) [r "Cancelled" polls])
                (>= (System/currentTimeMillis) deadline) (do (stop! id) [r "BudgetExceeded" polls])
                :else (do (Thread/sleep (long (min poll-ms (max 1 (- deadline (System/currentTimeMillis))))))
                          (if (cancelled?)
                            (do (stop! id) [r "Cancelled" polls])
                            (recur polls [st b]))))))
          (catch InterruptedException ie (stop! id) (throw ie)))
        ;; the pages of a complete answer
        pages (if (and (= "Complete" status) (:nextToken last-r))
                (loop [tok (:nextToken last-r) acc [] n 0]
                  (if (and tok (< n 1000))
                    (let [r (poll! tok)] (recur (:nextToken r) (conj acc r) (inc n)))
                    acc))
                [])
        all (cons last-r pages)
        result-rows (rows (mapcat :results all))
        stats (statistics (or (last (filter :statistics pages)) last-r))
        took (- (System/currentTimeMillis) started-ms)
        view {:env (:name e) :account (get-in e [:aws :account]) :groups groups :since t0 :until t1 :query query :status status
              :statistics stats :rows result-rows :elapsed-ms took :name name :query-id id}
        text (result-text view)
        blob (store/raw-put! ws (.getBytes ^String text StandardCharsets/UTF_8)
                             {:source :aws :op "logs.insights" :env (keyword (:name e)) :at (store/now)
                              :content-type "text/plain"})
        path (link-text! ws sess (or name "query") (:sha blob))
        result (cond-> {:env (:name e) :query-id id :status status :groups groups
                        :since (str t0) :until (str t1)
                        :bytes-scanned (get stats "bytesScanned")
                        :bytes-scanned-text (human-bytes (get stats "bytesScanned"))
                        :statistics stats :rows (count result-rows)
                        :path (str path) :sha (:sha blob) :polls polls :took-ms took
                        :raw @refs :result-rows result-rows :text text}
                 qfile (assoc :query-file (str qfile)))]
    (if (and throw? (not= "Complete" status))
      (fail (keyword "insights" (case status "BudgetExceeded" "budget-exceeded" (str/lower-case status)))
            (str "Logs Insights query " id " ended " status " after " (quot took 1000) " s; "
                 (human-bytes (get stats "bytesScanned")) " scanned; what came back is in " path)
            :hint (case status
                    "BudgetExceeded" "the query was stopped; raise budget-ms or narrow the window and the groups"
                    "Cancelled" "the query was stopped"
                    "Failed" "see the raw GetQueryResults response of the query; a malformed query fails here, not at StartQuery"
                    "see the raw GetQueryResults response")
            :path (str path) :result (dissoc result :text :result-rows))
      result)))

(defn run-query!
  "The op's entry: loads nothing, takes the environment record `e` (the op did `env/load` and the account check) and
  returns `query!`'s map without the bulky `:result-rows` and `:text` (they are in `:path`)."
  [ctx e opts]
  (dissoc (query! ctx e opts) :result-rows :text))
