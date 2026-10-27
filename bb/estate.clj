(ns estate
  "The estate on the local runtime: `bb estate:build|deploy|up|status|down [--ws W]`.

  - build:  the hotswap service image when missing (estate/images/jbr21, `bb estate:image` rebuilds it), Maven (Java 21 JDK from
            /usr/lib/jvm or $ESTATE_JAVA_HOME) -> estate/services/*/target/*.jar and target/dependency (the exploded classpath)
  - deploy: Floci must be up. Points the workspace's `local` environment at the runtime's host ports,
            `terraform apply` of estate/terraform/floci (scrubbed environment, fake credentials, plugin
            cache under .scratch/), then writes the rendered ECS task definitions into the workspace's
            attaches this repository to the workspace and adopts every `estate/services/*/deliberator-service.edn`
            with its rendered task definition (`service.adopt`); the roles are derived from the descriptions.
            `--auto-publish` sets APP_AUTO_PUBLISH=true on service A.
            Also seeds the second Floci region (eu-west-1) with the same parameters and an `estate/api-key` of its own and writes
            the environment `local2` for it (`service start a --secrets-from local2`).
  - up:     build when a jar is missing, runtime up (Floci, Postgres), deploy, `service.start` of every service.
  - status: runtime status plus the services' health.
  - down:   removes the workspace's containers and the Floci root's Terraform state (Floci keeps
            nothing, a stale state would lie).

  The workspace is `--ws W` (default \"default\"); its home is $DELIBERATOR_HOME. The runtime steps
  (`runtime up|status|down`, `sync-env`, `runtime.set`, `env.save`, `service.*`) go through the daemon (`daemon-client`; the
  service ops need it; nothing here writes `environments/` or `runtime.edn` itself); only Maven and Terraform run out of process. `--no-daemon` runs them in one-shot JVMs as before."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [daemon-client]))

(def repo (str (fs/absolutize ".")))
(def tf-dir (str repo "/estate/terraform/floci"))
(defn services
  "name -> the estate's `deliberator-service.edn` (plus `:dir`), read from estate/services/*."
  []
  (into (sorted-map)
        (for [f (sort (fs/glob (str repo "/estate/services") "*/deliberator-service.edn"))
              :let [d (edn/read-string (slurp (str f)))]]
          [(:name d) (assoc d :dir (str (fs/parent f)))])))

(defn- jar [{:keys [dir build]}] (str dir "/" (:artifact build)))
(defn- built? [{:keys [dir] :as s}] (and (fs/exists? (jar s)) (fs/exists? (str dir "/target/dependency"))))

(defn- arg [args k] (second (drop-while #(not= k %) args)))
(defn- ws-of [args] (or (arg args "--ws") "default"))

(defn- home []
  (or (System/getenv "DELIBERATOR_HOME")
      (if (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
        (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME" {:type "no-default-home"}))
        (str (System/getProperty "user.home") "/.local/share/deliberator"))))

(defn- java21 []
  (or (System/getenv "ESTATE_JAVA_HOME")
      (->> (fs/list-dir "/usr/lib/jvm") (map str) sort
           (filter #(and (re-find #"java-21" %) (fs/exists? (str % "/bin/javac")))) first)
      (throw (ex-info "no JDK 21 in /usr/lib/jvm; set ESTATE_JAVA_HOME" {}))))

(defn- ms-since [t0] (quot (- (System/nanoTime) t0) 1000000))

(defn- step [label f]
  (let [t0 (System/nanoTime) r (f)]
    (println (format "== %s: %d ms" label (ms-since t0)))
    r))

(defn- runtime
  "`daemon-client/runtime` for the task's `args` (they may carry `--no-daemon`); a failure stops the task."
  [args & cmd]
  (let [code (apply daemon-client/runtime (concat cmd (when (some #{"--no-daemon"} args) ["--no-daemon"])))]
    (when-not (zero? code) (throw (ex-info (str "runtime " (first cmd) " failed") {:babashka/exit code})))))

(defn- container
  "The runtime's container name of `role` in `ws` (store/container-name: `deliberator-<ws>-<role>`)."
  [ws role] (str "deliberator-" ws "-" role))

(defn- sha512 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-512")]
    (with-open [in (java.io.FileInputStream. (str f))]
      (let [buf (byte-array 65536)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(def image-dir (str repo "/estate/images/jbr21"))
(def image-ctx (str repo "/.scratch/images/jbr21/ctx"))

(defn image
  "Builds the hotswap service image of image.edn (JBR + HotswapAgent) unless `podman image exists`; `force` rebuilds.
  The archives are downloaded into .scratch/images/jbr21/ctx and verified there before the build."
  [force]
  (let [{:keys [image jbr hsa]} (edn/read-string (slurp (str image-dir "/image.edn")))]
    (if (and (not force) (zero? (:exit (p/shell {:continue true :out :string :err :string} "podman" "image" "exists" image))))
      (println "image" image "present")
      (do
        (fs/create-dirs image-ctx)
        (doseq [{:keys [file url] want :sha512} [jbr hsa] :let [f (str image-ctx "/" file)]]
          (when-not (and (fs/exists? f) (= want (sha512 f)))
            (println "downloading" url)
            (p/shell "curl" "-fsSL" "-o" f url)
            (when-not (= want (sha512 f))
              (fs/delete f)
              (throw (ex-info (str "checksum mismatch for " url) {})))))
        (step (str "podman build " image)
              #(p/shell "podman" "build" "-t" image "-f" (str image-dir "/Containerfile")
                        "--build-arg" (str "JBR_FILE=" (:file jbr)) "--build-arg" (str "JBR_SHA512=" (:sha512 jbr))
                        "--build-arg" (str "HSA_FILE=" (:file hsa)) "--build-arg" (str "HSA_SHA512=" (:sha512 hsa))
                        image-ctx))))))

(defn build []
  (image false)
  (let [jdk (java21)]
    (println "maven with" jdk)
    (p/shell {:dir (str repo "/estate/services") :extra-env {"JAVA_HOME" jdk}} "mvn" "-q" "-DskipTests" "package" "dependency:copy-dependencies" "-DincludeScope=runtime")))

(defn- tf-env []
  (-> (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_")) (System/getenv)))
      (assoc "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null"
             "TF_PLUGIN_CACHE_DIR" (str repo "/.scratch/tf-plugin-cache")
             "TF_IN_AUTOMATION" "1")))

(defn- terraform [& args]
  (fs/create-dirs (str repo "/.scratch/tf-plugin-cache"))
  (apply p/shell {:dir tf-dir :env (tf-env) :extra-env {}} "terraform" args))

(defn state-file
  "The Terraform state of the Floci root for workspace `ws`: `<home>/workspaces/<ws>/terraform/<root-id>/terraform.tfstate`, where core's
  `terraform.local` keeps the state of a root too (`tfroot/state-path`: `<basename>-<8 hex of the sha-256 of the canonical path>`), so
  a plan in the IDE sees what `bb estate:up` applied and two workspaces on one host never share a state (m3 05b)."
  [ws]
  (let [p (.getCanonicalPath (java.io.File. ^String tf-dir))
        hex (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes p "UTF-8"))))]
    (str (home) "/workspaces/" ws "/terraform/" (str/replace (fs/file-name p) #"[^A-Za-z0-9._-]" "_") "-" (subs hex 0 8) "/terraform.tfstate")))

(defn local-env
  "The workspace's `local` environment record (environments/local.edn, written by runtime sync-env)."
  [ws]
  (edn/read-string (slurp (str (home) "/workspaces/" ws "/environments/local.edn"))))

(defn local-endpoint
  "The workspace's `local` AWS endpoint."
  [ws]
  (get-in (local-env ws) [:aws :endpoint]))

(defn- write-local2!
  "The environment `local2` of the workspace: the same Floci as `local`, region eu-west-1, where terraform seeded
  the same parameter names and an `estate/api-key` of its own. The stand-in for a remote environment until M4
  (`dlb service start a --secrets-from local2`). Written through the daemon (`env.save`, validated), never as a file (R33)."
  [ws]
  (let [local (:env (daemon-client/call! "env.describe" {:ws ws :name "local"}))
        e {:kind "local"
           :description "Second local environment (eu-west-1 of the same Floci): other secret values, stand-in for a remote environment."
           :aws (assoc (:aws local) :region "eu-west-1")
           :targets (:targets local)}]
    (daemon-client/call! "env.save" {:ws ws :name "local2" :record e})))

(defn- tf-outputs
  "`terraform output -json` of the Floci root of `ws` as {name value}."
  [ws]
  (into {} (for [[k v] (json/parse-string (:out (p/shell {:dir tf-dir :env (tf-env) :out :string}
                                                           "terraform" "output" "-json" "-no-color" (str "-state=" (state-file ws)))))]
             [k (get v "value")])))

(defn- write-chain-targets!
  "The targets the chain adds to the workspace's `local` environment, from the Terraform outputs: the HTTP API
  (`api/main`, Floci's execute-api path for the `$default` stage), the two Lambdas and their log groups, the schedule group.
  Through the daemon (`env.save`, whole record), like `local2`."
  [ws out]
  (let [local (:env (daemon-client/call! "env.describe" {:ws ws :name "local"}))
        fns {"scheduler-create" (get out "scheduler_create_function") "ecs-scale" (get out "ecs_scale_function")}
        targets (merge {"api/main" (str (get-in local [:aws :endpoint]) "/_aws/execute-api/" (get out "api_id") "/%24default")
                        "scheduler/chain" (get out "schedule_group")}
                       (into {} (for [[k f] fns] [(str "function/" k) f]))
                       (into {} (for [[k f] fns] [(str "log/" k) {:cloudwatch (str "/aws/lambda/" f)}])))]
    (daemon-client/call! "env.save" {:ws ws :name "local"
                                     :record (update local :targets merge (update-keys targets keyword))})))

(defn deploy [args]
  (let [ws (ws-of args)
        auto (if (some #{"--auto-publish"} args) "true" "false")]
    (step "local environment follows runtime.edn" #(runtime args "sync-env" "--ws" ws))
    (let [endpoint (local-endpoint ws)]
      (println "floci endpoint" endpoint)
      (step "terraform init" #(terraform "init" "-input=false" "-no-color"))
      (fs/create-dirs (fs/parent (state-file ws)))
      (step "terraform apply" #(terraform "apply" "-auto-approve" "-input=false" "-no-color" (str "-state=" (state-file ws))
                                          "-var" (str "endpoint=" endpoint) "-var" (str "auto_publish=" auto)
                                          "-var" (str "container_endpoint=http://" (container ws "floci") ":4566")
                                          "-var" (str "service_a_url=http://" (container ws "service-a") ":8081")
                                          "-var" (str "db_url=jdbc:postgresql://" (container ws "postgres") ":5432/deliberator"))))
    (write-local2! (ws-of args))
    (step "chain targets in the local environment" #(write-chain-targets! ws (tf-outputs ws)))
    (step "adopt services"
          (fn []
            (let [out (json/parse-string (:out (p/shell {:dir tf-dir :env (tf-env) :out :string}
                                                        "terraform" "output" "-json" "-no-color" (str "-state=" (state-file ws)))))
                  tds (get-in out ["task_definitions" "value"])]
              (daemon-client/call! "repo.attach" {:ws ws :path repo})
              (fs/create-dirs (str repo "/.scratch/estate"))
              (doseq [[n s] (services)
                      :let [f (str repo "/.scratch/estate/taskdef-" n ".json")]]
                (spit f (get tds (get-in s [:taskdef :key])))
                (daemon-client/call! "service.adopt" {:ws ws :path (:dir s) :taskdef f})))))))

(defn- set-postgres-init!
  "The estate's SQL init directory is infrastructure of the estate: set it as Postgres' `:init` in the
  workspace's runtime.edn (created by `sync-env` when absent) before the infrastructure comes up. Through the daemon
  (`runtime.set`), never by editing the file (R33)."
  [args ws]
  (runtime args "sync-env" "--ws" ws)
  (let [f (str (home) "/workspaces/" ws "/runtime.edn")
        cfg (edn/read-string (slurp f))
        init (str repo "/estate/sql")]
    (when (and (get-in cfg [:containers :postgres]) (not= init (get-in cfg [:containers :postgres :init])))
      (daemon-client/call! "runtime.set" {:ws ws :path ["containers" "postgres" "init"] :value init}))))

(defn up [args]
  (let [ws (ws-of args) t0 (System/nanoTime)]
    (image false)
    (when-not (every? built? (vals (services)))
      (step "build" build))
    (set-postgres-init! args ws)
    (step "runtime (floci, postgres)" #(runtime args "up" "--infra" "--ws" ws))
    (deploy args)
    (doseq [n (keys (services))]
      (step (str "service start " n) #(daemon-client/call! "service.start" {:ws ws :name n})))
    (println (format "estate up in %d ms" (ms-since t0)))))

(defn- health [url]
  (try (let [r (p/shell {:out :string :err :string :continue true} "curl" "-s" "-m" "3" url)]
         (if (zero? (:exit r)) (str/trim (:out r)) "no answer"))
       (catch Exception e (str e))))

(defn status [args]
  (let [ws (ws-of args)]
    (runtime args "status" "--ws" ws)
    (doseq [n ["a" "b"]
            :let [url (get-in (local-env ws) [:targets (keyword (str "service/" n))])]]
      (println (str "service-" n " " url " /actuator/health") (health (str url "/actuator/health"))))))

(defn down [args]
  (let [ws (ws-of args)]
    (runtime args "down" "--ws" ws)
    (doseq [f (concat (fs/glob tf-dir "terraform.tfstate*")                     ; the state before it was per workspace
                      (fs/glob (fs/parent (state-file ws)) "terraform.tfstate*"))]
      (fs/delete f))
    (println "terraform state of the floci root removed")))
