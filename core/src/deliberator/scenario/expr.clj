(ns deliberator.scenario.expr
  "The small languages of the engine-owned directives, all pure.

  Durations: `500ms`, `2s`, `1.5s`, `3m`, `1h`; a bare integer is milliseconds. `(parse-duration s)`
  returns milliseconds or throws `{:type :scenario/expr}`.

  JSON-path-lite: `$` is the root, then any number of `.key` and `[n]` (n a non-negative integer);
  nothing else (no wildcards, filters, quoting, negative indexes). `(parse-path s) -> [\"key\" 0 ...]`,
  `(path-get json path) -> [found? value]`; maps are looked up by string key, then keyword key.

  Assertions (`@assert`): `<operand> <op> <value>` where operand is a fact name of the kind or a
  `$...` path into the step's JSON body, op is one of `== != < <= > >= contains matches`, and value
  is the rest of the line: a bare text, or a double-quoted string (JSON escapes) which is then
  always text. `(parse-assert s) -> {:operand :path :op :value :quoted? :text}` (`:path` set when
  the operand starts with `$`). Comparison, `(evaluate a actual found? type) -> {:ok :was}`:
  - both sides numbers (a number, or text that is a number; an unquoted literal; a `:duration` fact
    also accepts a duration literal such as `2s`) compare numerically; `:instant` facts compare as
    instants when the literal is an ISO-8601 instant; everything else compares as text (`<` etc. by
    `compare`); a missing operand is `null` (text `null`), never equal to anything else;
  - `contains`: a JSON array contains an element whose text equals the value, an object contains
    the key, anything else is a substring test; `matches`: `re-find` of the regex (optionally
    written `/regex/`) on the operand's text.
  `(parse-until s) -> {:assert :within-ms}` for `<assert> within <duration>`; `(parse-capture s)
  -> {:name :source :path}` for `name = <fact|$.path>`; `(capture-text v)` the text a captured
  value takes (numbers and strings as is, collections as compact JSON)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.time Instant)))

(defn- bad [msg & [data]]
  (throw (ex-info msg (merge {:type :scenario/expr :message msg} data))))

;; ---------------------------------------------------------------- durations

(defn parse-duration
  "Milliseconds of `2s`, `500ms`, `1.5s`, `3m`, `1h` or a bare integer (ms)."
  [s]
  (if-let [[_ n u] (re-matches #"(\d+(?:\.\d+)?)(ms|s|m|h)?" (str/trim (str s)))]
    (long (* (Double/parseDouble n) (case u nil 1 "ms" 1 "s" 1000 "m" 60000 "h" 3600000)))
    (bad (str "bad duration " (pr-str s) " (use 500ms, 2s, 3m, 1h)"))))

(defn duration? [s] (boolean (re-matches #"\d+(?:\.\d+)?(ms|s|m|h)" (str/trim (str s)))))

;; ---------------------------------------------------------------- json path

(defn path-text? [s] (str/starts-with? (str s) "$"))

(defn parse-path
  "`$.a[0].b` -> [\"a\" 0 \"b\"]."
  [s]
  (let [s (str s)]
    (when-not (str/starts-with? s "$") (bad (str "path must start with $: " s)))
    (loop [rest (subs s 1) out []]
      (cond
        (str/blank? rest) out
        :else
        (if-let [[m k] (re-find #"^\.([^.\[\]\s]+)" rest)]
          (recur (subs rest (count m)) (conj out k))
          (if-let [[m n] (re-find #"^\[(\d+)\]" rest)]
            (recur (subs rest (count m)) (conj out (Long/parseLong n)))
            (bad (str "bad path " s " at " (pr-str rest)
                      " (only .key and [n] after $)"))))))))

(defn path-get
  "[found? value] of `path` (as from `parse-path`) in parsed JSON."
  [json path]
  (loop [x json [k & ks :as p] (seq path)]
    (if (empty? p)
      [true x]
      (cond
        (and (integer? k) (sequential? x) (< k (count x))) (recur (nth x k) ks)
        (and (string? k) (map? x) (contains? x k)) (recur (get x k) ks)
        (and (string? k) (map? x) (contains? x (keyword k))) (recur (get x (keyword k)) ks)
        :else [false nil]))))

;; ---------------------------------------------------------------- text of values

(defn value-text
  "The text form of a value, for comparison and display."
  [v]
  (cond
    (nil? v) "null"
    (string? v) v
    (and (float? v) (== v (Math/rint (double v))) (< (Math/abs (double v)) 1e15)) (str (long v))
    (or (map? v) (sequential? v)) (json/write-str v)
    :else (str v)))

(defn capture-text [v] (value-text v))

(defn- ->number [x]
  (cond
    (number? x) x
    (and (string? x) (re-matches #"[+-]?\d+(\.\d+)?([eE][+-]?\d+)?" (str/trim x)))
    (bigdec (str/trim x))
    :else nil))

(defn- ->instant [x]
  (cond (instance? Instant x) x
        (string? x) (try (Instant/parse x) (catch Exception _ nil))
        :else nil))

;; ---------------------------------------------------------------- assertions

(def ops ["==" "!=" "<=" ">=" "<" ">" "contains" "matches"])

(defn- unquote-literal [s]
  (let [s (str/trim s)]
    (if (and (>= (count s) 2) (str/starts-with? s "\"") (str/ends-with? s "\""))
      [(try (json/read-str s) (catch Exception _ (subs s 1 (dec (count s))))) true]
      [s false])))

(defn- regex-of [text]
  (let [t (if (and (>= (count text) 2) (str/starts-with? text "/") (str/ends-with? text "/"))
            (subs text 1 (dec (count text)))
            text)]
    (try (re-pattern t) (catch Exception e (bad (str "bad regex " (pr-str text) ": " (ex-message e)))))))

(defn parse-assert
  "Parses `<operand> <op> <value>`; throws `:scenario/expr` with a message naming the problem."
  [s]
  (let [s (str/trim (str s))
        [_ operand op value] (re-matches #"(?s)(\S+)\s+(==|!=|<=|>=|<|>|contains|matches)\s+(.*)" s)]
    (when-not operand
      (bad (str "bad assertion " (pr-str s) "; expected <fact|$.path> <op> <value>, op one of "
                (str/join " " ops))))
    (let [[v quoted?] (unquote-literal value)]
      (when (= op "matches") (regex-of v))
      (cond-> {:operand operand :op op :value v :quoted? quoted? :text s}
        (path-text? operand) (assoc :path (parse-path operand))))))

(defn parse-until
  "`<assert> within <duration>` -> {:assert .. :within-ms ..}."
  [s]
  (if-let [[_ a w] (re-matches #"(?s)(.*\S)\s+within\s+(\S+)" (str/trim (str s)))]
    {:assert (parse-assert a) :within-ms (parse-duration w)}
    (bad (str "bad @until " (pr-str s) "; expected <assertion> within <duration>"))))

(defn parse-capture
  "`name = <fact|$.path>` -> {:name :source :path}."
  [s]
  (if-let [[_ n src] (re-matches #"([A-Za-z_][A-Za-z0-9_.-]*)\s*=\s*(\S+)" (str/trim (str s)))]
    (cond-> {:name n :source src}
      (path-text? src) (assoc :path (parse-path src)))
    (bad (str "bad @capture " (pr-str s) "; expected name = <fact|$.path>"))))

(defn- compare-values [op actual lit type quoted?]
  (let [text (value-text actual)
        lit-num (when-not quoted?
                  (or (->number lit)
                      (when (and (= type :duration) (duration? lit)) (parse-duration lit))))
        a-num (when-not (nil? actual) (->number actual))
        a-inst (when (= type :instant) (->instant actual))
        l-inst (when a-inst (->instant lit))]
    (cond
      (and a-inst l-inst) [(compare a-inst l-inst) true]
      (and a-num lit-num) [(compare (bigdec a-num) (bigdec lit-num)) true]
      :else [(compare text lit) false])))

(defn evaluate
  "Evaluates a parsed assertion against `actual` (found? says whether the operand exists) of the
  fact type `type` (or nil). Returns {:ok bool :was actual-or-nil :found? bool}. A missing operand
  (`found?` false) fails every operator except `!=` (anything but `!= null`) and `== null`: its text is
  never compared, so `$.x matches .*` or `$.x contains null` on an absent path does not pass."
  [{:keys [op value quoted?]} actual found? type]
  (let [actual (when found? actual)
        text (value-text actual)
        null-literal? (and (not quoted?) (= "null" value))
        ok (if-not found?
             (case op
               "==" null-literal?
               "!=" (not null-literal?)
               false)
             (case op
               "==" (zero? (first (compare-values op actual value type quoted?)))
               "!=" (not (zero? (first (compare-values op actual value type quoted?))))
               "<" (neg? (first (compare-values op actual value type quoted?)))
               "<=" (not (pos? (first (compare-values op actual value type quoted?))))
               ">" (pos? (first (compare-values op actual value type quoted?)))
               ">=" (not (neg? (first (compare-values op actual value type quoted?))))
               "contains" (cond
                            (sequential? actual) (boolean (some #(= (value-text %) value) actual))
                            (map? actual) (boolean (some #(= (name %) value) (keys actual)))
                            :else (str/includes? text value))
               "matches" (boolean (re-find (regex-of value) text))))]
    {:ok (boolean ok)
     :was (if (or (map? actual) (sequential? actual)) (value-text actual) actual)
     :found? (boolean found?)}))
