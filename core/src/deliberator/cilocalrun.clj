(ns deliberator.cilocalrun
  "A local run is a run (piece 03): the record of one `act` run at WORKSPACE level, in the layout of piece 01's kept runs
  (`cikept`), with origin `local`: `<workspace>/ci/local/<repo-key>/<n>-1/{run.edn, jobs.txt, act.jsonl, run.log, <job-id>.log,
  <job-id>/<NN>-<slug>.log}`. It is written WHILE the run goes (the manifest says `:final false` until the end), so `ci.jobs` of a local run shows its steps
  moving, and `ci.log` opens the log of a step that has ended. The run id is `local-<n>` (the tag of piece 04's task-definition revisions); the number is
  claimed by creating its directory (`Files/createDirectory` is atomic: two runs started in the same second get two numbers).

  The structure comes from act itself (`act --json`, one object per line, kept raw in `act.jsonl` before anything reads it): a step starts at
  `⭐ Run <Stage> <name>` and ends with `stepResult`, a job with `jobResult`; `apply-record` is the pure reducer over those records. Step names are
  made to read like GitHub's: `Run <command's first line>` for an unnamed step, `Run <action>` for a `uses:` step, `Post ...`, `Set up job`, `Complete job`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.cikept :as cikept]
            [deliberator.cisteps :as cisteps]
            [deliberator.git :as git]
            [deliberator.repo :as repo]
            [deliberator.store :as store])
  (:import (java.nio.file FileAlreadyExistsException Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.time OffsetDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def origin "local")

;; repository key ------------------------------------------------------------------------------------------

(defn slug-of-url [s]
  (when-let [[_ o r] (re-find #"github\.com[:/]+([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+?)(?:\.git)?(?:/|$)" (str s))] (str o "/" r)))

(defn key-of-slug [slug] (cikept/repo-key slug))

(defn- remote-slug [dir]
  (let [r (try (git/run dir ["config" "--get-regexp" "^remote\\..*\\.url$"]) (catch Exception _ nil))]
    (when (and r (zero? (:exit r)))
      (some #(slug-of-url (second (str/split % #"\s+" 2))) (str/split-lines (:out r))))))

(defn repo-key-of-dir
  "The key of the repository a local run belongs to: the GitHub slug of the checkout (its remotes' raw urls, so local runs sit next to the GitHub ones of
  the same repository), else the recorded repository's name, else the directory's name."
  [ws repo dir]
  (let [r (str repo)
        e (when-not (or (str/blank? r) (str/starts-with? r "/")) (try (repo/find ws r) (catch Exception _ nil)))]
    (or (when (re-matches #"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+" r) r)
        (some-> (:url e) slug-of-url)
        (remote-slug dir)
        (:name e)
        (.getName (java.io.File. (str dir))))))

(defn key-of-repo
  "The key of the repository `repo` (what `ci.runs` was given): a slug or GitHub url, a recorded repository (its url's slug, else its name), else the text."
  [ws repo]
  (let [r (str/trim (str repo))
        e (try (repo/find ws r) (catch Exception _ nil))]
    (or (slug-of-url r) (when (re-matches #"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+" r) r)
        (some-> (:url e) slug-of-url)
        (when e (or (some-> (:path e) remote-slug) (:name e)))
        r)))

(defn id->n
  "`local-3` -> \"3\", nil for anything else."
  [id]
  (second (re-matches #"local-(\d+)" (str id))))

;; numbering ------------------------------------------------------------------------------------------------

(defn root ^Path [ws key] (store/path ws "ci" origin (cikept/repo-key key)))

(defn- numbers [^Path root]
  (if (Files/exists root (make-array LinkOption 0))
    (with-open [s (Files/newDirectoryStream root)]
      (vec (keep (fn [^Path p] (some-> (re-matches #"(\d+)-\d+" (str (.getFileName p))) second parse-long)) (iterator-seq (.iterator s)))))
    []))

(defn claim!
  "Claims the next run number of `key` by creating its directory: -> `{:n \"3\" :dir Path}`."
  [ws key]
  (let [r (root ws key)]
    (Files/createDirectories r (make-array FileAttribute 0))
    (loop [n (inc (reduce max 0 (numbers r)))]
      (let [d (.resolve r (str n "-1"))]
        (if (try (Files/createDirectory d (make-array FileAttribute 0)) true (catch FileAlreadyExistsException _ false))
          {:n (str n) :dir d}
          (recur (inc n)))))))

;; the reducer -----------------------------------------------------------------------------------------------

(def ^:private frac (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'") (.withZone ZoneOffset/UTC)))

(defn- odt [t] (when t (try (OffsetDateTime/parse (str t)) (catch Exception _ nil))))
(defn utc-s
  "act's local-time stamp as an ISO instant in UTC, whole seconds (GitHub's step times)."
  [t] (some-> (odt t) .toInstant (.truncatedTo ChronoUnit/SECONDS) str))
(defn- stamp [t] (if-let [o (odt t)] (.format frac (.toInstant o)) (.format frac (store/now))))

(defn- first-line [s] (first (str/split-lines (str s))))

(defn- sid [rec] (first (or (:stepID rec) (:stepid rec))))

(defn- kind [rec]
  (case (sid rec) "--setup-job" :setup "--complete-job" :complete
        (case (:stage rec) "Post" :post "Pre" :pre :main)))

(defn- wf-step
  "The workflow's step an act record belongs to: by its `id`, else by its position (act's stepID of an unnamed step without an id is the index)."
  [rec wf-job]
  (let [id (str (sid rec)) steps (vec (:steps wf-job))]
    (or (first (filter #(= id (:id %)) steps))
        (when (re-matches #"\d+" id) (get steps (parse-long id))))))

(defn- base-name [rec wf-job]
  (let [nm (str (:step rec)) steps (:steps wf-job) ws (wf-step rec wf-job)]
    (cond (and ws (:name ws)) (:name ws)
          (and ws (:uses ws)) (str "Run " (:uses ws))
          ws (str "Run " (first-line nm))
          (some #(= nm (:name %)) steps) nm
          (some #(= nm (:uses %)) steps) (str "Run " nm)
          (some #(= nm (:run %)) steps) (str "Run " (first-line nm))
          (str/includes? nm "\n") (str "Run " (first-line nm))
          :else nm)))

(defn display-name [rec wf-job]
  (case (kind rec)
    :setup "Set up job" :complete "Complete job"
    :post (str "Post " (base-name rec wf-job))
    :pre (str "Pre " (base-name rec wf-job))
    (base-name rec wf-job)))

(defn- step-key [rec] [(kind rec) (str (:step rec)) (str (sid rec))])

(defn- job-index [st id] (first (keep-indexed (fn [i j] (when (= id (:id j)) i)) (:jobs st))))

(defn- ensure-job [st wf rec]
  (let [id (:jobID rec)]
    (if (job-index st id)
      st
      (let [wj (first (filter #(= id (:id %)) (:jobs wf)))]
        (update st :jobs conj {:id id :name (or (:name wj) id) :status "in_progress" :conclusion nil
                               :started-at (utc-s (:time rec)) :completed-at nil :duration-s nil :url nil :runner "act" :steps []})))))

(defn- add-line [st id n line]
  (update-in st [:lines id n] (fnil conj []) line))

(defn- step-lines
  "The lines a record adds to its step's log (GitHub's shape: the runner's timestamp, a space, the text)."
  [rec]
  (let [ts (stamp (:time rec))]
    (cond
      (:raw_output rec) (map #(str ts " " %) (str/split (str/replace (str (:msg rec)) #"\n$" "") #"\n" -1))
      (and (:command rec) (:raw rec)) [(str ts " " (str/replace (str (:raw rec)) #"\n$" ""))]
      (and (= "error" (:level rec)) (:step rec)) [(str ts " ##[error]" (:msg rec))]
      :else [])))

(defn apply-record
  "The state after act's JSON record `rec`. `st` is `{:jobs [...] :lines {job-id {step-number [line]}}}`, `wf` the parsed workflow (names)."
  [st wf rec]
  (if-not (:jobID rec)
    st
    (let [st (ensure-job st wf rec)
          id (:jobID rec)
          t (utc-s (:time rec))
          ji (job-index st id)]
      (cond
        (:jobResult rec)
        (update-in st [:jobs ji] (fn [j] (assoc j :status "completed" :conclusion (:jobResult rec) :completed-at t
                                                :duration-s (cisteps/duration-s (:started-at j) t))))
        (nil? (:step rec)) st
        :else
        (let [key (step-key rec)
              steps (get-in st [:jobs ji :steps])
              si (first (keep-indexed (fn [i s] (when (= key (:key s)) i)) steps))
              starts? (str/starts-with? (str (:msg rec)) "⭐ Run ")
              result (:stepResult rec)
              wj (first (filter #(= id (:id %)) (:jobs wf)))
              st (if (and (nil? si) (or starts? result))
                   (update-in st [:jobs ji :steps] conj {:number (inc (count steps)) :name (display-name rec wj) :status "in_progress" :conclusion nil
                                                         :started-at t :completed-at nil :duration-s nil :key key})
                   st)
              si (or si (when (or starts? result) (dec (count (get-in st [:jobs ji :steps])))))]
          (if (nil? si)
            st
            (let [n (get-in st [:jobs ji :steps si :number])
                  st (reduce #(add-line %1 id n %2) st (step-lines rec))]
              (if result
                (update-in st [:jobs ji :steps si]
                           (fn [s] (assoc s :status "completed" :conclusion (if (= "skipped" result) "skipped" result) :completed-at t
                                          :duration-s (if-let [ns (:executionTime rec)] (Math/round (/ (double ns) 1e9)) (cisteps/duration-s (:started-at s) t)))))
                st))))))))

(defn finish-state
  "At the end: jobs of the workflow that never ran are `skipped` (a failed job stops the ones that need it); steps still open end with the job's result."
  [st wf cancelled?]
  (let [seen (set (map :id (:jobs st)))
        missing (for [j (:jobs wf) :when (and (not (seen (:id j))) (or (nil? (:only wf)) true))]
                  {:id (:id j) :name (:name j) :status "completed" :conclusion (if cancelled? "cancelled" "skipped") :started-at nil :completed-at nil
                   :duration-s nil :url nil :runner nil :steps []})
        close (fn [j] (if (= "completed" (:status j)) j
                          (-> j (assoc :status "completed" :conclusion (if cancelled? "cancelled" "failure"))
                              (update :steps (fn [ss] (mapv #(if (= "in_progress" (:status %)) (assoc % :status "completed" :conclusion (if cancelled? "cancelled" "failure")) %) ss))))))]
    (-> st (update :jobs #(mapv close %)) (update :jobs into missing))))

(defn run-conclusion [jobs]
  (let [cs (map :conclusion jobs)]
    (cond (some #{"cancelled"} cs) "cancelled" (some #{"failure"} cs) "failure" :else "success")))

;; the record on disk ------------------------------------------------------------------------------------------

(defn- strip [j] (-> j (update :steps (fn [ss] (mapv #(dissoc % :key) ss)))))

(defn persist!
  "Writes the run as it is now: manifest (`:final` per the argument), `jobs.txt`, every job's log file and every step's lines. `m` is the manifest base
  (`:origin :repo :run :attempt :dir :run-row`); -> the manifest."
  [m st final?]
  (let [jobs (mapv strip (:jobs st))
        logs (into {}
                   (for [j jobs :let [ls (get-in st [:lines (:id j)])
                                      all (mapcat (fn [s] (get ls (:number s))) (:steps j))]]
                     [(:id j) {:state (if (= "completed" (:status j)) :kept :partial) :lines (count all)
                               :bytes (reduce + (map #(inc (count %)) all)) :sha nil
                               :steps (into {} (for [s (:steps j) :let [c (get ls (:number s))] :when (seq c)]
                                                 [(:number s) {:lines (count c) :exact true :cut :act}]))}]))
        m (assoc m :jobs jobs :logs logs :final (boolean final?) :kept-at (or (:kept-at m) (store/now)))]
    (doseq [j jobs :let [ls (get-in st [:lines (:id j)])]]
      (doseq [s (:steps j) :let [c (get ls (:number s))] :when (seq c)]
        (store/write-bytes-atomic! (cikept/step-path m (:id j) s) (.getBytes (str (str/join "\n" c) "\n") "UTF-8")))
      (let [all (mapcat (fn [s] (get ls (:number s))) (:steps j))]
        (store/write-bytes-atomic! (cikept/job-log-path m (:id j)) (.getBytes (str (str/join "\n" all) (when (seq all) "\n")) "UTF-8"))))
    (store/write-bytes-atomic! (.resolve (Path/of (str (:dir m)) (make-array String 0)) "jobs.txt")
                               (.getBytes ^String (cisteps/jobs-text {:repo (:repo m) :run (:run m) :attempt 1 :run-row (:run-row m) :final final?
                                                                      :source :kept :kept-at (:kept-at m) :fetched-at (store/now) :jobs jobs}) "UTF-8"))
    (cikept/write-manifest! m)))

(defn run-rows
  "The run rows of the local runs of `key`, newest first."
  [ws key]
  (let [r (root ws key)]
    (->> (numbers r)
         (keep (fn [n] (when-let [m (cikept/find-run ws origin key (str n) nil)] (assoc (:run-row m) :final (:final m)))))
         (sort-by #(parse-long (str (:number %))) >)
         vec)))

(defn read-jsonl-line [l] (try (json/read-str l :key-fn keyword) (catch Exception _ nil)))
