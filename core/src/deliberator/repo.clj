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
  (`:repo/not-found` lists the known names). `(list ws)` -> the entries in attach order.

  M5.1, the repository store: `(clone! ws url & {:keys [name on-cancel!]})` makes a BARE clone `repos/<name>.git` under the
  workspace (cloned into a temp directory and renamed, so a failed clone leaves nothing behind), gives it the fetch refspec
  `+refs/heads/*:refs/remotes/origin/*` (a bare clone has none: without it `fetch` would never move `origin/main`), fetches,
  and records `{:name :path :kind \"plain\" :bare true :url :default-branch :attached-at}` in the same list as attached
  checkouts. The name is the URL's last segment without `.git` (`:repo/bad-name` when it is not a plain name). Cloning the
  same url again returns the entry (`:existed true`); another url under the name is `:repo/name-taken`. `detach!` forgets the
  entry and leaves the clone on disk (nothing is deleted). `(find ws name)` -> the entry or `:repo/not-found`.
  `(git-dir entry)` is where git commands for the repository run (the bare directory, or the attached checkout)."
  (:refer-clojure :exclude [list find])
  (:require [clojure.string :as str]
            [deliberator.git :as git]
            [deliberator.store :as store]
            [deliberator.workspace :as workspace])
  (:import (java.nio.file Files LinkOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

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

(defn find
  "The entry named `name` in `ws`; `:repo/not-found` (with the known names) when there is none."
  [ws name]
  (let [repos (list ws)]
    (or (first (filter #(= name (:name %)) repos))
        (fail :repo/not-found (str "no repository " name) :known (mapv :name repos)
              :hint "repo.list shows them; repo.clone makes one from a url, repo.attach records an existing checkout"))))

(defn git-dir
  "The directory git commands for repository `entry` run in."
  [entry]
  (:path entry))

(defn url->name
  "The repository name of a clone url or path: the last segment without `.git`."
  [url]
  (let [u (str/replace (str url) #"[/\\]+$" "")
        seg (last (str/split u #"[/:\\]"))]
    (str/replace (str seg) #"\.git$" "")))

(def ^:private name-ok #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn default-branch
  "The default branch of a bare clone: where its HEAD points (set by the clone to the remote's default branch)."
  [dir]
  (let [r (git/run dir ["symbolic-ref" "-q" "--short" "HEAD"])]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn repos-dir [ws] (store/path ws "repos"))

(defn clone!
  [ws url & {:keys [name on-cancel!]}]
  (when (str/blank? (str url))
    (fail :repo/bad-url "the clone url is empty" :hint "pass a url or a path: dlb repo clone <url> [name]"))
  (let [nm (or name (url->name url))]
    (when-not (re-matches name-ok (str nm))
      (fail :repo/bad-name (str "cannot use " (pr-str nm) " as a repository name")
            :hint "letters, digits, . _ - ; pass a name: dlb repo clone <url> <name>"))
    (locking lock
      (let [existing (first (filter #(= nm (:name %)) (list ws)))
            target (.resolve ^Path (repos-dir ws) (str nm ".git"))]
        (cond
          (and existing (= (str url) (:url existing))) (assoc existing :existed true)
          existing (fail :repo/name-taken (str "a repository named " nm " is already recorded"
                                               (when (:url existing) (str " from " (:url existing))))
                         :hint "pass another name: dlb repo clone <url> <name>")
          (Files/exists target (make-array LinkOption 0))
          (fail :repo/name-taken (str (str target) " already exists on disk")
                :hint "a clone from an earlier workspace record; pass another name, or move that directory away")
          :else
          (let [tmp (.resolve ^Path (repos-dir ws) (str "." nm ".git.clone-" (subs (str (java.util.UUID/randomUUID)) 0 8)))]
            (Files/createDirectories (repos-dir ws) (make-array FileAttribute 0))
            (try
              (let [r (git/run (repos-dir ws) ["clone" "--bare" "--quiet" "--" (str url) (str tmp)]
                               {:budget-ms 600000 :on-cancel! on-cancel!})]
                (when-not (zero? (:exit r))
                  (fail :repo/clone-failed (str "git clone failed: " (let [e (str/trim (:err r))] (if (str/blank? e) (str "exit " (:exit r)) e)))
                        :hint "check the url and your git credentials (the daemon never prompts)"))
                (git/out tmp "config" "remote.origin.fetch" "+refs/heads/*:refs/remotes/origin/*")
                (git/out tmp "fetch" "--quiet" "--prune" "origin")
                (Files/move tmp target (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE])))
              (catch Throwable t
                (when (Files/exists tmp (make-array LinkOption 0))
                  (try (doseq [f (reverse (file-seq (.toFile tmp)))] (.delete ^java.io.File f)) (catch Exception _ nil)))
                (throw t)))
            (let [e {:name nm :path (str target) :kind "plain" :bare true :url (str url)
                     :default-branch (default-branch target) :attached-at (store/now)}]
              (save! ws (conj (list ws) e))
              e)))))))
