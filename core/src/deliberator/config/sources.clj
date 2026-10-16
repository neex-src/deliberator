(ns deliberator.config.sources
  "Pure parsing of the files a configuration chain starts from: Spring `application*.properties` /
  `application*.yml` placeholders, the line structure of `taskdef.json.tftpl`, and the assignments of a service in
  the Terraform `main.tf`. Text in, data out; nothing here touches the network or the store, and Terraform is never
  evaluated: an expression is kept as the raw text found on its line.

  `(placeholders text kind)` -> `[{:placeholder :default :line :property}]` (`kind` `:properties` | `:yaml`) and
  `(defined-keys text kind)`; `(tftpl text)` -> `{:literals {name {:kind :line :value}} :loops {:env n :secret n}}`;
  `(main-tf text key)` -> `{name {:kind :line :expr :literal? :value}}` for the `environment` / `secrets` maps of
  service `key`, `(main-tf-name text name)` the first assignment of `name` anywhere; `(parse-ref value-from)` the
  SSM parameter or secret an ECS `valueFrom` points at. Lines are 1-based."
  (:require [clojure.string :as str]))

;; placeholders --------------------------------------------------------------------------------

(defn- matching-close
  "Index of the `}` closing the `${` whose body starts at `from`, or nil."
  [^String s from]
  (loop [i from depth 1]
    (when (< i (count s))
      (let [c (.charAt s i)]
        (cond (= c \{) (recur (inc i) (inc depth))
              (= c \}) (if (= 1 depth) i (recur (inc i) (dec depth)))
              :else (recur (inc i) depth))))))

(defn- split-body
  "`name:default` at brace depth 0 -> [name default-or-nil]."
  [^String body]
  (loop [i 0 depth 0]
    (if (>= i (count body))
      [(str/trim body) nil]
      (let [c (.charAt body i)]
        (cond (= c \{) (recur (inc i) (inc depth))
              (= c \}) (recur (inc i) (dec depth))
              (and (= c \:) (zero? depth)) [(str/trim (subs body 0 i)) (subs body (inc i))]
              :else (recur (inc i) depth))))))

(defn scan-line
  "Every `${X}` / `${X:default}` in `s`, nested ones in a default included: `[{:placeholder :default}]`."
  [^String s]
  (loop [i 0 out []]
    (let [j (.indexOf s "${" i)
          end (when-not (neg? j) (matching-close s (+ j 2)))]
      (if-not end
        out
        (let [[nm dflt] (split-body (subs s (+ j 2) end))]
          (recur (inc end)
                 (cond-> (conj out {:placeholder nm :default dflt})
                   dflt (into (scan-line dflt)))))))))

(defn- comment? [^String line] (let [t (str/triml line)] (or (str/starts-with? t "#") (str/starts-with? t "!"))))

(defn- yaml-path
  "Walks yaml lines keeping the key path by indentation; -> [{:line n :text s :path \"a.b\"}] (path of the key on the
  line, or of the enclosing key for a list item / continuation)."
  [text]
  (loop [[l & more] (map-indexed vector (str/split-lines text)) stack [] out []]
    (if-not l
      out
      (let [[i s] l
            n (inc i)]
        (if (or (str/blank? s) (str/starts-with? (str/triml s) "#") (str/starts-with? (str/triml s) "---"))
          (recur more stack out)
          (let [indent (count (take-while #(= \space %) s))
                m (re-find #"^\s*(?:-\s+)?([^\s:#'\"\-][^:#]*?|\"[^\"]*\"|'[^']*'):(?:\s|$)" s)
                stack (if m (vec (take-while #(< (first %) indent) stack)) stack)
                stack (if m (conj stack [indent (str/trim (second m))]) stack)
                parents (if m stack (vec (take-while #(< (first %) indent) stack)))]
            (recur more stack (conj out {:line n :text s :path (str/join "." (map second (if m stack parents)))}))))))))

(defn placeholders
  "The placeholders of a properties / yaml file: `[{:placeholder :default :line :property}]`."
  [text kind]
  (case kind
    :properties
    (vec (for [[i s] (map-indexed vector (str/split-lines text))
               :when (not (comment? s))
               :let [key (second (re-find #"^\s*([^=:\s]+)\s*[=:]" s))]
               p (scan-line s)]
           (assoc p :line (inc i) :property key)))
    :yaml
    (vec (for [{:keys [line text path]} (yaml-path text)
               p (scan-line text)]
           (assoc p :line line :property (not-empty path))))))

(defn defined-keys
  "The property keys the file defines (dotted for yaml)."
  [text kind]
  (case kind
    :properties (set (keep #(second (re-find #"^\s*([^=:\s#!]+)\s*[=:]" %)) (str/split-lines text)))
    :yaml (set (keep (fn [{:keys [text path]}] (when (re-find #"^\s*(?:-\s+)?[^\s:#'\"\-][^:#]*?:(?:\s|$)" text) path))
                     (yaml-path text)))))

;; taskdef.json.tftpl --------------------------------------------------------------------------

(defn tftpl
  "Where the entries of a task definition template are: `:literals` (entries whose name is a string in the
  template) and `:loops` (the line a `%{ for k, v in environment|secrets ~}` directive emits entries on)."
  [text]
  (let [lines (vec (str/split-lines text))]
    (loop [i 0 section nil lit {} loops {}]
      (if (>= i (count lines))
        {:literals lit :loops loops}
        (let [s (nth lines i)
              sec (cond (re-find #"\"environment\"\s*:" s) :env
                        (re-find #"\"secrets\"\s*:" s) :secret
                        :else section)
              loop-kind (when-let [m (re-find #"%\{~?\s*for\s+\w+\s*,\s*\w+\s+in\s+(environment|secrets)\b" s)]
                          (if (= "environment" (second m)) :env :secret))
              nm (second (re-find #"\"name\"\s*:\s*\"([^\"$]+)\"" s))]
          (recur (inc i) sec
                 (if nm
                   (assoc lit nm (cond-> {:kind (or sec :env) :line (inc i)}
                                   (re-find #"\"value\"\s*:\s*\"([^\"$]*)\"" s) (assoc :value (second (re-find #"\"value\"\s*:\s*\"([^\"$]*)\"" s)))
                                   (re-find #"\"valueFrom\"\s*:\s*\"([^\"$]*)\"" s) (assoc :value-from (second (re-find #"\"valueFrom\"\s*:\s*\"([^\"$]*)\"" s)))))
                   lit)
                 (if loop-kind (assoc loops loop-kind (+ i 2)) loops)))))))

;; main.tf -------------------------------------------------------------------------------------

(defn- strip-strings
  "`s` with the contents of double-quoted strings blanked, for counting brackets."
  [^String s]
  (str/replace s #"\"(?:[^\"\\]|\\.)*\"" "\"\""))

(defn- region-end
  "Index of the last line of the bracket region starting on line `from` (same line when it opens nothing)."
  [lines from]
  (loop [i from depth 0 opened? false]
    (if (>= i (count lines))
      (dec (count lines))
      (let [t (strip-strings (nth lines i))
            d (- (count (re-seq #"[\{\(\[]" t)) (count (re-seq #"[\}\)\]]" t)))
            depth (+ depth d)
            opened? (or opened? (boolean (re-find #"[\{\(\[]" t)))]
        (if (or (and opened? (<= depth 0)) (and (not opened?) (= i from)))
          i
          (recur (inc i) depth opened?))))))

(defn- cut-expr
  "The expression after `=` on a line: inline maps end at the first `,` / `}` outside brackets, a map line at its end;
  trailing comments and commas are dropped."
  [^String rest inline?]
  (let [rest (str/trim (str/replace rest #"\s+(#|//).*$" ""))]
    (if inline?
      (loop [i 0 depth 0 in-str? false]
        (if (>= i (count rest))
          (str/trim rest)
          (let [c (.charAt rest i)]
            (cond (= c \") (recur (inc i) depth (not in-str?))
                  in-str? (recur (inc i) depth in-str?)
                  (#{\( \[ \{} c) (recur (inc i) (inc depth) in-str?)
                  (and (#{\) \]} c)) (recur (inc i) (dec depth) in-str?)
                  (and (zero? depth) (#{\, \}} c)) (str/trim (subs rest 0 i))
                  (= c \}) (recur (inc i) (dec depth) in-str?)
                  :else (recur (inc i) depth in-str?)))))
      (str/replace rest #",\s*$" ""))))

(defn- literal [expr]
  (cond (re-matches #"\"[^\"$%]*\"" expr) {:literal? true :value (subs expr 1 (dec (count expr)))}
        (re-matches #"-?\d+(\.\d+)?|true|false" expr) {:literal? true :value expr}
        :else {:literal? false}))

(defn- assignments
  "`NAME = expr` assignments (upper-case names) on `line` -> [[name expr]]."
  [^String line]
  (let [m (re-matcher #"(?<![\w.\"])([A-Z][A-Z0-9_]*)\s*=(?!=)\s*" line)]
    (loop [out []]
      (if (.find m)
        (let [nm (.group m 1)
              inline? (boolean (re-find #"\{" (subs line 0 (.start m))))
              expr (cut-expr (subs line (.end m)) inline?)]
          (recur (conj out [nm expr])))
        out))))

(defn- entry [kind line expr]
  (merge {:kind kind :line line :expr expr} (literal expr)))

(defn main-tf-name
  "The first `NAME = expr` anywhere in `text` -> {:line :expr :literal? [:value]} or nil."
  [text name]
  (some (fn [[i s]] (when-let [[_ e] (first (filter #(= name (first %)) (assignments s)))]
                      (entry nil (inc i) e)))
        (map-indexed vector (str/split-lines text))))

(defn main-tf
  "The `environment` / `secrets` entries declared for service `key` (`key = {` ... in `main.tf`): `{name {:kind :env|:secret
  :line :expr :literal? [:value]}}`. The first `key = {` block that declares anything wins."
  [text key]
  (let [lines (vec (str/split-lines text))
        starts (keep-indexed (fn [i s] (when (re-find (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote key) "\\s*=\\s*\\{")) s) i)) lines)
        scan (fn [from]
               (let [to (region-end lines from)]
                 (loop [i from kind nil end -1 out {}]
                   (if (> i to)
                     out
                     (let [s (nth lines i)
                           k (second (re-find #"^\s*(environment|secrets)\s*=" s))
                           [kind end] (cond k [(if (= k "environment") :env :secret) (region-end lines i)]
                                            (> i end) [nil end]
                                            :else [kind end])
                           out (if kind
                                 (reduce (fn [o [nm e]] (if (contains? o nm) o (assoc o nm (entry kind (inc i) e)))) out (assignments s))
                                 out)]
                       (recur (inc i) kind end out))))))]
    (or (first (remove empty? (map scan starts))) {})))

;; valueFrom -----------------------------------------------------------------------------------

(defn parse-ref
  "What an ECS `valueFrom` points at: `{:kind :ssm :name \"/x/y\"}` (ARN, `/x/y` or a bare name, the latter with
  `:hierarchical? false`), `{:kind :secret :arn :id :json-key}` or nil when it is not a reference (a template
  expression, empty)."
  [v]
  (let [v (some-> v str/trim)]
    (cond
      (str/blank? v) nil
      (re-find #"\$\{|\(|\s" v) nil
      :else
      (if-let [[_ n] (re-matches #"arn:[^:]*:ssm:[^:]*:[^:]*:parameter(/.*)" v)]
        {:kind :ssm :name n :hierarchical? true}
        (if-let [[_ rest] (re-matches #"arn:[^:]*:secretsmanager:[^:]*:[^:]*:secret:(.+)" v)]
          (let [[id jk] (str/split rest #":")
                prefix (subs v 0 (- (count v) (count rest)))]
            (cond-> {:kind :secret :arn (str prefix id) :id id}
              (not (str/blank? jk)) (assoc :json-key jk)))
          (cond (str/starts-with? v "/") {:kind :ssm :name v :hierarchical? true}
                (str/starts-with? v "arn:") nil
                (re-matches #"[A-Za-z0-9_.\-]+" v) {:kind :ssm :name v :hierarchical? false}
                :else nil))))))
