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
              (when-let [t (not-empty (:services (:topology run)))]   ; a remote environment has no services: no empty "TOPOLOGY " line
                [(str "TOPOLOGY " (str/join " " (for [[n m] t]
                                                 (str n "=" (name (:mode m)) (when (:serving m) (str "(" (name (:serving m)) ")")) (when (:url m) (str " " (:url m)))))))])
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

(defn- intern-lines
  "Two line vectors -> two int arrays of the same ids (equal lines, equal ids)."
  [a b]
  (let [m (java.util.HashMap.)
        id (fn [l] (or (.get m l) (let [i (.size m)] (.put m l i) i)))]
    [(int-array (map id a)) (int-array (map id b))]))

(defn- bisect
  "Myers' middle snake in linear space (the diff-match-patch formulation) of A[alo,ahi) and B[blo,bhi): the point [x y],
  relative to the starts, where a forward and a backward search meet, or nil. Memory is O(n + m), time O((n + m) D)."
  [^ints A ^ints B alo ahi blo bhi]
  (let [alo (long alo) blo (long blo)
        n (- (long ahi) alo) m (- (long bhi) blo)
        max-d (quot (+ n m 1) 2)
        off max-d
        len (+ (* 2 max-d) 2)
        v1 (int-array len -1)
        v2 (int-array len -1)
        _ (aset v1 (inc off) (int 0))
        _ (aset v2 (inc off) (int 0))
        delta (- n m)
        front? (odd? delta)
        st (long-array 4)]                  ; k1start k1end k2start k2end
    (loop [d 0]
      (when (< d max-d)
        (or
         (loop [k1 (+ (- d) (aget st 0))]
           (when (<= k1 (- d (aget st 1)))
             (let [ko (+ off k1)
                   x (if (or (= k1 (- d)) (and (not= k1 d) (< (aget v1 (dec ko)) (aget v1 (inc ko)))))
                       (aget v1 (inc ko))
                       (inc (aget v1 (dec ko))))
                   x2 (loop [x (long x) y (- (long x) k1)]
                        (if (and (< x n) (< y m) (== (aget A (+ alo x)) (aget B (+ blo y)))) (recur (inc x) (inc y)) x))
                   y2 (- x2 k1)]
               (aset v1 ko (int x2))
               (cond
                 (> x2 n) (do (aset st 1 (+ (aget st 1) 2)) (recur (+ k1 2)))
                 (> y2 m) (do (aset st 0 (+ (aget st 0) 2)) (recur (+ k1 2)))
                 front? (let [ko2 (- (+ off delta) k1)]
                          (if (and (<= 0 ko2) (< ko2 len) (not= -1 (aget v2 ko2)) (>= x2 (- n (aget v2 ko2))))
                            [x2 y2]
                            (recur (+ k1 2))))
                 :else (recur (+ k1 2))))))
         (loop [k2 (+ (- d) (aget st 2))]
           (when (<= k2 (- d (aget st 3)))
             (let [ko (+ off k2)
                   x (if (or (= k2 (- d)) (and (not= k2 d) (< (aget v2 (dec ko)) (aget v2 (inc ko)))))
                       (aget v2 (inc ko))
                       (inc (aget v2 (dec ko))))
                   x2 (loop [x (long x) y (- (long x) k2)]
                        (if (and (< x n) (< y m) (== (aget A (+ alo (- n x 1))) (aget B (+ blo (- m y 1))))) (recur (inc x) (inc y)) x))
                   y2 (- x2 k2)]
               (aset v2 ko (int x2))
               (cond
                 (> x2 n) (do (aset st 3 (+ (aget st 3) 2)) (recur (+ k2 2)))
                 (> y2 m) (do (aset st 2 (+ (aget st 2) 2)) (recur (+ k2 2)))
                 (not front?) (let [ko1 (- (+ off delta) k2)]
                                (if (and (<= 0 ko1) (< ko1 len) (not= -1 (aget v1 ko1)))
                                  (let [x1 (aget v1 ko1)]
                                    (if (>= x1 (- n x2)) [x1 (- x1 (- ko1 off))] (recur (+ k2 2))))
                                  (recur (+ k2 2))))
                 :else (recur (+ k2 2))))))
         (recur (inc d)))))))

(defn- diff-range!
  "Appends the edits of A[alo,ahi) against B[blo,bhi) to `out` as [op index] (:eq and :del index into A, :ins into B)."
  [^ints A ^ints B alo ahi blo bhi ^java.util.ArrayList out]
  (let [ahi (long ahi) bhi (long bhi)
        pre (loop [i 0] (if (and (< (+ alo i) ahi) (< (+ blo i) bhi) (== (aget A (+ alo i)) (aget B (+ blo i)))) (recur (inc i)) i))
        alo (+ alo pre) blo (+ blo pre)
        suf (loop [i 0] (if (and (< (+ alo i) ahi) (< (+ blo i) bhi) (== (aget A (- ahi 1 i)) (aget B (- bhi 1 i)))) (recur (inc i)) i))
        ahi' (- ahi suf) bhi' (- bhi suf)]
    (dotimes [i pre] (.add out [:eq (+ (- alo pre) i)]))
    (cond
      (== alo ahi') (doseq [j (range blo bhi')] (.add out [:ins j]))
      (== blo bhi') (doseq [i (range alo ahi')] (.add out [:del i]))
      :else (if-let [[x y] (bisect A B alo ahi' blo bhi')]
              (do (diff-range! A B alo (+ alo x) blo (+ blo y) out)
                  (diff-range! A B (+ alo x) ahi' (+ blo y) bhi' out))
              (do (doseq [i (range alo ahi')] (.add out [:del i]))
                  (doseq [j (range blo bhi')] (.add out [:ins j])))))
    (dotimes [i suf] (.add out [:eq (+ ahi' i)]))))

(defn- edits
  "Vector of [op line] with op :eq :del :ins: a shortest edit script (Myers, linear space; B26: no size limit, no
  delete-all/insert-all fallback, logs of any size and any divergence)."
  [a b]
  (let [a (vec a) b (vec b)
        [A B] (intern-lines a b)
        out (java.util.ArrayList.)]
    (diff-range! A B 0 (count a) 0 (count b) out)
    (persistent! (reduce (fn [v [op i]] (conj! v [op (if (= op :ins) (b i) (a i))])) (transient []) out))))

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
