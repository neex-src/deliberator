(ns ide-run
  "`bb ide:run [--ws W] [--home H]`: the sandbox IDE with the plugin, `DELIBERATOR_HOME` = H (default $DELIBERATOR_HOME, else
  ~/.local/share/deliberator) and `-Ddeliberator.jar` = core/target/deliberatord.jar, opened on the workspace project
  `<H>/workspaces/<W>/project` when it exists (otherwise the welcome screen: Deliberator | Open Workspace creates it).
  A daemon already holding H (a REPL-started one from `cljdev`, or `dlb`'s) is reused, never replaced by a second one.
  A GUI: needs a display."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn project-dir
  "The workspace project dir for home+ws, or nil when ws is nil or the dir does not exist."
  [home ws]
  (when ws
    (let [d (fs/path home "workspaces" ws "project")]
      (when (fs/directory? d) (str d)))))

(defn -main [& args]
  (let [home (str (fs/absolutize (or (arg args "--home") (System/getenv "DELIBERATOR_HOME")
                                     (when (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
                                       (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME" {:type "no-default-home"})))
                                     (str (System/getProperty "user.home") "/.local/share/deliberator"))))
        ws (arg args "--ws")
        proj (project-dir home ws)]
    (when (and ws (not proj))
      (println (str "note: " home "/workspaces/" ws "/project does not exist yet; use Deliberator | Open Workspace in the IDE")))
    (println "ide:run home" home "ws" (or ws "-") "project" (or proj "-"))
    (let [{:keys [exit]} (-> (apply p/process {:dir "ide" :inherit true}
                                    "./gradlew" "runIde" (str "-PdlbHome=" home)
                                    (when proj [(str "-PdlbProject=" proj)]))
                             deref)]
      (System/exit exit))))
