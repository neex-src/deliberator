(ns deliberator.events
  "EventBridge as a map, a question and an experiment (cicd-aws piece 05).

  `(map! ws env-name opts)`  reads bus -> rules (state, event pattern, schedule) -> targets (what it is, its name, an input transformer, a dead-letter
      queue, the role), every page, every call through `aws/invoke!` (raw first), and derives `cache/<env>/eventbridge.edn` and `.txt` (one line per
      bus, rule and target, tab separated, made for grep). A call that is not allowed (the sandbox's reader profile has `events:ListRules` and not
      `events:ListEventBuses` or `events:ListTargetsByRule`) is an entry in `:errors`, the rest still answers; when `ListEventBuses` fails the buses are
      `default` and the environment's `bus/*` targets. `(cached ws env-name)` reads the snapshot.
  `(who ws env-name opts)`  which rules an event would match and what they deliver to. It answers from the cached map (with its age; a map is fetched
      only when there is none, or with `:refresh true`), matching here with `deliberator.eventmatch` (held to the real `TestEventPattern` by a table),
      so it costs no AWS call and works for a map that was fetched yesterday. `:ask true` asks the service (`TestEventPattern`, one call per rule) and
      says which rules the service refused to answer for. A DISABLED rule that matches is listed with `:listening false`.
  `(feeds ws env-name target)`  the rules that deliver to a Lambda, queue or topic (its name or ARN), from the cached map: `ListRuleNamesByTarget` is not
      there on Floci and asks one call per bus on the real account, the map has it all.
  `(sample ws env-name opts)`  an event that matches a rule's pattern as a scenario step of kind `eventbridge`, written to
      `cache/<env>/eventbridge/samples/<bus>--<rule>.scn`; run it from the editor, on a remote environment the PutEvents asks like any other write."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.eventmatch :as em]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def ^:private max-pages 200)

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn cache-path [ws env-name] (store/path ws "cache" (store/safe-name env-name) "eventbridge.edn"))
(defn text-path [ws env-name] (store/path ws "cache" (store/safe-name env-name) "eventbridge.txt"))
(defn sample-path [ws env-name bus rule]
  (store/path ws "cache" (store/safe-name env-name) "eventbridge" "samples" (str (store/safe-name (str bus "--" rule)) ".scn")))

(defn cached "The snapshot of `env-name`, nil when none was fetched." [ws env-name] (store/read-edn (cache-path ws env-name)))

;; --- pure ---------------------------------------------------------------------------------------------------------

(defn target-kind
  "What a target ARN is: `lambda`, `sqs`, `sns`, `states` (a state machine), `events` (a bus or an API destination), `kinesis`, `firehose`, `ecs`, `logs`,
  `apigateway`, else the ARN's service word."
  [arn]
  (let [[_ _ svc _ _ res] (str/split (str arn) #":" 6)]
    (cond (and (= "events" svc) (str/starts-with? (str res) "api-destination/")) "api-destination"
          (= "events" svc) "events" (nil? svc) "?" :else svc)))

(defn target-name [arn]
  (let [parts (str/split (str arn) #":" 6) res (nth parts 5 (str arn))]
    (cond (str/starts-with? (str res) "function:") (second (str/split res #":"))
          (str/starts-with? (str res) "api-destination/") (second (str/split res #"/"))
          :else (last (str/split (str res) #"[/:]")))))

(defn pattern-line
  "One line of an event pattern for a row: `detail-type: MaintenanceRequested · source: acme.ops`; a schedule, `rate(5 minutes)`."
  [pattern schedule]
  (cond
    (and (str/blank? pattern) schedule) schedule
    (str/blank? pattern) ""
    :else
    (let [p (try (json/read-str pattern) (catch Exception _ nil))
          fmt (fn fmt [v]
                (cond (map? v) (let [[k x] (first v)]
                                 (case k
                                   "prefix" (str x "*") "suffix" (str "*" x) "wildcard" (str x) "equals-ignore-case" (str "~" x)
                                   "anything-but" (str "not " (if (sequential? x) (str/join "," x) (fmt x)))
                                   "numeric" (str "numeric " (str/join " " x)) "exists" (str "exists " x) "cidr" (str "cidr " x)
                                   (pr-str v)))
                      (sequential? v) (str/join " | " (map fmt v)) (nil? v) "null" :else (str v)))
          line (fn line [p ind] (for [[k v] p]
                                  (cond (= "$or" k) (str "any of " (count v))
                                        (map? v) (str k ".{" (str/join " · " (line v ind)) "}")
                                        :else (str k ": " (fmt v)))))]
      (if (map? p) (str/join " · " (line p 0)) pattern))))

(defn- target-row [t]
  (let [arn (:Arn t)]
    (cond-> {:id (:Id t) :arn arn :kind (target-kind arn) :name (target-name arn)}
      (:RoleArn t) (assoc :role (:RoleArn t))
      (get-in t [:DeadLetterConfig :Arn]) (assoc :dlq (get-in t [:DeadLetterConfig :Arn]))
      (:InputTransformer t) (assoc :input :transformer :input-template (get-in t [:InputTransformer :InputTemplate]))
      (:InputPath t) (assoc :input :path :input-path (:InputPath t))
      (some? (:Input t)) (assoc :input :constant))))

(defn- rule-row [bus r targets]
  (cond-> {:name (:Name r) :arn (:Arn r) :bus bus :state (:State r) :targets (vec targets)
           :pattern-line (pattern-line (:EventPattern r) (:ScheduleExpression r))}
    (:EventPattern r) (assoc :pattern (:EventPattern r))
    (:ScheduleExpression r) (assoc :schedule (:ScheduleExpression r))
    (:Description r) (assoc :description (:Description r))
    (:RoleArn r) (assoc :role (:RoleArn r))
    (:ManagedBy r) (assoc :managed-by (:ManagedBy r))))

(defn listening? [rule] (= "ENABLED" (:state rule)))

(defn text-view
  "The grep text of a snapshot: a line per bus, rule and target."
  [{:keys [env fetched-at buses errors]}]
  (str "# eventbridge in " env ", fetched " fetched-at "\n"
       "# bus\tname\tarn\n# rule\tbus\tname\tstate\tpattern or schedule\n# target\tbus\trule\tkind\tname\tinput\tdlq\tarn\n"
       (apply str
              (for [b buses]
                (str "bus\t" (:name b) "\t" (:arn b) "\n"
                     (apply str (for [r (:rules b)]
                                  (str "rule\t" (:name b) "\t" (:name r) "\t" (:state r) "\t" (:pattern-line r) "\n"
                                       (apply str (for [t (:targets r)]
                                                    (str "target\t" (:name b) "\t" (:name r) "\t" (:kind t) "\t" (:name t) "\t" (name (or (:input t) :none)) "\t"
                                                         (or (:dlq t) "-") "\t" (:arn t) "\n")))))))))
       (apply str (for [[k es] errors, e es] (str "error\t" (name k) "\t" e "\n")))))

;; --- fetch --------------------------------------------------------------------------------------------------------

(defn- call! [ctx svc op req refs]
  (let [r (aws/invoke! ctx (:env ctx) svc op req)
        raw (aws/raw-refs r)]
    (swap! refs conj {:op (name op) :request (get-in raw [:request :sha]) :response (get-in raw [:response :sha])})
    r))

(defn- err-text [r] (str (name (:op r :call)) ": " (or (:code r) (some-> (:category r) name)) " " (:message r)))

(defn- paged [ctx op req items refs]
  (loop [token nil acc [] n 0]
    (let [r (call! ctx :eventbridge op (cond-> req token (assoc :NextToken token)) refs)]
      (cond (aws/error? r) {:items acc :error (err-text (assoc r :op op))}
            (and (:NextToken r) (< n max-pages)) (recur (:NextToken r) (into acc (get r items)) (inc n))
            :else {:items (into acc (get r items))}))))

(defn- bus-names [ctx e refs errors]
  (let [lb (paged ctx :ListEventBuses {:Limit 100} :EventBuses refs)]
    (if (:error lb)
      (do (swap! errors update :buses (fnil conj []) (:error lb))
          (vec (distinct (concat (when (seq (:items lb)) (map :Name (:items lb)))
                                 ["default"]
                                 (for [[k v] (:targets e) :when (and (= "bus" (namespace k)) (string? v)) :let [n (last (str/split v #"/"))]] n)))))
      (vec (:items lb)))))

(defn map!
  "See the namespace docstring. -> `{:env :fetched-at :buses n :rules n :targets n :path :text :raw n [:errors {..}]}`; `:bus` narrows to one bus."
  [ws env-name {:keys [bus]}]
  (let [e (env/load ws env-name)
        ctx {:ws ws :env e}
        refs (atom []) errors (atom {})
        buses (let [bs (bus-names ctx e refs errors)] (if bus (vec (filter #(= bus (if (map? %) (:Name %) %)) bs)) bs))
        bus-rows (vec (for [b buses
                            :let [bname (if (map? b) (:Name b) b)
                                  rs (paged ctx :ListRules (cond-> {:Limit 100} (not= "default" bname) (assoc :EventBusName bname)) :Rules refs)
                                  _ (when (:error rs) (swap! errors update :rules (fnil conj []) (str bname " " (:error rs))))
                                  rules (doall
                                         (pmap (fn [r]
                                                 (let [ts (paged ctx :ListTargetsByRule (cond-> {:Rule (:Name r) :Limit 100} (not= "default" bname) (assoc :EventBusName bname)) :Targets refs)]
                                                   (when (:error ts) (swap! errors update :targets (fnil conj []) (str bname "/" (:Name r) " " (:error ts))))
                                                   (rule-row bname r (map target-row (:items ts)))))
                                               (:items rs)))]]
                        {:name bname :arn (when (map? b) (:Arn b)) :rules (vec (sort-by :name rules))}))
        now (store/now)]
    (when (and (empty? bus-rows) (seq @errors))
      (fail :events/map-failed (str "could not read EventBridge in environment " (:name e) ": " (first (first (vals @errors))))
            :hint (when-let [x (first (first (vals @errors)))] (str x)) :errors @errors))
    (let [snap (cond-> {:env (:name e) :fetched-at now :buses bus-rows :raw @refs}
                 (seq @errors) (assoc :errors @errors))
          p (store/write-edn! (cache-path ws (:name e)) snap)
          t (store/write-bytes-atomic! (text-path ws (:name e)) (.getBytes ^String (text-view snap) StandardCharsets/UTF_8))]
      (cond-> {:env (:name e) :fetched-at now :buses (count bus-rows) :rules (reduce + (map (comp count :rules) bus-rows))
               :targets (reduce + (for [b bus-rows r (:rules b)] (count (:targets r)))) :path (str p) :text (str t) :raw (count @refs) :table (text-view snap)}
        (seq @errors) (assoc :errors @errors)))))

(defn- age [snap now]
  (let [ms (- (.toEpochMilli ^Instant now) (.toEpochMilli ^Instant (:fetched-at snap)))
        s (quot (max 0 ms) 1000)]
    (cond (< s 60) (str s " s") (< s 3600) (str (quot s 60) " min") (< s 86400) (str (quot s 3600) " h " (rem (quot s 60) 60) " min") :else (str (quot s 86400) " days"))))

(defn- snapshot!
  "The cached map, fetched first when there is none or `refresh`: -> `[snap fetched?]`."
  [ws env-name refresh]
  (let [snap (when-not refresh (cached ws env-name))]
    (if snap [snap false] (do (map! ws env-name {}) [(cached ws env-name) true]))))

(defn show
  "The cached map with its age (`events/no-cache` when none)."
  [ws env-name]
  (let [snap (cached ws env-name)]
    (when-not snap (fail :events/no-cache (str "no EventBridge map of " env-name " yet") :hint "dlb events map fetches it (read calls only)"))
    (assoc snap :age (age snap (store/now)) :path (str (cache-path ws env-name)) :text (str (text-path ws env-name)) :cached true :table (text-view snap))))

;; --- who / feeds ---------------------------------------------------------------------------------------------------

(defn- walk-str [m] (json/read-str (json/write-str m)))

(defn event-of
  "The full event a question names: `event` (a JSON string or map), else `detail-type`, `source` and `detail` (JSON or map)."
  [{:keys [event detail-type source detail bus]}]
  (let [parse (fn [x] (cond (map? x) (walk-str x) (str/blank? (str x)) nil
                            :else (try (json/read-str (str x)) (catch Exception e (fail :events/bad-event (str "the event is not JSON: " (ex-message e)) :hint "an event is a JSON object, or give detail-type and source")))))
        ev (parse event)]
    (cond
      ev (merge {"detail" {}} ev)
      (or detail-type source) {"source" (or source "deliberator") "detail-type" (or detail-type "scenario") "detail" (or (parse detail) {})}
      :else (fail :events/no-event "events.who needs an event, or a detail-type and a source" :hint "dlb events who --detail-type MaintenanceRequested --source acme.ops"))))

(defn- full-event [ev]
  (merge {"id" "00000000-0000-0000-0000-000000000000" "account" "000000000000" "time" "2026-01-01T00:00:00Z" "region" "eu-central-1" "resources" []} ev))

(defn who
  "See the namespace docstring. -> `{:event :matches [{:bus :rule :state :listening :pattern-line :targets}] :fetched-at :age :cached :asked [:notes]}`."
  [ws env-name {:keys [bus refresh ask] :as q}]
  (let [ev (full-event (event-of q))
        [snap fetched?] (snapshot! ws env-name refresh)
        now (store/now)
        e (when ask (env/load ws env-name))
        ctx {:ws ws :env e}
        refs (atom [])
        notes (atom [])
        rules (for [b (:buses snap) :when (or (nil? bus) (= bus (:name b))) r (:rules b) :when (:pattern r)] r)
        ask-rule (fn [r] (let [res (call! ctx :eventbridge :TestEventPattern {:EventPattern (:pattern r) :Event (json/write-str ev)} refs)]
                           (if (aws/error? res)
                             (do (swap! notes conj (str (:bus r) "/" (:name r) ": " (err-text (assoc res :op :TestEventPattern)) "; matched here instead"))
                                 (em/matches? (json/read-str (:pattern r)) ev))
                             (boolean (:Result res)))))
        hit? (fn [r] (if ask (ask-rule r) (em/matches? (json/read-str (:pattern r)) ev)))
        matches (vec (for [r rules :when (hit? r)]
                       {:bus (:bus r) :rule (:name r) :state (:state r) :listening (listening? r) :pattern-line (:pattern-line r)
                        :targets (mapv #(select-keys % [:id :kind :name :arn :input :dlq]) (:targets r))}))]
    (cond-> {:env env-name :event ev :matches matches :count (count matches) :rules-checked (count rules) :cached (not fetched?)
             :fetched-at (:fetched-at snap) :age (age snap now) :asked (boolean ask)}
      bus (assoc :bus bus)
      (seq @notes) (assoc :notes @notes)
      (seq (:errors snap)) (assoc :map-errors (:errors snap)))))

(defn feeds
  "The rules that deliver to `target` (a name or an ARN of a Lambda, queue, topic ...), from the cached map."
  [ws env-name {:keys [target refresh]}]
  (when (str/blank? target) (fail :events/no-target "events.feeds needs a target" :hint "a function or queue name, or its ARN"))
  (let [[snap fetched?] (snapshot! ws env-name refresh)
        t (str/trim target)
        matches (vec (for [b (:buses snap) r (:rules b) tg (:targets r)
                           :when (or (= t (:arn tg)) (= t (:name tg)))]
                       {:bus (:name b) :rule (:name r) :state (:state r) :listening (listening? r) :pattern-line (:pattern-line r)
                        :target-id (:id tg) :target (:arn tg) :kind (:kind tg)}))]
    (cond-> {:env env-name :target t :matches matches :count (count matches) :cached (not fetched?) :fetched-at (:fetched-at snap) :age (age snap (store/now))}
      (seq (:errors snap)) (assoc :map-errors (:errors snap)))))

;; --- sample --------------------------------------------------------------------------------------------------------

(defn step-text
  "The scenario step of kind `eventbridge` that puts `source` / `detail-type` / `detail` (a map) on `bus`."
  [bus source detail-type detail]
  (str "### eventbridge put " bus "\n# @source " source "\n# @detail-type " detail-type "\n" (json/write-str detail) "\n"))

(defn sample
  "An event that matches the pattern of `:rule` (on `:bus`, default the only bus that has such a rule) as a step: -> `{:rule :bus :source :detail-type :detail :step :path :self-check}`."
  [ws env-name {:keys [rule bus refresh]}]
  (when (str/blank? rule) (fail :events/no-rule "events.sample needs a rule" :hint "the name of a rule of the map (dlb events map)"))
  (let [[snap _] (snapshot! ws env-name refresh)
        cands (for [b (:buses snap) :when (or (nil? bus) (= bus (:name b))) r (:rules b) :when (= rule (:name r))] r)]
    (when (empty? cands) (fail :events/no-such-rule (str "no rule " rule (when bus (str " on bus " bus)) " in the map of " env-name) :hint "dlb events map lists them"
                               :rules (vec (for [b (:buses snap) r (:rules b)] (str (:name b) "/" (:name r))))))
    (when (> (count cands) 1) (fail :events/ambiguous (str "rule " rule " is on " (count cands) " buses: " (str/join ", " (map :bus cands))) :hint "name the bus"))
    (let [r (first cands)]
      (when-not (:pattern r) (fail :events/no-pattern (str "rule " rule " has no event pattern (" (or (:schedule r) "no schedule either") ")") :hint "a schedule rule fires by itself"))
      (let [p (json/read-str (:pattern r))
            s (em/sample p)
            source (or (let [v (get s "source")] (when (string? v) v)) "deliberator.sample")
            dt (or (let [v (get s "detail-type")] (when (string? v) v)) "Sample")
            detail (let [d (get s "detail")] (if (map? d) d {}))
            self (em/matches? p (full-event {"source" source "detail-type" dt "detail" detail}))
            text (step-text (:bus r) source dt detail)
            path (store/write-bytes-atomic! (sample-path ws env-name (:bus r) (:name r)) (.getBytes ^String text StandardCharsets/UTF_8))]
        (cond-> {:env env-name :rule (:name r) :bus (:bus r) :source source :detail-type dt :detail detail :step text :path (str path) :self-check self
                 :pattern-line (:pattern-line r)}
          (not self) (assoc :note "the generated event does not match the pattern here: edit it by hand"))))))

(defn matched-rules
  "The rules of the CACHED map (nil when there is none) that `event` (full, parsed) matches on `bus`: for a step's result. No AWS call."
  [ws env-name bus event]
  (when-let [snap (cached ws env-name)]
    (vec (for [b (:buses snap) :when (= bus (:name b)) r (:rules b) :when (:pattern r)
               :when (try (em/matches? (json/read-str (:pattern r)) (full-event event)) (catch Exception _ false))]
           {:rule (:name r) :listening (listening? r)}))))
