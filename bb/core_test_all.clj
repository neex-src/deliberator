(ns core-test-all
  "`bb core:test:all [--ws W] [--keep]`: the two-pass recipe for the whole core suite.

  Pass A (estate down): `clojure -M:test`. The runtime tests (podman, runtime, steps, aws kinds) bring
  up and remove their own Floci/Postgres on host ports 14566/15432, so nothing may hold those ports;
  the tests that need an already running Floci skip with a message.

  Pass B (estate up, `bb estate:up --ws W`): the namespaces that need a running Floci
  (`DLB_TEST_FLOCI_URL`, default http://localhost:14566) plus the end-to-end tests that need the
  estate (`ESTATE_WS=W`), run against it; `steps-test` and `aws-kinds-test` run again here (their own
  runtime sits on free ports next to the estate).

  The estate is removed afterwards unless `--keep`. Both passes must be green. The workspace's
  `runtime.edn` must already carry 14566/15432 when 4566/5432 belong to another project. The home
  is `$DELIBERATOR_HOME`."
  (:require [babashka.process :as p]
            [core-build]
            [daemon-client]
            [estate]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn- clj [env & more]
  (:exit (apply p/shell {:dir "core" :continue true :extra-env env} "clojure" "-M:test" more)))

(defn- run [args]
  (let [ws (or (arg args "--ws") "default")
        keep? (some #{"--keep"} args)]
    (core-build/ensure-jar!)
    (println "== pass A: estate down, clojure -M:test")
    (estate/down ["--ws" ws])
    (let [a (clj {"DELIBERATOR_LOCAL_ENDPOINT" (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:19")})
          _ (println "== pass B: estate up, Floci-dependent namespaces")
          _ (estate/up ["--ws" ws])
          b (try (clj {"ESTATE_WS" ws "DELIBERATOR_LOCAL_ENDPOINT" (or (System/getenv "DLB_TEST_FLOCI_URL") "http://localhost:14566")}
                      "-n" "deliberator.aws-test" "-n" "deliberator.daemon-test"
                      "-n" "deliberator.estate-test" "-n" "deliberator.topology-estate-test" "-n" "deliberator.audit-estate-test"
                      "-n" "deliberator.audit-runtime-test"
                      "-n" "deliberator.steps-test" "-n" "deliberator.aws-kinds-test"
                      "-n" "deliberator.ide-ops-test")
                 (finally (when-not keep? (estate/down ["--ws" ws]))))]
      (println "== pass A exit" a ", pass B exit" b)
      (if (and (zero? a) (zero? b)) 0 1))))

(defn -main [& args]
  ;; the estate steps start the daemon of $DELIBERATOR_HOME when none runs; it is stopped again at the end
  (System/exit (daemon-client/stop-if-started! #(run args))))
