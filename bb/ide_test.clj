(ns ide-test
  "`bb ide:test [--known-bugs] [gradle args...]`: the fixture tests of the plugin (`./gradlew test`), or with `--known-bugs`
  only the tests that document product bugs (`./gradlew knownBugs`); every other argument goes to Gradle."
  (:require [babashka.process :refer [shell]]))

(defn -main [& args]
  (let [known? (some #{"--known-bugs"} args)
        rest-args (remove #{"--known-bugs"} args)]
    (apply shell {:dir "ide"} "./gradlew" (if known? "knownBugs" "test") rest-args)))
