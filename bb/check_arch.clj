(ns check-arch
  "`bb check:arch`: the layering rules of CLAUDE.md as a build check over the tracked and
  untracked-not-ignored files of the repository.

  - core/ never references `com.intellij`.
  - cli/ contains no `deliberator.` namespace (no business logic, nothing of the core).
  - ide/ never references `cognitect.aws`, `software.amazon`, Podman or JDBC.
  - ide/src/main/**/*.kt never names the store layout (test fixtures may write a temp home) (`sessions/`, `workspaces/`, `worktrees/`, `repos/`,
    `runs/`, any `.edn` file but the jar's `version.edn`): the IDE asks the daemon; the discovery
    contract (`daemon/endpoint`, `daemon/lock`, `daemon/daemon.out`) is the only disk knowledge.
  - nowhere in the repository does a forbidden (employer) name occur. The list is never kept in the repository: it is
    read from the owner's private config, `<config dir>/forbidden-names.txt` (one name per line, blank lines and `#`
    lines ignored; the config dir is $DELIBERATOR_CONFIG_DIR, else $XDG_CONFIG_HOME/deliberator, else
    ~/.config/deliberator), or from the env var DELIBERATOR_FORBIDDEN_NAMES (comma separated; a one-off or CI). A machine
    is marked as the owner's by the file `<config dir>/owner-machine` (its content does not matter): there a missing or
    empty list FAILS the check loudly (a vanished list must not turn the check into a silent pass); on any other machine
    the check prints that it was skipped, and why, and passes.

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

(defn config-dir
  "The directory of the owner's private config: $DELIBERATOR_CONFIG_DIR, else $XDG_CONFIG_HOME/deliberator, else
  ~/.config/deliberator. `getenv` is `System/getenv` (a function of the variable name)."
  [getenv]
  (or (getenv "DELIBERATOR_CONFIG_DIR")
      (str (or (getenv "XDG_CONFIG_HOME") (str (System/getProperty "user.home") "/.config")) "/deliberator")))

(defn forbidden-state
  "What the forbidden-names check does on this machine: `{:state :ok|:skip|:fail :names [..] :list path :marker path :message}`.
  `:ok` has names to look for, `:fail` is a machine marked as the owner's whose list is missing or empty, `:skip` is any other
  machine without a list."
  [getenv]
  (let [dir (config-dir getenv)
        list-file (str dir "/forbidden-names.txt")
        marker (str dir "/owner-machine")
        from-file (when (fs/exists? list-file)
                    (->> (str/split-lines (slurp list-file)) (map str/trim) (remove #(or (str/blank? %) (str/starts-with? % "#")))))
        from-env (->> (str/split (or (getenv "DELIBERATOR_FORBIDDEN_NAMES") "") #",") (map str/trim) (remove str/blank?))
        names (vec (distinct (concat from-file from-env)))]
    (cond
      (seq names) {:state :ok :names names :list list-file :marker marker}
      (fs/exists? marker)
      {:state :fail :names [] :list list-file :marker marker
       :message (str "this machine is marked as the owner's (" marker ") but its forbidden-names list " list-file
                     " is missing or empty (and DELIBERATOR_FORBIDDEN_NAMES is not set): the check cannot vouch that no"
                     " employer name is in the repository; restore the list (one name per line)")}
      :else
      {:state :skip :names [] :list list-file :marker marker
       :message (str "forbidden-names check skipped: no list at " list-file " (and no DELIBERATOR_FORBIDDEN_NAMES), and this machine"
                     " is not marked as the owner's (no " marker ")")})))

(defn- hits [files {:keys [dir re rule ext]}]
  (for [f files :when (and (str/starts-with? f dir) (or (nil? ext) (str/ends-with? f ext)))
        :let [t (text f)] :when t
        [i l] (map-indexed vector (str/split-lines t))
        :when (re-find re l)]
    (str rule ": " f ":" (inc i))))

(defn -main []
  (let [files (repo-files)
        {:keys [state names message]} (forbidden-state #(System/getenv %))
        name-rule (when (seq names)
                    {:rule "forbidden name" :dir ""
                     :re (re-pattern (str "(?i)" (str/join "|" (map #(java.util.regex.Pattern/quote %) names))))})
        bad (mapcat #(hits files %) (cond-> rules name-rule (conj name-rule)))]
    (when (= :skip state) (println (str "arch: " message)))
    (when (= :fail state) (println (str "arch FAILED: " message)))
    (if (or (seq bad) (= :fail state))
      (do (run! println bad) (println "arch violations:" (+ (count bad) (if (= :fail state) 1 0))) (System/exit 1))
      (println "arch ok:" (count files) "files checked"
               (if (= :ok state) (str "(" (count names) " forbidden names)") "(forbidden names not checked)")))))
