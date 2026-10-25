(ns deliberator.tflocal
  "Terraform onto the local emulator: `plan` or `apply` of a root directory against a `:local` environment. A remote environment is
  refused here, always (real AWS is changed by Terraform where it normally runs, never by this). The process runs with the
  AWS_* variables and the shared config removed (the root's own provider block carries the fake credentials), its output is
  streamed as `tf.line` events and persisted raw; the process tree is killed when the request is cancelled or the budget (10 min)
  runs out (`deliberator.process`). Variables the root declares are filled from the workspace (no stored state):
  `endpoint` is the environment's endpoint, `container_endpoint`, `service_a_url` and `db_url` come from the runtime's container
  names (`store/container-name`); an explicit `vars` map overrides. Action `vars` only returns the final values, nothing runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.process :as process]
            [deliberator.store :as store])
  (:import (java.io File)))

(defn- declared-vars
  "The names of the variables the .tf files of `dir` declare."
  [^File dir]
  (set (mapcat #(map second (re-seq #"(?m)^\s*variable\s+\"([^\"]+)\"" (slurp %)))
               (filter #(str/ends-with? (.getName ^File %) ".tf") (.listFiles dir)))))

(defn resolve-vars
  "The `-var` values of a run, name -> string: every variable the root declares that the workspace can fill (`endpoint`,
  `container_endpoint`, `service_a_url`, `db_url`), then `vars` (explicit, wins, declared or not)."
  [ws e ^File dir vars]
  (let [c #(store/container-name ws %)
        derived {"endpoint" (get-in e [:aws :endpoint])
                 "container_endpoint" (str "http://" (c "floci") ":4566")
                 "service_a_url" (str "http://" (c "service-a") ":8081")
                 "db_url" (str "jdbc:postgresql://" (c "postgres") ":5432/deliberator")}
        explicit (into {} (map (fn [[k v]] [(name k) (str v)])) vars)]
    (into (sorted-map) (merge (select-keys derived (declared-vars dir)) explicit))))

(defn- scrubbed-env [ws]
  (.mkdirs (.toFile (store/path ws "terraform-plugin-cache")))
  (-> (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_"))) (System/getenv))
      (assoc "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1"
             "TF_PLUGIN_CACHE_DIR" (str (store/path ws "terraform-plugin-cache")))))

(defn- run-streaming!
  "`terraform args` in `dir` through `process/run!`: scrubbed environment, lines streamed as `tf.line`, the tree killed on
  cancel or after `budget-ms` (`:process/timeout`, a `terraform apply` waiting on a state lock does not hold the request for ever)."
  [ws ^File dir args sink on-cancel! budget-ms]
  (process/run! (into ["terraform"] args)
                {:dir dir :env (scrubbed-env ws) :on-cancel! on-cancel! :budget-ms budget-ms :what (str "terraform " (first args))
                 :line! (fn [l] (when sink (sink {:event "tf.line" :line l})))}))

(defn- persist! [ws e action dir lines]
  (let [text (str/join "\n" lines)]
    (assoc (store/raw-put! ws (.getBytes ^String text "UTF-8")
                           {:source :file :op (str "terraform." action "-local") :env (:name e) :at (java.time.Instant/now)
                            :content-type "text/plain" :path (str dir)})
           :text text)))

(defn- run-action! [ws e dir ^File d action var-args all-vars sink on-cancel! budget-ms]
  (let [init-lines (volatile! [])
        [init res]
        (try
          (let [init (run-streaming! ws d ["init" "-input=false" "-no-color"] sink on-cancel! budget-ms)
                _ (vreset! init-lines (:lines init))]
            [init (if (zero? (:exit init))
                    (run-streaming! ws d (concat [action "-input=false" "-no-color"] (when (= action "apply") ["-auto-approve"]) var-args)
                                    sink on-cancel! budget-ms)
                    init)])
          (catch clojure.lang.ExceptionInfo ex
            ;; killed (budget or cancel): what terraform printed so far is persisted before the typed error leaves
            (if (#{:process/timeout :process/cancelled} (:type (ex-data ex)))
              (let [raw (persist! ws e action dir (concat @init-lines (:lines (ex-data ex))))]
                (throw (ex-info (ex-message ex) (assoc (ex-data ex) :sha (:sha raw) :path (str (:path raw)) :dir (str dir)) ex)))
              (throw ex))))
        all (vec (concat (:lines init) (when-not (identical? res init) (:lines res))))
        raw (persist! ws e action dir all)
        summary (or (some #(when (re-find #"^(Plan:|Apply complete!|No changes\.|Error:)" %) %) (reverse all)) "")]
    {:action action :dir (str dir) :env (:name e) :ok (zero? (:exit res)) :exit (:exit res) :summary summary
     :sha (:sha raw) :path (str (:path raw)) :output (:text raw) :vars all-vars}))

(defn run!
  "`{:action :dir :env :ok :exit :summary :sha :path :output :vars}`; `action` is `\"plan\"`, `\"apply\"` or `\"vars\"` (only `{:action :dir :env :vars}`,
  nothing runs); `vars` explicit `-var name=value`s over the ones `resolve-vars` fills.
  `on-cancel!` (the protocol's) kills terraform's process tree when the request is cancelled; `budget-ms` (default
  `process/default-budget-ms`, 10 min) bounds each terraform run (`:process/timeout`, the output so far persisted, `:sha`).
  Throws `{:type :tf/local-only}` unless the environment is `:local`, `{:type :tf/no-root}` for a directory without .tf files."
  [ws env-name {:keys [dir action vars sink on-cancel! budget-ms] :or {action "plan"}}]
  (let [e (env/load ws env-name)
        d (io/file (str dir))]
    (when-not (= :local (:kind e))
      (throw (ex-info (str "terraform runs only against a local environment; " (:name e) " is " (name (:kind e)))
                      {:type :tf/local-only :env (:name e)})))
    (when-not (and (.isDirectory d) (some #(str/ends-with? (.getName ^File %) ".tf") (.listFiles d)))
      (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))
    (when-not (#{"plan" "apply" "vars"} action)
      (throw (ex-info "action is plan, apply or vars" {:type :tf/bad-action})))
    (let [all-vars (resolve-vars ws e d vars)
          var-args (mapcat (fn [[k v]] ["-var" (str k "=" v)]) all-vars)]
      (if (= action "vars")
        {:action action :dir (str dir) :env (:name e) :vars all-vars}
        (run-action! ws e dir d action var-args all-vars sink on-cancel! budget-ms)))))
