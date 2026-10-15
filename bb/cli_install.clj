(ns cli-install
  "`bb cli:install`: symlink cli/dlb into ~/.local/bin/dlb (replacing an older link)."
  (:require [babashka.fs :as fs]))

(defn -main []
  (let [src (fs/canonicalize "cli/dlb")
        dir (fs/path (fs/home) ".local" "bin")
        dst (fs/path dir "dlb")]
    (fs/create-dirs dir)
    (when (fs/sym-link? dst) (fs/delete dst))
    (when (fs/exists? dst)
      (println "refusing to replace the regular file" (str dst))
      (System/exit 1))
    (fs/create-sym-link dst src)
    (println "linked" (str dst) "->" (str src))))
