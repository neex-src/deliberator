(ns cli-test
  "`bb cli:test`: layer-4 tests of `dlb` (cli/test/dlb_test.clj) against a real daemon. Builds the
  uberjar first when core/target/deliberatord.jar is missing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :as t]))

(defn -main []
  (when-not (fs/exists? "core/target/deliberatord.jar")
    (println "core/target/deliberatord.jar missing: building it")
    (p/shell {:dir "core"} "clojure" "-T:build" "uber"))
  (require 'dlb-test)
  (let [{:keys [fail error]} (t/run-tests 'dlb-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
