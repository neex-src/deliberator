(ns ide-test
  "`bb ide:test [--group a|b] [--known-bugs] [gradle args...]`: the fixture tests of the plugin (`./gradlew test`), or with
  `--known-bugs` only the tests that document product bugs (`./gradlew knownBugs`; the IDE side keeps Gradle's
  own exit code, there is no per-test verdict as in `bb cli:test --known-bugs`); every other argument goes to Gradle.
  `--group a|b` runs half of the test packages so each half fits one 10-minute call; `a` then `b` is the whole suite. The two
  groups must cover exactly the packages under ide/src/test/kotlin/com/neex/deliberator (a new package is added to a group
  here, or the task refuses).
  With `--known-bugs --tests X` it runs `./gradlew test -PknownBugs --tests X` (a lifecycle task like `knownBugs` rejects `--tests`)."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(def test-root "ide/src/test/kotlin/com/neex/deliberator")

(def groups
  {"a" ["audit" "config" "daemon" "explorer" "logs" "scn"]
   "b" ["environments" "repo" "run" "services" "testing" "ui" "workspace"]})

(defn packages
  "The test packages on disk: the directories under `test-root`."
  []
  (set (map fs/file-name (filter fs/directory? (fs/list-dir test-root)))))

(defn check-groups!
  "Throws (exit 2) when the groups and the packages on disk differ."
  []
  (let [on-disk (packages) grouped (set (mapcat val groups))]
    (when (not= on-disk grouped)
      (throw (ex-info (str "ide:test groups do not match the packages under " test-root
                           "; in no group: " (pr-str (sort (remove grouped on-disk)))
                           ", in a group but gone: " (pr-str (sort (remove on-disk grouped))))
                      {:babashka/exit 2})))))

(defn- group-args [args]
  (let [[before [_ g & after]] (split-with #(not= "--group" %) args)]
    (if g
      (concat before after
              (mapcat (fn [p] ["--tests" (str "com.neex.deliberator." p ".*")])
                      (or (groups g) (throw (ex-info (str "--group a or b, not " g) {:babashka/exit 2})))))
      args)))

(defn -main [& args0]
  (check-groups!)
  (let [args (group-args args0)
        known? (some #{"--known-bugs"} args)
        rest-args (remove #{"--known-bugs"} args)]
    (when-let [g (second (drop-while #(not= "--group" %) args0))]
      (println "ide:test group" g ":" (str/join " " (groups g))))
    (apply shell {:dir "ide"} "./gradlew"
           (concat
            (cond (not known?) ["test"]
                  (some #{"--tests"} rest-args) ["test" "-PknownBugs"]
                  :else ["knownBugs"])
            rest-args))))
