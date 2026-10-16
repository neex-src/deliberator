(ns deliberator.scenario.engine
  "Runs a scenario: one function from (workspace, environment, scenario file, session) to a run
  directory. `(start! ws env scenario-path & opts) -> Run` (non-blocking), `(run! ...)` = start and
  wait; options `{:session key (default: the current one) :sink :steps #{n..} :resolve-target f :vars {}
  :builtins {} :on-created (fn [run])}` (`:on-created` gets the Run before the first event can be
  emitted, so a registry can hold it by the time a client learns its id). `ws` is as in `store`; `env` is the environment record, only threaded to kinds
  (`:name` goes into run.edn); `:resolve-target` is the seam to `env/target` (`(f \"service/a\")`
  -> value, throws `{:type :env/unknown-target}`; the daemon passes an adapter that makes the key
  a keyword); `:steps` runs only those step numbers (captures of the others are then missing:
  seed them with `:vars`); `:confirm` is a confirmation token for remote mutating calls, handed to kinds
  in ctx. A step's `:error` rides on its `:step.finished` event.

  `(plan ws env path & opts)` is the static half and throws before anything is created or sent:
  parse errors (`:scenario/parse`), undeclared directives and facts, assertion/capture/duration
  syntax, `$` paths on kinds without that root, unknown variables (a capture defines its name for
  the steps after it) and unknown targets, each with the line. `start!` then allocates the run
  directory with `session/run-dir!`.

  Run directory: `run.edn` (scenario path and sha256, `:env`, `:session`, vars snapshot, `:volatile`
  var names, `:thread-model`, `:owner` (`{:pid :process-start}` of the JVM running it), `:status`
  (`:running`, then `:ok :failed :cancelled :error`, or `:interrupted` when `mark-orphans!` or a daemon
  stop closed a run nobody finished), `:started`/`:finished`, `:summary`, and per step the
  summary `{:n :kind :verb :target :name :label :status :elapsed :facts :fact-types :checks :captures
  :output :error ..}`), `scenario.scn` (hard link into the raw store), `steps/NNN-<kind>-<name>/`
  with `step.edn` (the full record incl. every attempt's request/response sha), `request.<ext>`,
  `response.<ext>` (hard links of the LAST attempt; every attempt is in the raw store, persisted
  before anything is parsed), `display.txt`; then `result.txt` from `render/result!`.

  Execution. Step correlation is `<run id>-NNN`; `{{correlation}}` is the run id,
  `{{step.correlation}}` the step's. Concurrent kinds are armed (`:arm`, synchronously, in order)
  before the first sequential step; their `:run` starts at once on its own thread, they are joined
  after the last sequential step. Sequential steps run in order, each on its own thread of the
  run's executor (virtual threads when `Thread.ofVirtual` exists - JDK 21+ - platform daemon
  threads otherwise; see `thread-model`). A step status is :ok, :failed (a check failed), :error
  (the kind threw or timed out; `@timeout` bounds one attempt, default 30 s or the kind's), :skipped
  (a variable it needs never arrived, or cancelled before it started) or :cancelled. Neither failed
  checks nor errors stop the run: the next step runs. `@until <assert> within <t>` re-runs the step
  every 500 ms until the assert holds or the window (from the first attempt) cannot fit another
  attempt; the last attempt's facts stand. Captures come from the last attempt and are visible to
  the steps after it. Run status: :ok, :failed, :cancelled, :error (setup failed).

  Sink: protocol `Sink`, `(event! sink m)`; a plain fn or nil also work. Events (maps with `:event`
  and the keys of architecture section 6): `:run.started {:run \"sessions/K/runs/ID\" :run-id :run-dir}`,
  `:step.started {:step n :kind :verb :name :target}`, `:line {:step n :text s}` (from kinds via
  `ctx :line!`), `:step.finished {:step n :status :facts :elapsed}`, `:run.finished {:status :steps
  :failed :paths {:run :result}}`. `(collecting-sink)` + `(events s)` for tests.

  The `Run` handle: deref (blocks) gives `{:status :steps :failed :run-id :paths}`; `(cancel! run)`
  stops the executor (interrupting the threads), closes started steps as :cancelled, marks the rest
  :skipped, and still writes `run.edn` (`:status :cancelled`) and `result.txt`; `:run-id`, `:run-dir`."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [deliberator.scenario.expr :as expr]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.scenario.parse :as parse]
            [deliberator.scenario.render :as render]
            [deliberator.scenario.vars :as vars]
            [deliberator.session :as session]
            [deliberator.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.time Duration Instant)
           (java.util.concurrent Callable ExecutionException ExecutorService Executors Future
                                 RejectedExecutionException ThreadFactory TimeUnit TimeoutException)))

;; ---------------------------------------------------------------- sink

(defprotocol Sink
  (event! [this m] "Receives one event map (`:event` plus its keys)."))

(extend-protocol Sink
  nil (event! [_ _] nil)
  clojure.lang.Fn (event! [f m] (f m)))

(defrecord CollectingSink [a]
  Sink (event! [_ m] (swap! a conj m) nil))

(defn collecting-sink [] (->CollectingSink (atom [])))
(defn events "All events a collecting sink has seen, in order." [s] @(:a s))

;; ---------------------------------------------------------------- threads

(defn- virtual-factory []
  (try (let [b (clojure.lang.Reflector/invokeStaticMethod Thread "ofVirtual" (object-array 0))]
         (clojure.lang.Reflector/invokeInstanceMethod b "factory" (object-array 0)))
       (catch Throwable _ nil)))

(def ^:private factory
  (or (virtual-factory)
      (let [n (atom 0)]
        (reify ThreadFactory
          (newThread [_ r] (doto (Thread. ^Runnable r (str "deliberator-step-" (swap! n inc)))
                             (.setDaemon true)))))))

(def thread-model
  "`:virtual` when this JVM has `Thread.ofVirtual` (JDK 21+), else `:platform`."
  (if (virtual-factory) :virtual :platform))

(def ^:dynamic *elapsed*
  "Test seam: `(fn [start-nanos end-nanos]) -> ms`."
  (fn [t0 t1] (quot (- t1 t0) 1000000)))

(def ^:private poll-ms 500)
(def ^:private default-timeout-ms 30000)
(def ^:private cancel-grace-ms 2000)

(defn- spawn ^Future [^ExecutorService scope f]
  (.submit scope ^Callable (bound-fn* f)))

;; ---------------------------------------------------------------- plan

(defn- invalid
  ([line msg] (invalid line msg :scenario/parse))
  ([line msg type]
   (throw (ex-info (str "line " line ": " msg) {:type type :line line :message msg}))))

(defn- dir-value [step name] (some #(when (= name (:name %)) (:value %)) (:directives step)))
(defn- dir-values [step name] (mapv :value (filter #(= name (:name %)) (:directives step))))
(defn- dir-line [step name] (some #(when (= name (:name %)) (:line %)) (:directives step)))

(defn- check-operand! [kmap line operand path]
  (if path
    (when-not (contains? (:capture-roots kmap) "$")
      (invalid line (str (:kind kmap) " steps have no JSON body: `" operand "` is not available")))
    (when-not (kinds/fact-type kmap operand)
      (invalid line (str "unknown fact `" operand "` for " (:kind kmap) "; facts: "
                         (str/join ", " (kinds/fact-names kmap)))))))

(defn- with-expr [line f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (if (= :scenario/expr (:type (ex-data e)))
           (invalid line (:message (ex-data e)))
           (throw e)))))

(defn- validate-step! [kmap step]
  (reduce (fn [seen {:keys [name line]}]
            (let [spec (first (filter #(= name (:name %)) (kinds/directive-specs kmap)))]
              (when (and (not (:repeatable? spec)) (contains? seen name))
                (invalid line (str "@" name " given twice")))
              (conj seen name)))
          #{} (:directives step))
  (doseq [{:keys [name line]} (:directives step)]
    (when (and (= name "body") (nil? (:body-file step)))
      (invalid line "@body needs a file")))
  (doseq [{:keys [name value line]} (:directives step)]
    (case name
      "assert" (let [a (with-expr line #(expr/parse-assert value))]
                 (check-operand! kmap line (:operand a) (:path a)))
      "until" (let [{:keys [assert]} (with-expr line #(expr/parse-until value))]
                (check-operand! kmap line (:operand assert) (:path assert)))
      "capture" (let [c (with-expr line #(expr/parse-capture value))]
                  (check-operand! kmap line (:source c) (:path c)))
      "timeout" (with-expr line #(expr/parse-duration value))
      nil)))

(defn- step-texts [step]
  (concat (when (:target step) [[(:line step) (:target step)]])
          (map (fn [{:keys [value line]}] [line value]) (:directives step))
          (when (:body step)
            [[(:line step) (String. ^bytes (:body step) StandardCharsets/ISO_8859_1)]])))

(defn- check-vars! [step defined resolve-target]
  (doseq [[line text] (step-texts step)
          r (vars/references text)]
    (if (str/includes? r "/")
      (when resolve-target
        (try (resolve-target r)
             (catch clojure.lang.ExceptionInfo e
               ;; a service that is off is a fact of the environment, not of the plan: the step that uses it fails
               (when-not (= :topology/service-off (:type (ex-data e)))
                 (throw (ex-info (str "line " line ": " (ex-message e)) (assoc (ex-data e) :line line) e))))))
      (when-not (contains? defined r)
        (throw (ex-info (str "line " line ": unknown variable {{" r "}}; known: "
                             (str/join ", " (sort defined)))
                        {:type :scenario/unknown-var :name r :line line
                         :known (vec (sort defined))}))))))

(defn- scenario-name [^Path p]
  (let [n (str (.getFileName p))] (str/replace n #"\.scn$" "")))

(defn plan
  "Static half of a run, see the ns docstring. Returns `{:path :sha :bytes :header :steps}` where
  each step carries `:kind-map`; throws, creating nothing."
  [ws env scenario-path & {:keys [steps resolve-target vars builtins]}]
  (let [path (.toAbsolutePath (.toPath (java.io.File. (str scenario-path))))
        bs (Files/readAllBytes path)
        parsed (parse/parse bs :path path :kinds (kinds/registry))
        _ (when (empty? (:steps parsed)) (throw (ex-info "the scenario has no steps" {:type :scenario/parse :line 1 :message "no steps"})))
        base (set (keys (vars/snapshot (:header parsed) :correlation "plan" :env env :vars vars :builtins builtins)))
        selected (when steps (set steps))
        _ (doseq [n selected]
            (when-not (<= 1 n (count (:steps parsed)))
              (throw (ex-info (str "no step " n) {:type :scenario/no-such-step :step n}))))
        planned
        (loop [ss (:steps parsed) defined (conj base "step.correlation") out []]
          (if-let [s (first ss)]
            (let [kmap (kinds/get-kind (:kind s))
                  _ (validate-step! kmap s)
                  _ (check-vars! s (if (:concurrent? kmap) base defined) resolve-target)
                  caps (map #(:name (with-expr (:line s) (fn [] (expr/parse-capture %)))) (dir-values s "capture"))]
              (recur (rest ss)
                     (if (:concurrent? kmap) defined (into defined caps))
                     (conj out (assoc s :kind-map kmap))))
            out))]
    {:path path :sha (store/sha256 bs) :bytes bs :header (:header parsed) :selected selected
     :steps planned}))

;; ---------------------------------------------------------------- helpers

(defn- safe [s] (str/replace (str s) #"[^A-Za-z0-9._-]+" "-"))

(defn- label [{:keys [n kind name verb]}]
  (str (format "%03d" n) "-"
       (str/join "-" (remove str/blank? [(safe kind) (safe (or name verb ""))]))))

(defn- emit! [st m]
  (locking (:lock st)
    (try (event! (:sink st) m) (catch Throwable _ nil))))

(defn- elapsed [t0] (*elapsed* t0 (System/nanoTime)))

(defn- call-with-timeout [st f timeout-ms gone]
  (let [fut (try (spawn (:scope st) f)
                 (catch RejectedExecutionException _ (throw (InterruptedException. "run cancelled"))))]
    (try
      (try (.get fut timeout-ms TimeUnit/MILLISECONDS)
           (catch TimeoutException _
             (reset! gone true)
             (.cancel fut true)
             (throw (ex-info (str "timed out after " timeout-ms " ms")
                             {:type :scenario/timeout :timeout-ms timeout-ms}))))
      (catch ExecutionException e (throw (or (.getCause e) e)))
      (catch InterruptedException e (reset! gone true) (.cancel fut true) (throw e)))))

(defn- content-type [ext]
  (case ext "json" "application/json" ("txt" "log" "csv" "http" "sql") "text/plain" nil "text/plain"
        "application/octet-stream"))

(defn- put! [st step r bytes]
  (when bytes
    (:sha (store/raw-put! (:ws st) bytes
                          {:source (or (:source r) :file)
                           :op (or (:op r) (str/join " " (remove str/blank? [(:kind step) (:verb step) (:target step)])))
                           :env (some-> (:env st) :name)
                           :at (store/now)
                           :content-type (or (:content-type r) (content-type (:response-ext r)))}))))

(defn- link! [st sha ^Path target]
  (when sha (store/link! (:ws st) sha target)))

(defn- facts-of [kmap r ms]
  (let [f (into {} (for [[k v] (:facts r)]
                     [(name k) (if (instance? Duration v) (.toMillis ^Duration v) v)]))]
    (cond-> f (not (contains? f "elapsed")) (assoc "elapsed" ms))))

(defn- body-json-of [r]
  (if (contains? r :body-json)
    (:body-json r)
    (when (and (= "json" (:response-ext r)) (:response-bytes r))
      (try (json/read-str (String. ^bytes (:response-bytes r) StandardCharsets/UTF_8))
           (catch Exception _ nil)))))

(defn- operand-value [kmap facts body-json {:keys [operand path]}]
  (if path
    (if (nil? body-json)
      [false nil nil]
      (let [[f v] (expr/path-get body-json path)] [f v nil]))
    [(contains? facts operand) (get facts operand) (kinds/fact-type kmap operand)]))

(defn- eval-assert [kmap facts body-json a type]
  (let [[f v t] (operand-value kmap facts body-json a)
        {:keys [ok was]} (expr/evaluate a v f t)]
    {:type type :expr (:text a) :ok ok :was was :fact-type t}))

(defn- err-map [^Throwable e]
  (let [d (ex-data e)]
    (cond-> {:message (or (ex-message e) (str (class e)))}
      (:type d) (assoc :type (:type d))
      (:hint d) (assoc :hint (:hint d)))))

(defn- vars-for [st step corr]
  (assoc @(:vars st) "step.correlation" corr))

(defn- resolve-step [st step vs]
  (let [res (:resolve-target st)
        line (:line step)
        sub #(vars/substitute % vs :resolve res :line line)]
    (-> step
        (update :target #(some-> % sub))
        (update :directives (fn [ds] (mapv #(update % :value sub) ds)))
        (update :body #(some-> % (vars/substitute-bytes vs :resolve res :line line))))))

(defn- step-summary [rec]
  (select-keys rec [:n :kind :verb :target :name :label :line :status :elapsed :facts :fact-types
                    :checks :captures :output :error :correlation :lines :concurrent?]))

(defn- close!
  [st rec]
  (Thread/interrupted)
  (let [d (.resolve ^Path (:steps-dir st) ^String (:label rec))]
    (Files/createDirectories d (make-array FileAttribute 0))
    (try (store/write-edn! (.resolve d "step.edn") rec)
         (catch Throwable e (emit! st {:event :line :step (:n rec) :text (str "cannot write step.edn: " (ex-message e))}))))
  (emit! st (cond-> {:event :step.finished :step (:n rec) :status (:status rec) :facts (:facts rec)
                     :elapsed (:elapsed rec)}
              (:error rec) (assoc :error (:error rec))))
  rec)

;; ---------------------------------------------------------------- one step

(defn- prepare
  "Resolves the step and (for concurrent kinds) arms it. Throws on unknown vars / arm failure."
  [st step]
  (let [kmap (:kind-map step)
        corr (str (:run-id st) "-" (format "%03d" (:n step)))
        step-dir (.resolve ^Path (:steps-dir st) ^String (label step))
        _ (Files/createDirectories step-dir (make-array FileAttribute 0))
        vs (vars-for st step corr)
        rstep (resolve-step st step vs)
        timeout-ms (if-let [t (dir-value rstep "timeout")]
                     (expr/parse-duration t)
                     (or (:default-timeout-ms kmap) default-timeout-ms))
        ctx {:ws (:ws st) :env (:env st) :vars vs :session (:session st) :run-dir (:run-dir st)
             :step-dir step-dir :step rstep :sink (:sink st)
             :line! (fn [text] (emit! st {:event :line :step (:n step) :text (str text)}))
             :correlation (:run-id st) :step-correlation corr
             :resolve-target (:resolve-target st) :attempt 0
             :confirm (:confirm (:opts st))
             :cancelled? (fn [] @(:cancelled st))}
        armed (when (and (:concurrent? kmap) (:arm kmap)) ((:arm kmap) ctx rstep))]
    {:rstep rstep :ctx (assoc ctx :armed armed) :corr corr :step-dir step-dir :timeout-ms timeout-ms}))

(defn- base-record [step prep started]
  {:n (:n step) :kind (:kind step) :verb (:verb step) :target (:target (or (:rstep prep) step))
   :name (:name step) :label (label step) :line (:line step)
   :correlation (:corr prep) :started started :concurrent? (boolean (:concurrent? (:kind-map step)))})

(defn- fail-record [st step prep started t0 ^Throwable e]
  (let [d (ex-data e)
        base (base-record step prep started)
        base (assoc base :correlation (or (:corr prep) (str (:run-id st) "-" (format "%03d" (:n step)))))
        status (cond
                 (= :scenario/not-started (:type d)) :skipped
                 (or @(:cancelled st) (instance? InterruptedException e)) :cancelled
                 (= :scenario/unknown-var (:type d)) :skipped
                 :else :error)]
    (assoc base :status status :finished (store/now) :elapsed (elapsed t0) :checks [] :captures {}
           :error (if (= status :skipped)
                    (if (= :scenario/not-started (:type d))
                      {:message "run cancelled before the step started"}
                      {:type :scenario/unknown-var
                     :message (str "variable {{" (:name d) "}} is not available (an earlier step did not capture it)")})
                    (if (= status :cancelled) {:message "cancelled"} (err-map e))))))

(defn- run-prepared
  [st step {:keys [rstep ctx step-dir timeout-ms] :as prep} started t0]
  (let [kmap (:kind-map step)
        until (some-> (dir-value rstep "until") expr/parse-until)
        until-line (dir-line rstep "until")
        asserts (mapv (fn [v] [(expr/parse-assert v)]) (dir-values rstep "assert"))
        caps (mapv expr/parse-capture (dir-values rstep "capture"))
        window-end (when until (+ (System/currentTimeMillis) (:within-ms until)))
        attempt!
        (fn [n]
          (let [ta (System/nanoTime)
                gone (atom false)
                deadline (.plusMillis (Instant/now) timeout-ms)
                ctx (assoc ctx :attempt n :deadline deadline
                           :attempt-deadline (if window-end
                                               (let [w (Instant/ofEpochMilli window-end)]
                                                 (if (.isBefore w deadline) w deadline))
                                               deadline)
                           :attempt-cancelled? (fn [] @gone))
                r (try (call-with-timeout st #((:run kmap) ctx rstep) timeout-ms gone)
                       (catch Throwable e
                         (when-let [d (ex-data e)]
                           (when (or (:request-bytes d) (:response-bytes d))
                             (let [rq (put! st step d (:request-bytes d))
                                   rs (put! st step d (:response-bytes d))]
                               (link! st rq (.resolve ^Path step-dir (str "request." (or (:request-ext d) "txt"))))
                               (link! st rs (.resolve ^Path step-dir (str "response." (or (:response-ext d) "txt")))))))
                         (throw e)))
                ms (elapsed ta)
                r (or r {})]
            {:r r :ms ms :facts (facts-of kmap r ms) :body-json (body-json-of r)
             :request-sha (put! st step r (:request-bytes r))
             :response-sha (put! st step r (:response-bytes r))}))
        until-check
        (fn [att]
          (when until
            (assoc (eval-assert kmap (:facts att) (:body-json att) (:assert until) :until)
                   :expr (:text (:assert until)))))
        [att attempts uchk]
        (loop [n 1 attempts []]
          (when @(:cancelled st) (throw (InterruptedException. "run cancelled")))
          (let [att (attempt! n)
                attempts (conj attempts (select-keys att [:ms :request-sha :response-sha]))
                uchk (until-check att)]
            (if (or (nil? until) (:ok uchk)
                    (> (+ (System/currentTimeMillis) poll-ms) window-end))
              [att attempts uchk]
              (do (Thread/sleep (long poll-ms)) (recur (inc n) attempts)))))
        r (:r att)
        facts (:facts att)
        ext-rq (or (:request-ext r) "txt") ext-rs (or (:response-ext r) "txt")
        rq-name (when (:request-sha att) (str "request." ext-rq))
        rs-name (when (:response-sha att) (str "response." ext-rs))
        _ (when rq-name (link! st (:request-sha att) (.resolve ^Path step-dir ^String rq-name)))
        _ (when rs-name (link! st (:response-sha att) (.resolve ^Path step-dir ^String rs-name)))
        display (when (some? (:display r)) "display.txt")
        _ (when display
            (store/write-bytes-atomic! (.resolve ^Path step-dir "display.txt")
                                       (.getBytes (str (:display r)) StandardCharsets/UTF_8)))
        checks (cond-> (mapv (fn [[a]] (eval-assert kmap facts (:body-json att) a :assert)) asserts)
                 uchk (conj (assoc uchk :attempts (count attempts) :line until-line)))
        cap-res (mapv (fn [c]
                        (let [[f v] (operand-value kmap facts (:body-json att) {:operand (:source c) :path (:path c)})]
                          (if (and f (some? v))
                            {:name (:name c) :value (expr/capture-text v)}
                            {:name (:name c) :failed true})))
                      caps)
        checks (into checks (for [[c cr] (map vector caps cap-res)]
                              {:type :capture :expr (str (:name c) " = " (:source c)) :ok (not (:failed cr))
                               :reason (when (:failed cr) "no value")}))
        captures (into {} (for [c cap-res :when (not (:failed c))] [(:name c) (:value c)]))
        status (if (every? :ok checks) :ok :failed)]
    (assoc (base-record step prep started)
           :status status :finished (store/now) :elapsed (elapsed t0)
           :facts facts
           :fact-types (into {} (keep (fn [k] (when-let [t (kinds/fact-type kmap k)] [k t])) (keys facts)))
           :checks checks :captures captures
           :output (or display rs-name)
           :request rq-name :response rs-name :lines (:lines r)
           :attempts attempts)))

(defn- exec-step
  "Runs one step to a closed record. Never throws."
  [st step prep]
  (let [t0 (System/nanoTime) started (store/now)]
    (when-not prep
      (emit! st {:event :step.started :step (:n step) :kind (:kind step) :verb (:verb step)
                 :name (:name step) :target (:target step)}))
    (close!
     st
     (let [prep* (volatile! prep)]
       (try
         (when (and (not prep) @(:cancelled st))
           (throw (ex-info "run cancelled before the step started" {:type :scenario/not-started})))
         (vreset! prep* (or prep (prepare st step)))
         (run-prepared st step @prep* started t0)
         (catch Throwable e
           (fail-record st step @prep* started t0 e)))))))

(defn- start-concurrent
  "Arms a concurrent step now; returns a Future of its closed record."
  [st step]
  (let [t0 (System/nanoTime) started (store/now)]
    (emit! st {:event :step.started :step (:n step) :kind (:kind step) :verb (:verb step)
               :name (:name step) :target (:target step)})
    (try
      (let [prep (prepare st step)]
        (try (spawn (:scope st) #(exec-step st step prep))
             (catch RejectedExecutionException e
               (doto (java.util.concurrent.CompletableFuture.)
                 (.complete (close! st (fail-record st step prep started t0 e)))))))
      (catch Throwable e
        (doto (java.util.concurrent.CompletableFuture.)
          (.complete (close! st (fail-record st step nil started t0 e))))))))

(defn- await-step [st ^Future fut step]
  (loop [cancelled-at nil]
    (let [r (try (.get fut 100 TimeUnit/MILLISECONDS)
                 (catch TimeoutException _ ::pending)
                 (catch ExecutionException e e))]
      (cond
        (instance? Throwable r)
        (close! st (fail-record st step nil (store/now) (System/nanoTime) (or (.getCause ^Throwable r) r)))
        (not= r ::pending) r
        :else
        (let [ca (or cancelled-at (when @(:cancelled st) (System/currentTimeMillis)))]
          (if (and ca (> (- (System/currentTimeMillis) ca) cancel-grace-ms))
            (do (.cancel fut true)
                (close! st (assoc (fail-record st step nil (store/now) (System/nanoTime)
                                               (InterruptedException. "step did not stop on cancel"))
                                  :status :cancelled)))
            (recur ca)))))))

;; ---------------------------------------------------------------- the run

(defn- summarise [recs]
  (let [checks (mapcat :checks recs)]
    {:steps (count recs)
     :failed (count (filter (comp #{:failed :error} :status) recs))
     :checks (count checks)
     :failed-checks (count (remove :ok checks))}))

(defn- run-status [st recs]
  (cond @(:cancelled st) :cancelled
        (every? (comp #{:ok} :status) recs) :ok
        :else :failed))

(defn- run-edn [st plan' status recs started finished extra]
  (merge {:run-id (:run-id st)
          :scenario {:path (str (:path plan')) :sha256 (:sha plan')}
          :env (some-> (:env st) :name)
          :scenario-env (:env (:header plan'))
          :session (:session st)
          :vars (:snapshot st)
          :volatile (vec (sort (vars/volatile-names (:header plan'))))
          :thread-model thread-model
          :status status :started started :finished finished
          :summary (summarise recs)
          :steps (mapv step-summary (sort-by :n recs))}
         (when-let [t (:topology (:opts st))] {:topology t})
         extra))

(declare owner)

(defn- coordinate! [st plan']
  (let [started (store/now)
        recs (atom [])
        snap (atom nil)
        write! (fn [status finished extra]
                 (store/write-edn! (.resolve ^Path (:run-dir st) "run.edn")
                                   (run-edn (assoc st :snapshot @snap) plan' status @recs started finished extra)))
        ;; progressive view: after every finished step run.edn says :running with the steps so far and
        ;; result.txt is rendered from it (trailer `RUN running ...`); the final write replaces both
        progress! (fn []
                    (try (write! :running nil {:owner (owner)})
                         (render/result! (:run-dir st))
                         (catch Throwable _ nil)))]
    (try
      (let [pick (:selected plan')
            steps (filter #(or (nil? pick) (contains? pick (:n %))) (:steps plan'))
            snapshot (vars/snapshot (:header plan') :correlation (:run-id st) :env (:env st)
                                    :vars (:vars (:opts st)) :builtins (:builtins (:opts st)))
            _ (reset! snap snapshot)
            _ (progress!)
            st (assoc st :snapshot snapshot :vars (atom snapshot))
            sha (store/raw-put! (:ws st) (:bytes plan')
                                {:source :file :op "scenario" :env (some-> (:env st) :name)
                                 :at (store/now) :content-type "text/plain"})]
        (link! st (:sha sha) (.resolve ^Path (:run-dir st) "scenario.scn"))
        (let [conc (filter #(:concurrent? (:kind-map %)) steps)
              sequ (remove #(:concurrent? (:kind-map %)) steps)
              futs (mapv (fn [s] [s (start-concurrent st s)]) conc)]
          (doseq [s sequ]
            (let [rec (if @(:cancelled st)
                        (exec-step st s nil)
                        (let [fut (try (spawn (:scope st) #(exec-step st s nil))
                                       (catch RejectedExecutionException _ nil))]
                          (if fut (await-step st fut s) (exec-step st s nil))))]
              (swap! recs conj rec)
              (progress!)
              (when (seq (:captures rec)) (swap! (:vars st) vars/overlay (:captures rec)))))
          (doseq [[s fut] futs]
            (swap! recs conj (await-step st fut s))
            (progress!)))
        (let [status (run-status st @recs)]
          (write! status (store/now) nil)
          [status @recs]))
      (catch Throwable e
        (write! :error (store/now) {:error (err-map e)})
        [:error @recs]))))

(defrecord Run [run-id run-dir cancel-fn done]
  clojure.lang.IDeref
  (deref [_] @done))

(defn cancel! "Cancels the run (idempotent)." [^Run run] ((:cancel-fn run)) nil)

(defn- owner []
  (let [h (java.lang.ProcessHandle/current)]
    {:pid (.pid h) :process-start (some-> (.startInstant (.info h)) (.orElse nil) str)}))

(defn- owner-alive?
  "True when the process in `owner` ({:pid :process-start}) is running now (same pid AND same start)."
  [{:keys [pid process-start]}]
  (boolean
   (when pid
     (let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
       (and h (.isAlive ^java.lang.ProcessHandle h)
            (or (nil? process-start)
                (= process-start (some-> (.startInstant (.info ^java.lang.ProcessHandle h)) (.orElse nil) str))))))))

(defn interrupt-run!
  "Closes the run in `run-dir` that nobody is running: `run.edn` becomes `:status :interrupted` (with
  `:finished` and `:interrupted-reason`) and `result.txt` is rendered from what is on disk. Returns the
  run id."
  [run-dir reason]
  (let [dir (if (instance? Path run-dir) run-dir (.toPath (java.io.File. (str run-dir))))
        f (.resolve ^Path dir "run.edn")
        r (assoc (store/read-edn f) :status :interrupted :finished (store/now) :interrupted-reason reason)]
    (store/write-edn! f r)
    (try (render/result! dir)
         (catch Throwable _
           (store/write-bytes-atomic! (.resolve ^Path dir "result.txt")
                                      (.getBytes (str "RUN interrupted: " reason "\n") StandardCharsets/UTF_8))))
    (:run-id r)))

(defn mark-orphans!
  "One pass over `<home>/workspaces/*/sessions/*/runs/*/run.edn` (default home: the store's): every run
  claiming `:running` whose owner process is not alive (same pid and process start; a run without an
  owner counts as dead) becomes `:interrupted`. A run whose owner is alive, this JVM included, is never
  touched. Returns the run dirs marked."
  ([] (mark-orphans! (store/home)))
  ([home]
   (let [dirs (fn [^Path p] (if (Files/isDirectory p (make-array java.nio.file.LinkOption 0))
                              (with-open [st (Files/list p)] (vec (iterator-seq (.iterator st))))
                              []))]
     (vec
      (for [w (dirs (.resolve ^Path home "workspaces"))
            sdir (dirs (.resolve ^Path w "sessions"))
            r (dirs (.resolve ^Path sdir "runs"))
            :let [f (.resolve ^Path r "run.edn")
                  run (try (store/read-edn f) (catch Throwable _ nil))]
            :when (and (= :running (:status run)) (not (owner-alive? (:owner run))))]
        (do (interrupt-run! r "the process running it ended without finishing it")
            r))))))

(defn- close-failed!
  "Last resort of `start!`: whatever escaped the run, `run.edn` stops saying `:running` (unless a status
  was already written) and `result.txt` exists."
  [st ^Throwable e]
  (let [dir ^Path (:run-dir st)
        f (.resolve dir "run.edn")
        run (try (store/read-edn f) (catch Throwable _ nil))]
    (try (when (or (nil? run) (= :running (:status run)))
           (store/write-edn! f (assoc (or run {:run-id (:run-id st)})
                                      :status :error :finished (store/now) :error (err-map e))))
         (catch Throwable _ nil))
    (try (render/result! dir)
         (catch Throwable _
           (try (store/write-bytes-atomic! (.resolve dir "result.txt")
                                           (.getBytes (str "RUN error: " (ex-message e) "\n") StandardCharsets/UTF_8))
                (catch Throwable _ nil))))))

(defn start!
  [ws env scenario-path & {:keys [session sink resolve-target] :as opts}]
  (let [plan' (plan ws env scenario-path opts)
        sess (or session (:key (session/current ws))
                 (throw (ex-info "no session: start one or pass :session" {:type :scenario/no-session})))
        run-dir (session/run-dir! ws sess (scenario-name (:path plan')))
        run-id (str (.getFileName run-dir))
        scope (Executors/newCachedThreadPool factory)
        cancelled (atom false)
        done (promise)
        st {:ws ws :env env :session sess :sink sink :run-dir run-dir :run-id run-id
            :steps-dir (.resolve run-dir "steps") :scope scope :cancelled cancelled
            :lock (Object.) :resolve-target resolve-target :opts opts}
        rel (str "sessions/" sess "/runs/" run-id)
        result-path (.resolve run-dir "result.txt")]
    (Files/createDirectories (:steps-dir st) (make-array FileAttribute 0))
    (store/write-edn! (.resolve run-dir "run.edn")
                      {:run-id run-id :status :running :session sess :started (store/now) :owner (owner)
                       :scenario {:path (str (:path plan')) :sha256 (:sha plan')}})
    (let [work (bound-fn*
                (fn []
                  (try
                    (emit! st {:event :run.started :run rel :run-id run-id :run-dir (str run-dir)})
                    (let [[status recs] (coordinate! st plan')
                          _ (try (render/result! run-dir) (catch Throwable _ nil))
                          sm (summarise recs)
                          res {:status status :steps (:steps sm) :failed (:failed sm)
                               :checks (:checks sm) :failed-checks (:failed-checks sm)
                               :run-id run-id
                               :paths {:run (str run-dir) :result (str result-path)}}]
                      (.shutdown scope)
                      (emit! st {:event :run.finished :status status :steps (:steps sm)
                                 :failed (:failed sm) :paths (:paths res)})
                      (deliver done res))
                    (catch Throwable e
                      (let [paths {:run (str run-dir) :result (str result-path)}]
                        (close-failed! st e)
                        (try (.shutdown scope) (catch Throwable _ nil))
                        (emit! st {:event :run.finished :status :error :steps 0 :failed 0 :paths paths})
                        (deliver done {:status :error :steps 0 :failed 0 :run-id run-id
                                       :error (err-map e) :paths paths}))))))
          th (.newThread ^ThreadFactory factory ^Runnable work)
          run (->Run run-id run-dir
                     (fn [] (reset! cancelled true) (.shutdownNow scope))
                     done)]
      ;; the creator sees the Run before anything can be emitted (a cancel for a run that is starting takes)
      (when-let [f (:on-created opts)] (f run))
      (.start ^Thread th)
      run)))

(defn run!
  "Starts the run and waits for it; returns `{:status :steps :failed :run-id :paths}`."
  [ws env scenario-path & opts]
  @(apply start! ws env scenario-path opts))
