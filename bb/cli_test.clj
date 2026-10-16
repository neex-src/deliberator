(ns cli-test
  "`bb cli:test [--known-bugs]`: layer-4 tests of `dlb` (cli/test/dlb_test.clj) and the audit's odd inputs
  (cli/test/audit_dlb_test.clj) against a real daemon. Builds the uberjar first when
  core/target/deliberatord.jar is missing. Tests whose var carries `:known-bug` metadata are left out
  unless `--known-bugs` is given, in which case ONLY those run. Prints the summary
  counts and exits non-zero on any failure or error."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :as t]))

(def namespaces '[dlb-test audit-dlb-test])

(defn- selected-vars [known-bugs?]
  (for [ns namespaces
        v (sort-by (comp :line meta) (filter #(:test (meta %)) (vals (ns-interns ns))))
        :when (= (boolean known-bugs?) (boolean (:known-bug (meta v))))]
    v))

(defn -main [& args]
  (when-not (fs/exists? "core/target/deliberatord.jar")
    (println "core/target/deliberatord.jar missing: building it")
    (p/shell {:dir "core"} "clojure" "-T:build" "uber"))
  (apply require namespaces)
  (let [vars (selected-vars (some #{"--known-bugs"} args))
        counters (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                   (doseq [[_ vs] (group-by (comp :ns meta) vars)]
                     (t/test-vars vs))
                   (assoc @t/*report-counters* :tests (count vars)))]
    (println (str "\nRan " (:tests counters) " tests containing " (+ (:pass counters) (:fail counters) (:error counters))
                  " assertions.\n" (:fail counters) " failures, " (:error counters) " errors."))
    (System/exit (if (zero? (+ (:fail counters) (:error counters))) 0 1))))
