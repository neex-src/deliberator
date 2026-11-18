(ns deliberator.tfsurvey
  "Given ANY Terraform, what runs on the local runtime and what does not, and why. `survey!` takes directories
  (a repository, a folder of repositories), finds the roots, reads each root's TEXT (`deliberator.hcl`, nothing evaluated) and then
  makes real Terraform say it: `init -json` and `plan -json` against the local environment with the override file exactly as
  `terraform.local` writes it, `terraform graph` for the dependencies. Every byte Terraform printed is persisted before anything
  parses it (the folder `<ws>/terraform-survey/<stamp>/<root>/`, and the raw store); the row of a root is derived from those files.

  A root is ONE row: `{:verdict \"runs\" | \"runs-partly\" | \"does-not-run\" :problems [{:class :subject :detail :where :source}]}`.
  Classes: `other-provider <name>`, `unsupported-service <aws type>`, `lookup <data address>`, `missing-var <name>`,
  `module-fetch <source>`, `other <first error line>`. Nothing here throws for a root: a root that crashes Terraform, hangs
  (`budget-ms` per Terraform run, then the process tree is killed and the data source it was waiting for is named), cannot init
  or cannot be overridden is a row with its reason (only a cancel of the request leaves as an exception).

  How a verdict is reached (every word of it seen from Terraform, or said to come from the measured list):
  - `init` fails -> does-not-run, the class from its diagnostics (a provider that cannot be installed, a module that cannot be fetched);
  - the full `plan` succeeds and no resource has a problem from the text or the list -> runs;
  - otherwise the resources that can run are those whose provider is the emulated aws (or a utility provider that needs no remote
    service), whose type the measured list (`floci-aws-types.edn`, `bb floci:measure`) proves, and whose dependencies (from
    `terraform graph`, transitively) are all in; a `plan -target=` of exactly them is run, and while it fails on a data source
    (or a hung one) that source and everything that depends on it is dropped and the plan is tried again (at most 6 times).
    A passing targeted plan is runs-partly, no passing plan (or no resource left) is does-not-run;
  - a variable without a default is filled with a placeholder for the plan (the row says so): missing-var, never fatal alone.

  `run-supported!` is `terraform.local` apply/destroy of exactly the resources that run (`-target` of the resources of the last
  analysis), every other resource listed with its class. The measured list is data: its date, image, digest and the provider
  version are in the file."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.hcl :as hcl]
            [deliberator.process :as process]
            [deliberator.store :as store]
            [deliberator.tflocal :as tflocal]
            [deliberator.tfroot :as tfroot])
  (:import (java.io File)
           (java.nio.file Files)
           (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent Callable Executors)))

;; the measured list ----------------------------------------------------------------------------------------

(defonce ^:private measured-delay
  (delay (some-> (io/resource "deliberator/floci-aws-types.edn") slurp edn/read-string)))

(defn measured
  "The measured list of aws resource types of the local runtime (`bb floci:measure`): `{:measured date :image {:digest ..}
  :provider {:version ..} :supported #{type} :unsupported {type why} :unproven {type why}}`, nil when the file is missing."
  [] @measured-delay)

(defn type-state
  "`{:state :supported|:unsupported|:unproven :why string}` of the aws resource type `t` by the measured list; a type the
  measurement did not know (newer provider) is `:unproven`."
  [t]
  (let [m (measured)]
    (cond
      (nil? m) {:state :unproven :why "no measured list in this build"}
      (contains? (:supported m) t) {:state :supported}
      (contains? (:unsupported m) t) {:state :unsupported :why (get (:unsupported m) t)}
      (contains? (:unproven m) t) {:state :unproven :why (get (:unproven m) t)}
      :else {:state :unproven :why "not in the measured list"})))

(def utility-providers
  "Providers whose resources run on this machine with no remote service: they are no problem here."
  #{"random" "null" "local" "tls" "time" "archive" "cloudinit" "template" "external" "terraform"})

(defn provider-of-type
  "The local name of the provider of resource/data type `t` (`zscaler_zpa_x` -> `zscaler`; `terraform_data` -> `terraform`)."
  [t]
  (if (str/starts-with? t "terraform_") "terraform" (first (str/split t #"_" 2))))

;; modules --------------------------------------------------------------------------------------------------

(defn module-kind
  "`:local` (`./x`), `:git` (`git::…`, `github.com/…`, `git@…`, an https URL ending `.git`), `:registry` (`ns/name/provider`,
  `host/ns/name/provider`, with an optional `//subdir`), else `:other` (s3::, gcs::, hg::, a plain URL)."
  [src]
  (let [src (str src)]
    (cond
      (re-find #"^\.\.?/" src) :local
      (re-find #"^(git::|git@|ssh://|github\.com/|bitbucket\.org/)" src) :git
      (re-find #"^https?://.*\.git(\?|//|$)" src) :git
      (re-find #"^[A-Za-z0-9._-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+(//.*)?$" src) :registry
      (re-find #"^[A-Za-z0-9.-]+\.[A-Za-z]+/[A-Za-z0-9._-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+(//.*)?$" src) :registry
      :else :other)))

;; the text of a root -----------------------------------------------------------------------------------------

(defn- placeholder-of
  "The value a missing variable gets for the plan, from the text of its `type`: `[value-text string?]`."
  [type-expr]
  (let [t (str/trim (or type-expr ""))]
    (cond
      (re-find #"^(list|set|tuple)" t) "[]"
      (re-find #"^(map|object)" t) "{}"
      (= "number" t) "0"
      (= "bool" t) "false"
      :else "placeholder")))

(defn inventory
  "What the text of root `dir` says (never evaluated; modules with a local source included, remote ones are not fetched):
  `{:providers [{:name :source :version :kind :emulated|:local|:other}] :backend :resources {type n} :data {type n}
  :variables-without-default [{:name :type :placeholder :file :line}] :modules [{:name :source :kind :version :file :line}]
  :tfvars [name] :auto-tfvars [name] :files n :unreadable [..]}`."
  [dir]
  (let [d (.getPath (.getCanonicalFile (io/file (str dir))))
        tr (try (hcl/tree d) (catch Throwable _ []))
        all (mapcat :blocks tr)
        root (:blocks (first tr))
        typed (fn [bs k] (filter #(and (= k (:type %)) (seq (:labels %))) bs))
        req (into {} (for [tf (filter #(= "terraform" (:type %)) root)
                           rp (filter #(= "required_providers" (:type %)) (:blocks tf))
                           [n v] (:attrs rp)]
                       [n {:source (get-in v [:obj "source" :lit]) :version (get-in v [:obj "version" :lit])}]))
        used (into (sorted-set) (concat (keys req)
                                        (for [b (typed all "provider")] (first (:labels b)))
                                        (map (comp provider-of-type first :labels) (typed all "resource"))
                                        (map (comp provider-of-type first :labels) (typed all "data"))))
        count-types (fn [k] (into (sorted-map) (frequencies (map (comp first :labels) (typed all k)))))
        tfv (sort (map #(.getName ^File %) (filter #(re-find #"\.tfvars(\.json)?$" (.getName ^File %)) (or (.listFiles (io/file d)) []))))]
    {:providers (vec (for [n used]
                       {:name n :source (get-in req [n :source]) :version (get-in req [n :version])
                        :kind (cond (= "aws" n) :emulated (utility-providers n) :local :else :other)}))
     :backend (try (let [b (:backend (tfroot/analyse d))] (cond-> {:kind (name (:kind b))} (:type b) (assoc :type (:type b)) (:bucket b) (assoc :bucket (:bucket b))))
                   (catch Throwable _ nil))
     :resources (count-types "resource")
     :data (count-types "data")
     :variables-without-default
     (vec (for [b (typed root "variable") :when (not (contains? (:attrs b) "default"))]
            {:name (first (:labels b)) :type (get-in b [:attrs "type" :expr]) :placeholder (placeholder-of (get-in b [:attrs "type" :expr]))
             :file (:file b) :line (:line b)}))
     :modules (vec (for [b (typed root "module") :let [src (hcl/lit b "source")]]
                     {:name (first (:labels b)) :source src :kind (some-> src module-kind name) :version (hcl/lit b "version")
                      :file (:file b) :line (:line b)}))
     :tfvars (vec tfv)
     :auto-tfvars (vec (filter #(or (= "terraform.tfvars" %) (= "terraform.tfvars.json" %) (re-find #"\.auto\.tfvars(\.json)?$" %)) tfv))
     :files (count (hcl/tf-files d))}))

;; finding the roots ------------------------------------------------------------------------------------------

(def ^:private skip-dirs #{".git" ".terraform" "node_modules" ".scratch" ".idea" "vendor"})

(defn- candidate-dirs [^File top]
  (letfn [(walk [^File d]
            (let [kids (or (.listFiles d) [])
                  here (when (some #(and (.isFile ^File %) (re-find #"\.tf(\.json)?$" (.getName ^File %))) kids) [d])]
              (concat here
                      (mapcat walk (sort-by #(.getName ^File %)
                                            (filter #(and (.isDirectory ^File %) (not (skip-dirs (.getName ^File %)))
                                                          (not (str/starts-with? (.getName ^File %) ".")))
                                                    kids))))))]
    (walk top)))

(defn discover
  "`dirs` (a repository, a folder of repositories, a root itself) -> `{:roots [{:dir :under :signals [..]}] :not-roots [{:dir :why}]}`.
  THE RULE: a directory with `.tf` files is a root unless it is (1) called by a `module` block with a local source from another
  directory in the scan: then it is a module of that root and is not run on its own; or (2) a library module: under a directory
  called `modules`, called by nobody in the scan, and carrying no `provider` and no `backend` block of its own (a module
  cannot, a root nearly always does; a library's callers may live in repositories not scanned, the row says so in `:not-roots`).
  `:signals` of a root: `backend`, `provider`, `called-by-nobody`, `tfvars`, `lock-file`."
  [dirs]
  (let [tops (mapv #(.getCanonicalFile (io/file (str %))) dirs)
        cands (distinct (mapcat (fn [t] (map (fn [d] [(.getPath ^File d) (.getPath ^File t)]) (candidate-dirs t))) tops))
        by-dir (into {} cands)
        parsed (into {} (for [[d _] cands] [d (try (hcl/parse-dir d) (catch Throwable _ []))]))
        called (into {} (for [[d bs] parsed, b bs :when (= "module" (:type b))
                              :let [m (hcl/local-source d b)] :when (and m (not= m d))]
                          [m d]))
        signals (fn [d]
                  (let [bs (get parsed d)
                        has (fn [t] (boolean (some #(= t (:type %)) bs)))
                        be (some #(and (= "terraform" (:type %)) (some (fn [x] (= "backend" (:type x))) (:blocks %))) bs)]
                    (cond-> []
                      be (conj "backend") (has "provider") (conj "provider")
                      (not (called d)) (conj "called-by-nobody")
                      (some #(re-find #"\.tfvars" (.getName ^File %)) (or (.listFiles (io/file d)) [])) (conj "tfvars")
                      (.exists (io/file d ".terraform.lock.hcl")) (conj "lock-file"))))
        verdicts (for [[d top] cands
                       :let [sig (signals d)
                             why (cond
                                   (called d) (str "a module of " (called d) " (called with a local source), run through that root")
                                   (and (re-find #"(^|/)modules(/|$)" (subs d (min (count d) (count (.getParent (io/file top))))))
                                        (not-any? #{"backend" "provider"} sig))
                                   "a library module: under modules/, called by nobody in this scan, no provider or backend block of its own")]]
                   (if why {:dir d :why why} {:dir d :under top :signals sig}))]
    {:roots (vec (filter :under verdicts))
     :not-roots (vec (remove :under verdicts))}))

;; reading what Terraform printed ------------------------------------------------------------------------------

(defn parse-json-lines
  "Terraform's `-json` output lines -> `{:diagnostics [{:severity :summary :detail :address :file :line}] :planned [{:address :action}]
  :changes {..} :started #{addr} :finished #{addr} :hung [addr] :plain [lines that are not JSON]}`; a line that does not parse is kept
  as plain text (a panic, a crash log), never dropped."
  [lines]
  (let [ev (for [l lines]
             (if (str/starts-with? (str/triml l) "{")
               (try (json/read-str l :key-fn keyword) (catch Throwable _ {:plain l}))
               {:plain l}))
        addr (fn [e] (or (get-in e [:hook :resource :addr]) (get-in e [:change :resource :addr])))
        started (set (for [e ev :when (#{"apply_start" "refresh_start" "provision_start"} (:type e))] (addr e)))
        finished (set (for [e ev :when (#{"apply_complete" "apply_errored" "refresh_complete" "provision_complete" "provision_errored"} (:type e))] (addr e)))]
    {:diagnostics (vec (for [e ev :when (= "diagnostic" (:type e))
                             :let [d (:diagnostic e)]]
                         {:severity (:severity d) :summary (:summary d) :detail (:detail d) :address (:address d)
                          :file (get-in d [:range :filename]) :line (get-in d [:range :start :line])}))
     :planned (vec (for [e ev :when (= "planned_change" (:type e))]
                     {:address (get-in e [:change :resource :addr]) :action (get-in e [:change :action])}))
     :changes (some #(when (= "change_summary" (:type %)) (:changes %)) ev)
     :started started :finished finished
     :hung (vec (sort (remove nil? (remove finished started))))
     :plain (vec (keep :plain ev))}))

(defn- strip-index [a] (str/replace (str a) #"\[[^\]]*\]" ""))

(defn- resource-address?
  "True for `aws_sqs_queue.q`, `data.aws_vpc.main`, `module.m.aws_x.y` (indexes dropped); false for a variable, local, output,
  provider or module node."
  [n]
  (boolean (and (re-matches #"(module\.[^.]+\.)*(data\.)?[a-z][A-Za-z0-9_]*\.[A-Za-z0-9_-]+" n)
                (not (re-find #"(^|\.)(var|local|output|provider)\.[^.]+$" n))
                (not (re-find #"(^|\.)module\.[^.]+$" n)))))

(defn parse-graph
  "`terraform graph` DOT text -> `{:nodes #{address} :deps {address #{address}}}`: resource and data addresses (indexes dropped, `(expand)`
  and `[root] ` dropped), `:deps` the transitive closure through every node of the graph (a local, a variable, an output)."
  [text]
  (let [norm (fn [s] (-> s (str/replace #"^\[root\]\s*" "") (str/replace #"\s*\((expand|close)\)$" "") strip-index str/trim))
        edges (for [[_ a b] (re-seq #"\"([^\"]+)\"\s*->\s*\"([^\"]+)\"" (str text))] [(norm a) (norm b)])
        nodes (map (comp norm second) (re-seq #"(?m)^\s*\"([^\"]+)\"\s*\[" (str text)))
        adj (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} edges)
        resource? resource-address?
        closure (fn [n] (loop [todo (vec (adj n)) seen #{}]
                          (if-let [x (first todo)]
                            (if (seen x) (recur (subvec todo 1) seen) (recur (into (subvec todo 1) (adj x)) (conj seen x)))
                            seen)))
        all (into #{} (filter resource?) (concat nodes (map first edges) (map second edges)))]
    {:nodes all
     :deps (into {} (for [n all] [n (into #{} (filter resource?) (closure n))]))}))

;; classifying a diagnostic ----------------------------------------------------------------------------------------

(defn- first-line [s] (str/trim (or (first (remove str/blank? (str/split-lines (str s)))) "")))

(defn- provider-name [text]
  (or (second (re-find #"registry\.terraform\.io/([\w.-]+/[\w.-]+)" (str text)))
      (second (re-find #"provider\[\"[^/\"]+/([\w.-]+/[\w.-]+)\"\]" (str text)))
      (second (re-find #"provider (?:registry\.terraform\.io/)?([\w.-]+/[\w.-]+)" (str text)))
      (second (re-find #"(?:provider|plugin) \"?([\w-]+)\"?" (str text)))))

(defn- addr-type
  "The resource or data type of an address (`module.m.data.aws_vpc.main` -> `aws_vpc`), nil for a non-resource address."
  [address]
  (when (resource-address? (strip-index address))
    (second (re-find #"([a-z][A-Za-z0-9_]*)\.[A-Za-z0-9_-]+$" (strip-index address)))))

(defn classify-diagnostic
  "The problem a Terraform error diagnostic is: `{:class :subject :detail :where [:address]}`. `modules` (the root's `module` blocks)
  gives a module's source from its name."
  [{:keys [summary detail address file line] :as _d} modules]
  (let [text (str summary "\n" detail)
        addr (when address (strip-index address))
        where (when file (str file (when line (str ":" line))))
        ty (when addr (addr-type addr))
        mod-src (fn []
                  (or (second (re-find #"source code from \"([^\"]+)\"" text))
                      (second (re-find #"module \"[^\"]*\" \([^)]*\) from ([^\s:]+)" text))
                      (when-let [n (second (re-find #"(?i)module \"([^\"]+)\"" text))]
                        (:source (first (filter #(= n (:name %)) modules))))
                      (second (re-find #"([\w.-]+/[\w-]+/[\w-]+)" detail))
                      "unknown source"))
        base (cond-> {:detail (first-line (if (str/blank? detail) summary detail)) :summary summary} where (assoc :where where) addr (assoc :address addr))]
    (cond
      (= "No value for required variable" summary)
      (assoc base :class "missing-var" :subject (or (second (re-find #"variable \"([^\"]+)\"" detail)) "?"))
      (re-find #"(?i)\bmodule\b" summary) (assoc base :class "module-fetch" :subject (mod-src))
      (re-find #"(?i)(failed to (query|install|load)|provider (registry|package|plugin)|unsupported provider|provider requirement|no available releases|could not (retrieve|load)|inconsistent dependency lock|plugin)" summary)
      (assoc base :class "other-provider" :subject (or (provider-name text) "?"))
      (and addr (re-find #"^provider\[" addr)) (assoc base :class "other-provider" :subject (or (provider-name addr) addr))
      (and ty (not= "aws" (provider-of-type ty)) (not (utility-providers (provider-of-type ty))))
      (assoc base :class "other-provider" :subject (provider-of-type ty))
      (and addr (re-find #"(^|\.)data\." addr)) (assoc base :class "lookup" :subject addr)
      :else (assoc base :class "other" :subject (first-line (if (str/blank? summary) detail summary))))))

;; running Terraform ----------------------------------------------------------------------------------------------

(def default-budget-ms
  "Per Terraform run of a survey: three minutes (a plan against the emulator takes seconds; a hung data source or a download
  must not hold a survey of a hundred roots)."
  180000)

(def ^:private ts-fmt (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (.withZone (ZoneId/systemDefault))))

(defn- write! [^File f ^String text]
  (.mkdirs (.getParentFile f))
  (spit f text))

(defn- tf-call!
  "One Terraform run of the analysis, its raw output persisted FIRST (named file in the root's folder, content-addressed raw copy), then
  parsed. -> `{:what :args :exit :lines :timeout? :cancelled? :error :sha :path :file :ms :parsed}`; a timeout is a result (`:timeout? true`
  with the lines so far), a refusal to start is `:error`; only a cancel of the request leaves as an exception."
  [{:keys [ws e dir prep folder sink on-cancel! budget-ms]} what file-name args json?]
  (let [t0 (System/currentTimeMillis)
        endpoint (get-in e [:aws :endpoint])
        r (try (assoc (tflocal/run-streaming! ws dir (:data-dir prep) args sink on-cancel! budget-ms endpoint) :ok true)
               (catch clojure.lang.ExceptionInfo ex
                 (case (:type (ex-data ex))
                   :process/timeout {:timeout? true :lines (:lines (ex-data ex)) :message (ex-message ex)}
                   :process/cancelled (throw ex)
                   :process/cannot-start {:error (ex-message ex) :lines []}
                   (throw ex))))
        lines (vec (:lines r))
        text (str/join "\n" lines)
        f (io/file folder file-name)
        _ (write! f (str text "\n"))
        raw (try (store/raw-put! ws (.getBytes text "UTF-8")
                                 {:source :file :op "terraform.survey" :env (:name e) :at (Instant/now) :content-type "text/plain" :path (str dir)})
                 (catch Throwable _ nil))]
    (cond-> {:what what :args args :lines lines :ms (- (System/currentTimeMillis) t0) :file (.getPath f)}
      (contains? r :exit) (assoc :exit (:exit r))
      (:timeout? r) (assoc :timeout? true :message (:message r))
      (:error r) (assoc :error (:error r))
      raw (assoc :sha (:sha raw) :path (str (:path raw)))
      json? (assoc :parsed (parse-json-lines lines)))))

(defn- ok? [c] (and (zero? (or (:exit c) -1)) (not (:timeout? c)) (not (:error c))))

(defn- call-summary [c] (select-keys c [:what :exit :timeout? :error :ms :sha :path :file]))

;; the analysis of one root -----------------------------------------------------------------------------------

(defn- data-address? [a] (boolean (re-find #"(^|\.)data\.[^.]+\.[^.]+$" a)))

(defn- static-problem
  "The problem the TEXT of a resource/data address makes certain (its provider is not the emulated one, its aws type is not in the
  measured list), nil when none."
  [address]
  (let [ty (addr-type address)
        prov (when ty (provider-of-type ty))]
    (cond
      (nil? ty) nil
      (and (not= "aws" prov) (not (utility-providers prov))) {:class "other-provider" :subject prov :source "text" :address address}
      (and (= "aws" prov) (not (data-address? address)) (not (#{"aws_iam_policy_document" "aws_partition" "aws_region"} ty)))
      (let [{:keys [state why]} (type-state ty)]
        (when-not (= :supported state)
          {:class "unsupported-service" :subject ty :address address :source "measured list"
           :detail (if (= :unsupported state) (str "Floci answers: " why) (str "not shown to work in the measurement (" why ")"))
           :evidence (name state)}))
      :else nil)))

(defn- group-problems
  "One problem per class and subject, with the addresses that have it (`:addresses`)."
  [ps]
  (vec (for [[_ g] (group-by (juxt :class :subject :source) (remove nil? ps))
             :let [p (first g)
                   as (vec (sort (distinct (keep :address g))))]]
         (cond-> (dissoc p :address) (seq as) (assoc :addresses as)))))

(defn- applicable
  "`[targets skipped]`: of the resource addresses `nodes` those that can run (no problem of their own and none among their
  dependencies `deps`) and, for every other resource, why (`:class :subject` of the first problem on its way, `:via`)."
  [nodes deps bad]
  (let [res (sort (remove #(data-address? %) nodes))
        why (fn [n] (or (when-let [p (bad n)] (assoc (select-keys p [:class :subject :detail :evidence]) :address n))
                        (some (fn [d] (when-let [p (bad d)] (-> (select-keys p [:class :subject :detail :evidence]) (assoc :address n :via d))))
                              (sort (get deps n)))))
        skipped (vec (keep why res))
        sk (set (map :address skipped))]
    [(vec (remove sk res)) skipped]))

(defn- placeholder-vars [inv missing]
  (into {} (for [v (:variables-without-default inv) :when (contains? missing (:name v))] [(:name v) (:placeholder v)])))

(defn- var-args [vars] (mapcat (fn [[k v]] ["-var" (str k "=" v)]) vars))

(defn- diag-problems [parsed modules source]
  (vec (for [d (:diagnostics parsed) :when (and (= "error" (:severity d)) (not (str/starts-with? (str (:summary d)) "Terraform encountered problems during initialisation")))]
         (assoc (classify-diagnostic d modules) :source source))))

(defn- fatal-problem [c modules source]
  (let [ps (when-let [p (:parsed c)] (diag-problems p modules source))]
    (cond
      (seq ps) ps
      (:timeout? c) [{:class "other" :subject (str "terraform " (:what c) " ran longer than the budget and was killed") :detail (:message c) :source source}]
      (:error c) [{:class "other" :subject (:error c) :source source}]
      :else (let [plain (or (some-> c :parsed :plain) (:lines c))
                  l (or (first (filter #(re-find #"(?i)panic|fatal|error|crash" %) plain)) (first (remove str/blank? plain)) (str "exit " (:exit c)))]
              [{:class "other" :subject (str/trim l) :detail (str "terraform " (:what c) " exit " (:exit c)) :source source}]))))

(defn- count-res [nodes] (count (remove data-address? nodes)))

(defn- analyse-root!
  "The whole analysis of one root, -> the row (see the namespace doc) with `:targets` (resource addresses that can run) and `:skipped`.
  `ctx0`: `{:ws :e :vars :siblings :sink :on-cancel! :budget-ms}`. Throws only on cancel."
  [{:keys [ws e vars siblings sink on-cancel! budget-ms needs-by-dir] :as ctx0} ^File dir folder]
  (let [t0 (System/currentTimeMillis)
        inv (inventory dir)
        modules (:modules inv)
        calls (atom [])
        stage! (fn [s] (when sink (sink {:event "survey.stage" :dir (str dir) :stage s})))
        row (fn [verdict problems extra]
              (merge {:dir (str dir) :verdict verdict :problems (group-problems problems) :inventory (dissoc inv :files) :folder (str folder)
                      :calls (mapv call-summary @calls) :ms (- (System/currentTimeMillis) t0)}
                     extra))
        _ (write! (io/file folder "inventory.json") (with-out-str (json/pprint (dissoc inv :modules) :escape-slash false)))
        prep (try (tflocal/prepare! ws e dir siblings)
                  (catch clojure.lang.ExceptionInfo ex
                    (if (= :tf/no-override (:type (ex-data ex)))
                      {:refused (str/join "; " (:problems (ex-data ex)))}
                      (throw ex))))]
    (if (:refused prep)
      (row "does-not-run" [{:class "other" :subject (:refused prep) :source "override" :detail "the override file cannot cover this root"}]
           {:targets [] :skipped []})
      (let [bms (or budget-ms default-budget-ms)
            ctx (assoc ctx0 :dir dir :prep prep :folder folder :budget-ms bms)
            ;; init downloads, graph reads: they get at least two minutes; the budget proper is for plans (a hung data source)
            call! (fn [what file args json?]
                    (let [c (tf-call! (if (#{"init" "graph"} what) (update ctx :budget-ms max 120000) ctx) what file args json?)] (swap! calls conj c) c))
            state-args (when (and (:exempt? prep) (:state prep)) [(str "-state=" (:state prep))])
            all-vars (tflocal/resolve-vars ws e dir vars)
            plan-args (fn [vs targets] (concat ["plan" "-input=false" "-no-color" "-json" "-lock=false"] state-args
                                               (map #(str "-target=" %) targets) (var-args vs)))
            _ (stage! "init")
            init (call! "init" "init.jsonl" ["init" "-input=false" "-no-color" "-json"] true)]
        (if-not (ok? init)
          (row "does-not-run" (fatal-problem init modules "terraform init") {:targets [] :skipped []})
          (let [_ (stage! "graph")
                g (call! "graph" "graph.dot" ["graph"] false)
                graph (when (ok? g) (parse-graph (str/join "\n" (:lines g))))
                nodes (or (:nodes graph) #{})
                deps (or (:deps graph) {})
                static-bad (into {} (keep (fn [n] (when-let [p (static-problem n)] [n p]))) nodes)
                total (count-res nodes)
                hung->problems (fn [c] (for [a (some-> c :parsed :hung) :when (nodes (strip-index a))]
                                         {:class (if (data-address? a) "lookup" "other") :subject (strip-index a) :address (strip-index a)
                                          :detail (str "did not answer within " (quot bms 1000) " s (terraform was killed)") :source "terraform plan"}))
                errors-of (fn [c] (concat (diag-problems (:parsed c) modules "terraform plan") (hung->problems c)))
                add-bad (fn [bad ps] (reduce (fn [bad p] (if (and (:address p) (nodes (:address p)) (not (bad (:address p)))) (assoc bad (:address p) p) bad)) bad ps))
                placeholders-for (fn [ps] (placeholder-vars inv (set (for [p ps :when (= "missing-var" (:class p))] (:subject p)))))
                ;; roots with resources of another provider never get a full plan: it would configure that provider (a real service)
                skip-full? (boolean (some #(= "other-provider" (:class %)) (vals static-bad)))
                plan1 (when-not skip-full?
                        (stage! "plan")
                        (call! "plan" "plan-1-full.jsonl" (plan-args all-vars []) true))
                errs1 (when plan1 (errors-of plan1))
                ph1 (placeholders-for errs1)
                vars1 (merge all-vars ph1)
                plan-full (if (seq ph1) (call! "plan-placeholders" "plan-2-placeholders.jsonl" (plan-args vars1 []) true) plan1)
                missing0 (vec (filter #(= "missing-var" (:class %)) errs1))
                full-ok (and plan-full (ok? plan-full))
                needs (vec (get needs-by-dir (str dir)))]
            (cond
              ;; the full plan passed and the text found nothing against it
              (and full-ok (empty? static-bad) (empty? missing0))
              (row "runs" [] {:targets (vec (sort (remove data-address? nodes))) :skipped [] :needs needs
                              :resources {:total total :local total} :planned (:changes (:parsed plan-full))})
              (nil? graph)
              (row "does-not-run" (concat (fatal-problem g modules "terraform graph") (when (and plan-full (not full-ok)) (errors-of plan-full)))
                   {:targets [] :skipped [] :needs needs})
              :else
              (let [bad0 (add-bad static-bad (when (and plan-full (not full-ok)) (remove #(= "missing-var" (:class %)) (errors-of plan-full))))]
                (loop [bad bad0 i 1 vs vars1 ph ph1 missing missing0]
                  (let [[targets skipped] (applicable nodes deps bad)
                        base (fn [] (concat (for [p missing] (assoc p :detail (str "no value and no default; " (if (contains? ph (:subject p)) "the plan ran with a placeholder" "no placeholder known"))))
                                            (vals bad)))
                        fail-row (fn [extra-problems] (row "does-not-run" (concat (base) extra-problems)
                                                           {:targets [] :skipped skipped :needs needs :resources {:total total :local 0}}))]
                    (if (empty? targets)
                      (fail-row nil)
                      (let [_ (stage! (str "plan -target (" (count targets) " resources, try " i ")"))
                            c (call! (str "plan-targets-" i) (str "plan-" (inc i) "-targets.jsonl") (plan-args vs targets) true)]
                        (if (ok? c)
                          (row (if (and (empty? skipped) (empty? bad) (empty? missing)) "runs" "runs-partly") (base)
                               {:targets targets :skipped skipped :placeholders ph :needs needs
                                :resources {:total total :local (count targets)} :planned (:changes (:parsed c))})
                          (let [ps (errors-of c)
                                new-ph (placeholders-for (remove #(contains? ph (:subject %)) ps))
                                bad' (add-bad bad (remove #(= "missing-var" (:class %)) ps))]
                            (cond
                              (>= i 6) (fail-row (fatal-problem c modules "terraform plan"))
                              (seq new-ph) (recur bad (inc i) (merge vs new-ph) (merge ph new-ph)
                                                  (into missing (filter #(and (= "missing-var" (:class %)) (contains? new-ph (:subject %))) ps)))
                              (not= bad bad') (recur bad' (inc i) vs ph missing)
                              :else (fail-row (fatal-problem c modules "terraform plan")))))))))))))))))

;; one root as a row ---------------------------------------------------------------------------------------------

(defn- root-row
  "`analyse-root!` that never throws for a root: any other exception becomes the row `does-not-run` with its message."
  [ctx dir folder]
  (try (analyse-root! ctx (io/file (str dir)) folder)
       (catch clojure.lang.ExceptionInfo ex
         (if (#{:process/cancelled} (:type (ex-data ex)))
           (throw ex)
           {:dir (str dir) :verdict "does-not-run" :folder (str folder) :targets [] :skipped []
            :problems [{:class "other" :subject (str (ex-message ex)) :source "deliberator" :detail (str (:type (ex-data ex)))}]}))
       (catch Throwable t
         {:dir (str dir) :verdict "does-not-run" :folder (str folder) :targets [] :skipped []
          :problems [{:class "other" :subject (str (.getName (class t)) ": " (.getMessage t)) :source "deliberator"}]})))

;; the table -------------------------------------------------------------------------------------------------------

(defn- problem-text [p] (str (:class p) " " (:subject p)))

(defn- pad [s n] (let [s (str s)] (if (>= (count s) n) s (str s (apply str (repeat (- n (count s)) \space))))))

(defn table
  "The readable text of a survey: one row per root (verdict, resources that run / all, problems), then the detail of every root
  that is not `runs`, then the summary line."
  [rows summary]
  (let [names (map :name rows)
        w (apply max 4 (map count names))
        line (fn [r] (str (pad (:verdict r) 13) (pad (:name r) (inc w))
                          (pad (if-let [rs (:resources r)] (str (:local rs) "/" (:total rs)) "-") 8)
                          (if (seq (:problems r))
                            (str/join "; " (distinct (map problem-text (:problems r))))
                            "-")))]
    (str (pad "VERDICT" 13) (pad "ROOT" (inc w)) (pad "RUN/ALL" 8) "PROBLEMS\n"
         (str/join "\n" (map line rows)) "\n\n"
         (apply str
                (for [r rows :when (not= "runs" (:verdict r))]
                  (str (:name r) "  " (:verdict r) "\n"
                       (apply str (for [p (:problems r)]
                                    (str "  " (problem-text p)
                                         (when (:detail p) (str "\n      " (:detail p)))
                                         (when (:where p) (str "\n      at " (:where p)))
                                         "\n      (" (:source p) ")\n")))
                       (when (seq (:skipped r))
                         (str "  skipped resources (" (count (:skipped r)) "):\n"
                              (apply str (for [s (take 12 (:skipped r))]
                                           (str "    " (:address s) "  <- " (:class s) " " (:subject s) (when (:via s) (str "  (through " (:via s) ")")) "\n")))
                              (when (< 12 (count (:skipped r))) (str "    ... " (- (count (:skipped r)) 12) " more, all in " (:folder r) "/row.json\n"))))
                       (when (seq (:needs r))
                         (str "  looks things up in other roots: apply " (str/join ", " (distinct (map :from (:needs r)))) " first (terraform.apply-all gives the order)\n"))
                       (when (seq (:placeholders r))
                         (str "  planned with placeholders: " (str/join ", " (for [[k v] (sort (:placeholders r))] (str k "=" v))) "\n"))
                       "  raw: " (:folder r) "\n\n")))
         summary "\n")))

;; the survey ----------------------------------------------------------------------------------------------------

(defn survey!
  "`{:dirs [..] :env :stamp :folder :roots [row] :not-roots [{:dir :why}] :summary {:runs n :runs-partly n :does-not-run n} :table text}`.
  `dirs` are repositories, folders of repositories or roots; `vars` explicit values for the roots that declare them; `budget-ms` per
  Terraform run (default `default-budget-ms`); `parallel` roots at a time (default 1). Throws `:tf/local-only` for a remote
  environment and `:tf/bad-roots` for no directory; a root never throws (see the namespace doc)."
  [ws env-name {:keys [dirs vars sink on-cancel! budget-ms parallel only-declared?]}]
  (let [e (env/load ws env-name)
        _ (when-not (= :local (:kind e))
            (throw (ex-info (str "terraform runs only against a local environment; " (:name e) " is " (name (:kind e))) {:type :tf/local-only :env (:name e)})))
        dirs (vec (remove str/blank? (map str dirs)))
        _ (when (empty? dirs) (throw (ex-info "give at least one directory" {:type :tf/bad-roots})))
        _ (doseq [d dirs] (when-not (.isDirectory (io/file d)) (throw (ex-info (str "not a directory: " d) {:type :tf/bad-roots :dir d}))))
        {:keys [roots not-roots]} (discover dirs)
        stamp (.format ts-fmt (Instant/now))
        folder (.toFile (store/path ws "terraform-survey" stamp))
        root-dirs (mapv :dir roots)
        n (count roots)
        needs-by-dir (try (let [g (tfroot/graph root-dirs) nm (into {} (map (juxt :name :dir)) (:roots g))]
                            (reduce (fn [m e] (update m (get nm (:to e)) (fnil conj []) (select-keys e [:from :via :ref]))) {} (:edges g)))
                          (catch Throwable _ {}))
        disp (fn [{:keys [dir under]}]
               (let [top (io/file under)
                     rel (str (.relativize (.toPath top) (.toPath (io/file dir))))]
                 (str (.getName top) (when-not (str/blank? rel) (str "/" rel)))))
        one (fn [i {:keys [dir] :as r}]
              (when sink (sink {:event "survey.root" :dir dir :index (inc i) :of n}))
              (let [rf (io/file folder (tfroot/root-id dir))
                    row (root-row {:ws ws :e e :vars (when vars (into {} (map (fn [[k v]] [(name k) (str v)])) vars))
                                   :siblings (remove #{dir} root-dirs) :needs-by-dir needs-by-dir :sink sink :on-cancel! on-cancel! :budget-ms budget-ms}
                                  dir rf)
                    row (assoc row :name (disp r) :signals (:signals r))]
                (write! (io/file rf "row.json") (with-out-str (json/pprint row :escape-slash false)))
                row))
        rows (if (< 1 (or parallel 1))
               (let [pool (Executors/newFixedThreadPool (int parallel))]
                 (try (mapv deref (mapv (fn [[i r]] (.submit pool ^Callable (fn [] (one i r)))) (map-indexed vector roots)))
                      (finally (.shutdown pool))))
               (vec (map-indexed one roots)))
        rows (mapv #(update % :verdict str) rows)
        summary (frequencies (map :verdict rows))
        sline (str n " root(s): " (get summary "runs" 0) " runs, " (get summary "runs-partly" 0) " runs-partly, " (get summary "does-not-run" 0)
                   " does-not-run" (when (seq not-roots) (str "; " (count not-roots) " director" (if (= 1 (count not-roots)) "y" "ies") " not run as a root (modules)"))
                   "\nmeasured list: " (if-let [m (measured)] (str (count (:supported m)) " aws resource types supported by Floci " (:floci-version m) " (measured " (:measured m) ")") "none")
                   "\nraw output and rows: " folder)
        txt (table rows sline)
        result {:dirs dirs :env (:name e) :stamp stamp :folder (str folder) :roots rows :not-roots not-roots
                :summary {:runs (get summary "runs" 0) :runs-partly (get summary "runs-partly" 0) :does-not-run (get summary "does-not-run" 0)}
                :table txt}]
    (write! (io/file folder "survey.txt") txt)
    (write! (io/file folder "survey.json") (with-out-str (json/pprint (dissoc result :table) :escape-slash false)))
    (when (seq not-roots)
      (write! (io/file folder "not-roots.txt") (str/join "\n" (for [r not-roots] (str (:dir r) "\t" (:why r))))))
    result))

(defn latest
  "The newest persisted survey of the workspace (principle 2: shown at once, with its age): `{:stamp :folder :age-s :table}` or nil."
  [ws]
  (let [d (.toFile (store/path ws "terraform-survey"))
        newest (last (sort (map #(.getName ^File %) (filter #(.isDirectory ^File %) (or (.listFiles d) [])))))]
    (when newest
      (let [f (io/file d newest "survey.txt")]
        (when (.exists f)
          {:stamp newest :folder (str (io/file d newest)) :age-s (quot (- (System/currentTimeMillis) (.lastModified f)) 1000) :table (slurp f)})))))

;; apply what can run -----------------------------------------------------------------------------------------

(defn run-supported!
  "`terraform.local` of apply or destroy restricted to the resources that run on the local runtime (see the namespace doc): the root
  is analysed first (init, graph, the iterated targeted plan; the folder `<ws>/terraform-survey/<stamp>/` keeps it like a survey), then
  `-target=` each resource that can run, and every other resource is listed with its class, in the result (`:skipped`) and
  in the persisted text. A root where no resource can run is `{:ok false}` with the reason, nothing is applied; a variable still
  missing (no value given) is the same. -> the `terraform.local` result plus `:supported-only true :targets :skipped :verdict :survey`."
  [ws env-name {:keys [dir action vars sink on-cancel! budget-ms siblings] :or {action "apply"} :as opts}]
  (let [e (env/load ws env-name)
        _ (when-not (= :local (:kind e))
            (throw (ex-info (str "terraform runs only against a local environment; " (:name e) " is " (name (:kind e))) {:type :tf/local-only :env (:name e)})))
        _ (when-not (#{"apply" "destroy"} action)
            (throw (ex-info "supported-only is for apply and destroy" {:type :tf/bad-action})))
        d (io/file (str dir))
        _ (when-not (and (.isDirectory d) (seq (hcl/tf-files d)))
            (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))
        stamp (.format ts-fmt (Instant/now))
        folder (.toFile (store/path ws "terraform-survey" (str stamp "-" action) (tfroot/root-id dir)))
        row (root-row {:ws ws :e e :vars (when vars (into {} (map (fn [[k v]] [(name k) (str v)])) vars)) :siblings (or siblings []) :sink sink :on-cancel! on-cancel! :budget-ms budget-ms}
                      dir folder)
        explicit (set (map (comp name key) vars))
        still-missing (seq (for [p (:problems row) :when (and (= "missing-var" (:class p)) (not (explicit (:subject p))))] (:subject p)))
        skipped (:skipped row)
        note-lines (concat [(str "supported-only: " (count (:targets row)) " resource(s) run here, " (count skipped) " skipped (verdict " (:verdict row) ")")]
                           (for [s skipped] (str "  skipped " (:address s) ": " (:class s) " " (:subject s) (when (:via s) (str " (through " (:via s) ")")))))
        base {:supported-only true :verdict (:verdict row) :targets (:targets row) :skipped skipped :survey (:folder row) :problems (:problems row)}]
    (write! (io/file folder "row.json") (with-out-str (json/pprint row :escape-slash false)))
    (cond
      (empty? (:targets row))
      (merge {:action action :dir (str dir) :env (:name e) :ok false :exit 1
              :summary (str "nothing in " dir " can run on the local runtime: " (str/join "; " (distinct (map problem-text (:problems row)))))}
             base)
      still-missing
      (merge {:action action :dir (str dir) :env (:name e) :ok false :exit 1
              :summary (str "variables without a value: " (str/join ", " still-missing) " (give them with vars)")}
             base)
      :else
      (merge (tflocal/run! ws (:name e) (assoc opts :targets (:targets row) :notes note-lines :action action)) base))))
