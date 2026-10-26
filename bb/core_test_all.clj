(ns core-test-all
  "`bb core:test:all [--ws W] [--keep]`: the two-pass recipe for the whole core suite.

  Pass A (estate down): `clojure -M:test`. The runtime tests (podman, runtime, steps, aws kinds) bring
  up and remove their own Floci/Postgres on host ports 14566/15432, so nothing may hold those ports;
  the tests that need an already running Floci skip with a SKIP line (counted and printed, expected here).

  Pass B (estate up, `bb estate:up --ws W`): the namespaces that need a running Floci or the estate, run against it
  (`DLB_TEST_FLOCI_URL`, default the workspace's own `local` endpoint, where estate:up put Floci; `DLB_TEST_PG_PORT`,
  default the port of its `db/main`; `ESTATE_WS=W` for the end-to-end tests). That list is not kept by hand: it is every
  `core/test/**/*_test.clj` that uses `deftest-floci` or `estate-ws` (`pass-b-namespaces`), plus `steps-test` and
  `aws-kinds-test`, which run again here against their own runtime on free ports next to the estate. Pass B is red when
  any test SKIPPED: with the estate up nothing may be skipped, a skip is a gap in the estate or in the sandbox (R12).

  The estate is removed afterwards unless `--keep`. Both passes must be green. The workspace's
  `runtime.edn` must carry 14566/15432 when 4566/5432 belong to another project. The home
  is `$DELIBERATOR_HOME`. `run` returns `{:a {...} :b {...}}` (exit, tests, assertions, failures, errors, skipped per pass)
  for `check:all`; `-main` exits 0 only when both passes are green."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [core-build]
            [daemon-client]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(def always-again
  "Namespaces that run in pass B too although they gate on nothing: their fixture brings up a runtime of its own."
  ["deliberator.steps-test" "deliberator.aws-kinds-test"])

(defn pass-b-namespaces
  "The estate-gated test namespaces: every `*_test.clj` under core/test whose text uses `deftest-floci` or `estate-ws`,
  by its `ns` form, plus `always-again`; sorted."
  []
  (->> (fs/glob "core/test" "**/*_test.clj")
       (map str)
       (filter #(re-find #"deftest-floci|estate-ws" (slurp %)))
       (keep #(second (re-find #"\(ns\s+([\w.-]+)" (slurp %))))
       (concat always-again)
       distinct sort vec))

(defn summarize
  "The counts of a `clojure -M:test` run from its output lines: `Ran N tests containing M assertions.`,
  `F failures, E errors.`, and the SKIP lines (`floci-util`'s `:skip` reporter prints `SKIP <test>: <reason>`)."
  [lines]
  (let [ran (some #(re-find #"^Ran (\d+) tests containing (\d+) assertions" %) lines)
        fe (some #(re-find #"^(\d+) failures, (\d+) errors" %) lines)
        skips (filter #(re-find #"^SKIP " %) lines)]
    {:tests (some-> ran second parse-long) :assertions (some-> ran (nth 2) parse-long)
     :failures (some-> fe second parse-long) :errors (some-> fe (nth 2) parse-long)
     :skipped (count skips) :skips (vec skips)}))

(defn- clj
  "`clojure -M:test more...` in core/ with `env`, its output streamed as it comes; -> `summarize` plus `:exit`."
  [env & more]
  (let [proc (apply p/process {:dir "core" :err :out :extra-env env} "clojure" "-M:test" more)
        lines (with-open [r (io/reader (:out proc))]
                (doall (for [l (line-seq r)] (do (println l) (flush) l))))]
    (assoc (summarize lines) :exit (:exit @proc))))

(defn- line [label {:keys [exit tests assertions failures errors skipped]}]
  (format "%s: exit %d, %s tests, %s assertions, %s failures, %s errors, %d skipped"
          label exit tests assertions failures errors skipped))

(defn green-a? [a] (zero? (:exit a)))
(defn green-b? [b] (and (zero? (:exit b)) (zero? (:skipped b))))

(defn run
  "The two passes; -> `{:a summary :b summary}`."
  [args]
  (let [ws (or (arg args "--ws") "default")
        keep? (some #{"--keep"} args)
        nss (pass-b-namespaces)]
    (core-build/ensure-jar!)
    (println "== pass A: estate down, clojure -M:test")
    (estate/down ["--ws" ws])
    (let [a (clj {"DELIBERATOR_LOCAL_ENDPOINT" (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:19")})
          _ (println "== pass B: estate up, the estate-gated namespaces:" (str/join " " nss))
          _ (estate/up ["--ws" ws])
          floci (or (System/getenv "DLB_TEST_FLOCI_URL") (estate/local-endpoint ws))
          pg (or (System/getenv "DLB_TEST_PG_PORT")
                 (some->> (get-in (estate/local-env ws) [:targets :db/main]) (re-find #"localhost:(\d+)/") second))
          b (try (apply clj (cond-> {"ESTATE_WS" ws "DLB_TEST_FLOCI_URL" floci "DELIBERATOR_LOCAL_ENDPOINT" floci}
                              pg (assoc "DLB_TEST_PG_PORT" pg))
                        (mapcat #(vector "-n" %) nss))
                 (finally (when-not keep? (estate/down ["--ws" ws]))))]
      (println (str "== " (line "pass A" a) " (the estate-gated tests, expected)"))
      (println (str "== " (line "pass B" b)))
      (when (pos? (:skipped b))
        (println "== pass B SKIPPED tests (a skip with the estate up is a failure):")
        (run! #(println "   " %) (:skips b)))
      {:a a :b b})))

(defn -main [& args]
  ;; the estate steps start the daemon of $DELIBERATOR_HOME when none runs; it is stopped again at the end
  (let [{:keys [a b]} (daemon-client/stop-if-started! #(run args))]
    (System/exit (if (and (green-a? a) (green-b? b)) 0 1))))
