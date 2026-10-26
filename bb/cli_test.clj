(ns cli-test
  "`bb cli:test [--known-bugs]`: layer-4 tests of `dlb` (cli/test/dlb_test.clj) and the audit's odd inputs
  (cli/test/audit_dlb_test.clj) against a real daemon started from a fresh jar (`core-build/ensure-jar!`: rebuilt when
  core/src is newer, not only when missing). Tests whose var carries `:known-bug` metadata are left out; with `--known-bugs`
  ONLY those run and the exit code is `known-bugs/verdict` (decision 24: they must fail until fixed, and BUGS.md lists them).
  A test that cannot run reports `:skip`: printed as a `SKIP` line and counted in the summary (with `ESTATE_WS` and
  `DELIBERATOR_HOME` set, as `bb check:all` does, the estate-gated test runs instead of skipping). Exits non-zero on any
  failure or error. `run` returns the counts for `check:all`."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [core-build]
            [known-bugs]))

(def namespaces '[dlb-test audit-dlb-test])

(def skipped (atom []))
(def failed-vars (atom #{}))

(defmethod t/report :skip [m]
  (swap! skipped conj (:message m))
  (println "SKIP" (:message m)))

(doseq [type [:fail :error]]
  (let [orig (get-method t/report type)]
    (defmethod t/report type [m]
      (when-let [v (first t/*testing-vars*)] (swap! failed-vars conj v))
      (orig m))))

(defn- selected-vars [known-bugs?]
  (for [ns namespaces
        v (sort-by (comp :line meta) (filter #(:test (meta %)) (vals (ns-interns ns))))
        :when (= (boolean known-bugs?) (boolean (:known-bug (meta v))))]
    v))

(defn- var-name [v] (str (ns-name (:ns (meta v))) "/" (:name (meta v))))

(defn run
  "Runs the selected tests; -> `{:tests :assertions :failures :errors :skipped :exit}`."
  [args]
  (core-build/ensure-jar!)
  (apply require namespaces)
  (let [known? (some #{"--known-bugs"} args)
        vars (selected-vars known?)
        counters (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                   (doseq [[_ vs] (group-by (comp :ns meta) vars)]
                     (t/test-vars vs))
                   (assoc @t/*report-counters* :tests (count vars)))
        failures (+ (:fail counters) (:error counters))
        exit (if known?
               (let [{:keys [exit message]} (known-bugs/verdict :cli (map var-name vars) (map var-name (remove @failed-vars vars)))]
                 (println "known-bugs:" message)
                 exit)
               (if (zero? failures) 0 1))]
    (println (str "\nRan " (:tests counters) " tests containing " (+ (:pass counters) (:fail counters) (:error counters))
                  " assertions.\n" (:fail counters) " failures, " (:error counters) " errors, " (count @skipped) " skipped."))
    {:tests (:tests counters) :assertions (+ (:pass counters) failures) :failures (:fail counters) :errors (:error counters)
     :skipped (count @skipped) :skips @skipped :exit exit}))

(defn -main [& args]
  (System/exit (:exit (run args))))
