(ns deliberator.steps.logs
  "Kind `logs`: tails a log for a window, armed before step 1 so the line an event produces is never
  missed. `### logs log/service-a [name]`, directives `# @filter <text|/regex/>` (repeatable: ALL must
  match) and `# @for 10s` (default 10s, the only clock: the kind's default timeout is an hour).

  The target resolves with `:resolve-target` to
    `{:container \"name\"}`   Podman container logs (`podman/logs`, follow, `since` = the arm instant;
                             the Podman endpoint is the workspace's runtime.edn, else the default)
    `{:file \"/path\"}`       (or a bare path string) polled every 200 ms from the byte offset at arm
                             time; a file that shrank restarts from 0
    `{:cloudwatch \"group\"}` `FilterLogEvents` through `aws/invoke!` every second from the arm
                             instant (events deduplicated by id); a first `@filter` that is a bare word
                             (letters, digits, `_`) also goes to CloudWatch as `filterPattern`.

  Entries: a header line plus the lines that continue it (indented lines, and Postgres
  `DETAIL:`/`HINT:`/`CONTEXT:`/`STATEMENT:`/`QUERY:`/`LOCATION:` lines) form one entry. The filters
  see the WHOLE entry (each filter must match somewhere in it) and a matching entry is emitted line
  for line, verbatim, to the stream (`:line!`) and to `response.log`. The default filter (no
  `@filter`) is the run's correlation id. An entry completes when the next header arrives or when
  the source is idle for 200 ms. A container that stops does not end the tail: the window is the only end. A line
  `-- container X stopped at T: log stream ended --` (bypassing the filters) marks the gap, the tail
  re-attaches when the container runs again (`-- ... running again at T --`). Facts: `lines` (matched
  lines), `gaps` (stop events), `elapsed`."
  (:require [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.common :as c])
  (:import (java.io RandomAccessFile)
           (java.nio.file Files Path Paths)
           (java.time Instant)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; --- filters and entries (pure) -------------------------------------------------------------------

(defn parse-filter
  "`text` (substring) or `/regex/` -> predicate over the entry text."
  [spec]
  (let [s (str/trim spec)]
    (if (and (>= (count s) 2) (str/starts-with? s "/") (str/ends-with? s "/"))
      (let [re (try (re-pattern (subs s 1 (dec (count s))))
                    (catch Exception e
                      (throw (ex-info (str "bad regex in @filter " s ": " (ex-message e))
                                      {:type :logs/bad-filter :hint "/regex/ uses Java syntax"}))))]
        #(boolean (re-find re %)))
      #(str/includes? % s))))

(def ^:private pg-detail #"^\S.{0,80}?\s(DETAIL|HINT|CONTEXT|STATEMENT|QUERY|LOCATION):\s")

(defn continuation?
  "True when `line` continues the entry above it."
  [line]
  (boolean (and (seq line)
                (or (#{\space \tab} (first line)) (re-find pg-detail line)))))

(defn group-entries
  "Lines -> vector of entries (vectors of lines), pure."
  [lines]
  (reduce (fn [acc l] (if (and (seq acc) (continuation? l))
                        (update acc (dec (count acc)) conj l)
                        (conj acc [l])))
          [] lines))

(defn entry-matches? [filters entry]
  (let [t (str/join "\n" entry)] (every? #(% t) filters)))

;; --- sources: each pushes raw lines onto a queue; ::end closes the stream --------------------------

(defn ->path ^Path [p] (Paths/get (str p) (make-array String 0)))

(defn- put-lines! [^LinkedBlockingQueue q lines] (doseq [l lines] (.put q l)))

(defn file-source
  "Polls `path` from `offset`. Returns a stop fn; pushes lines, never ends by itself."
  [path offset ^LinkedBlockingQueue q]
  (let [p (->path path) stop (atom false)]
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (loop [off offset partial ""]
                 (when-not @stop
                   (let [size (if (Files/isRegularFile p (make-array java.nio.file.LinkOption 0)) (Files/size p) 0)
                         [off partial] (if (< size off) [0 ""] [off partial])]
                     (if (> size off)
                       (let [bs (byte-array (- size off))]
                         (with-open [raf (RandomAccessFile. (.toFile p) "r")]
                           (.seek raf off) (.readFully raf bs))
                         (let [text (str partial (c/text bs))
                               parts (str/split text #"\n" -1)
                               done (butlast parts)]
                           (put-lines! q (map #(str/replace % #"\r$" "") done))
                           (recur size (last parts))))
                       (do (Thread/sleep 200) (recur off partial))))))
               (catch InterruptedException _ nil)
               (catch Throwable t (.put q t))))
           "deliberator-logs-file")
      (.setDaemon true) (.start))
    #(reset! stop true)))

(defn- container-running? [client name]
  (try (boolean (get-in (podman/inspect client name) [:State :Running]))
       (catch Throwable _ false)))

(defn podman-client [ctx]
  (let [cfg (runtime/read-config (:ws ctx))]
    (podman/client (get-in cfg [:podman :endpoint] (podman/default-endpoint)))))

(defn container-source
  "Follows the container's logs. When the stream ends or breaks after the first attach (the container
  stopped), pushes `{:gap text}` and waits for the container to run again (inspect every 500 ms), then
  re-attaches with `since` = the last line seen (lines at or before it are skipped); pushes
  `{:gap text}` again when it is back. Only an error of the FIRST attach is pushed as a Throwable."
  [ctx name since ^LinkedBlockingQueue q]
  (let [client (podman-client ctx)
        closer (atom nil) stop (atom false)]
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (loop [since since last-at nil attached? false]
                 (when-not @stop
                   (let [last-at (volatile! last-at)
                         err (try
                               (let [s (podman/logs client name :since since :follow true)]
                                 (reset! closer (:close (meta s)))
                                 (doseq [e s :while (not @stop)
                                         :when (or (nil? @last-at) (nil? (:at e)) (.isAfter ^Instant (:at e) ^Instant @last-at))]
                                   (when (:at e) (vreset! last-at (:at e)))
                                   (.put q (:text e)))
                                 nil)
                               (catch Throwable t (when-not @stop (if attached? t (throw t)))))]
                     (when-not @stop
                       (.put q {:stop? true :gap (str "-- container " name " stopped at " (Instant/now) ": log stream ended"
                                          (when err (str " (" (ex-message err) ")")) " --")})
                       (loop []
                         (when-not (or @stop (container-running? client name))
                           (Thread/sleep 500) (recur)))
                       (when-not @stop
                         (.put q {:gap (str "-- container " name " running again at " (Instant/now) ": log stream resumed --")})
                         (recur (or @last-at since) @last-at true))))))
               (catch InterruptedException _ nil)
               (catch Throwable t (when-not @stop (.put q t)))))
           "deliberator-logs-container")
      (.setDaemon true) (.start))
    #(do (reset! stop true) (when-let [f @closer] (try (f) (catch Throwable _ nil))))))

(defn- cw-pattern
  "A server side `filterPattern` for the first filter when it is a bare word (letters, digits, `_`):
  the one form CloudWatch and Floci agree on. Anything else is left to the kind's own filtering."
  [filters-raw]
  (when-let [f (first filters-raw)]
    (let [s (str/trim f)] (when (re-matches #"[A-Za-z0-9_]+" s) s))))

(defn- cw-call [ctx group req]
  (let [r (aws/invoke! {:ws (:ws ctx) :session (:session ctx)} (:env ctx) :logs :FilterLogEvents req)]
    (if (aws/error? r)
      (throw (ex-info (str "FilterLogEvents on " group ": " (or (:message r) (:code r) "failed"))
                      {:type :logs/cloudwatch :hint (:hint r) :aws r}))
      r)))

(defn cw-events
  "All events of `group` from `start` (epoch ms) on, paged, as given by FilterLogEvents."
  [ctx group start pattern]
  (loop [token nil acc []]
    (let [r (cw-call ctx group (cond-> {:logGroupName group :startTime start}
                                 pattern (assoc :filterPattern pattern)
                                 token (assoc :nextToken token)))
          acc (into acc (:events r))]
      (if-let [t (:nextToken r)] (recur t acc) acc))))

(defn cw-source [ctx group since pattern ^LinkedBlockingQueue q]
  (let [stop (atom false)]
    (doto (Thread.
           ^Runnable
           (fn []
             (try
               (loop [start (.toEpochMilli ^Instant since) seen #{}]
                 (when-not @stop
                   (let [events (cw-events ctx group start pattern)
                         fresh (remove #(contains? seen (:eventId %)) (sort-by :timestamp events))]
                     (doseq [e fresh]
                       (put-lines! q (str/split (str/replace (:message e) #"\n$" "") #"\n")))
                     (let [mx (reduce max start (map :timestamp events))]
                       (Thread/sleep 1000)
                       (recur mx (into #{} (comp (filter #(= mx (:timestamp %))) (map :eventId)) events))))))
               (catch InterruptedException _ nil)
               (catch Throwable t (.put q t))))
           "deliberator-logs-cloudwatch")
      (.setDaemon true) (.start))
    #(reset! stop true)))

;; --- the kind ---------------------------------------------------------------------------------------

(defn resolve-target-source
  "The source map `{:container n}|{:file p}|{:cloudwatch g}` of target key `t`, resolved with `resolve`
  (`(f \"log/service-a\")`); `where` names the environment in hints."
  [resolve t where]
  (let [_ (when (str/blank? t)
            (throw (ex-info "logs needs a log target" {:type :logs/no-target :hint "### logs log/<name>"})))
        v (resolve t)]
    (cond (string? v) {:file v}
          (and (map? v) (or (:container v) (:file v) (:cloudwatch v))) v
          :else (throw (ex-info (str "target " t " is not a log target: " (pr-str v))
                                {:type :logs/bad-target
                                 :hint (str "environment " where " must map it to {:container n}, {:file p} or {:cloudwatch group}")})))))

(defn- resolve-source [ctx step]
  (resolve-target-source (:resolve-target ctx) (:target step) (c/env-name ctx)))

(defn file-size [path]
  (let [p (->path path)] (if (Files/isRegularFile p (make-array java.nio.file.LinkOption 0)) (Files/size p) 0)))

(defn arm [ctx step]
  (let [src (resolve-source ctx step)]
    {:at (Instant/now) :source src
     :offset (when (:file src) (file-size (:file src)))}))

(defn snapshot
  "One-shot read of `source`: a vector of its lines now. File: the whole file. Container: its logs from
  `since` (Instant or nil = all) without following. CloudWatch: the events from `since` (required),
  paged, split into lines; `pattern` is the optional filterPattern."
  [ctx source since pattern]
  (cond
    (:file source) (let [p (->path (:file source))]
                     (if (Files/isRegularFile p (make-array java.nio.file.LinkOption 0))
                       (let [parts (str/split (c/text (Files/readAllBytes p)) #"\n" -1)
                             parts (if (= "" (last parts)) (butlast parts) parts)]
                         (mapv #(str/replace % #"\r$" "") parts))
                       []))
    (:container source) (vec (map :text (podman/logs (podman-client ctx) (:container source) :since since)))
    :else (vec (mapcat #(str/split (str/replace (:message %) #"\n$" "") #"\n")
                       (sort-by :timestamp (cw-events ctx (:cloudwatch source) (.toEpochMilli ^Instant since) pattern))))))

(defn start-source
  "Starts the source of `source` feeding queue `q`; returns its stop fn. `offset` (file) and `at`
  (container, CloudWatch: an Instant) say where it starts; `pattern` is the CloudWatch filterPattern."
  [ctx source offset at pattern q]
  (cond (:file source) (file-source (:file source) offset q)
        (:container source) (container-source ctx (:container source) at q)
        :else (cw-source ctx (:cloudwatch source) at pattern q)))

(defn drain!
  "Consumes queue `q` until epoch-ms `end` or `(cancelled?)`: groups lines into entries, emits (calls
  `line!` for) the entries matching every filter and the gap lines, throws `:logs/source` with `hint` when
  the source failed. Returns `{:out [emitted lines] :raw [every source line] :gaps n :gap-lines n}`."
  [^LinkedBlockingQueue q end cancelled? filters line! hint]
  (let [out (volatile! []) raw (volatile! []) pending (volatile! []) gaps (volatile! 0) gap-lines (volatile! 0)
        emit! (fn [entry]
                (when (entry-matches? filters entry)
                  (doseq [l entry] (line! l) (vswap! out conj l))))
        flush! (fn [] (when (seq @pending) (emit! @pending) (vreset! pending [])))]
    (loop []
      (let [left (- end (System/currentTimeMillis))]
        (when (and (pos? left) (not (cancelled?)))
          (let [x (.poll q (min 200 left) TimeUnit/MILLISECONDS)]
            (cond
              (nil? x) (do (flush!) (recur))
              (instance? Throwable x)
              (throw (ex-info (or (ex-message x) (str (class x)))
                              (merge {:type :logs/source :hint hint}
                                     (select-keys (ex-data x) [:hint]))
                              x))
              (map? x) (do (flush!)
                           (when (:stop? x) (vswap! gaps inc))
                           (vswap! gap-lines inc)
                           (line! (:gap x)) (vswap! out conj (:gap x))
                           (recur))
              (= ::end x) (flush!)
              :else (do (vswap! raw conj x)
                        (if (and (seq @pending) (continuation? x))
                          (vswap! pending conj x)
                          (do (flush!) (vreset! pending [x])))
                        (recur)))))))
    (flush!)
    {:out @out :raw @raw :gaps @gaps :gap-lines @gap-lines}))

(defn run [ctx step]
  (let [{:keys [at source offset]} (or (:armed ctx) (arm ctx step))
        raw-filters (c/dirs step "filter")
        raw-filters (if (seq raw-filters) raw-filters [(:correlation ctx)])
        filters (mapv parse-filter raw-filters)
        window (c/duration-dir step "for" 10000)
        t0 (System/currentTimeMillis)
        end (min (+ t0 window) (.toEpochMilli ^Instant (or (:attempt-deadline ctx) (:deadline ctx))))
        q (LinkedBlockingQueue.)
        stop (start-source ctx source offset at (cw-pattern raw-filters) q)
        {out :out gaps :gaps gap-lines :gap-lines}
        (try (drain! q end (:cancelled? ctx) filters (:line! ctx)
                     (str "check the log target " (:target step) " in environment " (c/env-name ctx)))
             (finally (stop)))
        gaps (volatile! gaps) gap-lines (volatile! gap-lines) out (volatile! out)]
    (let [lines @out]
      (cond-> {:facts {"lines" (- (count lines) @gap-lines)
                       "gaps" @gaps}
               :lines (- (count lines) @gap-lines)
               :source :file :op (str "logs " (:target step)) :content-type "text/plain"}
        (seq lines) (assoc :response-bytes (c/utf8 (str (str/join "\n" lines) "\n")) :response-ext "log")
        (empty? lines) (assoc :display (str "(no lines matched " (str/join " AND " raw-filters)
                                            " in " (quot window 1000) " s)"))))))

(def kind
  {:kind "logs" :verbs #{nil} :target :required
   :directives [{:name "filter" :hint "<text|/regex/>" :values ["{{correlation}}"] :repeatable? true
                 :doc "an entry must match every filter; default: the run's correlation id"}
                {:name "for" :hint "<duration>" :values ["5s" "10s" "30s" "1m"] :doc "tail window (default 10s)"}]
   :facts [{:name "lines" :type :number} {:name "gaps" :type :number}]
   :capture-roots #{} :concurrent? true
   :default-timeout-ms 3600000
   :arm arm :run run})

(kinds/register! kind)
