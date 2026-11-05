(ns deliberator.universal
  "The universal JSON: everything Deliberator knows about a workspace that a model needs to
  write scenario steps for it, generated from the daemon's own tables and files and from nothing else. No estate name is
  written in this namespace or in the skill the model gets: if the model must know a thing, the workspace says it.

  `(build ws env-name opts) -> {:json <map> :skills [{:repo :file :src}]}`; `opts`: `:operations` (the rows of
  `op.describe`; the ones the `op` kind offers are listed), `:discover?` (default: true for a `:local` environment, never
  for a remote one: writing steps touches no account), `:discover-ms` (budget of the discovery, 10 s).
  `(write! dir built) -> {:path :skills}` writes `<dir>/universal.json` (`render`) and copies the skill files to
  `<dir>/skills/<repo>/<name>` (the `:path` of each skill entry is relative to `dir`).

  Top level of the JSON: `universal` (format version), `workspace`, `generated` (instant), `environment` {name kind region
  description}, `targets` {key value} (every key is a `{{variable}}`, and a bare `queue/x` on a step line is a target),
  `topology` {service {mode url}}, `services` (the service descriptions: name kind repo module port health image depends), `repositories` {name path
  kind}, `skills` [{repo file path title bytes}] (`<repo>/deliberator/skills/*.md` of every attached checkout), `terraform`
  [{repo dir kind type name ident file line}] (blocks of the `.tf` files, read by `hcl`, never evaluated), `aws` {resources
  errors} (`aws.discover` of a local environment, at most 200 names per kind), `engine-directives`, `step-kinds` (the
  registered kinds minus the inert ones; a kind with more than 40 verbs lists `verb-prefixes` and `verbs-count`),
  `operations` [{op class doc args}], `variables`, `unavailable` {source reason} (a source that failed or was skipped is
  named, never silently empty). `render` writes one entry per line: Grep on the file returns whole entries."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.awsdiscover :as awsdiscover]
            [deliberator.env :as env]
            [deliberator.hcl :as hcl]
            [deliberator.repo :as repo]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.scenario.vars :as vars]
            [deliberator.service.description :as description]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.nio.file Files StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

(def format-version 1)

(def ^:private verb-cap "More verbs than this are listed as prefixes and a count." 40)
(def ^:private discover-cap 200)
(def ^:private terraform-cap 600)
(def ^:private skip-dirs #{".git" ".terraform" "node_modules" ".scratch" "target" "build" ".gradle" ".idea" ".clj-kondo" ".cpcache"})

;; sources --------------------------------------------------------------------------------------

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(def ^:private secret-key #"(?i)pass(word)?|secret|token|credential|private")

(defn- redact
  "A target string without the secret in it: `password=...` style parameters and `user:pass@` in a URL. The model needs
  the key of a target, never its credentials; the daemon resolves the value itself."
  [s]
  (-> (str s)
      (str/replace #"(?i)([?&;\s](?:password|passwd|pwd|secret|token|api[-_]?key|access[-_]?key)=)[^&;\s\"]+" "$1<redacted>")
      (str/replace #"(://[^/:@\s]+:)[^@/\s]+@" "$1<redacted>@")))

(defn- clean
  "A target value without secrets: a map keeps its keys unless they look like one, strings are redacted."
  [v]
  (cond
    (map? v) (into (sorted-map) (for [[k x] v :when (not (re-find secret-key (kw-name k)))] [(kw-name k) (if (string? x) (redact x) x)]))
    (string? v) (redact v)
    :else v))

(defn- environment [e]
  (let [targets (reduce (fn [ts [k _]]
                          (if (= "service" (namespace k))
                            (assoc ts k (try (env/target e k)
                                             (catch clojure.lang.ExceptionInfo x
                                               (if (= :topology/service-off (:type (ex-data x))) "off" (throw x)))))
                            ts))
                        (or (:targets e) {}) (:targets e))]
    {:environment (cond-> {:name (:name e) :kind (name (:kind e)) :region (get-in e [:aws :region])}
                    (:description e) (assoc :description (:description e))
                    (:read-only e) (assoc :read-only true))
     :targets (into (sorted-map) (for [[k v] targets] [(kw-name k) (clean v)]))
     :topology (into (sorted-map)
                     (for [[n t] (:topology e)]
                       [(kw-name n) (cond-> {:mode (kw-name (:mode t))}
                                      (:url t) (assoc :url (:url t)))]))}))

(defn- services [ws]
  (mapv (fn [d] (cond-> (select-keys d [:name :kind :repo :module :port :health :image])
                  (seq (:depends d)) (assoc :depends (:depends d))))
        (description/list ws)))

(defn- skill-dir ^File [r] (io/file (:path r) "deliberator" "skills"))

(defn- title-of [^File f]
  (or (some->> (str/split-lines (slurp f))
               (map #(str/trim (str/replace % #"^#+\s*" "")))
               (remove str/blank?) first)
      (.getName f)))

(defn- skills-of [repos]
  (vec (for [r repos
             :let [d (skill-dir r)]
             :when (.isDirectory d)
             ^File f (sort-by #(.getName ^File %) (.listFiles d))
             :when (and (.isFile f) (str/ends-with? (.getName f) ".md"))]
         {:repo (:name r) :file (.getName f) :src f
          :path (str "skills/" (:name r) "/" (.getName f))
          :title (title-of f) :bytes (.length f)})))

(defn- tf-dirs
  "Directories under `root` (depth <= 6) that hold `.tf` files, skipping tool and build directories."
  [^File root]
  (letfn [(walk [^File d depth]
            (when (and (.isDirectory d) (<= depth 6) (not (skip-dirs (.getName d))))
              (concat (when (seq (hcl/tf-files d)) [d])
                      (mapcat #(walk % (inc depth)) (sort-by #(.getName ^File %) (or (.listFiles d) []))))))]
    (walk root 0)))

(defn- ident [b] (some #(hcl/lit b %) ["name" "function_name" "bucket" "cluster_name" "queue_name" "table_name"]))

(defn- terraform [repos]
  (let [rows (vec (for [r repos
                        :let [root (io/file (:path r))]
                        ^File d (tf-dirs root)
                        b (hcl/parse-dir d)
                        :when (#{"resource" "data" "module"} (:type b))
                        :let [labels (:labels b)]]
                    (cond-> {:repo (:name r)
                             :dir (let [p (.relativize (.toPath root) (.toPath d))] (if (str/blank? (str p)) "." (str p)))
                             :kind (:type b)
                             :type (if (= "module" (:type b)) "module" (first labels))
                             :name (if (= "module" (:type b)) (first labels) (second labels))
                             :file (.getName (io/file (:file b))) :line (:line b)}
                      (ident b) (assoc :ident (ident b)))))]
    {:rows (vec (take terraform-cap rows)) :truncated (> (count rows) terraform-cap)}))

(defn- discovered [ws env-name ms]
  (let [f (future (awsdiscover/discover! ws env-name))
        r (deref f ms ::timeout)]
    (cond
      (= ::timeout r)
      (do (future-cancel f) {:error (str "aws.discover did not answer within " ms " ms")})
      (and (empty? (remove empty? (vals (:resources r)))) (seq (:errors r)))
      {:error (str "aws.discover reached nothing: " (first (distinct (vals (:errors r)))))}
      :else
      {:value (-> r
                  (update :resources (fn [m] (into (sorted-map)
                                                   (for [[k items] m]
                                                     [k (mapv #(select-keys % [:name :cluster :containers]) (take discover-cap items))]))))
                  (select-keys [:resources :errors :truncated]))})))

;; the registries -------------------------------------------------------------------------------

(defn- brief-directive [d] (cond-> {:name (:name d)}
                             (:hint d) (assoc :hint (:hint d))
                             (seq (:values d)) (assoc :values (:values d))
                             (:repeatable d) (assoc :repeatable true)
                             (:doc d) (assoc :doc (:doc d))))

(defn- shape [k]
  (cond-> {:target (:target k)
           :directives (mapv brief-directive (remove :engine (:directives k)))
           :facts (mapv #(str (:name %) ":" (:type %)) (remove #(= "elapsed" (:name %)) (:facts k)))
           :capture-roots (:capture-roots k)}
    (:concurrent k) (assoc :concurrent true)
    (:default-timeout-ms k) (assoc :default-timeout-ms (:default-timeout-ms k))))

(defn- step-kinds []
  (vec (for [k (kinds/describe-kinds) :when (not (:inert k))]
         (let [verbs (:verbs k)
               big? (> (count verbs) verb-cap)]
           (cond-> (assoc (shape k) :kind (:kind k) :verb-optional (:verb-optional k))
             (not big?) (assoc :verbs verbs)
             big? (assoc :verbs-count (count verbs)
                         :verb-prefixes (vec (sort (distinct (map #(first (str/split % #"\." 2)) verbs))))
                         :verb-note (case (:kind k)
                                      "aws" "the verb is service.Operation (the prefixes are the services), e.g. sqs.ListQueues"
                                      "op" "the verb is an operation name, listed in `operations`"
                                      "the verbs are too many to list"))
             (:verb-kinds k) (assoc :verb-kinds (mapv #(assoc (shape %) :verb (:verb %)) (:verb-kinds k))))))))

(defn- first-sentence [s]
  (let [s (str/replace (str s) #"\s+" " ")
        m (re-find #"^.{20,}?[.;:](?=\s|$)" s)]
    (let [t (or m s)] (if (> (count t) 150) (str (subs t 0 147) "...") t))))

(defn- ops-rows [rows]
  (let [offered (:verbs (kinds/get-kind "op"))]
    (vec (for [{:keys [op class doc args order]} rows
               :when (or (nil? offered) (contains? offered op))]
           {:op op :class class :doc (first-sentence doc)
            :args (into (sorted-map)
                        (for [n order :let [a (get args n)] :when (and a (not (:ignored a)) (not (#{"ws" "session"} n)))]
                          [n (str (:type a) (when (:required a) "!"))]))}))))

;; build ----------------------------------------------------------------------------------------

(defmacro ^:private source
  "Runs `body`; a throw is recorded in `unavailable` under `k` and the value is `default`."
  [unavailable k default & body]
  `(try ~@body
        (catch Throwable e#
          (swap! ~unavailable assoc ~k (or (ex-message e#) (str (class e#))))
          ~default)))

(defn build
  [ws env-name {:keys [operations discover? discover-ms] :or {discover-ms 10000}}]
  (let [unavailable (atom (sorted-map))
        e (env/load ws env-name)
        local? (= :local (:kind e))
        discover? (if (some? discover?) discover? local?)
        envs (source unavailable "environment" {:environment {:name env-name}} (environment e))
        repos (source unavailable "repositories" [] (repo/checkouts ws))
        skills (source unavailable "skills" [] (skills-of repos))
        tf (source unavailable "terraform" {:rows []} (terraform repos))
        aws (when discover?
              (let [r (discovered ws env-name discover-ms)]
                (when (:error r) (swap! unavailable assoc "aws" (:error r)))
                (:value r)))
        _ (when-not discover? (swap! unavailable assoc "aws" (str "not asked: environment " env-name " is " (name (:kind e))
                                                                  (when-not local? ", and writing steps touches no account"))))
        json* (cond-> {:universal format-version
                       :workspace (store/ws-name ws)
                       :generated (str (store/now))
                       :environment (:environment envs)
                       :targets (:targets envs)
                       :topology (:topology envs)
                       :services (source unavailable "services" [] (services ws))
                       :repositories (mapv #(select-keys % [:name :path :kind]) repos)
                       :skills (mapv #(select-keys % [:repo :file :path :title :bytes]) skills)
                       :terraform (:rows tf)
                       :aws (or aws {})
                       :engine-directives (mapv brief-directive kinds/engine-directives)
                       :step-kinds (step-kinds)
                       :operations (source unavailable "operations" [] (ops-rows operations))
                       :variables {:builtin (conj (vec vars/builtin-names) "env.name" "env.kind" "step.correlation")
                                   :targets "every environment target key is also a variable, e.g. {{<key>}}"}}
                (:truncated tf) (assoc :terraform-truncated terraform-cap))]
    {:json (assoc json* :unavailable @unavailable)
     :skills (mapv #(select-keys % [:repo :file :src :path]) skills)}))

;; output ---------------------------------------------------------------------------------------

(defn- j [x]
  (json/write-str x :escape-slash false :escape-unicode false
                  :key-fn kw-name
                  :value-fn (fn [_ v] (cond (keyword? v) (kw-name v)
                                            (set? v) (vec (sort v))
                                            (instance? java.nio.file.Path v) (str v)
                                            (instance? java.time.temporal.TemporalAccessor v) (str v)
                                            :else v))))

(def ^:private key-order
  [:universal :workspace :generated :environment :targets :topology :services :repositories :skills :terraform :aws
   :engine-directives :step-kinds :operations :variables :unavailable])

(defn render
  "The JSON text of `m`: top-level keys on their own lines; an array one element per line, an object one entry per
  line, anything deeper compact, so a Grep returns whole entries."
  [m]
  (let [m (concat (for [k key-order :when (contains? m k)] [k (get m k)])
                  (for [[k v] m :when (not (some #{k} key-order))] [k v]))
        entry (fn [[k v]]
                (str "  " (j (kw-name k)) ": "
                     (cond
                       (and (sequential? v) (seq v)) (str "[\n" (str/join ",\n" (map #(str "    " (j %)) v)) "\n  ]")
                       (and (map? v) (seq v)) (str "{\n" (str/join ",\n" (map (fn [[k x]] (str "    " (j (kw-name k)) ": " (j x))) v)) "\n  }")
                       :else (j v))))]
    (str "{\n" (str/join ",\n" (map entry m)) "\n}\n")))

(defn write!
  "Writes the build into `dir`: `universal.json` and the skill files; -> `{:path :skills n}`."
  [dir {:keys [json skills]}]
  (let [d (.toPath (io/file (str dir)))
        _ (Files/createDirectories d (make-array FileAttribute 0))
        f (.resolve d "universal.json")]
    (store/write-bytes-atomic! f (.getBytes ^String (render json) "UTF-8"))
    (doseq [{:keys [repo file src]} skills
            :let [t (.resolve d (str "skills/" repo "/" file))]]
      (Files/createDirectories (.getParent t) (make-array FileAttribute 0))
      (Files/copy (.toPath ^File src) t (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))
    {:path (str f) :skills (count skills)}))
