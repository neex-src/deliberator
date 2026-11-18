(ns deliberator.hcl
  "A reader for the *shape* of Terraform's `.tf` files, never an evaluator: blocks with their labels and line numbers, and the
  literal string attributes in them. Enough to answer questions like \"which `provider \\\"aws\\\"` blocks does this root have\",
  \"what does this `terraform_remote_state` point at\", \"on which line is `aws_sqs_queue.inbox` declared\" without running
  Terraform. Comments and heredocs are skipped, strings with `${…}` are marked as templates and are never
  literals, every expression that is not a plain string, a boolean or an object is opaque (`{}`, with `:expr` the source text of a short one:
  a variable's `type = list(string)`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

;; tokens -------------------------------------------------------------------------------------

(defn- ident-start? [c] (or (Character/isLetter (char c)) (= \_ c)))
(defn- ident-part? [c] (or (Character/isLetterOrDigit (char c)) (= \_ c) (= \- c)))

(defn- skip-template
  "Index after the `}` that closes the `${` or `%{` whose `{` is at `i` (strings inside are skipped whole)."
  [^String s i]
  (let [n (count s)]
    (loop [i (inc i) depth 1]
      (cond
        (>= i n) n
        :else (let [c (.charAt s i)]
                (cond
                  (= c \{) (recur (inc i) (inc depth))
                  (= c \}) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
                  (= c \") (recur (let [[_ j] (loop [j (inc i)]
                                                (cond (>= j n) [nil n]
                                                      (= \\ (.charAt s j)) (recur (+ j 2))
                                                      (= \" (.charAt s j)) [nil (inc j)]
                                                      :else (recur (inc j))))]
                                    j)
                                  depth)
                  :else (recur (inc i) depth)))))))

(defn tokens
  "The tokens of `text`: `{:t :ident|:str|:punct|:op|:nl|:heredoc|:other :v string :line n [:template? true]}`."
  [^String text]
  (let [n (count text)]
    (loop [i 0 line 1 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (.charAt text i)
              nxt (when (< (inc i) n) (.charAt text (inc i)))]
          (cond
            (= c \newline) (recur (inc i) (inc line) (conj! out {:t :nl :line line}))
            (Character/isWhitespace c) (recur (inc i) line out)
            (or (= c \#) (and (= c \/) (= nxt \/)))
            (recur (let [j (.indexOf text "\n" i)] (if (neg? j) n j)) line out)
            (and (= c \/) (= nxt \*))
            (let [j (.indexOf text "*/" (+ i 2))
                  e (if (neg? j) n (+ j 2))]
              (recur e (+ line (count (filter #(= \newline %) (subs text i e)))) out))
            (= c \")
            (let [[j tpl?]
                  (loop [j (inc i) tpl? false]
                    (cond
                      (>= j n) [n tpl?]
                      (= \\ (.charAt text j)) (recur (+ j 2) tpl?)
                      (= \" (.charAt text j)) [(inc j) tpl?]
                      (and (#{\$ \%} (.charAt text j)) (< (inc j) n) (= \{ (.charAt text (inc j))))
                      (recur (skip-template text (inc j)) true)
                      :else (recur (inc j) tpl?)))
                  raw (subs text (inc i) (max (inc i) (dec j)))]
              (recur j (+ line (count (filter #(= \newline %) raw)))
                     (conj! out (cond-> {:t :str :v (str/replace raw #"\\([\"\\])" "$1") :line line} tpl? (assoc :template? true)))))
            (and (= c \<) (= nxt \<) (re-find #"^<<-?[A-Za-z_]" (subs text i (min n (+ i 5)))))
            (let [[_ tag] (re-find #"^<<-?([A-Za-z_][A-Za-z0-9_]*)" (subs text i))
                  body-start (let [j (.indexOf text "\n" i)] (if (neg? j) n (inc j)))
                  end (loop [p body-start]
                        (cond (>= p n) n
                              :else (let [e (let [k (.indexOf text "\n" p)] (if (neg? k) n k))]
                                      (if (= tag (str/trim (subs text p e))) e (recur (inc e))))))]
              (recur end (+ line (count (filter #(= \newline %) (subs text i end)))) (conj! out {:t :heredoc :line line})))
            (ident-start? c)
            (let [j (loop [j (inc i)] (if (and (< j n) (ident-part? (.charAt text j))) (recur (inc j)) j))]
              (recur j line (conj! out {:t :ident :v (subs text i j) :line line})))
            (and nxt (#{"==" "=>" "!=" "<=" ">=" "&&" "||"} (str c nxt)))
            (recur (+ i 2) line (conj! out {:t :op :v (str c nxt) :line line}))
            (#{\{ \} \[ \] \( \) \= \, \: \? \.} c) (recur (inc i) line (conj! out {:t :punct :v (str c) :line line}))
            :else (recur (inc i) line (conj! out {:t :other :v (str c) :line line}))))))))

;; structure ----------------------------------------------------------------------------------

(defn- punct? [tok v] (and (= :punct (:t tok)) (= v (:v tok))))

(declare parse-body)

(defn- parse-attr
  "The attribute whose value starts at token `i`: `[value next-index]`; the value is `{:lit s}` for one plain string, `{:obj {name attr}}`
  for an object literal, `{}` for anything else; it ends at a newline, a `,` or a `}` outside brackets."
  [toks i]
  (let [n (count toks)
        end (loop [j i depth 0]
              (if (>= j n)
                j
                (let [t (nth toks j)]
                  (cond
                    (and (zero? depth) (or (= :nl (:t t)) (punct? t ",") (punct? t "}"))) j
                    (and (= :punct (:t t)) (#{"{" "[" "("} (:v t))) (recur (inc j) (inc depth))
                    (and (= :punct (:t t)) (#{"}" "]" ")"} (:v t))) (recur (inc j) (dec depth))
                    :else (recur (inc j) depth)))))
        expr (subvec toks i end)
        v (cond
            (and (= 1 (count expr)) (= :str (:t (first expr))) (not (:template? (first expr)))) {:lit (:v (first expr))}
            (and (= 1 (count expr)) (= :ident (:t (first expr))) (#{"true" "false"} (:v (first expr)))) {:bool (= "true" (:v (first expr)))}
            (and (seq expr) (punct? (first expr) "{") (punct? (peek expr) "}"))
            {:obj (:attrs (first (parse-body (subvec expr 1 (dec (count expr))) 0)))}
            :else (if (and (seq expr) (<= (count expr) 24)) {:expr (str/join (map #(case (:t %) :str (str "\"" (:v %) "\"") :nl "" (:v %)) expr))} {}))]
    [(assoc v :line (:line (nth toks i (peek toks)))) end]))

(defn- parse-body
  "`[{:attrs {name value} :blocks [block]} next-index]` of the tokens from `i` up to the `}` that closes the body (or the end)."
  [toks i]
  (let [n (count toks)]
    (loop [i i attrs {} blocks []]
      (if (>= i n)
        [{:attrs attrs :blocks blocks} n]
        (let [t (nth toks i)]
          (cond
            (punct? t "}") [{:attrs attrs :blocks blocks} (inc i)]
            (= :ident (:t t))
            (let [j (loop [j (inc i)]            ; labels: strings or identifiers, up to `=` or `{`
                      (if (and (< j n) (or (= :str (:t (nth toks j))) (= :ident (:t (nth toks j))))) (recur (inc j)) j))
                  lead (when (< j n) (nth toks j))]
              (cond
                (and lead (punct? lead "=") (= j (inc i)))
                (let [[v e] (parse-attr toks (inc j))] (recur e (assoc attrs (:v t) v) blocks))
                (and lead (punct? lead "{"))
                (let [[body e] (parse-body toks (inc j))]
                  (recur e attrs (conj blocks (assoc body :type (:v t) :labels (mapv :v (subvec toks (inc i) j)) :line (:line t)))))
                :else (recur (inc i) attrs blocks)))
            :else (recur (inc i) attrs blocks)))))))

(defn parse
  "The blocks of `text`: `[{:type \"resource\" :labels [\"aws_sqs_queue\" \"inbox\"] :line 12 :attrs {\"name\" {:lit \"x\" :line 13}}
  :blocks [nested]}]`."
  [text]
  (:blocks (first (parse-body (tokens text) 0))))

(defn tf-files
  "The `*.tf` files of directory `dir`, sorted (not `.tf.json`: Terraform's JSON syntax is not read here)."
  [dir]
  (->> (or (.listFiles (io/file (str dir))) [])
       (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".tf")))
       (sort-by #(.getName ^File %))
       vec))

(defn parse-dir
  "Every block of every `.tf` file of `dir`, each with `:file` (absolute path)."
  [dir]
  (vec (for [^File f (tf-files dir)
             b (parse (slurp f))]
         (assoc b :file (.getPath (.getCanonicalFile f))))))

(defn lit
  "The literal string of attribute `k` of `block`, nil when absent or not a plain string."
  [block k]
  (get-in block [:attrs k :lit]))

(defn bool
  "The literal `true`/`false` of attribute `k` of `block`, nil when absent or not a plain boolean."
  [block k]
  (get-in block [:attrs k :bool]))

(defn local-source
  "The directory a `module` block's literal `source` names when it is a path (`./x`, `../x`) that exists, else nil."
  [dir block]
  (when-let [s (lit block "source")]
    (when (re-find #"^\.\.?/" s)
      (let [d (.getCanonicalFile (io/file (str dir) s))]
        (when (.isDirectory d) (.getPath d))))))

(defn tree
  "`[{:dir :blocks}]` of root `dir` and of every module it reaches through local sources (once each, the root first)."
  [dir]
  (loop [todo [(.getPath (.getCanonicalFile (io/file (str dir))))] seen #{} out []]
    (if-let [d (first todo)]
      (if (seen d)
        (recur (rest todo) seen out)
        (let [bs (parse-dir d)]
          (recur (concat (rest todo) (keep #(when (= "module" (:type %)) (local-source d %)) bs))
                 (conj seen d) (conj out {:dir d :blocks bs}))))
      out)))

;; locate -------------------------------------------------------------------------------------

(defn- address-segments
  "`module.stack[\"a\"].aws_sqs_queue.inbox[0]` -> `[[\"module\" \"stack\"] [\"aws_sqs_queue\" \"inbox\"]]`-like: a flat vector of
  names with the indexes dropped."
  [address]
  (->> (str/split (str/replace (str address) #"\[[^\]]*\]" "") #"\.")
       (remove str/blank?)
       vec))

(defn locate
  "Where the resource at `address` (`aws_sqs_queue.inbox`, `module.stack.aws_sqs_queue.inbox[0]`, `data.aws_vpc.default`)
  is declared in the root `dir`: `{:file :line :address}`; through `module \"x\"` segments into the module's directory when its
  source is a local path (a module from a registry: the `module` block's own line with `:module-only true`). nil when no block
  matches."
  [dir address]
  (loop [d (.getPath (.getCanonicalFile (io/file (str dir)))) segs (address-segments address)]
    (let [bs (parse-dir d)]
      (cond
        (empty? segs) nil
        (and (= "module" (first segs)) (second segs))
        (when-let [m (first (filter #(and (= "module" (:type %)) (= [(second segs)] (:labels %))) bs))]
          (if-let [md (local-source d m)]
            (if (seq (drop 2 segs))
              (recur md (drop 2 segs))
              {:file (:file m) :line (:line m) :address address :module-only true})
            {:file (:file m) :line (:line m) :address address :module-only true}))
        :else
        (let [[t ty nm] (if (= "data" (first segs)) ["data" (second segs) (nth segs 2 nil)] ["resource" (first segs) (second segs)])
              b (first (filter #(and (= t (:type %)) (= [ty nm] (:labels %))) bs))]
          (when (and ty nm b)
            {:file (:file b) :line (:line b) :address address}))))))
