(ns deliberator.test-util
  "Test helpers: a temporary `$DELIBERATOR_HOME` under `target/test-homes/` (gitignored; never the
  real home) and a fixed clock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [deliberator.store :as store])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util UUID)))

(defn temp-home
  "Creates and returns a fresh temp home directory (a File)."
  []
  (let [d (io/file (System/getProperty "user.dir") "target" "test-homes" (str (UUID/randomUUID)))]
    (.mkdirs d)
    d))

(defmacro with-temp-home
  "Runs body with `store/*home*` bound to a fresh temporary home."
  [& body]
  `(binding [store/*home* (temp-home)] ~@body))

(def ^:private max-unix-path-bytes
  "The daemon's limit for `<home>/daemon/deliberatord.sock` (daemon.clj); a longer path falls back to tcp."
  100)

(defn- sock-too-long? [^java.io.File home]
  (> (count (.getBytes (str (io/file home "daemon" "deliberatord.sock")) "UTF-8")) max-unix-path-bytes))

(defn- rand5 [] (Long/toString (+ 60466176 (long (rand 1.0e9))) 36))

(defn- main-checkout
  "The main checkout's root (parent of git's common dir), or nil."
  []
  (let [r (try (-> (ProcessBuilder. ^java.util.List ["git" "rev-parse" "--path-format=absolute" "--git-common-dir"])
                   (.directory (io/file (System/getProperty "user.dir"))) (.redirectErrorStream true) .start)
               (catch Exception _ nil))]
    (when r
      (let [out (str/trim (slurp (.getInputStream ^Process r)))]
        (when (and (zero? (.waitFor ^Process r)) (str/ends-with? out "/.git"))
          (subs out 0 (- (count out) 5)))))))

(defn short-home
  "A fresh home whose unix socket path fits the daemon's 100 bytes, from any checkout path. The real
  directory is `target/th/<rand>` under the cwd; when its socket path is too long (a worktree under
  `.scratch/wt-*/`), the home is a short symlink `<main checkout>/.scratch/s/<rand>` to it. -> File, or
  nil when even that is too long. Hand it back to `release-home!`."
  []
  (let [real (io/file (System/getProperty "user.dir") "target" "th" (rand5))]
    (.mkdirs real)
    (if-not (sock-too-long? real)
      real
      (when-let [root (main-checkout)]
        (let [dir (io/file root ".scratch" "s")
              link (io/file dir (rand5))]
          (.mkdirs dir)
          (when-not (sock-too-long? link)
            (Files/createSymbolicLink (.toPath link) (.toPath real) (make-array FileAttribute 0))
            link))))))

(defn release-home!
  "Removes the symlink of a `short-home` (the real directory stays, under target/), after a short grace:
  a cancelled run of a stopped daemon may still write its last files through the link, and a write
  through a dangling link would recreate the directory in `.scratch/s/`."
  [^java.io.File home]
  (when (and home (Files/isSymbolicLink (.toPath home)))
    (Thread/sleep 1500)
    (Files/deleteIfExists (.toPath home))))

(defmacro with-short-home
  "Binds `store/*home*` to a `short-home` for body, removes its symlink after; without a usable short
  home reports a `:skip` and does not run body."
  [& body]
  `(if-let [h# (short-home)]
     (try (binding [store/*home* h#] ~@body) (finally (release-home! h#)))
     (t/do-report {:type :skip :message (str "unix socket path over " ~max-unix-path-bytes " bytes even via .scratch/s; skipped")})))

(defn fixed-clock [^String iso] (let [i (Instant/parse iso)] (constantly i)))

(defn meta-for
  "A valid raw meta map."
  ([] (meta-for {}))
  ([m] (merge {:source :http :op "GET /x" :env :local :at (Instant/parse "2026-10-16T10:00:00Z")
               :content-type "text/plain"} m)))

(defn bytes-of [^String s] (.getBytes s "UTF-8"))
