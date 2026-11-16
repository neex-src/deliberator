(ns deliberator.eventmatch
  "EventBridge event patterns, matched here: `(matches? pattern event)` over parsed JSON (string keys), and `(sample pattern)`, an event detail
  (and source / detail-type) that a pattern matches. The matcher is held to the REAL `TestEventPattern`: `eventmatch_table.edn` (test resources)
  has 67 patterns with the answer the real endpoint gave for each (`rvw-deploy`, 2026-11-16), and `eventmatch-test` asserts this namespace gives the same.

  What is implemented, as the table shows AWS to do it: a pattern is a map; each key is a field of the event; an object value recurses (an array of
  objects in the event matches when one element matches the whole sub-pattern), an array value is an OR of matchers; `$or` at any level is an OR of
  sub-patterns, ANDed with the sibling fields. A matcher is a literal (string, number compared as numbers, true/false, null) or one of `prefix`,
  `suffix`, `equals-ignore-case`, `wildcard` (`*`), `anything-but` (a literal, a list, `{prefix}`, `{suffix}`, `{wildcard}`), `numeric` (`[op n ...]`),
  `exists` and `cidr`. An event field that is an array matches when any of its elements does. A field the event lacks matches only `exists false`.
  What AWS rejects (an empty pattern, a one-branch `$or`) is `nil` from `valid` here and left to the service."
  (:require [clojure.string :as str]))

(defn- num? [x] (number? x))

(defn- literal? [m] (or (string? m) (number? m) (boolean? m) (nil? m)))

(defn- lit= [m v]
  (cond (and (num? m) (num? v)) (== m v)
        (and (string? m) (string? v)) (= m v)
        (and (boolean? m) (boolean? v)) (= m v)
        (and (nil? m) (nil? v)) true
        :else false))

(defn- wildcard->re [w]
  (re-pattern (str "^" (str/join ".*" (map #(java.util.regex.Pattern/quote %) (str/split (str w) #"\*" -1))) "$")))

(defn- cidr? [cidr ip]
  (try
    (let [[net bits] (str/split cidr #"/")
          ->long (fn [s] (let [p (map #(Long/parseLong %) (str/split s #"\."))] (reduce (fn [a b] (+ (* a 256) b)) 0 p)))
          bits (Long/parseLong bits)
          mask (bit-and 0xFFFFFFFF (bit-shift-left 0xFFFFFFFF (- 32 bits)))]
      (= (bit-and (->long net) mask) (bit-and (->long ip) mask)))
    (catch Exception _ false)))

(defn- numeric? [ops v]
  (and (num? v)
       (loop [[op n & more] ops]
         (cond (nil? op) true
               (not (case op "=" (== v n) ">" (> v n) ">=" (>= v n) "<" (< v n) "<=" (<= v n) false)) false
               :else (recur more)))))

(declare op-match?)

(defn- positive?
  "Does the single event value `v` satisfy the operator map `m` (other than exists)?"
  [m v]
  (let [[op arg] (first m)]
    (case op
      "prefix" (and (string? v) (str/starts-with? v arg))
      "suffix" (and (string? v) (str/ends-with? v arg))
      "equals-ignore-case" (and (string? v) (.equalsIgnoreCase ^String v ^String arg))
      "wildcard" (and (string? v) (boolean (re-matches (wildcard->re arg) v)))
      "numeric" (numeric? arg v)
      "cidr" (and (string? v) (cidr? arg v))
      "anything-but" (not (cond (literal? arg) (lit= arg v)
                                (sequential? arg) (some #(lit= % v) arg)
                                (map? arg) (positive? arg v)
                                :else false))
      false)))

(defn- matcher-match? [m v]
  (cond (literal? m) (lit= m v)
        (map? m) (positive? m v)
        :else false))

(defn- field-matches?
  "Matchers (the array of a pattern) against the event's value of the field: present? false = the field is absent."
  [matchers present? v]
  (if-not present?
    (boolean (some #(and (map? %) (= false (get % "exists"))) matchers))
    (boolean
     (some (fn [m]
             (if (and (map? m) (contains? m "exists"))
               (= true (get m "exists"))
               (if (sequential? v)
                 (some #(matcher-match? m %) v)
                 (matcher-match? m v))))
           matchers))))

(defn matches?
  "True when `event` (parsed JSON: maps with string keys) matches `pattern`."
  [pattern event]
  (every?
   (fn [[k pv]]
     (if (= "$or" k)
       (boolean (some #(matches? % event) pv))
       (let [present? (and (map? event) (contains? event k))
             v (when present? (get event k))]
         (cond
           (map? pv) (cond (map? v) (matches? pv v)
                           (sequential? v) (boolean (some #(and (map? %) (matches? pv %)) v))
                           :else (and (not present?) false))
           (sequential? pv) (field-matches? pv present? v)
           :else false))))
   pattern))

(defn valid
  "nil when AWS would reject `pattern` outright (an empty object, a `$or` of fewer than two objects), else the pattern."
  [pattern]
  (when (and (map? pattern) (seq pattern)
             (every? (fn [[k v]] (cond (= "$or" k) (and (sequential? v) (>= (count v) 2) (every? valid v))
                                       (map? v) (valid v) :else (sequential? v)))
                     pattern))
    pattern))

;; --- a sample event of a pattern ---------------------------------------------------------------------------------

(defn- sample-value
  "A value that satisfies the array of matchers `ms`, or ::absent when the pattern wants the field missing, or nil when there is none."
  [ms]
  (let [m (first (remove #(and (map? %) (= false (get % "exists"))) ms))]
    (cond
      (and (nil? m) (some #(and (map? %) (= false (get % "exists"))) ms)) ::absent
      (literal? m) m
      (map? m)
      (let [[op arg] (first m)]
        (case op
          "prefix" (str arg "sample")
          "suffix" (str "sample" arg)
          "equals-ignore-case" arg
          "wildcard" (str/replace (str arg) "*" "x")
          "numeric" (let [[o n o2 n2] arg]
                      (cond (and o2 (#{">" ">="} o) (#{"<" "<="} o2)) (/ (+ n n2) 2.0)
                            (#{">"} o) (inc n) (#{">="  "=" "<="} o) n (#{"<"} o) (dec n) :else 1))
          "exists" "sample"
          "cidr" (let [net (first (str/split arg #"/"))] net)
          "anything-but" (let [ex (cond (literal? arg) [arg] (sequential? arg) arg :else [])]
                           (first (remove (set ex) ["sample" "sample-1" "sample-2"])))
          nil))
      :else nil)))

(defn sample
  "A parsed-JSON event fragment (the keys of `pattern`) that `pattern` matches: the first branch of each `$or`, the first matcher of each array."
  [pattern]
  (reduce
   (fn [acc [k pv]]
     (cond
       (= "$or" k) (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b)) acc (sample (first pv)))
       (map? pv) (assoc acc k (sample pv))
       (sequential? pv) (let [v (sample-value pv)] (if (= ::absent v) acc (assoc acc k v)))
       :else acc))
   {} pattern))
