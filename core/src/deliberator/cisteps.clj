(ns deliberator.cisteps
  "A CI job and its steps as text, pure (piece 01 of the cicd-aws pile). Nothing here reads a file or talks to a host:
  the callers (`deliberator.ci`, `deliberator.cikept`) hand in the job log's lines and the steps the CI system reported.

  The shape of a step is the contract of `ci.jobs` whatever ran it (a GitHub run, a local run): `{:number :name :status :conclusion
  :started-at :completed-at :duration-s}`; `status` is queued|in_progress|completed, `conclusion` nil until completed.

  Cutting a job log into its steps (`cut`). Measured on GitHub Actions (hosted and self-hosted): the run's log archive has
  NO per-step files, only the whole job; the job log is cut by markers. A main step starts at `##[group]Run <cmd>` (the text is the
  command or the action, not the step's name), a post step at `Post job cleanup.`, `Complete job` at `Cleaning up orphan processes`,
  `Set up job` is everything before the first main marker. The API's step numbers have gaps, so steps are aligned to markers by ORDER
  (skipped steps have no lines). When the marker counts do not match the steps (a composite action prints its own `##[group]Run`)
  the cut falls back to each step's started/completed window (second resolution; boundary lines may appear in two steps) and every
  step of that job says `:exact false`: `exact-note` is the first line of such a step's file."
  (:require [clojure.string :as str])
  (:import (java.time Instant)))

(def ts-re #"^\uFEFF?(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z) ?(.*)$")

(defn- body [line] (if-let [m (re-matches ts-re line)] (nth m 2) line))

(defn- sec [line]
  (when-let [m (re-matches ts-re line)] (.getEpochSecond (Instant/parse (nth m 1)))))

(defn- parse-sec [s] (when (seq s) (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil))))

(defn duration-s
  "Seconds between two ISO instants (strings), nil when one is missing."
  [started completed]
  (let [a (parse-sec started) b (parse-sec completed)] (when (and a b) (max 0 (- b a)))))

(defn kind
  "A step's role in the log: :setup, :main, :post or :complete (by the names GitHub gives them)."
  [{:keys [name]}]
  (let [n (str name)]
    (cond (= n "Set up job") :setup
          (= n "Complete job") :complete
          (str/starts-with? n "Post ") :post
          :else :main)))

(defn has-log?
  "A step that ran has lines in the job log (a skipped or not yet started one has none)."
  [s]
  (and (= "completed" (:status s)) (not= "skipped" (:conclusion s))))

(def exact-note
  "# deliberator: this step's log was cut by time (the job log's markers did not match the steps), not exact: boundary lines may be missing or doubled")

(defn- by-time [lines steps]
  (let [secs (vec (let [last-sec (volatile! nil)]
                    (map (fn [l] (let [s (or (sec l) @last-sec)] (vreset! last-sec s) s)) lines)))]
    (into {}
          (map (fn [s]
                 (let [a (parse-sec (:started-at s)) b (parse-sec (:completed-at s))]
                   [(:number s)
                    {:lines (if (and a b) (vec (keep-indexed (fn [i l] (let [x (nth secs i)] (when (and x (<= a x b)) l))) lines)) [])
                     :exact false :cut :time}])))
          (filter has-log? steps))))

(defn cut
  "The job log's `lines` (a vector of strings) cut for `steps` (ordered by number).
  -> `{step-number {:lines [..] :exact bool :cut :markers|:time}}` for every step that has a log."
  [lines steps]
  (let [lines (vec lines)
        idx (fn [pred] (vec (keep-indexed (fn [i l] (when (pred (body l)) i)) lines)))
        runs (idx #(str/starts-with? % "##[group]Run "))
        posts (idx #(= % "Post job cleanup."))
        orphan (first (idx #(str/starts-with? % "Cleaning up orphan processes")))
        active (filter has-log? steps)
        of (fn [k] (filterv #(= k (kind %)) active))
        mains (of :main) pst (of :post) setup (first (of :setup)) done (first (of :complete))
        ok? (and (= (count runs) (count mains)) (= (count posts) (count pst))
                 (or (nil? done) orphan) (<= (count (of :setup)) 1) (<= (count (of :complete)) 1))]
    (if-not ok?
      (by-time lines steps)
      (let [starts (vec (concat (when setup [[(:number setup) 0]])
                                (map (fn [s i] [(:number s) i]) mains runs)
                                (map (fn [s i] [(:number s) i]) pst posts)
                                (when done [[(:number done) orphan]])))
            ends (concat (map second (rest starts)) [(count lines)])]
        (into {}
              (map (fn [[n from] to]
                     [n {:lines (subvec lines from (max from to)) :exact true :cut :markers}])
                   starts ends))))))

(defn select-step
  "The step of `steps` that `sel` (a number, a numeric string, or a name) names; nil when none, `:ambiguous` when a name part matches several."
  [steps sel]
  (let [s (str/trim (str sel))]
    (or (when (re-matches #"\d+" s) (first (filter #(= (parse-long s) (:number %)) steps)))
        (let [exact (filter #(= (str/lower-case s) (str/lower-case (str (:name %)))) steps)
              part (filter #(str/includes? (str/lower-case (str (:name %))) (str/lower-case s)) steps)]
          (cond (= 1 (count exact)) (first exact)
                (> (count exact) 1) (first exact)
                (= 1 (count part)) (first part)
                (> (count part) 1) :ambiguous)))))

(defn slug [s] (let [x (str/replace (str s) #"[^A-Za-z0-9._-]+" "-")] (subs x 0 (min 60 (count x)))))

(defn fmt-duration
  "`12s`, `3m 05s`, `1h 02m`."
  [s]
  (when s
    (cond (< s 60) (str s "s")
          (< s 3600) (format "%dm %02ds" (quot s 60) (mod s 60))
          :else (format "%dh %02dm" (quot s 3600) (mod (quot s 60) 60)))))

(defn result [{:keys [conclusion status]}] (or conclusion status))

(defn jobs-text
  "The text view of a `ci.jobs` answer: a header, one line per job, one indented line per step."
  [{:keys [repo run attempt run-row final source kept-at fetched-at jobs]}]
  (str "# jobs of " repo " run " run " (#" (:number run-row) ", attempt " attempt "): " (result run-row)
       ", " (:event run-row) " " (:branch run-row) " " (subs (str (:sha run-row)) 0 (min 7 (count (str (:sha run-row))))) " " (pr-str (:title run-row)) "\n"
       "# " (if (= :kept source) (str "kept, fetched " kept-at) (str "fetched " fetched-at (when-not final ", not final: the run is not finished, nothing is kept"))) "\n"
       (apply str
              (for [j jobs]
                (str "job " (:id j) " " (:name j) " " (result j) (some->> (:duration-s j) fmt-duration (str " ")) "\n"
                     (apply str (for [s (:steps j)]
                                  (str "  step " (:number s) " " (:name s) " " (result s) (some->> (:duration-s s) fmt-duration (str " "))
                                       (when (:log s) (str " log " (:lines s) " lines" (when (false? (:exact s)) " (cut by time, not exact)")))
                                       "\n"))))))))
