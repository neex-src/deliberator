(ns claude-settings
  "`bb claude:settings [DIR...]`: append the allow rules of `.claude/dev-settings.json` to
  `<DIR>/.claude/settings.local.json` (default DIR: the repository root). Claude Code reads project
  settings from the directory a session starts in, so name that directory when it is not the repo
  (`bb claude:settings ..` for sessions started in ~/projects). Existing keys and rules stay; rules
  already there are not added twice.

  `autoMode` (the permission classifier's context) is not read from project settings, only from
  user settings or `--settings`. The task does not touch user settings; it prints the
  `claude --settings` line that loads the file for one session."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(def source-file (str (fs/path (fs/parent (fs/parent *file*)) ".claude" "dev-settings.json")))

(defn merge-allow
  "Settings map `current` with the allow rules of `extra` appended, keeping order, without duplicates."
  [current extra]
  (let [have (get-in current ["permissions" "allow"] [])]
    (assoc-in current ["permissions" "allow"] (vec (distinct (concat have extra))))))

(defn apply-to! [dir rules]
  (let [target (fs/path dir ".claude" "settings.local.json")
        current (if (fs/exists? target) (json/parse-string (slurp (str target))) {})
        merged (merge-allow current rules)
        added (- (count (get-in merged ["permissions" "allow"])) (count (get-in current ["permissions" "allow"] [])))]
    (fs/create-dirs (fs/parent target))
    (spit (str target) (str (json/generate-string merged {:pretty true}) "\n"))
    (println (str target ": " added " rule(s) added, " (count (get-in merged ["permissions" "allow"])) " in total"))))

(defn -main [& dirs]
  (let [rules (get-in (json/parse-string (slurp source-file)) ["permissions" "allow"])
        dirs (if (seq dirs) dirs [(str (fs/parent (fs/parent source-file)))])]
    (doseq [d dirs] (apply-to! (str (fs/canonicalize d)) rules))
    (println (str "autoMode is read from user settings or --settings only; for one session: claude --settings " source-file))))
