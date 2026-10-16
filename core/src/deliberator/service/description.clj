(ns deliberator.service.description
  "What a service IS, as data: `services/<name>.edn` in the workspace (decision 13's split, M3 piece 02;
  decision 42 has the exact shape). The runtime half (how it runs here) stays in `runtime.edn`;
  `(->spec ws desc)` is the one place a description becomes a runtime `:jar` role.

  Keys: `:name` (`a` -> role `:service/a`, container `deliberator-<ws>-service-a`), `:kind` (`:spring-boot`
  | `:node-lambda`), `:repo` (name of an attached repository, nil = none) and `:module` (relative to that
  repository, absolute when `:repo` is nil), `:build {:cmd [argv] :dir <relative to the module> :artifact
  <relative to the module, the last segment may contain *> :java <major version>}`, `:image`, `:port`,
  `:jdwp`, `:health` (path), `:java-opts`, `:main` (main class, hotswap image), `:image-kind` (`:hotswap`, default
  for the estate image, or `:plain`: any other `:image` is plain), `:reload {:cmd :dir :modules :deps-args}` (see
  `reload-config`), `:depends {\"ENV_VAR\" \"service\"}` (the entry holds that service's URL: topology
  rewrites it, piece 03), `:taskdef {:module <terraform module> :container :key
  :environment {name value} :secrets {name valueFrom}}` (the rendered entries as last adopted; references
  only, never secret values), `:adopted {:at :from :file|:terraform}`.

  `(read ws name)` nil when absent, `(write! ws desc)`, `(names ws)`, `(list ws)`, `(module-dir ws desc)`,
  `(artifact ws desc)` (the built file or nil), `(->spec ws desc)`, `(from-taskdef …)` for the Terraform fallback."
  (:refer-clojure :exclude [read list])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.repo :as repo]
            [deliberator.store :as store])
  (:import (java.io File)
           (java.nio.file Files LinkOption Path)))

(def hotswap-image
  "The service image of the estate (JBR + HotswapAgent, `estate/images/jbr21/`, built by `bb estate:build`)."
  "localhost/deliberator/jbr21-hotswap:21.0.11")
(def plain-image "docker.io/library/eclipse-temurin:21-jre")
(def default-image hotswap-image)
(def kinds #{:spring-boot :node-lambda})

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn image-kind
  "`:hotswap` (JBR + HotswapAgent, exploded classpath, in-process reload) or `:plain` (a JRE running the fat jar, JDWP
  reload). `:image-kind` says it; else an absent `:image` or the hotswap image is hotswap and any other image is plain."
  [{:keys [image image-kind]}]
  (or image-kind (if (or (nil? image) (= image hotswap-image)) :hotswap :plain)))

(defn dir [ws] (store/path ws "services"))
(defn path [ws name] (store/path ws "services" (str (store/safe-name name) ".edn")))

(defn role
  "The runtime role keyword of service `name`."
  [name] (keyword "service" (store/safe-name name)))

(defn validate
  "Throws `service/invalid` unless `desc` has a name, a known kind and, for a spring-boot service, a port."
  [desc]
  (let [bad (cond-> []
              (not (string? (:name desc))) (conj "name")
              (not (kinds (:kind desc))) (conj "kind (spring-boot or node-lambda)")
              (and (= :spring-boot (:kind desc)) (not (integer? (:port desc)))) (conj "port")
              (and (:jdwp desc) (not (integer? (:jdwp desc)))) (conj "jdwp")
              (and (= :spring-boot (:kind desc)) (= :hotswap (image-kind desc)) (not (string? (:main desc))))
              (conj "main (the main class, for the hotswap image; or :image of a plain JRE)")
              (and (contains? desc :reload) (not (map? (:reload desc)))) (conj ":reload ({:cmd [argv] :dir :modules [\"sibling\"]})")
              (and (contains? desc :depends)
                   (not (and (map? (:depends desc)) (every? string? (keys (:depends desc))) (every? string? (vals (:depends desc))))))
              (conj ":depends ({\"ENV_VAR\" \"service\"})"))]
    (when (seq bad)
      (fail :service/invalid (str "invalid service description: " (str/join ", " bad)) :problems bad))
    (store/safe-name (:name desc))
    desc))

(defn read
  "The description of service `name`, nil when absent."
  [ws name]
  (store/read-edn (path ws name)))

(defn names [ws]
  (let [d (dir ws)]
    (if (Files/isDirectory d (make-array LinkOption 0))
      (with-open [st (Files/list d)]
        (->> (iterator-seq (.iterator st)) (map #(str (.getFileName ^Path %)))
             (filter #(str/ends-with? % ".edn")) (map #(subs % 0 (- (count %) 4))) sort vec))
      [])))

(defn list [ws] (vec (keep #(read ws %) (names ws))))

(defn write!
  "Validates and writes `desc`; -> the path."
  [ws desc]
  (validate desc)
  (store/write-edn! (path ws (:name desc)) desc))

;; locations ----------------------------------------------------------------------------------

(defn module-dir
  "The module directory (File) of `desc`; `service/repo-not-attached` when its repository is not attached."
  ^File [ws {:keys [repo module]}]
  (if repo
    (let [r (first (filter #(= repo (:name %)) (repo/list ws)))]
      (when-not r
        (fail :service/repo-not-attached (str "repository " repo " is not attached to workspace " (store/ws-name ws))
              :hint "repo.attach the checkout (dlb repo attach PATH), or service.adopt the module again"))
      (io/file (:path r) module))
    (io/file module)))

(defn- glob->re [s] (re-pattern (str "\\A" (str/join ".*" (map #(java.util.regex.Pattern/quote %) (str/split s #"\*" -1))) "\\z")))

(defn artifact
  "The built artifact file of `desc` (the newest match of a globbed name), or nil when nothing is built."
  [ws desc]
  (let [a (get-in desc [:build :artifact])]
    (when a
      (let [f (io/file (module-dir ws desc) a)]
        (if (str/includes? (.getName f) "*")
          (let [re (glob->re (.getName f))
                cs (filter #(and (.isFile ^File %) (re-matches re (.getName ^File %)) (not (str/ends-with? (.getName ^File %) ".original")))
                           (some-> (.getParentFile f) .listFiles))]
            (last (sort-by #(.lastModified ^File %) cs)))
          (when (.isFile f) f))))))

(defn artifact-path
  "Where the artifact is or will be, as a string (never throws for an unattached repository: the runtime then
  reports the missing jar)."
  [ws desc]
  (try (let [f (or (artifact ws desc) (io/file (module-dir ws desc) (str (get-in desc [:build :artifact]))))]
         (str (.getAbsoluteFile f)))
       (catch clojure.lang.ExceptionInfo _
         (str "/repository-" (:repo desc) "-not-attached/" (:module desc) "/" (get-in desc [:build :artifact])))))

;; description -> runtime spec -----------------------------------------------------------------

(defn runnable? [desc] (= :spring-boot (:kind desc)))

(defn reload-config
  "What `service.reload` and the hotswap role need, from `:reload` with defaults derived from `:build`:
  `{:cmd :dir :modules :deps-args :main}`. `:cmd` defaults to the build command with its `package|verify|install` phase
  replaced by `compile` and `-o` (offline) added; `:dir` to the build dir; `:modules` (sibling modules of that dir whose
  `target/classes` stand in for their jar) to []; `:deps-args` (appended to the build command by `service.build`)
  to `dependency:copy-dependencies -DincludeScope=runtime`."
  [{:keys [build reload main]}]
  (let [cmd (mapv str (:cmd build))
        mvn? (= "mvn" (first cmd))]
    {:cmd (or (:cmd reload)
              (when mvn?
                (let [c (mapv #(if (#{"package" "verify" "install"} %) "compile" %) cmd)]
                  (into [(first c) "-o"] (rest c)))))
     :dir (or (:dir reload) (:dir build) ".")
     :modules (vec (:modules reload))
     :deps-args (or (:deps-args reload) ["dependency:copy-dependencies" "-DincludeScope=runtime"])
     :main main}))

(defn exploded
  "The `:exploded` runtime spec of a hotswap service: `{:dirs [{:seed :staging :mount}] :dependency :main}`. The first dir
  is the service's own `target/classes` at `/app/classes`, then one per `:reload :modules` entry at `/app/classes-<module>`
  (listed first on the classpath, so they shadow the module's jar in `target/dependency`)."
  [ws desc]
  (let [mod (module-dir ws desc)
        rc (reload-config desc)
        base (.getCanonicalFile (io/file mod (:dir rc)))
        stage (fn [m] (.toFile (store/path ws "services" (store/safe-name (:name desc)) (if m (str "classes-" m) "classes"))))]
    {:dirs (into [{:seed (str (io/file mod "target/classes")) :staging (str (stage nil)) :mount "/app/classes"}]
                 (for [m (:modules rc)]
                   {:seed (str (io/file base m "target/classes")) :staging (str (stage m)) :mount (str "/app/classes-" m)}))
     :dependency (str (io/file mod "target/dependency"))
     :main (:main rc)}))

(defn ->spec
  "The runtime `:jar` role of `desc`: image, the jar, `port` and `jdwp` published on the same host ports,
  the adopted environment and secret references, a health probe on the port."
  [ws {:keys [image port jdwp health java-opts taskdef] :as desc}]
  (cond-> {:image (or image default-image)
           :ports (into (sorted-map) (map (fn [p] [p p]) (remove nil? [port jdwp])))
           :env (into (sorted-map) (:environment taskdef))
           :secrets (into (sorted-map) (:secrets taskdef))
           :health {:http (or health "/actuator/health") :port port}}
    (= :hotswap (image-kind desc)) (assoc :exploded (exploded ws desc))
    (not= :hotswap (image-kind desc)) (assoc :jar (artifact-path ws desc))
    jdwp (assoc :debug jdwp)
    (seq java-opts) (assoc :java-opts (vec java-opts))))

;; the Terraform fallback ----------------------------------------------------------------------

(defn parse-taskdef
  "ECS container definitions (JSON text of the `task_definitions` entry) -> `{:container :ports :environment
  :secrets}` of the first container."
  [text]
  (let [cd (first (json/read-str (str text)))]
    {:container (get cd "name")
     :ports (mapv #(get % "containerPort") (get cd "portMappings"))
     :environment (into (sorted-map) (map (fn [e] [(get e "name") (get e "value")]) (get cd "environment")))
     :secrets (into (sorted-map) (map (fn [e] [(get e "name") (get e "valueFrom")]) (get cd "secrets")))}))

(def default-builds
  {:spring-boot {:cmd ["mvn" "-q" "-DskipTests" "package"] :dir "." :artifact "target/*.jar"}
   :node-lambda {:cmd ["npm" "ci"] :dir "."}})

(defn from-taskdef
  "A description derived from a rendered task definition when the module carries no
  `deliberator-service.edn`: kind from the module (pom.xml, package.json), default build, `name` or the
  container name without its `service-` prefix."
  [^File module {:keys [container ports]} {:keys [name jdwp]}]
  (let [kind (cond (.isFile (io/file module "pom.xml")) :spring-boot
                   (.isFile (io/file module "package.json")) :node-lambda
                   :else (fail :service/no-description
                               (str "no deliberator-service.edn in " module " and nothing to derive a kind from (pom.xml or package.json)")
                               :hint "add a deliberator-service.edn to the module"))
        n (or name (some-> container (str/replace #"^service-" "")) (.getName module))]
    (cond-> {:name n :kind kind :build (get default-builds kind)}
      (first ports) (assoc :port (first ports))
      jdwp (assoc :jdwp jdwp))))
