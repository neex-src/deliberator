(ns deliberator.tflocal
  "Terraform onto the local emulator: `plan` or `apply` of a root directory against a `:local` environment. A remote environment is
  refused here, always (real AWS is changed by Terraform where it normally runs, never by this). The process runs with the
  AWS_* variables and the shared config removed (the root's own provider block carries the fake credentials), its output is
  streamed as `tf.line` events and persisted raw; the process tree is killed when the request is cancelled or the budget (10 min)
  runs out (`deliberator.process`). Variables the root declares are filled from the workspace (no stored state):
  `endpoint` is the environment's endpoint, `container_endpoint`, `service_a_url` and `db_url` come from the runtime's container
  names (`store/container-name`); an explicit `vars` map overrides. Action `vars` only returns the final values, nothing runs.

  Any root runs here: before `init` the file `<root>/deliberator_override.tf.json` is written (`tfroot`): the
  root's `provider \"aws\"` blocks point at the emulator with fake credentials and the state is a local one under the workspace,
  `TF_DATA_DIR` too, so the checkout's own `.terraform` and state stay what they are. The file is never removed (it is the
  user's; without the generated marker line it is theirs to edit and is never rewritten), a missing gitignore entry is
  reported loudly, a root whose providers the override does not cover is refused (`:tf/no-override`), except a root that
  already points its own providers at an emulator (the estate's Floci root). Action `override` writes the file and runs nothing."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.process :as process]
            [deliberator.store :as store]
            [deliberator.tfroot :as tfroot])
  (:import (java.io File)))

(defn declared-vars
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

(def ^:private env-allowed
  "What a Terraform run of the local runtime inherits from the daemon's environment: the shell basics, proxies, the ssh agent (a
  git module of a private repository is fetched as the user's `terraform init` would), `TF_VAR_*` and `TF_CLI_*`. Nothing else:
  a Datadog, GitHub, Vault or cloud token of the user's shell never reaches a provider that is not the emulator."
  #"^(PATH|HOME|USER|LOGNAME|SHELL|LANG|LANGUAGE|LC_[A-Z_]+|TERM|TMPDIR|TZ|SSH_AUTH_SOCK|XDG_[A-Z_]+|HTTPS?_PROXY|https?_proxy|NO_PROXY|no_proxy|SSL_CERT_(FILE|DIR)|GIT_SSH_COMMAND|TF_VAR_.+|TF_CLI_.+|TF_REGISTRY_.+)$")

(defn scrubbed-env
  "The environment of a Terraform process: `env-allowed` only, plus what keeps it on the emulator and off every prompt: fake
  AWS keys, `AWS_ENDPOINT_URL` (every aws service not named in the override's `endpoints` goes to the emulator too, never to the
  real AWS), no metadata lookups, no shared config, git and ssh that never ask."
  ([ws data-dir] (scrubbed-env ws data-dir nil))
  ([ws data-dir endpoint]
   (.mkdirs (.toFile (store/path ws "terraform-plugin-cache")))
   (let [base (into {} (filter (fn [[k _]] (re-matches env-allowed k))) (System/getenv))]
     (cond-> (assoc base
                    "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1"
                    "AWS_ACCESS_KEY_ID" "test" "AWS_SECRET_ACCESS_KEY" "test" "AWS_EC2_METADATA_DISABLED" "true"
                    "GIT_TERMINAL_PROMPT" "0" "TF_PLUGIN_CACHE_DIR" (str (store/path ws "terraform-plugin-cache")))
       (not (contains? base "GIT_SSH_COMMAND")) (assoc "GIT_SSH_COMMAND" "ssh -o BatchMode=yes -o ConnectTimeout=15")
       endpoint (assoc "AWS_ENDPOINT_URL" endpoint)
       data-dir (assoc "TF_DATA_DIR" data-dir)))))

(defn run-streaming!
  "`terraform args` in `dir` through `process/run!`: scrubbed environment (`endpoint` as AWS_ENDPOINT_URL when given), lines
  streamed as `tf.line`, the tree killed on cancel or after `budget-ms` (`:process/timeout`, a `terraform apply` waiting on a state
  lock does not hold the request for ever)."
  ([ws dir data-dir args sink on-cancel! budget-ms] (run-streaming! ws dir data-dir args sink on-cancel! budget-ms nil))
  ([ws ^File dir data-dir args sink on-cancel! budget-ms endpoint]
   (process/run! (into ["terraform"] args)
                 {:dir dir :env (scrubbed-env ws data-dir endpoint) :on-cancel! on-cancel! :budget-ms budget-ms :what (str "terraform " (first args))
                  :line! (fn [l] (when sink (sink {:event "tf.line" :line l})))})))

(defn persist! [ws e action dir lines]
  (let [text (str/join "\n" lines)]
    (assoc (store/raw-put! ws (.getBytes ^String text "UTF-8")
                           {:source :file :op (str "terraform." action "-local") :env (:name e) :at (java.time.Instant/now)
                            :content-type "text/plain" :path (str dir)})
           :text text)))

(defn- git-ignored?
  "True / false when `git check-ignore` says the file is / is not ignored in its repository, nil when the directory is not in one
  (or git is not there)."
  [^File d name]
  (try (let [r (shell/with-sh-dir d (shell/sh "git" "check-ignore" "-q" "--" name))]
         (case (:exit r) 0 true 1 false nil))
       (catch Exception _ nil)))

(defn prepare!
  "Everything before `init`: the override file of root `d` (written when it is not the user's own and its text changed),
  its coverage check (`:tf/no-override` when an `aws` provider is left uncovered), the gitignore check. A root that already points
  its own providers at an emulator needs and gets no file. -> `{:exempt? :file :written? :services :state :data-dir :warnings
  :pre-lines}`; `:pre-lines` are the warnings as the text lines `tf.line` events carry (WARNING: ...)."
  [ws e ^File d siblings]
  (let [a (tfroot/analyse d)
        f (io/file d tfroot/override-name)]
    (if (:emulator? a)
      ;; a root that points itself at the emulator needs no override, but its state is the workspace's, not the checkout's:
      ;; two workspaces on one host apply the same root onto two emulators (m3 05b)
      (do (.mkdirs (.toFile (tfroot/state-dir ws d)))
          {:exempt? true :services (vec (sort (:services a))) :warnings [] :pre-lines [] :state (tfroot/state-path ws d)})
      (let [aws (:aws e)
            creds (:credentials aws)
            doc (tfroot/override-doc a {:endpoint (:endpoint aws) :region (:region aws)
                                        :access-key (or (:access-key-id creds) "test") :secret-key (or (:secret-access-key creds) "test")
                                        :state-path (tfroot/state-path ws d)
                                        :remote-states (tfroot/remote-state-targets ws d (cons d siblings))})
            theirs? (and (.exists f) (not (tfroot/generated? f)))
            effective (if theirs?
                        (try (json/read-str (slurp f))
                             (catch Exception ex
                               (throw (ex-info (str (.getPath f) " is not readable JSON and has no generated marker, so it is yours and is left alone: " (ex-message ex))
                                               {:type :tf/no-override :problems ["unreadable override file"] :file (.getPath f)}))))
                        doc)
            problems (tfroot/coverage-problems a effective)]
        (when (seq problems)
          (throw (ex-info (str "terraform.local refuses " d ": " (str/join "; " problems))
                          {:type :tf/no-override :problems problems :file (.getPath f)
                           :hint (str "a root runs on the local runtime only through " tfroot/override-name
                                      " (written for you unless you took the file over), which must cover every provider \"aws\" block")})))
        (.mkdirs (.toFile (tfroot/state-dir ws d)))
        (let [pretty (with-out-str (json/pprint doc :escape-slash false) (println))
              write? (and (not theirs?) (not= pretty (when (.exists f) (slurp f))))
              _ (when write? (spit f pretty))
              ign (git-ignored? d tfroot/override-name)
              unredirected (remove (set (keys (tfroot/remote-state-targets ws d (cons d siblings)))) (map :name (:remote-states a)))
              warnings (vec (concat
                             (when (false? ign)
                               [(str tfroot/override-name " in " d " is NOT gitignored: add the line `" tfroot/override-name
                                     "` to the repository's .gitignore (it holds fake keys and local endpoints and must not be committed)")])
                             (for [n unredirected]
                               (str "data.terraform_remote_state." n " is not redirected to a local state: give the root it reads along (terraform.apply-all) or it reads the real backend"))
                             (when (seq (:unmapped a))
                               [(str "no local endpoint is known for " (str/join ", " (:unmapped a)) ": those calls are not sent to the emulator")])))]
          {:exempt? false :file (.getPath f) :written? write? :user-owned? theirs? :services (vec (sort (conj (:services a) "sts")))
           :state (tfroot/state-path ws d) :data-dir (tfroot/data-dir ws d) :warnings warnings
           :pre-lines (mapv #(str "WARNING: " %) warnings)})))))

(defn- run-action! [ws e dir ^File d prep action var-args all-vars sink on-cancel! budget-ms targets notes]
  (let [init-lines (volatile! [])
        pre (vec (concat (:pre-lines prep) notes))
        endpoint (get-in e [:aws :endpoint])
        _ (when sink (doseq [l pre] (sink {:event "tf.line" :line l})))
        data-dir (:data-dir prep)
        [init res]
        (try
          (let [init (run-streaming! ws d data-dir ["init" "-input=false" "-no-color"] sink on-cancel! budget-ms endpoint)
                _ (vreset! init-lines (:lines init))]
            [init (if (zero? (:exit init))
                    (run-streaming! ws d data-dir (concat [action "-input=false" "-no-color"]
                                                          (when (and (:exempt? prep) (:state prep)) [(str "-state=" (:state prep))])
                                                          (when (#{"apply" "destroy"} action) ["-auto-approve"]) (map #(str "-target=" %) targets) var-args)
                                    sink on-cancel! budget-ms endpoint)
                    init)])
          (catch clojure.lang.ExceptionInfo ex
            ;; killed (budget or cancel): what terraform printed so far is persisted before the typed error leaves
            (if (#{:process/timeout :process/cancelled} (:type (ex-data ex)))
              (let [raw (persist! ws e action dir (concat pre @init-lines (:lines (ex-data ex))))]
                (throw (ex-info (ex-message ex) (assoc (ex-data ex) :sha (:sha raw) :path (str (:path raw)) :dir (str dir)) ex)))
              (throw ex))))
        all (vec (concat pre (:lines init) (when-not (identical? res init) (:lines res))))
        raw (persist! ws e action dir all)
        summary (or (some #(when (re-find #"^(Plan:|Apply complete!|Destroy complete!|No changes\.|Error:)" %) %) (reverse all)) "")]
    (cond-> {:action action :dir (str dir) :env (:name e) :ok (zero? (:exit res)) :exit (:exit res) :summary summary
             :sha (:sha raw) :path (str (:path raw)) :output (:text raw) :vars all-vars}
      (not (:exempt? prep)) (assoc :override (select-keys prep [:file :written? :user-owned? :services :state]))
      (seq (:warnings prep)) (assoc :warnings (:warnings prep)))))

(defn run!
  "`{:action :dir :env :ok :exit :summary :sha :path :output :vars [:override :warnings]}`; `action` is `\"plan\"`, `\"apply\"`, `\"destroy\"`,
  `\"vars\"` (only `{:action :dir :env :vars}`, nothing runs) or `\"override\"` (writes the override file, runs nothing:
  `{:action :dir :env :override :warnings}`); `vars` explicit `-var name=value`s over the ones `resolve-vars` fills; `siblings`
  other roots whose state a `terraform_remote_state` of this root may read (`terraform.apply-all` gives the roots it runs).
  `only-declared?` drops the explicit `vars` the root does not declare (several roots, one map).
  `on-cancel!` (the protocol's) kills terraform's process tree when the request is cancelled; `budget-ms` (default
  `process/default-budget-ms`, 10 min) bounds each terraform run (`:process/timeout`, the output so far persisted, `:sha`).
  `targets` (resource addresses) becomes one `-target=` each, `notes` (text lines) are put in front of the persisted output
  (`deliberator.tfsurvey/run-supported!` gives both: the resources that can run here and why the others do not).
  Throws `{:type :tf/local-only}` unless the environment is `:local`, `{:type :tf/no-root}` for a directory without .tf files,
  `{:type :tf/no-override}` for a root the override cannot cover."
  [ws env-name {:keys [dir action vars sink on-cancel! budget-ms siblings only-declared? targets notes] :or {action "plan"}}]
  (let [e (env/load ws env-name)
        d (io/file (str dir))]
    (when-not (= :local (:kind e))
      (throw (ex-info (str "terraform runs only against a local environment; " (:name e) " is " (name (:kind e)))
                      {:type :tf/local-only :env (:name e)})))
    (when-not (and (.isDirectory d) (some #(str/ends-with? (.getName ^File %) ".tf") (.listFiles d)))
      (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))
    (when-not (#{"plan" "apply" "destroy" "vars" "override"} action)
      (throw (ex-info "action is plan, apply, destroy, vars or override" {:type :tf/bad-action})))
    (let [vars (if only-declared?
                 (let [decl (declared-vars d)] (into {} (filter (fn [[k _]] (decl (name k)))) vars))
                 vars)
          all-vars (resolve-vars ws e d vars)
          var-args (mapcat (fn [[k v]] ["-var" (str k "=" v)]) all-vars)]
      (case action
        "vars" {:action action :dir (str dir) :env (:name e) :vars all-vars}
        "override" (let [prep (prepare! ws e d siblings)]
                     (cond-> {:action action :dir (str dir) :env (:name e) :exempt (boolean (:exempt? prep))
                              :override (select-keys prep [:file :written? :user-owned? :services :state])}
                       (seq (:warnings prep)) (assoc :warnings (:warnings prep))))
        (run-action! ws e dir d (prepare! ws e d siblings) action var-args all-vars sink on-cancel! budget-ms targets notes)))))
