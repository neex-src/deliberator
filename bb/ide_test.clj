(ns ide-test
  "`bb ide:test [--known-bugs] [gradle args...]`: the fixture tests of the plugin (`./gradlew test`), or with `--known-bugs`
  only the tests that document product bugs (`./gradlew knownBugs`); every other argument goes to Gradle.
  With `--known-bugs --tests X` it runs `./gradlew test -PknownBugs --tests X` (a lifecycle task like `knownBugs` rejects `--tests`)."
  (:require [babashka.process :refer [shell]]))

(defn -main [& args]
  (let [known? (some #{"--known-bugs"} args)
        rest-args (remove #{"--known-bugs"} args)]
    (apply shell {:dir "ide"} "./gradlew"
           (concat
            (cond (not known?) ["test"]
                 (some #{"--tests"} rest-args) ["test" "-PknownBugs"]
                 :else ["knownBugs"])
            rest-args))))
