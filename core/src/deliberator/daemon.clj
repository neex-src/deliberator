(ns deliberator.daemon
  "`deliberatord`: the long-lived core process, one instance per `$DELIBERATOR_HOME` (lock),
  speaking the protocol of `docs/architecture.md` section 6. Options `--home`, `--idle-minutes`,
  `--tcp`; writes `daemon/endpoint`. In the skeleton `-main` only prints the version and exits."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:gen-class))

(defn version
  "The version string from the classpath resource `deliberator/version.edn`."
  []
  (with-open [r (java.io.PushbackReader. (io/reader (io/resource "deliberator/version.edn")))]
    (:version (edn/read r))))

(defn -main
  "Print the version and exit."
  [& _args]
  (println (str "deliberatord " (version))))
