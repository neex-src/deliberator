(ns dlb.render
  "Everything `dlb` prints: help generated from `op.describe` (never a hand-written list), event
  lines (`line` text verbatim, one line per step start and finish, one per runtime event) and the
  terminal result (a short summary, then `label: path` lines). Presentation only."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [dlb.args :as args]))

;; help ----------------------------------------------------------------------------------------

(defn- first-sentence [s]
  (let [s (str/replace (str s) #"\s+" " ")
        m (re-find #"^.*?[.;](?= |$)" s)]
    (str/replace (str/trim (or m s)) #"[;:]$" "")))

(defn- area-of [op] (first (str/split op #"\.")))
(defn- verb-of [op] (second (str/split op #"\." 2)))

(defn usage
  "`dlb session start <key> [--title string] [--ws string]`."
  [{:keys [op args] :as entry}]
  (let [req (args/required-order entry)
        opt (remove (set req) (args/arg-order entry))
        words (str/split op #"\.")]
    (str "dlb " (str/join " " words)
         (apply str (map #(str " <" % ">") req))
         (apply str (map #(str " [--" % (when (not= "bool" (get-in args [(keyword %) :type])) (str " " (get-in args [(keyword %) :type]))) "]") opt)))))

(defn- arg-lines [{:keys [args] :as entry}]
  (let [req (args/required-order entry)]
    (for [k (concat req (remove (set req) (args/arg-order entry)))
          :let [{:keys [type required doc default]} (get args (keyword k))]]
      (str "  " (if required (str "<" k ">") (str "--" k)) "  " type
           (when (some? default) (str "  (default " (pr-str default) ")"))
           (when doc (str "  " doc))))))

(defn op-help [{:keys [op class doc events] :as entry}]
  (str/join "\n"
            (concat [(usage entry) "" (str op "  [" class "]  " doc) ""]
                    (arg-lines entry)
                    (when (seq events) ["" (str "events: " (str/join " " events))]))))

(defn area-help [table area]
  (str/join "\n"
            (for [{:keys [op doc] :as e} (filter #(= area (area-of (:op %))) table)]
              (str (usage e) "\n    " (first-sentence doc)))))

(defn overview [table version]
  (let [areas (sort (distinct (map (comp area-of :op) table)))
        w (apply max 4 (map #(count (:op %)) table))]
    (str/join "\n"
              (concat
               [(str "dlb: the Deliberator command line (daemon " version ", " (count table) " operations)")
                ""
                "  dlb <area> <verb> [<required args>] [--key value]... [--json] [--home DIR]"
                "  dlb run <scenario> [--env E]     sugar for scenario.run"
                "  dlb help [area|op]               this text, generated from op.describe"
                ""
                "Positional words fill the required args in the order shown by `dlb help <op>`; every arg is also --name value."
                "  dlb daemon status                 version, pid, home of the running daemon (never starts one)"
                "Exit codes: 0 ok, 1 failed checks / differing runs / confirm-required, 2 error."
                ""]
               (mapcat (fn [area]
                         (cons (str area ":")
                               (for [{:keys [op doc]} (filter #(= area (area-of (:op %))) table)]
                                 (str "  " (format (str "%-" w "s") (str/replace op "." " ")) "  " (first-sentence doc)))))
                       areas)))))

(defn help [table version words]
  (let [{:keys [op unknown area]} (args/find-op table words)]
    (cond
      (empty? words) (overview table version)
      op (op-help op)
      (some #(= (first words) (area-of (:op %))) table) (area-help table (first words))
      :else (str "no such operation or area: " (or unknown (first words)) "\n\n" (overview table version)))))

;; events --------------------------------------------------------------------------------------

(defn- pad3 [n] (format "%03d" (long (or n 0))))

(defn step-label [ev] (str/join " " (keep #(some-> (get ev %) str not-empty) [:kind :verb :target :name])))

(defn event-line
  "The text for one event, or nil. `steps` is an atom {n label} kept across events."
  [steps {:keys [event] :as ev}]
  (case event
    "run.started" (str "run " (:run-id ev))
    "step.started" (do (swap! steps assoc (:step ev) (step-label ev))
                       (str "▶ " (pad3 (:step ev)) " " (step-label ev)))
    "step.finished"
    (let [st (:status ev)
          mark (case st "ok" "✓" "failed" "✗" "error" "ERROR" "skipped" "-" "✗")
          base (str mark " " (pad3 (:step ev)) " " (get @steps (:step ev) "") 
                    (when (:elapsed ev) (str "  " (:elapsed ev) " ms")))]
      (if-let [e (:error ev)]
        (str base "  " (or (:message e) (pr-str e)))
        (if (#{"failed" "skipped"} st) (str base "  [" st "]") base)))
    "line" (if (:stream ev)
             (str (:at ev) " " (:stream ev) " " (:text ev))
             (:text ev))
    "confirm.required" (str "! confirmation required, step " (:step ev) ": " (:summary ev))
    "run.finished" nil
    "service.build.line" (:text ev)
    "logs.line" (:text ev)
    "service.reload.step" (str "reload " (:name ev) ": " (or (:detail ev) (:step ev)))
    "service.started" (str "service started " (:name ev) " " (:container ev))
    "service.stopped" (str "service stopped " (:name ev) " " (:container ev))
    (if (str/starts-with? event "runtime.")
      (str event " " (str/join " " (keep #(some-> (get ev %) str) [:role :name :image :action :reason :detail])))
      nil)))

;; results -------------------------------------------------------------------------------------

(defn- scalar? [v] (or (string? v) (number? v) (boolean? v) (nil? v)))

(defn- tail [v]
  (let [s (str v)] (if (> (count s) 60) (str "…" (subs s (- (count s) 59))) s)))

(defn- scalars? [v] (and (sequential? v) (seq v) (every? scalar? v)))

(defn- compact
  "One line for a row: scalars and scalar lists as k=v, any other non-empty value as k=<json>, so nothing is dropped."
  [m]
  (str/join " " (for [[k v] m :when (or (and (scalar? v) (some? v)) (and (coll? v) (seq v)))]
                  (str (name k) "="
                       (cond (scalars? v) (str/join "," v)
                             (scalar? v) (tail v)
                             :else (json/generate-string v))))))

(defn run-summary [{:keys [status steps failed checks failed-checks]}]
  (str (str/upper-case (str status)) "  " steps " steps, " failed " failed"
       (when checks (str "; " checks " checks, " (or failed-checks 0) " failed"))))

(defn- print-paths [m]
  (doseq [[k v] (sort-by key m)] (println (str (name k) ": " v))))

(defn- print-entries
  "Prints the entries of `m` as `key: value` lines under `ind`; nested maps and rows recurse, indented by two."
  [ind m]
  (doseq [[k v] (sort-by (comp str key) m)]
    (cond
      (scalar? v) (println (str ind (name k) ": " v))
      (and (map? v) (every? scalar? (vals v)) (#{:paths} k))
      (doseq [[pk pv] (sort-by (comp str key) v)] (println (str ind (name pk) ": " pv)))
      (and (sequential? v) (every? map? v))
      (do (println (str ind (name k) ": " (count v)))
          (doseq [row v] (println (str ind "  " (compact row)))))
      (and (map? v) (seq v) (every? map? (vals v)) (every? #(every? (complement coll?) (vals %)) (vals v)))
      (do (println (str ind (name k) ":"))
          (doseq [[n m] (sort-by (comp str key) v)] (println (str ind "  " (name n) "  " (compact m)))))
      (map? v) (do (println (str ind (name k) ":"))
                   (print-entries (str ind "  ") v))
      (sequential? v) (println (str ind (name k) ": " (str/join ", " (map #(if (scalar? %) (str %) (json/generate-string %)) v))))
      :else (println (str ind (name k) ": " (json/generate-string v))))))

(defn- service-line [{:keys [kind cluster name revision version containers desired running image code-sha256 deployments last-modified]}]
  (let [kind (str (clojure.core/name (keyword kind)))]
    (str "  " (format "%-7s" kind)
         (if (= "ecs" kind) (str cluster "/" name "  rev " revision) (str name "  " version))
         "  "
         (if (= "ecs" kind)
           (str (str/join "," (for [c containers] (str (:name c) ":" (or (:tag c) (:digest c) (:image c)))))
                "  " desired "/" running " running")
           (str (or image code-sha256 "")))
         (when-let [u (if (= "ecs" kind) (->> deployments (keep :updated-at) sort last) last-modified)] (str "  updated " u)))))

(defn- print-deployed
  "`deployed.show`: the age first (it is a cached view), a line per service, the CodeDeploy deployments, the section errors."
  [{:keys [env fetched-at age services deployments errors path text]}]
  (println (str env ": " (count services) " service" (when (not= 1 (count services)) "s")
                ", fetched " fetched-at " (" age " ago; dlb deployed refresh fetches again)"))
  (doseq [s services] (println (service-line s)))
  (when (seq deployments)
    (println (str "codedeploy: " (count deployments)))
    (doseq [d deployments]
      (println (str "  " (:id d) "  " (:application d) "/" (:group d) "  " (:status d) "  " (:created-at d)))))
  (doseq [[sec es] errors, e es]
    (println (str "error " (name sec) " " (:op e) ": " (or (:code e) (:category e)) " " (:message e))))
  (println (str "path: " path))
  (println (str "text: " text)))

(defn- print-insights
  "`logs.insights`: the status, rows and bytes scanned (what Insights bills) on the first line, then the files."
  [{:keys [status rows bytes-scanned bytes-scanned-text took-ms path query-file query-id raw groups since until] :as r}]
  (println (str (str/upper-case (str status)) "  " rows " row" (when (not= 1 rows) "s") ", "
                bytes-scanned-text " scanned (bytes-scanned: " bytes-scanned ")"
                (when took-ms (str ", " (format "%.1f" (/ took-ms 1000.0)) " s"))))
  (println (str "groups: " (str/join ", " groups) "   window: " since " .. " until))
  (println (str "path: " path))
  (when query-file (println (str "query-file: " query-file)))
  (println (str "query-id: " query-id "   raw calls: " (count raw)))
  (when-let [s (:statistics r)] (println (str "statistics: " (str/join " " (for [[k v] (sort-by key s)] (str (name k) "=" v)))))))

(defn print-result
  "Prints an ok result generically: run summaries, `diff`, `label: path` lines, scalars and rows."
  [op result]
  (cond
    (and (= "deployed.show" op) (map? result) (contains? result :services)) (print-deployed result)
    (and (= "logs.insights" op) (map? result) (contains? result :bytes-scanned)) (print-insights result)
    (and (sequential? result) (every? map? result))
    (do (println (str (count result) (if (= 1 (count result)) " row" " rows")))
        (doseq [row result] (println (str "  " (compact row)))))
    (not (map? result)) (println (json/generate-string result))
    (and (contains? result :status) (contains? result :steps))
    (do (println (run-summary result))
        (when (:run-id result) (println (str "run-id: " (:run-id result))))
        (print-paths (:paths result)))
    (contains? result :diff)
    (do (when (seq (:diff result)) (print (:diff result)) (flush))
        (when (:equal result) (println "runs are equal")))
    :else
    (print-entries "" result)))

(defn print-error
  "Prints a terminal error. confirm-required also shows the finished run and how to confirm."
  [op {:keys [type message hint token summary step result] :as err} argv]
  (if (= type "confirm-required")
    (do (when result (print-result op result))
        (println (str "confirmation required" (when step (str " (step " step ")")) ": " summary))
        (println (str "to approve: dlb " (str/join " " argv) " --confirm " token
                      (when (and step (= op "scenario.run")) (str " --steps " step)))))
    (binding [*out* *err*]
      (println (str "error: " type ": " message))
      (when hint (println (str "hint: " hint))))))
