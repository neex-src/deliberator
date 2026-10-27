(ns deliberator.pr-test
  "M5.5 `pr.open`: the git half on a local bare repository that carries `refs/pull/1/head` (no network, no GitHub: the clone's url is a
  path), and the text of a PR's discussion from synthetic API documents. The GitHub half against a real public PR is `pr-github-test`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.pr :as pr]
            [deliberator.test-util :as tu]))

(defn- git [dir & args]
  (let [r (apply sh/sh "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c" "commit.gpgsign=false" "-c" "protocol.file.allow=always"
                 (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {:r r})))
    (str/trim (:out r))))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))

(defn- commit! [work file text msg]
  (spit (io/file work file) text) (git work "add" ".") (git work "commit" "-m" msg) (git work "rev-parse" "HEAD"))

(defn- fixture
  "A bare origin with main (one commit) and a PR head `refs/pull/1/head` (one more commit on a branch that is NOT in refs/heads)."
  [root]
  (let [origin (io/file root "origin.git") work (io/file root "work")]
    (.mkdirs origin)
    (git origin "init" "--bare" "-b" "main" ".")
    (git root "clone" "--quiet" (str origin) "work")
    (commit! work "a.txt" "one\n" "one")
    (git work "branch" "-M" "main") (git work "push" "-u" "origin" "main")
    (git work "checkout" "-b" "feature")
    (let [head (commit! work "pr.txt" "the change\n" "the change")]
      (git work "push" "origin" "feature:refs/pull/1/head")
      (git work "checkout" "main")
      (git work "branch" "-D" "feature")
      {:origin (str origin) :work (str work) :head head})))

(defn- push-pr! [work sha] (git work "push" "--force" "origin" (str sha ":refs/pull/1/head")))

(deftest describe-lists-pr-open-and-ci-local
  (let [by (into {} (map (juxt :op identity)) (ops/describe))]
    (is (= ["repo" "n" "github" "session" "ws"] (:order (by "pr.open"))))
    (is (= ["repo" "workflow" "job" "worktree" "event" "env" "image" "session" "ws"] (:order (by "ci.local"))))
    (is (= ["logs.line"] (:events (by "ci.local"))))))

(deftest open-fetches-the-pull-head-into-a-worktree
  (tu/with-temp-home
    (let [{:keys [origin work head]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})
          r (call "pr.open" {:repo "proj" :n 1})
          wt (get-in r [:worktree :path])]
      (testing "the worktree is at the PR head, on branch pr-1, under worktrees/proj"
        (is (= head (:head-sha r)))
        (is (= "refs/pull/1/head" (:ref r)))
        (is (= "pr-1" (get-in r [:worktree :branch])))
        (is (true? (get-in r [:worktree :created])))
        (is (str/ends-with? wt "/worktrees/proj/pr-1"))
        (is (= head (git wt "rev-parse" "HEAD")))
        (is (= "the change\n" (slurp (io/file wt "pr.txt")))))
      (testing "a repository whose url is a path has no GitHub half: said, not failed"
        (is (nil? (:github r)))
        (is (str/includes? (:note r) "no GitHub repository")))
      (testing "the ref lives outside refs/remotes/origin: a later worktree.start (fetch --prune) keeps it"
        (call "worktree.start" {:repo "proj" :branch "other"})
        (is (= head (git (:path (call "repo.clone" {:url origin :name "proj"})) "rev-parse" "refs/pull/1/head"))))
      (testing "again, nothing moved"
        (let [r2 (call "pr.open" {:repo "proj" :n 1})]
          (is (false? (get-in r2 [:worktree :created])))
          (is (false? (get-in r2 [:worktree :moved])))))
      (testing "the PR gets a new commit: a clean worktree is fast-forwarded"
        (let [new (do (git work "checkout" "-q" head) (commit! work "pr.txt" "the change\nmore\n" "more"))
              _ (push-pr! work new)
              r3 (call "pr.open" {:repo "proj" :n 1})]
          (is (true? (get-in r3 [:worktree :moved])))
          (is (= new (git wt "rev-parse" "HEAD")))
          (is (= "the change\nmore\n" (slurp (io/file wt "pr.txt"))))
          (testing "a dirty worktree is left alone and says so"
            (spit (io/file wt "pr.txt") "edited\n")
            (let [newer (commit! work "pr.txt" "the change\nmore\nand more\n" "and more")
                  _ (push-pr! work newer)
                  r4 (call "pr.open" {:repo "proj" :n 1})]
              (is (true? (get-in r4 [:worktree :stale])))
              (is (false? (get-in r4 [:worktree :moved])))
              (is (= "edited\n" (slurp (io/file wt "pr.txt"))))
              (is (= newer (:head-sha r4)) "the ref moved even though the worktree did not")
              (git wt "checkout" "--" "pr.txt")
              (testing "a force-push that drops the worktree's commits: diverged, not reset"
                (git work "checkout" "-q" "main")
                (let [other (commit! work "other.txt" "x\n" "elsewhere")
                      _ (push-pr! work other)
                      r5 (call "pr.open" {:repo "proj" :n 1})]
                  (is (true? (get-in r5 [:worktree :diverged])))
                  (is (= "the change\nmore\n" (slurp (io/file wt "pr.txt"))) "the worktree kept its files"))))))))))

(deftest open-errors-are-typed
  (tu/with-temp-home
    (let [{:keys [origin]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})]
      (is (= :pr/bad-number (:type (thrown #(call "pr.open" {:repo "proj" :n 0})))))
      (is (= :pr/fetch-failed (:type (thrown #(call "pr.open" {:repo "proj" :n 99})))) "no such pull ref")
      (is (str/includes? (:hint (thrown #(call "pr.open" {:repo "proj" :n 99}))) "refs/pull"))
      (is (= :repo/not-found (:type (thrown #(call "pr.open" {:repo "nope" :n 1}))))))))

(deftest open-works-in-an-attached-checkout-too
  (tu/with-temp-home
    (let [{:keys [origin head]} (fixture (tu/temp-home))
          dir (io/file (tu/temp-home) "mine")]
      (git (tu/temp-home) "clone" "--quiet" origin (str dir))
      (call "repo.attach" {:path (str dir)})
      (let [r (call "pr.open" {:repo "mine" :n 1})]
        (is (= head (git (get-in r [:worktree :path]) "rev-parse" "HEAD")))
        (is (= head (git dir "rev-parse" "refs/pull/1/head")))))))

(def ^:private docs
  {:pr {"title" "Add the thing" "state" "closed" "merged" true "user" {"login" "alice"} "body" "Does it.\r\nTwice."
        "base" {"ref" "main"} "head" {"ref" "feat" "label" "bob:feat" "sha" "abcdef0123456789"}
        "commits" 2 "changed_files" 3 "additions" 10 "deletions" 4 "html_url" "https://github.com/o/r/pull/7"}
   :reviews [{"user" {"login" "carol"} "state" "CHANGES_REQUESTED" "body" "Please split it." "submitted_at" "2026-01-02T10:00:00Z"}
             {"user" {"login" "dave"} "state" "COMMENTED" "body" "" "submitted_at" "2026-01-02T11:00:00Z"}]
   :inline [{"id" 1 "path" "src/a.clj" "line" 10 "user" {"login" "carol"} "created_at" "2026-01-02T10:00:01Z"
             "diff_hunk" "@@ -1,3 +1,4 @@\n a\n+b\n c" "body" "Why not a map?"}
            {"id" 2 "path" "src/a.clj" "line" 10 "in_reply_to_id" 1 "user" {"login" "bob"} "created_at" "2026-01-02T12:00:00Z" "body" "Order matters."}
            {"id" 3 "path" "README.md" "line" 2 "user" {"login" "carol"} "created_at" "2026-01-02T10:00:02Z" "diff_hunk" "" "body" "Typo."}]
   :conversation [{"id" 9 "user" {"login" "erin"} "created_at" "2026-01-03T09:00:00Z" "body" "Merged, thanks."}]})

(deftest review-text-groups-threads-under-their-file
  (let [t (pr/review-text docs {:slug "o/r" :n 7 :fetched "T" :auth "none" :head-sha "abcdef0123456789"})]
    (is (str/includes? t "# o/r#7: Add the thing"))
    (is (str/includes? t "merged, by alice, main <- bob:feat, head abcdef012345"))
    (is (str/includes? t "3 inline comments, 1 reviews, 1 conversation comments") "an empty COMMENTED review is not a review")
    (is (str/includes? t "carol  CHANGES_REQUESTED") t)
    (is (not (str/includes? t "dave")))
    (testing "files in order, README before src; the thread: root, hunk tail, reply indented"
      (is (< (str/index-of t "README.md:2") (str/index-of t "src/a.clj:10")))
      (let [i (str/index-of t "src/a.clj:10")
            thread (subs t i (str/index-of t "## conversation"))]
        (is (str/includes? thread "  | +b"))
        (is (str/includes? thread "  Why not a map?"))
        (is (< (str/index-of thread "Why not a map?") (str/index-of thread "reply: bob")))
        (is (str/includes? thread "    Order matters."))))
    (is (str/includes? t "Does it.\nTwice.") "CRLF as LF")
    (is (str/includes? t "erin  2026-01-03T09:00:00Z  #9"))))
