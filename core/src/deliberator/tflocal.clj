(ns deliberator.tflocal
  "Terraform onto the local emulator: `plan` or `apply` of a root directory against a `:local` environment. A remote environment is
  refused here, always (real AWS is changed by Terraform where it normally runs, never by this). The process runs with the
  AWS_* variables and the shared config removed (the root's own provider block carries the fake credentials), its output is
  streamed as `tf.line` events and persisted raw, and the root's `endpoint` variable, when it has one, is set to the environment's endpoint."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.store :as store])
  (:import (java.io File)))

(defn- declares-endpoint? [^File dir]
  (boolean (some #(re-find #"(?m)^\s*variable\s+\"endpoint\"" (slurp %))
                 (filter #(str/ends-with? (.getName ^File %) ".tf") (.listFiles dir)))))

(defn- scrubbed-env [ws]
  (.mkdirs (.toFile (store/path ws "terraform-plugin-cache")))
  (-> (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_"))) (System/getenv))
      (assoc "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null" "TF_IN_AUTOMATION" "1"
             "TF_PLUGIN_CACHE_DIR" (str (store/path ws "terraform-plugin-cache")))))

(defn- run-streaming! [ws ^File dir args sink]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into ["terraform"] args))
             (.directory dir) (.redirectErrorStream true))
        _ (doto (.environment pb) (.clear) (.putAll (scrubbed-env ws)))
        p (.start pb)
        lines (java.util.ArrayList.)]
    (with-open [r (io/reader (.getInputStream p))]
      (doseq [l (line-seq r)]
        (.add lines l)
        (when sink (sink {:event "tf.line" :line l}))))
    {:exit (.waitFor p) :lines (vec lines)}))

(defn run!
  "`{:action :dir :env :ok :exit :summary :sha :path}`; `action` is `\"plan\"` or `\"apply\"`; `vars` extra `-var name=value`s.
  Throws `{:type :tf/local-only}` unless the environment is `:local`, `{:type :tf/no-root}` for a directory without .tf files."
  [ws env-name {:keys [dir action vars sink] :or {action "plan"}}]
  (let [e (env/load ws env-name)
        d (io/file (str dir))]
    (when-not (= :local (:kind e))
      (throw (ex-info (str "terraform runs only against a local environment; " (:name e) " is " (name (:kind e)))
                      {:type :tf/local-only :env (:name e)})))
    (when-not (and (.isDirectory d) (some #(str/ends-with? (.getName ^File %) ".tf") (.listFiles d)))
      (throw (ex-info (str "no Terraform files in " dir) {:type :tf/no-root :dir (str dir)})))
    (when-not (#{"plan" "apply"} action)
      (throw (ex-info "action is plan or apply" {:type :tf/bad-action})))
    (let [endpoint (get-in e [:aws :endpoint])
          all-vars (cond-> (or vars {}) (and (declares-endpoint? d) (not (contains? (or vars {}) "endpoint"))) (assoc "endpoint" endpoint))
          var-args (mapcat (fn [[k v]] ["-var" (str k "=" v)]) all-vars)
          init (run-streaming! ws d ["init" "-input=false" "-no-color"] sink)
          res (if (zero? (:exit init))
                (run-streaming! ws d (concat [action "-input=false" "-no-color"] (when (= action "apply") ["-auto-approve"]) var-args) sink)
                init)
          all (vec (concat (:lines init) (when-not (identical? res init) (:lines res))))
          text (str/join "\n" all)
          raw (store/raw-put! ws (.getBytes ^String text "UTF-8")
                              {:source :file :op (str "terraform." action "-local") :env (:name e) :at (java.time.Instant/now)
                               :content-type "text/plain" :path (str dir)})
          summary (or (some #(when (re-find #"^(Plan:|Apply complete!|No changes\.|Error:)" %) %) (reverse all)) "")]
      {:action action :dir (str dir) :env (:name e) :ok (zero? (:exit res)) :exit (:exit res) :summary summary
       :sha (:sha raw) :path (str (:path raw)) :output text})))
