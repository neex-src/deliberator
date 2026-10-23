(ns ide-run
  "`bb ide:run [--ws W] [--home H] [--open FILE]`: the sandbox IDE with the plugin, `DELIBERATOR_HOME` = H (default $DELIBERATOR_HOME,
  else ~/.local/share/deliberator) and `-Ddeliberator.jar` = core/target/deliberatord.jar, opened on the workspace project
  `<H>/workspaces/<W>/project`. A workspace that does not exist yet is created first (`workspace.open`, through the daemon of H,
  started when needed), so `--ws` always opens a project; `--open` opens a file in it as well. A daemon already holding H (a
  REPL-started one from `cljdev`, or `dlb`'s) is reused, never replaced by a second one. A GUI: needs a display.

  `bb tour:ide` (`tour-ide`): the tour with nothing to set first: home $DELIBERATOR_HOME, else `<repo>/.scratch/tour-home` (the same
  default as `bb tour`), workspace `tour`, this checkout attached (so tour/ is in the project), session `TOUR-<yyyymmdd>` current
  (the one `bb tour` uses; running a chapter never asks for one), `tour/01-estate.scn` open."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [core-build]
            [dlb.client :as client]
            [dlb.endpoint :as endpoint]))

(defn- arg [args k] (second (drop-while #(not= k %) args)))

(defn project-dir
  "The workspace project dir for home+ws, or nil when ws is nil or the dir does not exist."
  [home ws]
  (when ws
    (let [d (fs/path home "workspaces" ws "project")]
      (when (fs/directory? d) (str d)))))

(defn- jar []
  (core-build/ensure-jar!)
  (str (fs/absolutize "core/target/deliberatord.jar")))

(defn- call!
  "One request to the daemon of `home` (started when not running); -> the result, or throws with the op's error."
  [home op args]
  (let [{:keys [channel]} (endpoint/connect-compatible! home (jar))]
    (try
      (let [t (:terminal (client/request! channel op args (fn [_])))]
        (if (:ok t)
          (:result t)
          (throw (ex-info (str op " failed: " (get-in t [:error :message])) {:babashka/exit 1}))))
      (finally (client/close! channel)))))

(defn- ensure-workspace!
  "Creates workspace `ws` in `home` when missing and attaches `attach` (a checkout) when it is not attached yet."
  [home ws attach]
  (when-not (project-dir home ws)
    (println "creating workspace" ws "in" home)
    (call! home "workspace.open" {:ws ws}))
  (when attach
    (let [path (str (fs/canonicalize attach))
          attached (call! home "repo.list" {:ws ws})]
      (when-not (some #(= path (str (fs/canonicalize (:path %)))) attached)
        (println "attaching" path "to workspace" ws)
        (call! home "repo.attach" {:ws ws :path path})))))

(defn launch!
  "Runs the sandbox IDE (Gradle runIde) for `home`, opened on the project of `ws` (created when missing) and `open` (a file)."
  [home ws open attach]
  (core-build/ensure-jar!)
  (when ws (ensure-workspace! home ws attach))
  (let [proj (project-dir home ws)
        open (some-> open fs/absolutize str)]
    (println "ide:run home" home "ws" (or ws "-") "project" (or proj "-") (if open (str "open " open) ""))
    (let [{:keys [exit]} (-> (apply p/process {:dir "ide" :inherit true}
                                    "./gradlew" "runIde" (str "-PdlbHome=" home)
                                    (concat (when proj [(str "-PdlbProject=" proj)])
                                            (when open [(str "-PdlbOpen=" open)])))
                             deref)]
      (System/exit exit))))

(defn -main [& args]
  (let [home (str (fs/absolutize (or (arg args "--home") (System/getenv "DELIBERATOR_HOME")
                                     (when (System/getenv "DELIBERATOR_NO_DEFAULT_HOME")
                                       (throw (ex-info "DELIBERATOR_NO_DEFAULT_HOME is set: pass --home or DELIBERATOR_HOME" {:type "no-default-home"})))
                                     (str (System/getProperty "user.home") "/.local/share/deliberator"))))]
    (launch! home (arg args "--ws") (arg args "--open") nil)))

(defn tour-home
  "The home of the tour: $DELIBERATOR_HOME, else `<repo>/.scratch/tour-home`."
  []
  (str (fs/absolutize (or (System/getenv "DELIBERATOR_HOME") ".scratch/tour-home"))))

(defn tour-ide [& _]
  (let [home (tour-home)
        session (str "TOUR-" (.format (java.time.LocalDate/now java.time.ZoneOffset/UTC) java.time.format.DateTimeFormatter/BASIC_ISO_DATE))]
    ;; the session bb tour uses too: running a chapter never stops to ask for one
    (ensure-workspace! home "tour" ".")
    (call! home "session.start" {:ws "tour" :key session})
    (launch! home "tour" "tour/01-estate.scn" ".")))
