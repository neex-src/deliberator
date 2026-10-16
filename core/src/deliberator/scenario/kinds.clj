(ns deliberator.scenario.kinds
  "The kind registry and THE contract pieces 06 and 07 code against. A kind is a data map,
  registered once at namespace load with `(register! kind-map)` (each kind lives in one namespace
  under `deliberator.steps.*`; the daemon requires them all):

      {:kind          \"sqs\"                    ; the word after `###`
       :verbs         #{\"send\" \"receive\"}      ; the word after the kind; nil in the set = verb optional/none
       :target        :required                 ; :none | :optional (default) | :required -- what a lone
                                                ;   word after the verb is: a target (`queue/inbox`,
                                                ;   `https://..`, `{{service/a}}`) or a name
       :directives    [{:name \"attr\" :hint \"k=v\" :values [\"..\"] :repeatable? true :doc \"..\"}]
       :facts         [{:name \"count\" :type :number}  ; :number :string :id :instant :duration (ms)
                       {:name \"header.*\" :type :string}] ; a trailing `*` declares a family; `elapsed`
                                                ;   (:duration) is implicit for every kind
       :capture-roots #{\"$\"}                   ; path roots usable in @assert/@capture/@until ($ = JSON body)
       :concurrent?   false                     ; true: armed before step 1, runs beside the others, joined at the end
       :default-timeout-ms 30000                ; optional, per attempt; `@timeout` overrides
       :arm           (fn [ctx step] armed)     ; concurrent kinds only, optional: called synchronously, in
                                                ;   scenario order, before the first sequential step starts; its
                                                ;   return value reaches `:run` as `(:armed ctx)`
       :run           (fn [ctx step] result)}   ; the operation

  `step` is the parsed step with every `{{var}}` / `{{service/a}}` already substituted:
  `{:n :kind :verb :target :name :directives [{:name :value :line}] :body <byte[] or nil> :line}`;
  `:body` is the exact bytes (inline body or `# @body` file); kinds read their own directives from
  `:directives` (engine-owned ones are already handled).

  `ctx` keys: `:ws :env` (the environment record, only threaded through) `:vars` (snapshot incl. captures
  so far, string->string) `:session` (key) `:run-dir` `:step-dir` (java.nio Paths; the step directory
  exists) `:sink` `:line!` (`(line! text)` streams a `line` event for this step) `:correlation` (run id)
  `:step-correlation` (`<run id>-NNN`) `:deadline` (Instant, the attempt's timeout) `:resolve-target`
  (`(f \"service/a\")` -> value, throws `:env/unknown-target`) `:armed` `:attempt` (1-based; >1 under
  `@until`) `:cancelled?` (fn)
  `:attempt-deadline` (Instant: `:deadline`, or the end of the step's `@until` window when that comes first;
  waits of polling kinds are bounded by it, see `attempt-remaining-ms`) `:attempt-cancelled?` (fn, true once
  the engine gave up on this attempt, by timeout or interrupt: a late reply must not act). Blocking calls should be interruptible: cancel and timeout interrupt
  the thread.

  `:run` RETURNS (all keys optional except what you produce):

      {:facts          {\"status\" 201 \"elapsed\" 84}  ; declared names (string or keyword keys); values: numbers,
                                                     ;   strings, Instants; Durations become ms
       :body-json      x     ; parsed JSON (string keys) for `$` paths; if the key is absent and
                             ;   :response-ext is \"json\" the engine parses the persisted response itself
       :lines          n     ; number of output lines (informational, in step.edn)
       :request-bytes  b     ; byte[]; persisted via store/raw-put! and hard-linked as request.<ext>
       :response-bytes b     ; byte[]; same, as response.<ext> (the last attempt only is linked,
                             ;   every attempt is in the raw store)
       :request-ext \"http\" :response-ext \"json\"   ; file extensions (default \"txt\")
       :display        text  ; optional: what result.txt shows instead of the response (sql table,
                             ;   log lines); stored as display.txt
       :source :aws :op \"..\" :content-type \"..\"}  ; optional raw-store meta (default :file, kind
                                                  ;   and verb, derived from the extension)

  A thrown exception is a step ERROR (status `:error`); its ex-data may carry `:request-bytes` /
  `:response-bytes` / `:hint`, which are persisted/shown. Kinds never write blobs themselves.

  Engine-owned directives (`engine-directives`): `@assert @capture @until @timeout @body @name`."
  (:require [clojure.string :as str]))

(def fact-types #{:number :string :id :instant :duration})

(def engine-directives
  "Directives every kind has; the language is in `deliberator.scenario.expr`."
  [{:name "assert" :hint "<fact|$.path> <op> <value>" :repeatable? true
    :values ["==" "!=" "<" "<=" ">" ">=" "contains" "matches"]}
   {:name "capture" :hint "name = <fact|$.path>" :repeatable? true}
   {:name "until" :hint "<assert> within <duration>"}
   {:name "timeout" :hint "<duration>"}
   {:name "body" :hint "<file, relative to the scenario>"}
   {:name "name" :hint "<step name>"}])

(def implicit-facts [{:name "elapsed" :type :duration}])

(defonce ^:private registry* (atom {}))

(defn registry "kind name -> kind map." [] @registry*)

(defn get-kind [name] (get @registry* name))

(defn unregister! [name] (swap! registry* dissoc name) nil)

(defn- invalid [kind-map msg]
  (throw (ex-info (str "invalid kind " (pr-str (:kind kind-map)) ": " msg)
                  {:type :kinds/invalid :kind (:kind kind-map)})))

(defn register!
  "Validates and registers a kind map (replacing a kind of the same name); returns the map."
  [{:keys [kind verbs facts run concurrent? arm] :as kind-map}]
  (when-not (and (string? kind) (re-matches #"[A-Za-z][A-Za-z0-9_-]*" kind))
    (invalid kind-map ":kind must be a word"))
  (when-not (ifn? run) (invalid kind-map ":run must be a function"))
  (when (and arm (not concurrent?)) (invalid kind-map ":arm only makes sense with :concurrent? true"))
  (when (and verbs (not (set? verbs))) (invalid kind-map ":verbs must be a set"))
  (doseq [{:keys [name type]} facts]
    (when-not (and (string? name) (fact-types type))
      (invalid kind-map (str "fact " (pr-str name) " needs a string :name and a :type of " fact-types))))
  (swap! registry* assoc kind kind-map)
  kind-map)

(defn directive-specs
  "All directive specs of a kind map: engine-owned first, then the kind's own."
  [kind-map]
  (vec (concat engine-directives (:directives kind-map))))

(defn fact-specs [kind-map]
  (vec (concat implicit-facts (:facts kind-map))))

(defn fact-type
  "The declared type of fact `name` for the kind map (exact name, then `prefix.*` family), or nil
  when the fact is not declared."
  [kind-map name]
  (let [specs (fact-specs kind-map)]
    (or (some #(when (= (:name %) name) (:type %)) specs)
        (some (fn [{n :name t :type}]
                (when (and (str/ends-with? n "*") (str/starts-with? name (subs n 0 (dec (count n)))))
                  t))
              specs))))

(defn fact-names [kind-map] (mapv :name (fact-specs kind-map)))

(defn attempt-remaining-ms
  "Milliseconds until `(:attempt-deadline ctx)`: the earlier of the attempt's timeout and the end of the
  step's `@until` window. Polling kinds bound their waits by this, not by `:deadline`."
  [ctx]
  (max 0 (- (.toEpochMilli ^java.time.Instant (or (:attempt-deadline ctx) (:deadline ctx))) (System/currentTimeMillis))))

(defn remaining-ms
  "Milliseconds until `(:deadline ctx)`, the attempt's timeout."
  [ctx]
  (max 0 (- (.toEpochMilli ^java.time.Instant (:deadline ctx)) (System/currentTimeMillis))))
