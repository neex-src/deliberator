(ns build
  "tools.build script: `clojure -T:build uber` produces target/deliberatord.jar, an AOT-compiled
  uberjar whose Main-Class is deliberator.daemon. Run from core/ (bb core:build does)."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private jar-file "target/deliberatord.jar")

(defn clean
  "Remove core/target."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build target/deliberatord.jar from src/, resources/ and the :deps basis."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir
                    :ns-compile '[deliberator.daemon]})
    (b/uber {:class-dir class-dir :uber-file jar-file :basis basis
             :main 'deliberator.daemon})
    (println "built" jar-file)))
