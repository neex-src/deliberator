(ns estate
  "The estate on the local runtime: `bb estate:build|deploy|up|status|down [--ws W]`.

  - build:  Maven (Java 21 JDK from /usr/lib/jvm or $ESTATE_JAVA_HOME) -> estate/services/*/target/*.jar
  - deploy: Floci must be up. Points the workspace's `local` environment at the runtime's host ports,
            `terraform apply` of estate/terraform/floci (scrubbed environment, fake credentials, plugin
            cache under .scratch/), then writes the rendered ECS task definitions into the workspace's
            `runtime.edn` as the `:service/a` / `:service/b` roles (`deliberator.runtime adopt-taskdefs`).
            `--auto-publish` sets APP_AUTO_PUBLISH=true on service A.
  - up:     build when a jar is missing, runtime up (Floci, Postgres), deploy, runtime up (services).
  - status: runtime status plus the services' health.
  - down:   removes the workspace's containers and the Floci root's Terraform state (Floci keeps
            nothing, a stale state would lie).

  The workspace is `--ws W` (default \"default\"); its home is $DELIBERATOR_HOME."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo (str (fs/absolutize ".")))
(def tf-dir (str repo "/estate/terraform/floci"))
(def services {"a" {:jar "estate/services/service-a/target/service-a-0.1.0.jar" :debug 5005}
               "b" {:jar "estate/services/service-b/target/service-b-0.1.0.jar" :debug 5006}})

(defn- arg [args k] (second (drop-while #(not= k %) args)))
(defn- ws-of [args] (or (arg args "--ws") "default"))

(defn- home []
  (or (System/getenv "DELIBERATOR_HOME") (str (System/getProperty "user.home") "/.local/share/deliberator")))

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

(defn- runtime [& args]
  (apply p/shell {:dir (str repo "/core")} "clojure" "-M" "-m" "deliberator.runtime" args))

(defn build []
  (let [jdk (java21)]
    (println "maven with" jdk)
    (p/shell {:dir (str repo "/estate/services") :extra-env {"JAVA_HOME" jdk}} "mvn" "-q" "-DskipTests" "package")))

(defn- tf-env []
  (-> (into {} (remove (fn [[k _]] (str/starts-with? k "AWS_")) (System/getenv)))
      (assoc "AWS_CONFIG_FILE" "/dev/null" "AWS_SHARED_CREDENTIALS_FILE" "/dev/null"
             "TF_PLUGIN_CACHE_DIR" (str repo "/.scratch/tf-plugin-cache")
             "TF_IN_AUTOMATION" "1")))

(defn- terraform [& args]
  (fs/create-dirs (str repo "/.scratch/tf-plugin-cache"))
  (apply p/shell {:dir tf-dir :env (tf-env) :extra-env {}} "terraform" args))

(defn- local-endpoint [ws]
  (let [f (str (home) "/workspaces/" ws "/environments/local.edn")]
    (get-in (edn/read-string (slurp f)) [:aws :endpoint])))

(defn deploy [args]
  (let [ws (ws-of args)
        auto (if (some #{"--auto-publish"} args) "true" "false")]
    (step "local environment follows runtime.edn" #(runtime "sync-env" "--ws" ws))
    (let [endpoint (local-endpoint ws)]
      (println "floci endpoint" endpoint)
      (step "terraform init" #(terraform "init" "-input=false" "-no-color"))
      (step "terraform apply" #(terraform "apply" "-auto-approve" "-input=false" "-no-color"
                                          "-var" (str "endpoint=" endpoint) "-var" (str "auto_publish=" auto))))
    (step "adopt task definitions"
          (fn []
            (let [out (json/parse-string (:out (p/shell {:dir tf-dir :env (tf-env) :out :string}
                                                        "terraform" "output" "-json" "-no-color")))
                  tds (get-in out ["task_definitions" "value"])
                  d {:root repo
                     :postgres-init (str repo "/estate/sql")
                     :services (into {} (for [[n s] services] [n (assoc s :taskdef (get tds n))]))}
                  f (str repo "/.scratch/estate/adopt.edn")]
              (fs/create-dirs (fs/parent f))
              (spit f (pr-str d))
              (runtime "adopt-taskdefs" "--ws" ws "--file" f))))))

(defn up [args]
  (let [ws (ws-of args) t0 (System/nanoTime)]
    (when-not (every? #(fs/exists? (str repo "/" (:jar %))) (vals services))
      (step "build" build))
    (step "runtime (floci, postgres)" #(runtime "up" "--infra" "--ws" ws))
    (deploy args)
    (step "runtime (services)" #(runtime "up" "--ws" ws))
    (println (format "estate up in %d ms" (ms-since t0)))))

(defn- health [url]
  (try (let [r (p/shell {:out :string :err :string :continue true} "curl" "-s" "-m" "3" url)]
         (if (zero? (:exit r)) (str/trim (:out r)) "no answer"))
       (catch Exception e (str e))))

(defn status [args]
  (let [ws (ws-of args)]
    (runtime "status" "--ws" ws)
    (doseq [[n port] [["a" 8081] ["b" 8082]]]
      (println (str "service-" n " :" port " /actuator/health") (health (str "http://localhost:" port "/actuator/health"))))))

(defn down [args]
  (let [ws (ws-of args)]
    (runtime "down" "--ws" ws)
    (doseq [f (fs/glob tf-dir "terraform.tfstate*")] (fs/delete f))
    (println "terraform state of the floci root removed")))
