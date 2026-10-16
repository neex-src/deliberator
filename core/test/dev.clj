(ns dev
  "REPL entry for `cljdev` (its classpath has `test/`, so it lives here): `(dev/start!)` runs the daemon in this JVM on the home's normal socket (so the
  CLI and the IDE connect to it), `(dev/stop!)` stops it. Options as `deliberator.daemon/start!`:
  `(start! {:home \"…\" :tcp true :idle-minutes 0})`; the idle shutdown is off by default here."
  (:require [clojure.java.io]))

(defonce ^:private handle (atom nil))

(defn start!
  "Starts the daemon unless one runs from this REPL; returns its endpoint."
  ([] (start! {}))
  ([opts]
   (when-not @handle
     (reset! handle ((requiring-resolve 'deliberator.daemon/start!) (merge {:idle-minutes 0} opts))))
   (:endpoint @handle)))

(defn stop!
  "Stops the daemon started by `start!`."
  []
  (when-let [h @handle]
    ((:stop! h))
    (reset! handle nil)
    :stopped))
