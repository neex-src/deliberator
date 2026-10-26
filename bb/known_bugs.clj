(ns known-bugs
  "The known-bug contract of decision 24, enforced instead of described (review 2026-10-25, R30): a test tagged
  `^:known-bug` (core, cli) pins a product bug listed under `## Pinned by known-bug tests` in BUGS.md, one bullet per test
  naming its var in backticks (`deliberator.foo-test/bar`, `dlb-test/baz`); it must FAIL until the bug is fixed, and the fix
  drops the tag and the bullet in the same commit.

  `verdict` turns a run of only the tagged tests into an exit code: 0 when there is nothing to run and BUGS.md agrees, or
  when every tagged test failed as it must; 1 when a tagged test PASSED (its bug is fixed: drop the tag), or when the number
  of tagged tests that ran is not the number BUGS.md pins for that layer. `core` is `bb core:test:known-bugs`."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn pinned
  "The test names BUGS.md pins under `## Pinned by known-bug tests` (backticked `ns/test` names of its bullets), by layer:
  `:core` (`deliberator.` namespaces), `:cli` (`dlb-test`, `audit-dlb-test`), `:ide` (Kotlin `Class.method`)."
  []
  (let [lines (str/split-lines (slurp "BUGS.md"))
        section (->> lines (drop-while #(not (str/starts-with? % "## Pinned by known-bug tests"))) rest
                     (take-while #(not (str/starts-with? % "## "))))
        names (for [l section :when (re-find #"^\s*[-*] " l) n (map second (re-seq #"`([^`\s]+)`" l))] n)]
    (group-by (fn [n] (cond (str/starts-with? n "deliberator.") :core
                            (re-find #"^(audit-)?dlb-test/" n) :cli
                            :else :ide))
              names)))

(defn verdict
  "-> `{:exit :message}` for `layer` from `ran` (the tagged tests that ran, as names when known, else a count) and `passed`
  (the ones that passed, names or count)."
  [layer ran passed]
  (let [n (if (number? ran) ran (count ran))
        k (if (number? passed) passed (count passed))
        want (count (get (pinned) layer))
        names (fn [x] (if (number? x) "" (str ": " (str/join ", " x))))]
    (cond
      (not= n want) {:exit 1 :message (format "BUGS.md pins %d %s test(s) under 'Pinned by known-bug tests', %d ran%s: list each tagged test there, one bullet, its var in backticks"
                                             want (name layer) n (names ran))}
      (zero? n) {:exit 0 :message (format "no known-bug tests in %s, and BUGS.md pins none" (name layer))}
      (pos? k) {:exit 1 :message (format "%d of %d known-bug test(s) PASSED%s: the bug is fixed, drop the tag and the BUGS.md bullet in the same commit" k n (names passed))}
      :else {:exit 0 :message (format "%d known-bug test(s) fail as they must (BUGS.md agrees)" n)})))

(defn core
  "`clojure -M:test:known-bugs` in core/, output streamed; the tagged tests that ran come from the runner's summary, the ones
  that passed are those without a FAIL/ERROR line. -> exit code."
  []
  (let [proc (p/process {:dir "core" :err :out} "clojure" "-M:test:known-bugs")
        lines (with-open [r (io/reader (:out proc))] (doall (for [l (line-seq r)] (do (println l) (flush) l))))
        exit (:exit @proc)
        ran (or (some #(some-> (re-find #"^Ran (\d+) tests" %) second parse-long) lines) 0)
        failed (set (keep #(second (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" %)) lines))
        {:keys [exit message]} (if (and (not (zero? exit)) (zero? ran) (not-any? #(re-find #"^Ran " %) lines))
                                 {:exit exit :message "the known-bug run did not reach its summary (compile or load error above)"}
                                 (verdict :core ran (- ran (count failed))))]
    (println "known-bugs:" message)
    exit))

(defn -main [& _] (System/exit (core)))
