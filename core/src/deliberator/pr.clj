(ns deliberator.pr
  "A pull request as a worktree (M5.5): `(open! ws {:repo :n ...})`.

  The git half works on any repository with an `origin` that advertises `refs/pull/<n>/head` (GitHub does; so does a local bare repo
  that carries the ref, which is how it is tested): it fetches `+refs/pull/<n>/head:refs/pull/<n>/head` into the repository (that
  namespace is outside `refs/remotes/origin/*`, so the `fetch --prune` of `worktree.start` never prunes it) and attaches a worktree
  `worktrees/<repo>/pr-<n>` on branch `pr-<n>`. Opening the same PR again fetches again and fast-forwards a clean worktree to the new
  head; a dirty one, or one that has commits of its own the new head does not contain, is left as it is (`:moved false`, `:stale` /
  `:diverged` true). The branch is a local mirror: nothing is ever pushed.

  The GitHub half runs when the repository's GitHub slug is known (`github` `owner/name`, else the recorded url, else `origin`'s url;
  `github` false skips it) and is read-only GET like all of `ci.*`, every response raw first:
    - the PR itself (`GET /pulls/<n>`), the reviews, the inline review comments and the conversation comments (100 per page, at most
      10 pages each), written as ONE text file `sessions/<k>/pr/pr-<owner>-<repo>-<n>-review.txt`: header, then each inline thread
      under its file and line (the diff hunk's last lines, replies indented), the reviews with their state, the conversation;
    - the checks: `ci.runs` of the PR's head sha (`runs-<owner>-<repo>-<sha>-<ts>.txt` under `sessions/<k>/ci/`), and its rows with
      the counts by result.
  A GitHub failure (rate limit, no access) does not undo the worktree: the result carries `:github-error`."
  (:require [clojure.string :as str]
            [deliberator.ci :as ci]
            [deliberator.git :as git]
            [deliberator.repo :as repo]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.worktree :as worktree])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn- parse-n [n]
  (let [v (cond (integer? n) n (re-matches #"\s*#?(\d+)\s*" (str n)) (parse-long (second (re-matches #"\s*#?(\d+)\s*" (str n)))))]
    (if (and v (pos? v))
      v
      (fail :pr/bad-number (str "not a pull request number: " (pr-str n)) :hint "dlb pr open <repo> <number>"))))

(defn pull-ref [n] (str "refs/pull/" n "/head"))

;; the git half --------------------------------------------------------------------------------------------

(defn- fetch-head! [gd n on-cancel!]
  (let [ref (pull-ref n)
        r (git/run gd ["fetch" "--quiet" "--no-tags" "origin" (str "+" ref ":" ref)] {:budget-ms 300000 :on-cancel! on-cancel!})]
    (when-not (zero? (:exit r))
      (fail :pr/fetch-failed (str "git fetch of " ref " failed: " (let [e (str/trim (:err r))] (if (str/blank? e) (str "exit " (:exit r)) e)))
            :hint "check the number and that origin advertises refs/pull/<n>/head (GitHub does; a private repository needs your git credentials)"))
    (git/out gd "rev-parse" "--verify" (str ref "^{commit}"))))

(defn- attach!
  "The worktree `pr-<n>` of `repo-name` at `head`: made when absent, fast-forwarded when clean and behind. -> the result map."
  [ws repo-name n head]
  (let [name (str "pr-" n)
        row (first (filter #(= name (:name %)) (worktree/list ws :repo repo-name)))]
    (if-not row
      (let [r (worktree/start! ws repo-name :branch name :base (pull-ref n) :fetch false)
            wt (:path r)]
        ;; a branch pr-<n> left from an earlier worktree is checked out as it was: bring it to the head when it can go there
        (if (= head (git/out wt "rev-parse" "HEAD"))
          {:name name :path wt :branch name :head head :created true :moved false}
          (if (git/ok? wt "merge-base" "--is-ancestor" "HEAD" head)
            (do (git/out wt "merge" "--ff-only" "--quiet" head) {:name name :path wt :branch name :head head :created true :moved true})
            {:name name :path wt :branch name :head (git/out wt "rev-parse" "HEAD") :created true :moved false :diverged true})))
      (let [wt (:path row)
            cur (git/out wt "rev-parse" "HEAD")]
        (cond
          (= cur head) {:name name :path wt :branch (:branch row) :head head :created false :moved false}
          (pos? (:dirty row)) {:name name :path wt :branch (:branch row) :head cur :created false :moved false :stale true
                               :hint "the worktree has changes; commit or stash them, then open the PR again"}
          (git/ok? wt "merge-base" "--is-ancestor" "HEAD" head)
          (do (git/out wt "merge" "--ff-only" "--quiet" head)
              {:name name :path wt :branch (:branch row) :head head :created false :moved true :from cur})
          :else {:name name :path wt :branch (:branch row) :head cur :created false :moved false :diverged true
                 :hint "the worktree has commits the new head does not contain (a force-push?); worktree.remove it to start over"})))))

;; the GitHub half -----------------------------------------------------------------------------------------

(defn- origin-url [gd]
  (let [r (git/run gd ["remote" "get-url" "origin"])]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn slug-for
  "`owner/name` of the GitHub repository of recorded repository `entry`: `github` if given, else its url, else origin's url; nil if none."
  [entry gd github]
  (or (when (string? github) (some-> (not-empty github) ((fn [g] (or (when (re-matches ci/slug-re g) g) (ci/slug-of-url g))))))
      (ci/slug-of-url (:url entry))
      (ci/slug-of-url (origin-url gd))))

(defn- pages
  "Every item of a paged list endpoint (100 per page, at most 10 pages), each page a raw blob."
  [c path]
  (loop [page 1 acc []]
    (let [items (ci/json-of (ci/api! c path {:per_page 100 :page page}))
          acc (into acc items)]
      (if (and (= 100 (count items)) (< page 10)) (recur (inc page) acc) acc))))

(defn- indent [s pad] (str/join "\n" (map #(str pad %) (str/split-lines (str s)))))

(defn- body-text [s] (let [t (-> (str s) (str/replace "\r\n" "\n") str/trim)] (if (str/blank? t) "(no text)" t)))

(defn- user [m] (get-in m ["user" "login"] "ghost"))

(defn- hunk-tail [hunk]
  (when-not (str/blank? hunk) (str/join "\n" (take-last 3 (str/split-lines hunk)))))

(defn- thread-lines [root replies]
  (concat [(str (user root) "  " (get root "created_at") "  #" (get root "id") (when (get root "outdated") "  (outdated)"))]
          (when-let [h (hunk-tail (get root "diff_hunk"))] [(indent h "  | ")])
          [(indent (body-text (get root "body")) "  ")]
          (mapcat (fn [r] (cons (str "  reply: " (user r) "  " (get r "created_at") "  #" (get r "id"))
                                [(indent (body-text (get r "body")) "    ")]))
                  replies)))

(defn review-text
  "The text of a PR's discussion from the parsed API documents `{:pr :reviews :inline :conversation}` and fetch facts
  `{:slug :n :fetched :auth}`."
  [{:keys [pr reviews inline conversation]} {:keys [slug n fetched auth head-sha]}]
  (let [by-parent (group-by #(get % "in_reply_to_id") inline)
        roots (->> (get by-parent nil)
                   (sort-by (juxt #(get % "path" "") #(or (get % "line") (get % "original_line") 0) #(get % "created_at" ""))))
        replies-of (fn [r] (sort-by #(get % "created_at" "") (get by-parent (get r "id"))))
        by-file (group-by #(get % "path") roots)
        ordered-files (sort (keys by-file))
        all-reviews reviews
        reviews (remove #(and (= "COMMENTED" (get % "state")) (str/blank? (get % "body"))) reviews)]
    (str "# " slug "#" n ": " (get pr "title") "\n"
         "# " (if (get pr "merged") "merged" (get pr "state")) (when (get pr "draft") ", draft") ", by " (user pr)
         ", " (get-in pr ["base" "ref"]) " <- " (get-in pr ["head" "label"] (get-in pr ["head" "ref"]))
         ", head " (subs (str (or head-sha (get-in pr ["head" "sha"]))) 0 (min 12 (count (str (or head-sha (get-in pr ["head" "sha"]))))))
         (when-let [m (get pr "mergeable_state")] (str ", mergeable_state " m)) "\n"
         "# " (get pr "commits") " commits, " (get pr "changed_files") " files, +" (get pr "additions") " -" (get pr "deletions")
         "; " (get pr "html_url") "\n"
         "# " (count inline) " inline comments, " (count all-reviews) " reviews, " (count conversation) " conversation comments; fetched "
         fetched ", auth " auth "\n\n"
         "## description\n" (body-text (get pr "body")) "\n\n"
         "## reviews with a verdict or text (" (count reviews) ")\n"
         (apply str (for [r (sort-by #(get % "submitted_at" "") reviews)]
                      (str (user r) "  " (get r "state") "  " (get r "submitted_at") "\n"
                           (when-not (str/blank? (get r "body")) (str (indent (body-text (get r "body")) "  ") "\n")))))
         "\n## inline comments (" (count inline) ")\n"
         (apply str (for [f ordered-files
                          r (get by-file f)]
                      (str "\n" f ":" (or (get r "line") (get r "original_line") "?") "\n"
                           (str/join "\n" (thread-lines r (replies-of r))) "\n")))
         "\n## conversation (" (count conversation) ")\n"
         (apply str (for [c (sort-by #(get % "created_at" "") conversation)]
                      (str "\n" (user c) "  " (get c "created_at") "  #" (get c "id") "\n" (indent (body-text (get c "body")) "  ") "\n"))))))

(defn- pr-dir ^Path [ws sess]
  (let [d (.resolve (session/dir ws sess) "pr")]
    (Files/createDirectories d (make-array FileAttribute 0))
    d))

(defn- github-half!
  [ws sess slug n head-sha]
  (let [c (ci/ctx ws sess)
        [owner name] (str/split slug #"/")
        base (str "/repos/" owner "/" name)
        pr (ci/json-of (ci/api! c (str base "/pulls/" n)))
        reviews (pages c (str base "/pulls/" n "/reviews"))
        inline (pages c (str base "/pulls/" n "/comments"))
        conversation (pages c (str base "/issues/" n "/comments"))
        text (review-text {:pr pr :reviews reviews :inline inline :conversation conversation}
                          {:slug slug :n n :fetched (store/now) :auth (:auth c) :head-sha head-sha})
        p (ci/write-view! (pr-dir ws sess) (str "pr-" (ci/safe owner) "-" (ci/safe name) "-" n "-review") ".txt"
                          (.getBytes ^String text StandardCharsets/UTF_8))
        checks (ci/runs! ws {:repo slug :head-sha head-sha :limit 100 :session sess})]
    {:github slug :auth (:auth c)
     :pr {:title (get pr "title") :state (get pr "state") :merged (boolean (get pr "merged")) :draft (boolean (get pr "draft"))
          :author (user pr) :base (get-in pr ["base" "ref"]) :head-ref (get-in pr ["head" "ref"]) :head-sha-github (get-in pr ["head" "sha"])
          :url (get pr "html_url") :commits (get pr "commits") :changed-files (get pr "changed_files")}
     :comments {:path (str p) :inline (count inline) :reviews (count reviews) :conversation (count conversation)}
     :checks {:path (:path checks) :count (:count checks) :total (:total checks)
              :by-result (into (sorted-map) (frequencies (map #(or (:conclusion %) (:status %)) (:rows checks))))
              :rows (mapv #(select-keys % [:id :workflow :status :conclusion :url]) (:rows checks))}}))

;; open ----------------------------------------------------------------------------------------------------------

(defn open!
  [ws {:keys [repo n github session on-cancel!]}]
  (let [n (parse-n n)
        entry (repo/find ws repo)
        gd (repo/git-dir entry)
        _ (when-not (origin-url gd)
            (fail :pr/no-origin (str repo " has no origin remote") :hint "a pull request is fetched from origin; repo.clone records one"))
        slug (when-not (false? github) (slug-for entry gd github))
        _ (when (and slug (nil? session))
            (fail :session/none "no session: the pull request's comments and checks are written into one" :hint "pass session, or start one (session.start)"))
        head (fetch-head! gd n on-cancel!)
        wt (attach! ws (:name entry) n head)
        gh (when slug
             (try (github-half! ws session slug n head)
                  (catch clojure.lang.ExceptionInfo e
                    {:github slug :github-error (ex-message e) :github-error-type (:type (ex-data e)) :hint (:hint (ex-data e))})))]
    (merge {:repo (:name entry) :n n :ref (pull-ref n) :head-sha head :worktree wt}
           (or gh {:github nil :note (if (false? github)
                                       "github skipped"
                                       "no GitHub repository known for this clone (pass github owner/name): the checks and comments were not fetched")}))))
