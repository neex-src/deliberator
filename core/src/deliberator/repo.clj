(ns deliberator.repo
  "Repositories attached to a workspace: `workspace.edn :repositories`, a vector of
  `{:name :path :kind :attached-at}`. This records an attachment of an existing checkout and nothing
  else (the IDE does the IDE half; the store and worktrees are M5); files are never touched.

  `(attach! ws path & {:keys [kind]})` -> the entry. The path must exist (`:repo/path-not-found`) and be
  a directory (`:repo/not-a-directory`); it is stored as the real path; the name is the directory's name.
  Kind: `gradle` when the directory has `build.gradle*` or `settings.gradle*`, else `maven` with a
  `pom.xml`, else `plain` (gradle wins when both exist); an explicit `kind` must be one of those
  (`:repo/bad-kind`). Attaching a path again returns its entry (a given kind updates it); another path
  with the same name is `:repo/name-taken`. `(detach! ws name)` -> the removed entry
  (`:repo/not-found` lists the known names). `(list ws)` -> the entries in attach order."
  (:refer-clojure :exclude [list])
  (:require [clojure.string :as str]
            [deliberator.store :as store]
            [deliberator.workspace :as workspace])
  (:import (java.nio.file Files LinkOption Path Paths)))

(def kinds #{"gradle" "maven" "plain"})

(def ^:private lock (Object.))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn detect-kind
  "`gradle`, `maven` or `plain` for directory `dir` (a Path)."
  [^Path dir]
  (let [names (with-open [st (Files/list dir)]
                (set (map #(str (.getFileName ^Path %)) (iterator-seq (.iterator st)))))]
    (cond (some #(or (str/starts-with? % "build.gradle") (str/starts-with? % "settings.gradle")) names) "gradle"
          (contains? names "pom.xml") "maven"
          :else "plain")))

(defn list
  "The attached repositories of `ws`, in attach order."
  [ws]
  (vec (:repositories (workspace/load ws))))

(defn- save! [ws repos] (workspace/save! (assoc (workspace/load ws) :repositories (vec repos))))

(defn attach!
  [ws path & {:keys [kind]}]
  (when (and kind (not (kinds kind)))
    (fail :repo/bad-kind (str "unknown repository kind " (pr-str kind)) :hint (str "one of " (str/join ", " (sort kinds)))))
  (let [p (Paths/get (str path) (make-array String 0))
        _ (when-not (Files/exists p (make-array LinkOption 0))
            (fail :repo/path-not-found (str "no such path " path) :hint "pass the checkout's absolute path"))
        _ (when-not (Files/isDirectory p (make-array LinkOption 0))
            (fail :repo/not-a-directory (str path " is not a directory") :hint "attach the repository's root directory"))
        real (str (.toRealPath p (make-array LinkOption 0)))
        nm (str (.getFileName (Paths/get real (make-array String 0))))]
    (locking lock
      (let [repos (list ws)
            same (first (filter #(= real (:path %)) repos))
            clash (first (filter #(and (= nm (:name %)) (not= real (:path %))) repos))]
        (cond
          clash (fail :repo/name-taken (str "a repository named " nm " is already attached from " (:path clash))
                      :hint "detach it first; the name is the directory's name")
          same (if (and kind (not= kind (:kind same)))
                 (let [e (assoc same :kind kind)]
                   (save! ws (map #(if (= (:name %) nm) e %) repos)) e)
                 same)
          :else (let [e {:name nm :path real :kind (or kind (detect-kind (Paths/get real (make-array String 0))))
                         :attached-at (store/now)}]
                  (save! ws (conj repos e))
                  e))))))

(defn detach!
  [ws name]
  (locking lock
    (let [repos (list ws)
          e (first (filter #(= name (:name %)) repos))]
      (when-not e
        (fail :repo/not-found (str "no attached repository " name) :known (mapv :name repos)
              :hint "repo.list shows the attached repositories"))
      (save! ws (remove #(= name (:name %)) repos))
      e)))
