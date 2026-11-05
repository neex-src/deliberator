(ns dlb.args
  "argv -> `{:op :args}` using the schema of `op.describe` (a vector of `{op class doc args events}`
  with `args` = `{name {type required doc default}}`). Rules:
  - the op is `<area>.<verb>` from the first two words, else the first word alone (`hello`);
    `run` is sugar for `scenario.run`;
  - the remaining words are positionals; they fill the op's required args in the order `op.describe` declares (`order`);
  - `--key value` or `--key=value` sets any arg of the op; a bool arg may omit its value;
  - values are typed by the schema: int, number, bool, string[] and int[] (comma-separated),
    object (JSON), any (JSON when it parses, else string)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- fail [msg & {:as data}] (throw (ex-info msg (assoc data :type "bad-args" :cli true))))

(def sugar {"run" "scenario.run" "universal" "workspace.universal"})

(defn- find-op-at [table words]
  (let [by-name (into {} (map (juxt :op identity)) table)
        [a b & more] words]
    (cond
      (nil? a) {:none true}
      (get sugar a) {:op (by-name (sugar a)) :rest (rest words)}
      (and b (not (str/starts-with? b "--")) (by-name (str a "." b))) {:op (by-name (str a "." b)) :rest more}
      (by-name a) {:op (by-name a) :rest (rest words)}
      :else {:unknown (if (and b (not (str/starts-with? b "--"))) (str a " " b) a) :area a})))

(defn find-op
  "Takes the words. Flags may precede the op (`--ws W service list`): the op is the first position, after
  leading `--flag [value]` words, where the words name an op; those flags are then moved behind it.
  -> {:op entry :rest words} or {:unknown str}."
  [table words]
  (or (when (some-> (first words) (str/starts-with? "--"))
        (some (fn [i]
                (let [r (find-op-at table (drop i words))]
                  (when (:op r) (update r :rest #(concat % (take i words))))))
              (range 1 (count words))))
      (find-op-at table words)))

(defn coerce
  "Converts the string `s` to the schema type `t`."
  [k t s]
  (let [bad (fn [what] (fail (str "--" k ": " (pr-str s) " is not " what)))
        csv (fn [] (if (str/blank? s) [] (mapv str/trim (str/split s #","))))]
    (case t
      "string" s
      "int" (try (Long/parseLong s) (catch Exception _ (bad "an int")))
      "number" (try (Double/parseDouble s) (catch Exception _ (bad "a number")))
      "bool" (case (str/lower-case s)
               ("true" "yes" "1" "on") true
               ("false" "no" "0" "off") false
               (bad "true or false"))
      "string[]" (csv)
      "int[]" (try (mapv #(Long/parseLong %) (csv)) (catch Exception _ (bad "a list of ints")))
      "object" (let [v (try (json/parse-string s) (catch Exception _ ::bad))]
                 (if (map? v) v (bad "a JSON object")))
      "any" (try (json/parse-string s) (catch Exception _ s))
      s)))

(defn arg-order
  "All arg names of the op in declared order (`:order` of `op.describe`)."
  [entry]
  (vec (or (:order entry) (sort (map name (keys (:args entry)))))))

(defn required-order
  "Names of the required args, and of those flagged `positional` (optional, but the natural first word,
  like `scenario.parse path`), in declared order: the positional slots."
  [entry]
  (vec (filter #(let [a (get (:args entry) (keyword %))] (or (:required a) (:positional a))) (arg-order entry))))

(defn parse
  "Maps `words` (after the op) onto args of `entry`. Throws a `bad-args` ex-info with `:cli true`."
  [entry words]
  (let [schema (into {} (map (fn [[k v]] [(name k) v])) (:args entry))
        slots (required-order entry)]
    (loop [[w & more :as ws] words, args {}, pos 0]
      (cond
        (empty? ws) args

        (str/starts-with? w "--")
        (let [[_ k inline] (re-matches #"--([^=]+)(?:=(.*))?" w)
              spec (or (schema k) (fail (str "unknown argument --" k " for " (:op entry))
                                        :hint (str "arguments: " (str/join ", " (map #(str "--" %) (sort (keys schema)))))))
              t (:type spec)]
          (cond
            inline (recur more (assoc args (keyword k) (coerce k t inline)) pos)
            (and (= t "bool") (not (#{"true" "false"} (first more))))
            (recur more (assoc args (keyword k) true) pos)
            (empty? more) (fail (str "--" k " needs a value"))
            :else (recur (rest more) (assoc args (keyword k) (coerce k t (first more))) pos)))

        (< pos (count slots))
        (let [k (slots pos)]
          (recur more (assoc args (keyword k) (coerce k (:type (schema k)) w)) (inc pos)))

        :else (fail (str (:op entry) ": unexpected argument " (pr-str w))
                    :hint (str "positionals: " (if (seq slots) (str/join " " (map #(str "<" % ">") slots)) "none")
                               "; other arguments are --key value"))))))
