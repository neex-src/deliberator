(ns deliberator.worktree
  "Worktrees of a recorded repository (M5.1): disposable checkouts under `worktrees/<repo>/<name>/` of the workspace, made from
  the latest default branch with no question asked. Git is the only state: nothing is written besides what `git worktree`
  keeps in the repository, so `list` reads `git worktree list --porcelain` and a worktree removed behind our back is simply
  gone (reported `:missing`, pruned by `remove`).

  `(start! ws repo & {:keys [branch base fetch on-cancel!]})` -> `{:repo :name :branch :path :base :base-sha :fetched
  :created-branch}`:
    - fetches `origin` first (`fetch` false skips it; a repository without an `origin` remote has nothing to fetch). A failed
      fetch is an error (`:worktree/fetch-failed`): a worktree from a stale main is the thing this op exists to prevent;
    - `base` defaults to the latest default branch: `origin/<default>` where `<default>` is the bare clone's HEAD (an attached
      checkout: `origin/HEAD`, else `origin/main`, `origin/master`, `main`, `master`); `:worktree/no-base` when none resolves;
    - `branch` (default `work-<yyyyMMdd-HHmmss>`, UTC) is created at `base` without upstream tracking, unless it already
      exists: a local branch is checked out as it is, a branch only on `origin` is created from it (tracking). The directory
      is the branch name with `/` as `-`; one that exists is `:worktree/exists` (nothing is overwritten).
  `(list ws & {:keys [repo]})` -> rows `{:repo :name :branch :head :path :dirty :missing}` (`dirty` = changed paths).
  `(remove! ws repo name & {:keys [force]})` -> `{:repo :name :path :branch :branch-kept true}`; a worktree with changes is
  `:worktree/dirty` (the changes listed) unless `force`. The branch is never deleted: commits made there stay in the repository."
  (:refer-clojure :exclude [list])
  (:require [clojure.string :as str]
            [deliberator.git :as git]
            [deliberator.repo :as repo]
            [deliberator.store :as store])
  (:import (java.nio.file Files LinkOption Path)
           (java.time ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def ^:private stamp (-> (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") (.withZone ZoneOffset/UTC)))

(defn dir
  "`worktrees/<repo>` of the workspace."
  ^Path [ws repo-name] (store/path ws "worktrees" (store/safe-name repo-name)))

(defn- dir-name [branch] (str/replace (str branch) #"[^A-Za-z0-9._-]+" "-"))

(defn- exists? [^Path p] (Files/exists p (make-array LinkOption 0)))

(defn- ref? [gd ref] (git/ok? gd "rev-parse" "--verify" "-q" (str ref "^{commit}")))

(defn- origin? [gd] (some #{"origin"} (str/split-lines (git/out gd "remote"))))

(defn- default-base
  "The ref a fresh worktree starts from, or nil."
  [gd entry]
  (let [head (when (:bare entry) (repo/default-branch gd))
        origin-head (let [r (git/run gd ["symbolic-ref" "-q" "--short" "refs/remotes/origin/HEAD"])]
                      (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))
        candidates (if head
                     [(str "origin/" head) head]
                     (concat (when origin-head [origin-head]) ["origin/main" "origin/master" "main" "master"]))]
    (first (filter #(ref? gd %) candidates))))

(defn- porcelain
  "The `git worktree list --porcelain` blocks of `gd` -> [{:path :head :branch :bare :prunable}]."
  [gd]
  (->> (str/split (git/out gd "worktree" "list" "--porcelain") #"\n\n+")
       (keep (fn [block]
               (let [kv (into {} (keep (fn [l] (let [[k v] (str/split l #" " 2)] (when (seq k) [(keyword k) (or v true)])))
                                       (str/split-lines block)))]
                 (when (:worktree kv)
                   {:path (:worktree kv) :head (:HEAD kv) :bare (boolean (:bare kv)) :prunable (boolean (:prunable kv))
                    :branch (some-> (:branch kv) (str/replace #"^refs/heads/" ""))}))))
       vec))

(defn start!
  [ws repo-name & {:keys [branch base fetch on-cancel!] :or {fetch true}}]
  (let [entry (repo/find ws repo-name)
        gd (repo/git-dir entry)
        origin (origin? gd)
        _ (when (and fetch origin)
            (let [r (git/run gd ["fetch" "--quiet" "--prune" "origin"] {:budget-ms 300000 :on-cancel! on-cancel!})]
              (when-not (zero? (:exit r))
                (fail :worktree/fetch-failed (str "git fetch failed: " (let [e (str/trim (:err r))] (if (str/blank? e) (str "exit " (:exit r)) e)))
                      :repo repo-name :hint "worktree.start with fetch false makes one from what the repository has now"))))
        base-ref (or base (default-base gd entry)
                     (fail :worktree/no-base (str "no main branch found in " repo-name)
                           :hint "pass base (a branch or commit)"))
        _ (when-not (ref? gd base-ref)
            (fail :worktree/no-base (str "base " (pr-str base-ref) " does not resolve in " repo-name) :hint "git rev-parse in the repository shows what exists"))
        branch (or (not-empty branch) (str "work-" (.format stamp (store/now))))
        _ (when-not (zero? (:exit (git/run gd ["check-ref-format" "--branch" branch])))
            (fail :worktree/bad-branch (str "not a branch name: " (pr-str branch)) :hint "see git check-ref-format"))
        name (dir-name branch)
        wt (.resolve (dir ws repo-name) name)
        _ (when (exists? wt)
            (fail :worktree/exists (str "worktrees/" repo-name "/" name " exists") :path (str wt)
                  :hint "worktree.remove it first, or pass another branch name"))
        local? (ref? gd (str "refs/heads/" branch))
        remote? (and origin (ref? gd (str "refs/remotes/origin/" branch)))
        args (cond local? ["worktree" "add" (str wt) branch]
                   remote? ["worktree" "add" "-b" branch (str wt) (str "origin/" branch)]
                   :else ["worktree" "add" "--no-track" "-b" branch (str wt) base-ref])]
    (Files/createDirectories (dir ws repo-name) (make-array java.nio.file.attribute.FileAttribute 0))
    (let [r (git/run gd args)]
      (when-not (zero? (:exit r))
        (fail :worktree/add-failed (str "git worktree add failed: " (str/trim (:err r))) :branch branch
              :hint "a branch can be checked out in one worktree at a time")))
    {:repo repo-name :name name :branch branch :path (str wt)
     :base (cond (or local? remote?) nil :else base-ref)
     :base-sha (git/out wt "rev-parse" "HEAD") :fetched (boolean (and fetch origin))
     :created-branch (not (or local? remote?))}))

(defn- under? [^String root ^String p] (str/starts-with? p (str root "/")))

(defn- rows [ws entry]
  (let [rname (:name entry)
        root (str (dir ws rname))]
    (if-not (exists? (.toPath (java.io.File. ^String (:path entry))))
      []
      (vec (for [w (porcelain (repo/git-dir entry))
                 :when (and (not (:bare w)) (under? root (:path w)))
                 :let [missing (not (exists? (.toPath (java.io.File. ^String (:path w)))))]]
             {:repo rname :name (subs (:path w) (inc (count root))) :branch (:branch w)
              :head (some-> (:head w) (subs 0 (min 10 (count (:head w)))))
              :path (:path w) :missing missing
              :dirty (if missing 0 (count (remove str/blank? (str/split-lines (git/out (:path w) "status" "--porcelain")))))})))))

(defn list
  "The worktrees of `repo` (a name) or of every recorded repository, oldest recorded repository first."
  [ws & {:keys [repo]}]
  (let [entries (if repo [(repo/find ws repo)] (repo/list ws))]
    (vec (mapcat #(rows ws %) entries))))

(defn remove!
  [ws repo-name wt-name & {:keys [force]}]
  (let [entry (repo/find ws repo-name)
        gd (repo/git-dir entry)
        row (or (first (filter #(= wt-name (:name %)) (rows ws entry)))
                (fail :worktree/not-found (str "no worktree " wt-name " of " repo-name)
                      :known (mapv :name (rows ws entry)) :hint "worktree.list shows them"))]
    (when (and (not force) (not (:missing row)) (pos? (:dirty row)))
      (let [lines (vec (take 20 (remove str/blank? (str/split-lines (git/out (:path row) "status" "--porcelain")))))]
        (fail :worktree/dirty (str wt-name " has " (:dirty row) " changed path(s); nothing was removed")
              :lines lines :hint "commit or stash them, or pass force")))
    (git/out gd "worktree" "remove" "--force" (:path row))
    (git/run gd ["worktree" "prune"])
    {:repo repo-name :name wt-name :path (:path row) :branch (:branch row) :branch-kept true}))
