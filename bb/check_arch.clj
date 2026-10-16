(ns check-arch
  "`bb check:arch`: the layering rules of CLAUDE.md as a build check over the tracked and
  untracked-not-ignored files of the repository.

  - core/ never references `com.intellij`.
  - cli/ contains no `deliberator.` namespace (no business logic, nothing of the core).
  - ide/ never references `cognitect.aws`, `software.amazon`, Podman or JDBC.
  - ide/src/main/**/*.kt never names the store layout (test fixtures may write a temp home) (`sessions/`, `workspaces/`, `worktrees/`, `repos/`,
    `runs/`, any `.edn` file but the jar's `version.edn`): the IDE asks the daemon; the discovery
    contract (`daemon/endpoint`, `daemon/lock`, `daemon/daemon.out`) is the only disk knowledge.
  - nowhere in the repository does a forbidden (employer) name occur. The list is never kept in
    the repository: it is read from the env var DELIBERATOR_FORBIDDEN_NAMES (comma separated) or
    from the gitignored file .scratch/forbidden-names.txt (one per line); without either the check
    prints that it was skipped and passes.

  Detection is a case-sensitive (case-insensitive for names) regex over file contents; binary
  files are skipped."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def rules
  [{:rule "core/ must not reference com.intellij" :dir "core/" :re #"com\.intellij"}
   {:rule "cli/ must not reference deliberator.* namespaces" :dir "cli/" :re #"\bdeliberator\.[a-z]"}
   {:rule "ide/ must not reference AWS, Podman or JDBC" :dir "ide/"
    :re #"(?i)cognitect\.aws|software\.amazon|podman|java\.sql|jdbc|org\.postgresql"}
   {:rule "ide/ Kotlin must not name the store layout (ask the daemon)" :dir "ide/src/main/" :ext ".kt"
    :re #"\b(?:sessions|workspaces|worktrees|repos|runs)/|(?<!version)\.edn\b"}])

(defn- repo-files []
  (->> (p/shell {:out :string} "git" "ls-files" "--cached" "--others" "--exclude-standard")
       :out str/split-lines (remove str/blank?) (filter fs/regular-file?)))

(defn- text [f]
  (let [bytes (fs/read-all-bytes f)]
    (when-not (some zero? (take 4096 bytes))
      (String. ^bytes bytes "UTF-8"))))

(defn- forbidden-names []
  (let [f ".scratch/forbidden-names.txt"
        raw (or (System/getenv "DELIBERATOR_FORBIDDEN_NAMES")
                (when (fs/exists? f) (slurp f)))]
    (->> (str/split (or raw "") #"[,\n]") (map str/trim) (remove str/blank?))))

(defn- hits [files {:keys [dir re rule ext]}]
  (for [f files :when (and (str/starts-with? f dir) (or (nil? ext) (str/ends-with? f ext)))
        :let [t (text f)] :when t
        [i l] (map-indexed vector (str/split-lines t))
        :when (re-find re l)]
    (str rule ": " f ":" (inc i))))

(defn -main []
  (let [files (repo-files)
        names (forbidden-names)
        name-rule (when (seq names)
                    {:rule "forbidden name" :dir ""
                     :re (re-pattern (str "(?i)" (str/join "|" (map #(java.util.regex.Pattern/quote %) names))))})
        bad (mapcat #(hits files %) (cond-> rules name-rule (conj name-rule)))]
    (when-not name-rule
      (println "arch: forbidden-names check skipped (no DELIBERATOR_FORBIDDEN_NAMES, no .scratch/forbidden-names.txt)"))
    (if (seq bad)
      (do (run! println bad) (println "arch violations:" (count bad)) (System/exit 1))
      (println "arch ok:" (count files) "files checked"))))
