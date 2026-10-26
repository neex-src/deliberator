(ns deliberator.scenario.render
  "Text rendering of runs, derived only from the run directory (`run.edn`, `steps/*`).

  `(result! run-dir) -> Path` writes `result.txt`; `(render-text run-dir & {:keys [normalise?]})` is the
  same text as a string. Layout, one section per step, a blank line between sections:

      ### <kind> [verb] [target] [name] · <status> · <elapsed> ms
      <output verbatim: display.txt if the kind wrote one, else the response file; no clipping>
      ERROR: <message>            (status error; a `hint:` line when the error has one)
      skipped: <reason>           (status skipped)
      ✓ status == 201
      ✗ lines >= 2 (was 1)
      ✓ until $.Item.status.S == ACKED (attempt 3)
      captured: id=ord-1, n=2     (only when something was captured)

      FAILED 2/5                  (failed checks / checks; only when a check failed)
      TOPOLOGY a=local b=external http://h:1   (only when the run recorded one: `run.edn :topology`)
      RUN <ok|failed|cancelled|error> steps <n> failed <m>   (m = steps with status failed or error)

  Statuses of a step: ok, failed (a check failed), error (the step threw), skipped (not run: a
  variable it needs never arrived, or the run was cancelled), cancelled (was running at cancel).

  `(diff a b) -> string`: unified diff (3 lines of context, `--- <run a>` / `+++ <run b>`) of the two
  runs' texts after normalising what legitimately changes between runs, empty when nothing else
  differs. Normalised: elapsed milliseconds; check values and captures whose declared fact type is
  :duration, :instant or :id (`<duration>` `<instant>` `<id>`); `(attempt n)`; everywhere in the
  text: the run directory path, the run id (hence step correlation ids), the values of volatile
  vars (`{{name}}`), values of :id/:instant facts of that step, and any UUID or ISO-8601 instant
  (`<uuid>` `<instant>`); epoch-second dates (`CreationDate`, `LastModificationDate`, `LastModifiedDate`) and `Version`
  numbers of AWS documents."
  (:require [clojure.string :as str]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)))

(defn- ->path ^Path [p] (if (instance? Path p) p (.toPath (java.io.File. (str p)))))

(defn- read-text [^Path p]
  (when (Files/isRegularFile p (make-array java.nio.file.LinkOption 0))
    (String. (Files/readAllBytes p) StandardCharsets/UTF_8)))

(def ^:private uuid-re #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
(def ^:private instant-re #"\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:[.,]\d+)?(?:Z|[+-]\d\d:?\d\d)?")

(defn- replace-all [s pairs]
  (reduce (fn [s [from to]] (if (str/blank? from) s (str/replace s from to))) s
          (sort-by (comp - count first) pairs)))

(defn- normaliser
  "A fn text -> text for one step of `run`, or identity when not normalising."
  [run run-dir step normalise?]
  (if-not normalise?
    identity
    (let [vol (set (:volatile run))
          var-pairs (for [[k v] (:vars run) :when (and (contains? vol k) (not (str/blank? v)))]
                      [v (str "{{" k "}}")])
          fact-pairs (for [[k v] (:facts step)
                           :let [t (get (:fact-types step) k)]
                           :when (and (#{:id :instant} t) (string? v) (>= (count v) 4))]
                       [v (str "<" k ">")])
          run-pairs [[(str run-dir) "<run-dir>"] [(:run-id run) "<run>"]]]
      (fn [text]
        (-> (replace-all text (concat run-pairs var-pairs fact-pairs))
            (str/replace uuid-re "<uuid>")
            ;; SQS hashes the attribute values, which carry the run's ids: it changes with them
            (str/replace #"(?<=\"MD5OfMessageAttributes\":\")[0-9a-f]{32}" "<md5>")
            ;; epoch-second dates and the version counter of AWS documents (Scheduler, SSM): a new run makes new ones
            (str/replace #"(?<=\"(?:CreationDate|LastModificationDate|LastModifiedDate)\":)[0-9.eE+]+" "<epoch>")
            (str/replace #"(?<=\"Version\":)\d+" "<n>")
            (str/replace instant-re "<instant>"))))))

(defn- show [v]
  (let [s (cond (nil? v) "<absent>" (instance? java.time.Instant v) (str v) :else (str v))
        s (str/replace s "\n" "⏎")]
    (if (> (count s) 200) (str (subs s 0 200) "…") s)))

(defn- check-line [norm normalise? {:keys [type expr ok was attempts fact-type reason]}]
  (let [volatile? (and normalise? (some? was) (#{:duration :instant :id} fact-type))
        was-s (if volatile? (str "<" (name fact-type) ">") (norm (show was)))
        att (when attempts (str "attempt " (if normalise? "<n>" attempts)))
        expr (norm (or expr ""))]
    (case type
      :capture (if ok (str "✓ capture " expr) (str "✗ capture " expr " (" (or reason "no value") ")"))
      :until (if ok
               (str "✓ until " expr " (" att ")")
               (str "✗ until " expr " (was " was-s ", " att ")"))
      (if ok (str "✓ " expr) (str "✗ " expr " (was " was-s ")")))))

(defn- section [run-dir run step normalise?]
  (let [norm (normaliser run run-dir step normalise?)
        {:keys [kind verb target name status elapsed error checks captures output]} step
        head (str "### " (str/join " " (remove str/blank? [kind verb (some-> target norm) name]))
                  " · " (clojure.core/name status)
                  (when (and elapsed (not (#{:skipped} status)))
                    (str " · " (if normalise? "<ms>" elapsed) " ms")))
        out (when output (read-text (.resolve (.resolve (.resolve run-dir "steps") ^String (:label step)) ^String output)))
        out (when-not (str/blank? out) (norm out))]
    (str/join
     "\n"
     (concat
      [head]
      (when out [(if (str/ends-with? out "\n") (subs out 0 (dec (count out))) out)])
      (when error
        (case status
          :cancelled ["cancelled"]
          :skipped [(str "skipped: " (norm (:message error)))]
          (cons (str "ERROR: " (norm (str (:message error))))
                (when (:hint error) [(str "hint: " (norm (str (:hint error))))]))))
      (map #(check-line norm normalise? %) checks)
      (when (seq captures)
        [(str "captured: " (str/join ", " (for [[k v] (sort-by key captures)]
                                            (str k "=" (norm (str v))))))])))))

(defn render-text
  [run-dir & {:keys [normalise?]}]
  (let [run-dir (->path run-dir)
        run (store/read-edn (.resolve run-dir "run.edn"))
        steps (:steps run)
        checks (mapcat :checks steps)
        failed-checks (count (remove :ok checks))
        failed-steps (count (filter (comp #{:failed :error} :status) steps))
        tail (concat
              (when-let [t (:services (:topology run))]
                [(str "TOPOLOGY " (str/join " " (for [[n m] t]
                                                 (str n "=" (name (:mode m)) (when (:url m) (str " " (:url m)))))))])
              (when (pos? failed-checks) [(str "FAILED " failed-checks "/" (count checks))])
              (when (:error run) [(str "ERROR: " (:message (:error run)))])
              [(str "RUN " (name (:status run)) " steps " (count steps) " failed " failed-steps)])]
    (str (apply str (for [s steps] (str (section run-dir run s normalise?) "\n\n")))
         (str/join "\n" tail) "\n")))

(defn result!
  "Writes `<run-dir>/result.txt`; returns its Path."
  [run-dir]
  (let [run-dir (->path run-dir)
        p (.resolve run-dir "result.txt")]
    (store/write-bytes-atomic! p (.getBytes ^String (render-text run-dir) StandardCharsets/UTF_8))
    p))

;; ---------------------------------------------------------------- diff

(defn- edits
  "Vector of [op line] with op :eq :del :ins."
  [a b]
  (let [a (vec a) b (vec b)
        pre (loop [i 0] (if (and (< i (count a)) (< i (count b)) (= (a i) (b i))) (recur (inc i)) i))
        suf (loop [i 0] (if (and (< i (- (count a) pre)) (< i (- (count b) pre))
                                 (= (a (- (count a) 1 i)) (b (- (count b) 1 i))))
                          (recur (inc i)) i))
        ma (subvec a pre (- (count a) suf))
        mb (subvec b pre (- (count b) suf))
        n (count ma) m (count mb)
        mid (if (> (* (inc n) (inc m)) 40000000)
              (concat (map #(vector :del %) ma) (map #(vector :ins %) mb))
              (let [w (inc m)
                    t (int-array (* (inc n) w))]
                (doseq [i (range (dec n) -1 -1) j (range (dec m) -1 -1)]
                  (aset t (+ (* i w) j)
                        (if (= (ma i) (mb j))
                          (inc (aget t (+ (* (inc i) w) (inc j))))
                          (max (aget t (+ (* (inc i) w) j)) (aget t (+ (* i w) (inc j)))))))
                (loop [i 0 j 0 out (transient [])]
                  (cond
                    (and (< i n) (< j m) (= (ma i) (mb j))) (recur (inc i) (inc j) (conj! out [:eq (ma i)]))
                    (and (< i n) (or (>= j m) (>= (aget t (+ (* (inc i) w) j)) (aget t (+ (* i w) (inc j))))))
                    (recur (inc i) j (conj! out [:del (ma i)]))
                    (< j m) (recur i (inc j) (conj! out [:ins (mb j)]))
                    :else (persistent! out)))))]
    (vec (concat (map #(vector :eq %) (subvec a 0 pre)) mid
                 (map #(vector :eq %) (subvec a (- (count a) suf)))))))

(defn unified
  "Unified diff text of two line vectors, empty string when equal."
  [a b la lb]
  (let [ops (edits a b)
        ctx 3
        idx (keep-indexed (fn [i [op _]] (when (not= op :eq) i)) ops)]
    (if (empty? idx)
      ""
      (let [groups (reduce (fn [gs i]
                             (let [[s e] (peek gs)]
                               (if (and s (<= (- i e) (* 2 ctx)))
                                 (conj (pop gs) [s i])
                                 (conj gs [i i]))))
                           [] idx)
            a-before (reductions + 0 (map (fn [[op _]] (if (= op :ins) 0 1)) ops))
            b-before (reductions + 0 (map (fn [[op _]] (if (= op :del) 0 1)) ops))
            a-before (vec a-before) b-before (vec b-before)]
        (str "--- " la "\n+++ " lb "\n"
             (apply str
                    (for [[s e] groups
                          :let [from (max 0 (- s ctx)) to (min (count ops) (+ e ctx 1))
                                seg (subvec ops from to)
                                na (count (remove #(= :ins (first %)) seg))
                                nb (count (remove #(= :del (first %)) seg))
                                sa (+ (a-before from) (if (zero? na) 0 1))
                                sb (+ (b-before from) (if (zero? nb) 0 1))]]
                      (str "@@ -" sa "," na " +" sb "," nb " @@\n"
                           (apply str (for [[op l] seg]
                                        (str (case op :eq " " :del "-" :ins "+") l "\n")))))))))))

(defn diff
  "Unified diff of the normalised texts of two run directories (empty when equal)."
  [a b]
  (let [lines #(str/split-lines (render-text % :normalise? true))
        name #(str (.getFileName (->path %)))]
    (unified (lines a) (lines b) (name a) (name b))))
