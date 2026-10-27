(ns deliberator.ci
  "GitHub Actions as text (M5.2, M5.3). Everything goes through `deliberator.github` (read-only GETs, every response raw in
  `raw/` first, the token never persisted); what lands under `sessions/<k>/ci/` is derived from those blobs:

    `(runs! ws {:repo :workflow :branch :status :limit :head-sha :session})`  -> the runs of a workflow (or of the repository) as
        `runs-<owner>-<repo>-<workflow>-<ts>.txt`, one line per run; `{:path :count :total :rows :repo :workflow :auth}`.
    `(log! ws {:repo :run :job :session})`  -> the log of one job via the API (`GET .../jobs/<id>/logs`, a 302 to a signed
        text blob; no zip): `<owner>-<repo>-<run>-<job>-<jobid>.log`, a hard link to the raw blob when the bytes are already
        plain text, else the text with the BOM dropped and CRLF as LF (Windows runners). Without `job`: the run's first failed
        job. `{:path :lines :bytes :sha :job :run :conclusion :raw}`.
    `(tail! ws {:repo :run :job :session :emit! :cancelled? :interval-ms})`  -> follows a job as `logs.line` events with the
        machinery of `logs.fetch` (`logfetch/follow-run!`: one session file, appended as lines arrive, adopted as a raw blob at
        the end). GitHub publishes a job's log only once the JOB has completed (measured: a running job's log
        redirect answers 404 BlobNotFound), so while it runs the lines are `[deliberator] step <n> <name>: <status>` transitions
        polled from the job (each poll is a raw blob too), and when the job completes its whole log follows.
    `(diff! ws {:repo :run :job :against :normalise :session})`  -> the failed job's log against the last green run of the
        same workflow and the same job name (same branch first, then any branch, before the failed run; `against` names a run
        instead), both through `log!`, through `render/unified` after `normalise-line` has removed what legitimately differs
        between runs (timestamps, durations, rates, uuids, long hashes, runner ids and image versions). `a` is green, `b` is
        red: a `-` line is what the failed run did not do. Written to `diff-<owner>-<repo>-<red>-vs-<green>-<job>.diff`.

  `repo` is `owner/name`, a github URL, or the name of a recorded repository (its url); `run` is an id or a run/job URL
  (`https://github.com/o/r/actions/runs/123/job/456`, which also carries the repo and job)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.github :as gh]
            [deliberator.logfetch :as logfetch]
            [deliberator.repo :as repo]
            [deliberator.scenario.render :as render]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent LinkedBlockingQueue)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def stamp (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (.withZone ZoneOffset/UTC)))

;; arguments -----------------------------------------------------------------------------------------

(def slug-re #"([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+)")

(defn slug-of-url [s]
  (when-let [[_ o r] (re-find #"github\.com[:/]+([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+?)(?:\.git)?(?:/|$)" (str s))]
    (str o "/" r)))

(defn parse-repo
  "`owner/name` -> {:owner :name :slug}; accepts a github URL and the name of a recorded repository."
  [ws s]
  (let [s (str/trim (str s))
        slug (cond (re-matches slug-re s) s
                   (slug-of-url s) (slug-of-url s)
                   :else (when-let [u (:url (first (filter #(= s (:name %)) (repo/list ws))))] (slug-of-url u)))]
    (when-not slug
      (fail :ci/bad-repo (str "cannot tell the GitHub repository from " (pr-str s))
            :hint "owner/name, a github url, or the name of a repository from repo.list that was cloned from github"))
    (let [[o n] (str/split slug #"/")] {:owner o :name n :slug slug})))

(defn parse-run
  "A run id or a run/job URL -> {:run \"123\" :job \"456\"-or-nil :repo \"o/r\"-or-nil}."
  [s]
  (let [s (str/trim (str s))]
    (if-let [[_ o r run job] (re-find #"github\.com/([^/]+)/([^/]+)/actions/runs/(\d+)(?:/jobs?/(\d+))?" s)]
      {:run run :job job :repo (str o "/" r)}
      (if (re-matches #"\d+" s)
        {:run s}
        (fail :ci/bad-run (str "not a run id or run url: " (pr-str s)) :hint "a number, or https://github.com/o/r/actions/runs/123")))))

(defn- target [ws {:keys [repo run job]}]
  (let [u (parse-run run)
        slug (or repo (:repo u))
        _ (when-not slug (fail :ci/bad-repo "which repository? the run is a bare id" :hint "pass repo (owner/name), or a run url"))]
    (assoc (parse-repo ws slug) :run (:run u) :job (or job (:job u)))))

(defn ctx [ws session]
  (let [{:keys [token source]} (gh/resolve-token ws)]
    {:ws ws :session session :token token :auth source}))

(defn api! [c path & [query]]
  (let [r (gh/get! c (gh/api-url (:ws c) path query))]
    (if (<= 200 (:status r) 299)
      r
      (gh/error-for r (str "GET " path) (:auth c)))))

(defn json-of [{:keys [body]}] (json/read-str (String. ^bytes body StandardCharsets/UTF_8)))

;; files under sessions/<k>/ci/ -------------------------------------------------------------------------

(defn ci-dir ^Path [ws sess]
  (let [d (.resolve (session/dir ws sess) "ci")]
    (Files/createDirectories d (make-array FileAttribute 0))
    d))

(defn safe [s] (str/replace (str s) #"[^A-Za-z0-9._-]+" "-"))

(defn write-view!
  "Writes `bytes` as `<dir>/<base><ext>` (`-2`, `-3` when another content holds the name); -> the Path."
  [^Path dir base ext ^bytes bytes]
  (loop [n 1]
    (let [p (.resolve dir (str base (when (> n 1) (str "-" n)) ext))]
      (cond (not (Files/exists p (make-array LinkOption 0))) (do (store/write-bytes-atomic! p bytes) p)
            (java.util.Arrays/equals (Files/readAllBytes p) bytes) p
            :else (recur (inc n))))))

;; runs ------------------------------------------------------------------------------------------------------

(defn- workflow-id
  "The workflow reference of a runs URL: an id or file name as given, else the workflow whose name matches."
  [c {:keys [owner name]} wf]
  (if (or (re-matches #"\d+" wf) (re-find #"\.ya?ml$" wf))
    wf
    (let [wfs (get (json-of (api! c (str "/repos/" owner "/" name "/actions/workflows") {:per_page 100})) "workflows")
          hit (first (filter #(or (= (str/lower-case wf) (str/lower-case (get % "name" "")))
                                  (= (str/lower-case wf) (str/lower-case (last (str/split (get % "path" "") #"/"))))) wfs))]
      (if hit
        (str (get hit "id"))
        (fail :ci/no-workflow (str "no workflow named " (pr-str wf) " in " owner "/" name)
              :known (mapv #(get % "name") wfs) :hint "a workflow's name, its file name (ci.yml) or its id")))))

(defn- run-row [r]
  {:id (get r "id") :number (get r "run_number") :attempt (get r "run_attempt") :workflow (get r "name")
   :workflow-id (get r "workflow_id") :title (get r "display_title") :branch (get r "head_branch") :sha (get r "head_sha")
   :event (get r "event") :status (get r "status") :conclusion (get r "conclusion")
   :created-at (get r "created_at") :url (get r "html_url")})

(defn- row-line [{:keys [id number conclusion status branch sha created-at title]}]
  (format "%-12s #%-6s %-12s %-24s %s %s  %s" id number (or conclusion status) (or branch "") (subs (str sha) 0 (min 7 (count (str sha))))
          (or created-at "") (or title "")))

(defn runs!
  [ws {:keys [repo workflow branch status limit session head-sha]}]
  (let [t (parse-repo ws repo)
        c (ctx ws session)
        wf (when (seq workflow) (workflow-id c t workflow))
        path (str "/repos/" (:owner t) "/" (:name t) "/actions" (when wf (str "/workflows/" wf)) "/runs")
        resp (api! c path {:per_page (min 100 (max 1 (or limit 20))) :branch (not-empty branch) :status (not-empty status) :head_sha (not-empty head-sha)})
        doc (json-of resp)
        rows (mapv run-row (get doc "workflow_runs"))
        total (get doc "total_count")
        text (str "# runs of " (:slug t) (when wf (str ", workflow " workflow)) (when branch (str ", branch " branch))
                  (when status (str ", status " status))
                  (when (seq head-sha) (str ", head " (subs head-sha 0 (min 12 (count head-sha))))) ": " (count rows) " of " total
                  ", fetched " (store/now) ", auth " (:auth c) "\n"
                  "# id #number result branch sha created title\n"
                  (str/join "\n" (map row-line rows)) (when (seq rows) "\n"))
        p (write-view! (ci-dir ws session) (str "runs-" (safe (:slug t)) (when workflow (str "-" (safe workflow)))
                                                     (when (seq head-sha) (str "-" (subs head-sha 0 (min 12 (count head-sha))))) "-" (.format stamp (store/now)))
                       ".txt" (.getBytes ^String text StandardCharsets/UTF_8))]
    {:path (str p) :count (count rows) :total total :rows rows :repo (:slug t) :workflow workflow :auth (:auth c)
     :rate-remaining (:rate-remaining resp) :raw (:sha resp)}))

;; jobs and logs -----------------------------------------------------------------------------------------------

(defn- job-row [j]
  {:id (get j "id") :name (get j "name") :status (get j "status") :conclusion (get j "conclusion")
   :started-at (get j "started_at") :completed-at (get j "completed_at") :run-id (get j "run_id") :attempt (get j "run_attempt")
   :steps (mapv (fn [s] {:number (get s "number") :name (get s "name") :status (get s "status") :conclusion (get s "conclusion")})
                (get j "steps"))})

(defn- run-jobs [c t run]
  (let [r (api! c (str "/repos/" (:owner t) "/" (:name t) "/actions/runs/" run "/jobs") {:per_page 100 :filter "latest"})]
    {:jobs (mapv job-row (get (json-of r) "jobs")) :raw (:sha r)}))

(defn- pick-job
  "The job of `jobs` that `job` (an id, a name, or nil = the first failed one) names."
  [jobs job run]
  (let [names (mapv :name jobs)]
    (cond
      (empty? jobs) (fail :ci/no-jobs (str "run " run " has no jobs") :hint "a run that never started has none")
      (nil? job) (or (first (filter #(= "failure" (:conclusion %)) jobs))
                     (fail :ci/no-failed-job (str "run " run " has no failed job") :known names
                           :hint "name the job: dlb ci log <run> <job>"))
      :else (let [by-id (first (filter #(= (str (:id %)) (str job)) jobs))
                  exact (filter #(= (str/lower-case (:name %)) (str/lower-case (str job))) jobs)
                  part (filter #(str/includes? (str/lower-case (:name %)) (str/lower-case (str job))) jobs)]
              (or by-id
                  (when (= 1 (count exact)) (first exact))
                  (when (and (empty? exact) (= 1 (count part))) (first part))
                  (fail :ci/no-job (str "no single job " (pr-str job) " in run " run) :known names
                        :hint "the job's id or its full name"))))))

(defn log-text
  "The text of a raw job log: BOM dropped, CRLF as LF."
  ^String [^bytes raw]
  (-> (String. raw StandardCharsets/UTF_8)
      (str/replace #"^\uFEFF" "")
      (str/replace "\r\n" "\n")))

(defn- fetch-job-log
  "The raw job log -> `{:body :sha}`; the endpoint answers 302 to a signed blob that is fetched without the token."
  [c t job-id]
  (let [path (str "/repos/" (:owner t) "/" (:name t) "/actions/jobs/" job-id "/logs")
        r (gh/get! c (gh/api-url (:ws c) path nil))]
    (cond
      (and (#{301 302 303 307} (:status r)) (:location r))
      (let [b (gh/get! (assoc c :auth? false :accept "*/*") (:location r))]
        (cond (<= 200 (:status b) 299) (assoc b :api-sha (:sha r))
              (= 404 (:status b)) (fail :ci/log-not-available (str "the log of job " job-id " is not published yet")
                                        :hint "GitHub publishes a job's log when the job completes; ci.tail follows a running job")
              :else (fail :ci/http-error (str "the log blob answered " (:status b)))))
      (<= 200 (:status r) 299) (assoc r :api-sha (:sha r))
      :else (gh/error-for r (str "GET " path) (:auth c)))))

(defn log!
  [ws {:keys [session] :as args}]
  (let [t (target ws args)
        c (ctx ws session)
        {:keys [jobs raw]} (run-jobs c t (:run t))
        job (pick-job jobs (:job t) (:run t))
        _ (when (not= "completed" (:status job))
            (fail :ci/log-not-available (str "job " (:name job) " is " (:status job) "; its log is published when it completes")
                  :hint "dlb ci tail follows a running job"))
        {:keys [body sha]} (fetch-job-log c t (:id job))
        text (log-text body)
        bytes (.getBytes text StandardCharsets/UTF_8)
        same? (java.util.Arrays/equals ^bytes body bytes)
        base (str (safe (:slug t)) "-" (:run t) "-" (safe (:name job)) "-" (:id job))
        dir (ci-dir ws session)
        p (if same?
            (let [p (.resolve dir (str base ".log"))] (:path (store/link! ws sha p)))
            (write-view! dir base ".log" bytes))]
    {:path (str p) :lines (count (str/split-lines text)) :bytes (count body) :sha sha :repo (:slug t) :run (:run t)
     :job (select-keys job [:id :name :status :conclusion :started-at :completed-at]) :conclusion (:conclusion job)
     :other-failed (mapv :name (filter #(and (= "failure" (:conclusion %)) (not= (:id %) (:id job))) jobs))
     :raw {:jobs raw :log sha} :auth (:auth c)}))

;; tail ----------------------------------------------------------------------------------------------------------

(defn- job-source
  "`start!` for `follow-run!`: a thread polling the job every `interval-ms`."
  [c t job-id interval-ms]
  (fn [^LinkedBlockingQueue q]
    (let [stop? (atom false)
          end :deliberator.steps.logs/end
          th (Thread.
              ^Runnable
              (bound-fn []
                (try
                  (loop [seen {}]
                    (when-not @stop?
                      (let [j (job-row (json-of (api! c (str "/repos/" (:owner t) "/" (:name t) "/actions/jobs/" job-id))))
                            now (into {} (map (fn [s] [(:number s) [(:status s) (:conclusion s)]])) (:steps j))
                            changed (filter (fn [s] (not= (get seen (:number s)) (get now (:number s)))) (:steps j))]
                        (doseq [s changed]
                          (.put q (str "[deliberator] step " (:number s) " " (:name s) ": " (str/join " " (remove nil? (get now (:number s)))))))
                        (if (= "completed" (:status j))
                          (let [{:keys [body]} (fetch-job-log c t job-id)]
                            (.put q (str "[deliberator] job " (:name j) " completed: " (:conclusion j)))
                            (doseq [l (str/split-lines (log-text body))] (.put q l))
                            (.put q end))
                          (do (Thread/sleep (long interval-ms))
                              (recur now))))))
                  (catch InterruptedException _ nil)
                  (catch Throwable e (.put q e))))
              "deliberator-ci-tail")]
      (.setDaemon th true)
      (.start th)
      (fn [] (reset! stop? true) (.interrupt th)))))

(defn tail!
  [ws {:keys [session emit! cancelled? interval-ms] :as args}]
  (let [t (target ws args)
        c (ctx ws session)
        {:keys [jobs]} (run-jobs c t (:run t))
        job (pick-job jobs (:job t) (:run t))
        tgt (str "ci-" (safe (:slug t)) "-" (:run t) "-" (safe (:name job)))]
    (assoc (logfetch/follow-run! ws {:target tgt :session session :emit! emit! :cancelled? (or cancelled? (constantly false))
                                     :start! (job-source c t (:id job) (or interval-ms 5000))
                                     :src-kind :http :env-key nil :op-label (str "ci.tail " (:slug t) " run " (:run t) " job " (:id job))
                                     :kind "ci" :now (store/now) :sub "ci"
                                     :hint (str "check the run and the token (" (:auth c) ")")})
           :repo (:slug t) :run (:run t) :job (select-keys job [:id :name]))))

;; diff (M5.3) ---------------------------------------------------------------------------------------------------------

(def ^:private normalisers
  "[regex replacement] applied in order to every line of a CI log."
  [[#"^\uFEFF?\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z ?" ""]                       ; the runner's line timestamp
   [#"\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:[.,]\d+)?(?:Z|[+-]\d\d:?\d\d)?" "<ts>"]    ; instants inside a line
   [#"\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),? \d\d? \w{3} \d{4} \d\d:\d\d:\d\d(?: \w+)?" "<ts>"]
   [#"\b\d\d:\d\d:\d\d(?:[.,]\d+)?\b" "<time>"]
   [#"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b" "<uuid>"]
   [#"\b[0-9a-f]{40}\b|\b[0-9a-f]{64}\b" "<sha>"]
   [#"(?i)\b\d+(?:\.\d+)?\s?(?:[KMG]i?B|B)(?:s|ytes)?/s(?:ec)?\b" "<rate>"]            ; 5.1 MBs/sec
   [#"\b\d+m\s?\d+(?:\.\d+)?s\b" "<dur>"]                                         ; 1m 23s
   [#"(?i)\b\d+(?:\.\d+)?\s?(?:ms|millis|milliseconds|secs?|seconds?|minutes?|mins?)\b" "<dur>"]
   [#"\b\d+(?:\.\d+)?s\b" "<dur>"]
   [#"(?i)(runner name:? ?['\"]?)[^'\"\n]+" "$1<runner>"]
   [#"GitHub[ -]Actions[ -]\d+" "GitHub Actions <id>"]
   [#"(?i)(runner (?:group )?id:? ?)\d+" "$1<id>"]
   [#"^(\s*)(Version|Commit|Build Date|Image Release|Included Software|Azure Region):.*$" "$1$2: <varies>"]    ; the runner image header
   [#"(Current runner version: ')[^']*'" "$1<varies>'"]])

(defn normalise-line
  "A log line with what changes from run to run removed (see the namespace doc)."
  [line]
  (reduce (fn [l [re rep]] (str/replace l re rep)) line normalisers))

(defn- find-green
  "The latest run before `red` of its workflow in which the job named `job-name` succeeded:
  -> {:run id :job job-row :scanned n}. Same branch first, then any; `against` forces a run."
  [c t red job-name against]
  (let [wf (:workflow-id red)
        before (str "<" (:created-at red))
        runs (fn [branch]
               (map run-row (get (json-of (api! c (str "/repos/" (:owner t) "/" (:name t) "/actions/workflows/" wf "/runs")
                                                {:per_page 20 :status "success" :created before :branch branch}))
                                 "workflow_runs")))
        cands (distinct (concat (runs (:branch red)) (runs nil)))
        scanned (volatile! 0)]
    (or (if against
          (let [{:keys [jobs]} (run-jobs c t against)
                j (first (filter #(= job-name (:name %)) jobs))]
            (when (and j (= "success" (:conclusion j))) {:run (str against) :job j :scanned 0}))
          (some (fn [r]
                  (when (< @scanned 10)
                    (vswap! scanned inc)
                    (let [{:keys [jobs]} (run-jobs c t (:id r))
                          j (first (filter #(= job-name (:name %)) jobs))]
                      (when (and j (= "success" (:conclusion j))) {:run (str (:id r)) :job j :scanned @scanned}))))
                (remove #(= (str (:id %)) (str (:id red))) cands)))
        (fail :ci/no-green-run (str "no earlier successful run of the workflow has a green job " (pr-str job-name))
              :hint "pass against (a run id) to compare with a particular run"))))

(defn diff!
  [ws {:keys [session normalise against] :or {normalise true} :as args}]
  (let [t (target ws args)
        c (ctx ws session)
        red-run (run-row (json-of (api! c (str "/repos/" (:owner t) "/" (:name t) "/actions/runs/" (:run t)))))
        {:keys [jobs]} (run-jobs c t (:run t))
        job (pick-job jobs (:job t) (:run t))
        _ (when-not (= "failure" (:conclusion job))
            (fail :ci/not-failed (str "job " (:name job) " of run " (:run t) " is " (or (:conclusion job) (:status job)) ", not failed")
                  :hint "ci.diff compares a failed job with the last green one"))
        green (find-green c t red-run (:name job) (some-> against str))
        red-log (log! ws {:repo (:slug t) :run (:run t) :job (str (:id job)) :session session})
        green-log (log! ws {:repo (:slug t) :run (:run green) :job (str (:id (:job green))) :session session})
        lines #(let [ls (str/split-lines (slurp %))] (if normalise (mapv normalise-line ls) (vec ls)))
        a (lines (:path green-log))
        b (lines (:path red-log))
        la (str "green " (:slug t) " run " (:run green) " " (:name job))
        lb (str "red " (:slug t) " run " (:run t) " " (:name job))
        d (render/unified a b la lb)
        base (str "diff-" (safe (:slug t)) "-" (:run t) "-vs-" (:run green) "-" (safe (:name job)))
        p (write-view! (ci-dir ws session) base ".diff"
                       (.getBytes ^String (if (str/blank? d) (str "# the logs are equal" (when normalise " after normalisation") "\n") d) StandardCharsets/UTF_8))]
    {:path (str p) :diff (let [ls (str/split-lines d)]
                           (if (> (count ls) 300)
                             (str (str/join "\n" (take 300 ls)) "\n... " (- (count ls) 300) " more lines in " p "\n")
                             d))
     :truncated (> (count (str/split-lines d)) 300) :equal (str/blank? d) :normalised (boolean normalise) :repo (:slug t)
     :red {:run (:run t) :job (:name job) :job-id (:id job) :log (:path red-log) :lines (count b)}
     :green {:run (:run green) :job (:name job) :job-id (:id (:job green)) :log (:path green-log) :lines (count a) :scanned (:scanned green)}
     :added (count (filter #(str/starts-with? % "+") (rest (rest (str/split-lines d)))))
     :removed (count (filter #(str/starts-with? % "-") (rest (rest (str/split-lines d)))))}))
