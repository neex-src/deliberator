(ns deliberator.cikept
  "Kept CI runs (piece 01): the jobs and logs of a FINISHED run never change, so they are fetched once and kept at
  WORKSPACE level (never under a session), served again with no network and no token, and outliving the CI system's own log expiry.

  Layout: `<workspace>/ci/<origin>/<repo-key>/<run>-<attempt>/` where `origin` names what ran it (`github` today; a run of another
  origin, e.g. a local run, keeps its own `origin` directory, nothing here knows GitHub):
    `run.edn`        the manifest: `{:origin :repo :run :attempt :kept-at :run-row :jobs [..] :raw {..} :logs {job-id {:state :kept|:none|:gone
                     :lines :bytes :sha :steps {n {:lines :exact :cut}}}}}`
    `jobs.json`      the raw response of the jobs call (same bytes as `raw/`)
    `jobs.txt`       the text view
    `<job-id>.log`   the whole job log as text
    `<job-id>/<NN>-<slug>.log`  one step's lines; a step cut by time has `cisteps/exact-note` as its first line.
  A run in progress is never written here. Nothing in this namespace talks to a host."
  (:require [clojure.string :as str]
            [deliberator.cisteps :as cisteps]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path)))

(defn- exists? [^Path p] (Files/exists p (make-array LinkOption 0)))

(defn repo-key [slug] (str/replace (str slug) #"[^A-Za-z0-9._-]+" "-"))

(defn dir ^Path [ws origin slug run attempt]
  (store/path ws "ci" origin (repo-key slug) (str run "-" attempt)))

(defn- attempts [ws origin slug run]
  (let [^Path root (store/path ws "ci" origin (repo-key slug))]
    (when (exists? root)
      (with-open [s (Files/newDirectoryStream root)]
        (->> (iterator-seq (.iterator s))
             (keep (fn [^Path p] (let [n (str (.getFileName p))]
                                   (when-let [[_ r a] (re-matches #"(\d+)-(\d+)" n)]
                                     (when (= r (str run)) (parse-long a))))))
             sort vec)))))

(defn find-run
  "The manifest of a kept run (`attempt` nil: the newest kept one) with `:dir`, nil when none is kept."
  [ws origin slug run attempt]
  (let [a (or (some-> attempt str parse-long) (last (attempts ws origin slug run)))]
    (when a
      (let [d (dir ws origin slug run a)]
        (when-let [m (store/read-edn (.resolve d "run.edn"))]
          (assoc m :dir (str d)))))))

(defn- bytes-of ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- at ^Path [m & parts] (Path/of (str (:dir m)) (into-array String (map str parts))))

(defn write-manifest! [m]
  (store/write-edn! (at m "run.edn") (dissoc m :dir))
  m)

(defn keep-run!
  "Writes a finished run: manifest, raw jobs response, text view. `m` has `:origin :repo :run :attempt :run-row :jobs :raw`; `jobs-bytes` and
  `text` are the files' contents. -> the manifest with `:dir` and `:kept-at`."
  [ws m ^bytes jobs-bytes text]
  (let [d (dir ws (:origin m) (:repo m) (:run m) (:attempt m))
        m (assoc m :dir (str d) :kept-at (or (:kept-at m) (store/now)) :logs (or (:logs m) {}))]
    (store/write-bytes-atomic! (.resolve d "jobs.json") jobs-bytes)
    (store/write-bytes-atomic! (.resolve d "jobs.txt") (bytes-of text))
    (write-manifest! m)))

(defn job-log-path ^Path [m job-id] (at m (str job-id ".log")))

(defn step-path ^Path [m job-id {:keys [number name]}]
  (at m (str job-id) (format "%02d-%s.log" (long number) (cisteps/slug name))))

(defn write-job-log!
  "Keeps a job's log: the whole text, every step's lines (`cuts` from `cisteps/cut`, step-number -> {:lines :exact :cut}) and the
  manifest entry. -> the manifest."
  [m job ^String text sha cuts]
  (let [id (:id job)
        _ (store/write-bytes-atomic! (job-log-path m id) (bytes-of text))
        steps (into {} (for [s (:steps job) :let [c (get cuts (:number s))] :when c]
                         (do (store/write-bytes-atomic! (step-path m id s)
                                                        (bytes-of (str (when-not (:exact c) (str cisteps/exact-note "\n"))
                                                                       (str/join "\n" (:lines c)) (when (seq (:lines c)) "\n"))))
                             [(:number s) (select-keys (assoc c :lines (count (:lines c))) [:lines :exact :cut])])))]
    (write-manifest! (assoc-in m [:logs id] {:state :kept :lines (count (str/split-lines text)) :bytes (count (bytes-of text))
                                             :sha sha :steps steps}))))

(defn mark-log!
  "A job with no log to keep: `:none` (it never ran) or `:gone` (the CI system dropped it). -> the manifest."
  [m job-id state]
  (write-manifest! (assoc-in m [:logs job-id] {:state state})))

(defn log-state [m job-id] (get-in m [:logs job-id :state]))

(defn enrich
  "The manifest's jobs with `:log` (the kept job log's path) and each step's `:log :lines :exact` where a file is kept."
  [m]
  (mapv (fn [j]
          (let [l (get-in m [:logs (:id j)])]
            (assoc j :log (when (= :kept (:state l)) (str (job-log-path m (:id j))))
                   :steps (mapv (fn [s]
                                  (let [c (get-in l [:steps (:number s)])]
                                    (merge {:log nil :lines nil :exact nil} s
                                           (when c {:log (str (step-path m (:id j) s)) :lines (:lines c) :exact (:exact c)}))))
                                (:steps j)))))
        (:jobs m)))

(defn complete?
  "Every job of the kept run has its log state recorded."
  [m]
  (every? #(log-state m (:id %)) (:jobs m)))
