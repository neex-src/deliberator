(ns ijdev.boot
  "The nREPL server inside the IDE. Started once per IDE by the plugin (ijdev.Nrepl), bound to 127.0.0.1.

  Port: -Dijdev.port (default 0 = any free port). The port is written to <dir>/nrepl-port and <dir>/.nrepl-port, where <dir> is
  -Dijdev.dir (the ijdev CLI passes <gradle project>/.ijdev) or <IDE system dir>/ijdev. Every request runs with the union class loader
  as context class loader, so `(import ...)` reaches any loaded plugin. The `user` namespace starts with `ide`, `hs` and `plugins`
  aliased (ijdev.ide, ijdev.hotswap, ijdev.plugins)."
  (:require [clojure.java.io :as io]
            [nrepl.server :as server])
  (:import (com.intellij.openapi.application PathManager)))

(defonce state (atom nil))

(defn state-dir []
  (io/file (or (System/getProperty "ijdev.dir") (str (PathManager/getSystemPath) "/ijdev"))))

(defn- prepare-user-ns! []
  (binding [*ns* (create-ns 'user)]
    (refer-clojure)
    (require '[ijdev.ide :as ide] '[ijdev.hotswap :as hs] '[ijdev.plugins :as plugins] '[clojure.repl :refer [doc source apropos dir pst]] 'clojure.pprint)))

(defn start!
  "Starts the server (idempotent) with `loader` as the context class loader of every request. Returns the port."
  [^ClassLoader loader]
  (or (:port @state)
      (let [handler (server/default-handler)
            wrapped (fn [msg]
                      (.setContextClassLoader (Thread/currentThread) loader)
                      (handler msg))
            srv (server/start-server :bind "127.0.0.1" :port (Integer/getInteger "ijdev.port" 0) :handler wrapped)
            port (:port srv)
            dir (state-dir)]
        (prepare-user-ns!)
        (.mkdirs dir)
        (doseq [f ["nrepl-port" ".nrepl-port"]]
          (spit (io/file dir f) (str port)))
        (reset! state {:server srv :port port :loader loader :dir (str dir)})
        (.info (com.intellij.openapi.diagnostic.Logger/getInstance "ijdev") (str "ijdev: nREPL on 127.0.0.1:" port ", port file in " dir))
        port)))

(defn stop! []
  (when-let [{:keys [server dir]} @state]
    (server/stop-server server)
    (doseq [f ["nrepl-port" ".nrepl-port"]]
      (io/delete-file (io/file dir f) true))
    (reset! state nil)))
