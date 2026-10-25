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
  Persist first: the source is `raw-put!` as one blob (a file byte for byte, other sources their lines joined by newlines; a file that is not a regular file is `logs/not-a-file` unless `for` listens) (nothing is filtered at the source, not even
  for CloudWatch); with `filter` (`text` or `/regex/`, an entry is kept whole, as in the kind) the kept
  entries are a second blob. The answer's blob is hard-linked to `sessions/<session>/logs/<target>-<ts>.log`.
  Returns `{:path :lines :bytes :sha :target :source}` (`:source` = container, file or cloudwatch).

  Events. With `emit!` (the op passes the request's) a fetch that listens streams every line it emits as
  `logs.line {target n text at}` while it arrives (`n` from 1; a gap line, the container going away or back, carries
  `gap true` and is in no file); the result and the files are those of a fetch without events.

  Follow (`follow true`, or `for` = `until-cancelled`): the same source, listened to until `cancelled?` turns true (the
  client left or cancelled), ONE file for the whole follow. The raw lines are appended to it (flushed per line) as they
  arrive: the session's `logs/<target>-<ts>.log`, or with a `filter` a work file `raw/follow/<id>.log` while the filtered
  entries are appended to the session file. A sidecar `raw/follow/<id>.edn` names them and the owning process. At the end
  the files become raw blobs by hard link (`store/raw-adopt!`, no byte array, any length) and the session file IS the
  blob; a follow whose daemon died is finished the same way at the next daemon start (`adopt-orphans!`). The result
  has `:follow true`. A follow of a container without `since` starts at the current end of its log (`follow-start`: only the
  lines written from now on, like `tail -f -n 0`); with `since` it starts there, as before. A file follow still starts at byte 0
  and CloudWatch at its 15 minute default."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.scenario.expr :as expr]
            [deliberator.session :as session]
            [deliberator.steps.logs :as logs]
            [deliberator.store :as store])
  (:import (java.nio.file Files OpenOption StandardOpenOption)
           (java.time Duration Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent LinkedBlockingQueue)))

(defn- bad [msg] (throw (ex-info msg {:type :logs/bad-arg :hint "since: an ISO-8601 instant or a duration like 10m; for: 500ms, 2s, 3m, 1h"})))

(defn parse-since
  "An ISO-8601 instant, or a duration ago (`10m`) relative to `now`."
  [s now]
  (let [s (str/trim (str s))]
    (cond (expr/duration? s) (.minus ^Instant now (Duration/ofMillis (expr/parse-duration s)))
          :else (try (Instant/parse s) (catch Exception _ (bad (str "bad since " (pr-str s))))))))

(defn follow-start
  "Where a follow reads from: `since` (an Instant, nil = none given) when given, else for a container `now`, so that
  following does not replay the whole log first (podman `--since now`). A file (byte 0) and CloudWatch (its default
  `since` is set before) are left as they are. `kind` is \"container\", \"file\" or \"cloudwatch\"."
  [since kind now]
  (or since (when (= "container" kind) now)))

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

(defn- new-answer-file!
  "Creates (atomically, empty) the next free `logs/<target>-<ts>[-n].log` of the session; -> Path."
  [ws sess target]
  (let [base (str (str/replace (str target) #"[^A-Za-z0-9._-]+" "-") "-" (.format stamp (store/now)))
        dir (.resolve (session/dir ws sess) "logs")]
    (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
    (loop [n 1]
      (let [p (.resolve dir (str base (when (> n 1) (str "-" n)) ".log"))]
        (if (try (Files/createFile p (make-array java.nio.file.attribute.FileAttribute 0)) true
                 (catch java.nio.file.FileAlreadyExistsException _ false))
          p
          (recur (inc n)))))))

(defn- follow-dir [ws] (store/path ws "raw" "follow"))

(defn- owner []
  (let [h (java.lang.ProcessHandle/current)]
    {:pid (.pid h) :process-start (some-> (.startInstant (.info h)) (.orElse nil) str)}))

(defn- owner-alive? [{:keys [pid process-start]}]
  (boolean
   (when pid
     (let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
       (and h (.isAlive ^java.lang.ProcessHandle h)
            (or (nil? process-start)
                (= process-start (some-> (.startInstant (.info ^java.lang.ProcessHandle h)) (.orElse nil) str))))))))

(defn- finish-follow!
  "Turns the files of a follow (sidecar `side`, a map read from `raw/follow/<id>.edn`) into raw blobs and removes the
  sidecar. -> `{:raw :answer}` (the `raw-adopt!` results; one and the same when nothing was filtered)."
  [ws {:keys [work answer filtered? raw-meta answer-meta sidecar]}]
  (let [raw (store/raw-adopt! ws work raw-meta)
        ans (if filtered? (store/raw-adopt! ws answer answer-meta) raw)]
    (when-not (:new? ans)   ; the same bytes were in the store already: the session file becomes a link to that blob, as for any fetch
      (let [af (.toPath (io/file answer)) tmp (.resolveSibling af (str "." (.getFileName af) ".relink"))]
        (try (Files/deleteIfExists tmp)
             (Files/createLink tmp (:path ans))
             (Files/move tmp af (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                                       java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
             (catch java.io.IOException _ (Files/deleteIfExists tmp)))))
    (when filtered? (Files/deleteIfExists (.toPath (io/file work))))
    (Files/deleteIfExists (.toPath (io/file sidecar)))
    {:raw raw :answer ans}))

(defn adopt-orphans!
  "At daemon start: every follow in `<home>/workspaces/*/raw/follow/*.edn` whose owner process is gone is finished
  (`finish-follow!`): the live file becomes a raw blob. -> the session files adopted."
  [home]
  (let [wsdir (io/file (str home) "workspaces")]
    (vec (for [w (or (.listFiles wsdir) [])
               side (or (.listFiles (io/file w "raw" "follow")) [])
               :when (str/ends-with? (.getName side) ".edn")
               :let [m (try (store/read-edn (.toPath side)) (catch Throwable _ nil))]
               :when (and m (not (owner-alive? (:owner m))))]
           (do (try (if (.isFile (io/file (:work m)))
                      (finish-follow! (.getName w) (assoc m :sidecar (str side)))
                      (Files/deleteIfExists (.toPath side)))
                    (catch Throwable _ nil))
               (:answer m))))))

(defn- follow!
  [ws e source kind target since-i pred session emit! cancelled? ctx now]
  (let [id (subs (str (java.util.UUID/randomUUID)) 0 8)
        dir (.toFile (follow-dir ws))
        _ (.mkdirs dir)
        answer (.toFile (new-answer-file! ws session target))
        filtered? (boolean pred)
        work (if filtered? (io/file dir (str id ".log")) answer)
        sidecar (io/file dir (str id ".edn"))
        src-kind (case kind "container" :podman "file" :file :aws)
        mk-meta (fn [suffix] {:source src-kind :op (str "logs.fetch " target " (follow)" suffix) :env (keyword (:name e))
                              :at now :content-type "text/plain"})
        side {:work (str work) :answer (str answer) :filtered? filtered? :target target
              :raw-meta (mk-meta "") :answer-meta (mk-meta (str " (filter " (:filter ctx) ")"))
              :owner (owner) :started now :sidecar (str sidecar)}
        _ (store/write-edn! (.toPath sidecar) side)
        opts (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND])
        raw-out (Files/newOutputStream (.toPath work) opts)
        ans-out (when filtered? (Files/newOutputStream (.toPath answer) opts))
        n (volatile! 0)
        kept (volatile! 0)
        write! (fn [out ^String l] (.write out (.getBytes (str l "\n") "UTF-8")) (.flush out))
        q (LinkedBlockingQueue.)
        stop (logs/start-source ctx source 0 since-i nil q)]
    (try
      (logs/drain! q Long/MAX_VALUE cancelled? (if pred [pred] [])
                   (fn [l] (when ans-out (write! ans-out l))
                     (vswap! kept inc)
                     (when emit! (emit! {:event "logs.line" :target target :n (vswap! n inc) :text l :at (str (Instant/now))})))
                   (str "check the log target " target " in environment " (:name e))
                   {:raw! (fn [l] (write! raw-out l))
                    :gap! (fn [l] (when emit! (emit! {:event "logs.line" :target target :n (vswap! n inc) :text l :at (str (Instant/now)) :gap true})))
                    :collect? false})
      (finally
        (stop)
        (.close raw-out)
        (when ans-out (.close ans-out))))
    (let [{:keys [answer] :as r} (finish-follow! ws side)]
      {:path (str (.toAbsolutePath (.toPath (io/file (:answer side))))) :lines @kept :bytes (:bytes answer) :sha (:sha answer)
       :target target :source kind :follow true})))

(declare fetch-window!)

(defn fetch!
  [ws env-name target & {:keys [session since for filter emit! cancelled? follow]}]
  (let [e (env/load ws env-name)
        now (store/now)
        source (if (str/starts-with? (str target) "/")
                 {:file target}
                 (logs/resolve-target-source (fn [k] (env/target e (keyword k))) target (:name e)))
        kind (cond (:container source) "container" (:file source) "file" :else "cloudwatch")
        since-i (some-> since (parse-since now))
        since-i (or since-i (when (:cloudwatch source) (.minus ^Instant now (Duration/ofMinutes 15))))
        follow? (boolean (or follow (= "until-cancelled" (some-> for str str/trim str/lower-case))))
        for (if follow? nil for)
        for-ms (when for
                 (try (let [ms (expr/parse-duration for)]
                        (if (neg? ms) (throw (ex-info "negative" {})) ms))
                      (catch Exception _ (bad (str "bad for " (pr-str for))))))
        listening? (boolean (and for-ms (pos? for-ms)))
        file-path (some-> (:file source) logs/->path)
        _ (when (and file-path (not listening?)
                     (not (java.nio.file.Files/isRegularFile file-path (make-array java.nio.file.LinkOption 0))))
            (throw (ex-info (str "not a file: " (:file source))
                            {:type :logs/not-a-file :target target
                             :hint "give the path of an existing log file (a directory or a missing path is refused unless for listens for it)"})))
        pred (some-> filter logs/parse-filter)
        ctx {:ws ws :env e :session session :filter filter}
        n (volatile! 0)
        line! (if emit!
                (fn [l] (emit! {:event "logs.line" :target target :n (vswap! n inc) :text l :at (str (Instant/now))}))
                (fn [_]))
        gap! (if emit!
               (fn [l] (emit! {:event "logs.line" :target target :n (vswap! n inc) :text l :at (str (Instant/now)) :gap true}))
               (fn [_]))
        ]
    (if follow?
      (follow! ws e source kind target (follow-start since-i kind now) pred session emit! (or cancelled? (constantly false)) ctx now)
      (fetch-window! ws e source kind target since-i pred session filter line! gap! cancelled? ctx now file-path listening? for-ms))))

(defn- fetch-window!
  [ws e source kind target since-i pred session filter line! gap! cancelled? ctx now file-path listening? for-ms]
  (let [file-bytes (when file-path
                     ;; a file is persisted as it is (CRLF included); `lines` is only a view of it
                     (when listening? (Thread/sleep (long for-ms)))
                     (if (java.nio.file.Files/isRegularFile file-path (make-array java.nio.file.LinkOption 0))
                       (java.nio.file.Files/readAllBytes file-path)
                       (byte-array 0)))
        lines (cond
                file-bytes (let [parts (str/split (String. ^bytes file-bytes "UTF-8") #"\n" -1)
                                 parts (if (= "" (last parts)) (butlast parts) parts)]
                             (mapv #(str/replace % #"\r$" "") parts))
                listening? (let [q (LinkedBlockingQueue.)
                                 stop (logs/start-source ctx source 0 since-i nil q)]
                             (try (:raw (logs/drain! q (+ (System/currentTimeMillis) for-ms) (or cancelled? (constantly false)) (if pred [pred] []) line!
                                                     (str "check the log target " target " in environment " (:name e))
                                                     {:gap! gap!}))
                                  (finally (stop))))
                :else (logs/snapshot ctx source since-i nil))
        _ (when (and file-bytes listening?)   ; a file is read after the window: its lines go out then
            (let [ls (logs/group-entries lines)]
              (doseq [en ls :when (logs/entry-matches? (if pred [pred] []) en) l en] (line! l))))
        text (fn [ls] (if (seq ls) (str (str/join "\n" ls) "\n") ""))
        put! (fn [bs op-suffix]
               (store/raw-put! ws bs
                               {:source (case kind "container" :podman "file" :file :aws)
                                :op (str "logs.fetch " target op-suffix) :env (keyword (:name e)) :at now
                                :content-type "text/plain"}))
        raw (put! (or file-bytes (.getBytes ^String (text lines) "UTF-8")) "")
        kept (if pred (vec (mapcat identity (clojure.core/filter #(pred (str/join "\n" %)) (logs/group-entries lines)))) lines)
        answer (if pred (put! (.getBytes ^String (text kept) "UTF-8") (str " (filter " filter ")")) raw)
        path (link-answer! ws session target (:sha answer))]
    {:path (str path) :lines (count kept) :bytes (:bytes answer) :sha (:sha answer)
     :target target :source kind}))
