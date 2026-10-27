(ns deliberator.repo-worktree-test
  "M5.1 on local bare repositories (no network): `repo.clone`, `worktree.start|list|remove` through `ops/call!` on a temporary
  home. The origin is a bare repository under the test home, a second working clone pushes to it, so 'the latest main' is
  something the test can move."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.ops :as ops]
            [deliberator.repo :as repo]
            [deliberator.service.description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- git [dir & args]
  (let [r (apply sh/sh "git" "-c" "user.name=t" "-c" "user.email=t@t" "-c" "commit.gpgsign=false" "-c" "protocol.file.allow=always"
                 (concat args [:dir (str dir)]))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {:r r})))
    (str/trim (:out r))))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- fixture
  "A bare origin with one commit on main, and a working clone `work` that can push more. -> {:origin :work}"
  [root]
  (let [origin (io/file root "origin.git") work (io/file root "work")]
    (.mkdirs origin)
    (git origin "init" "--bare" "-b" "main" ".")
    (git root "clone" "--quiet" (str origin) "work")
    (spit (io/file work "a.txt") "one\n")
    (git work "add" ".") (git work "commit" "-m" "one") (git work "branch" "-M" "main") (git work "push" "-u" "origin" "main")
    {:origin (str origin) :work (str work)}))

(defn- push-commit! [work file text msg]
  (spit (io/file work file) text) (git work "add" ".") (git work "commit" "-m" msg) (git work "push" "origin" "HEAD")
  (git work "rev-parse" "HEAD"))

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))

(deftest describe-lists-the-m5-1-ops
  (let [by (into {} (map (juxt :op identity)) (ops/describe))]
    (doseq [[op order class] [["repo.clone" ["url" "name" "ws"] "mutate"]
                              ["worktree.start" ["repo" "branch" "base" "fetch" "ws"] "mutate"]
                              ["worktree.list" ["repo" "ws"] "read"]
                              ["worktree.remove" ["repo" "name" "force" "ws"] "mutate"]]]
      (testing op (is (= order (:order (by op)))) (is (= class (:class (by op))))))))

(deftest clone-makes-a-bare-store-entry
  (tu/with-temp-home
    (let [{:keys [origin]} (fixture (tu/temp-home))
          e (call "repo.clone" {:url origin :name "proj"})]
      (is (= "proj" (:name e)))
      (is (true? (:bare e)))
      (is (= "main" (:default-branch e)))
      (is (= (str (store/path "w" "repos" "proj.git")) (:path e)))
      (is (.isFile (io/file (:path e) "HEAD")) "a bare repository")
      (is (= "+refs/heads/*:refs/remotes/origin/*" (git (:path e) "config" "remote.origin.fetch")))
      (is (str/includes? (git (:path e) "branch" "-r") "origin/main") "fetched: origin/main exists")
      (is (= [(select-keys e [:name :path])] (mapv #(select-keys % [:name :path]) (call "repo.list" {}))) "recorded with the attached ones")
      (testing "the same url again is the same entry; another url under the name is refused"
        (is (true? (:existed (call "repo.clone" {:url origin :name "proj"}))))
        (is (= :repo/name-taken (:type (thrown #(call "repo.clone" {:url (str origin "/") :name "proj"})))))))))

(deftest clone-names-and-failures
  (tu/with-temp-home
    (let [{:keys [origin]} (fixture (tu/temp-home))]
      (testing "the name defaults to the last segment without .git"
        (is (= "origin" (:name (call "repo.clone" {:url origin})))))
      (testing "a bad name"
        (is (= :repo/bad-name (:type (thrown #(call "repo.clone" {:url origin :name "../x"}))))))
      (testing "a failed clone records nothing and leaves nothing behind"
        (let [d (thrown #(call "repo.clone" {:url "/no/such/repo.git" :name "ghost"}))
              repos (store/path "w" "repos")]
          (is (= :repo/clone-failed (:type d)))
          (is (not (str/includes? (str (mapv :name (call "repo.list" {}))) "ghost")))
          (is (= ["origin.git"] (sort (.list (io/file (str repos))))) "no temp directory left"))))))

(deftest worktree-from-the-latest-main
  (tu/with-temp-home
    (let [{:keys [origin work]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})
          first-sha (git work "rev-parse" "HEAD")
          w1 (call "worktree.start" {:repo "proj" :branch "feat/one"})
          new-sha (push-commit! work "b.txt" "two\n" "two")
          w2 (call "worktree.start" {:repo "proj"})]
      (testing "the first worktree starts from main at that time, on a new branch, without upstream"
        (is (= "feat/one" (:branch w1)))
        (is (= "feat-one" (:name w1)))
        (is (= (str (store/path "w" "worktrees" "proj" "feat-one")) (:path w1)))
        (is (= first-sha (:base-sha w1)))
        (is (true? (:created-branch w1)))
        (is (true? (:fetched w1)))
        (is (= "origin/main" (:base w1)))
        (is (= "feat/one" (git (:path w1) "rev-parse" "--abbrev-ref" "HEAD")))
        (is (.isFile (io/file (:path w1) "a.txt"))))
      (testing "the next one fetches first: it has the commit pushed in between, with no questions and no arguments"
        (is (= new-sha (:base-sha w2)))
        (is (re-matches #"work-\d{8}-\d{6}" (:branch w2)))
        (is (.isFile (io/file (:path w2) "b.txt")))
        (is (not (.exists (io/file (:path w1) "b.txt"))) "the first worktree is untouched"))
      (testing "an existing directory is never overwritten"
        (is (= :worktree/exists (:type (thrown #(call "worktree.start" {:repo "proj" :branch "feat/one"}))))))
      (testing "an unknown repository"
        (is (= :repo/not-found (:type (thrown #(call "worktree.start" {:repo "nope"})))))))))

(deftest worktree-of-a-branch-that-exists-on-origin
  (tu/with-temp-home
    (let [{:keys [origin work]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})
          _ (git work "checkout" "-b" "topic") 
          topic-sha (push-commit! work "t.txt" "topic\n" "topic")
          w (call "worktree.start" {:repo "proj" :branch "topic"})]
      (is (false? (:created-branch w)))
      (is (= topic-sha (:base-sha w)))
      (is (= "origin/topic" (git (:path w) "rev-parse" "--abbrev-ref" "topic@{upstream}")) "tracks the remote branch"))))

(deftest worktree-from-an-attached-checkout
  (tu/with-temp-home
    (let [{:keys [origin]} (fixture (tu/temp-home))
          co (io/file (tu/temp-home) "co")
          _ (git (tu/temp-home) "clone" "--quiet" origin (str co))
          _ (call "repo.attach" {:path (str co)})
          w (call "worktree.start" {:repo "co" :branch "x"})]
      (is (= (git co "rev-parse" "origin/main") (:base-sha w)))
      (is (= [{:repo "co" :name "x" :branch "x"}] (mapv #(select-keys % [:repo :name :branch]) (call "worktree.list" {})))))))

(deftest list-and-remove
  (tu/with-temp-home
    (let [{:keys [origin]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})
          w1 (call "worktree.start" {:repo "proj" :branch "one"})
          w2 (call "worktree.start" {:repo "proj" :branch "two"})]
      (spit (io/file (:path w2) "dirty.txt") "x")
      (let [rows (call "worktree.list" {})
            by (into {} (map (juxt :name identity)) rows)]
        (is (= #{"one" "two"} (set (keys by))))
        (is (= 0 (:dirty (by "one"))))
        (is (= 1 (:dirty (by "two"))))
        (is (= "two" (:branch (by "two"))))
        (is (= rows (call "worktree.list" {:repo "proj"}))))
      (testing "remove refuses changes, names them, and removes nothing"
        (let [d (thrown #(call "worktree.remove" {:repo "proj" :name "two"}))]
          (is (= :worktree/dirty (:type d)))
          (is (= ["?? dirty.txt"] (:lines d)))
          (is (.isDirectory (io/file (:path w2))))))
      (testing "a clean one goes; the branch stays in the repository"
        (let [r (call "worktree.remove" {:repo "proj" :name "one"})]
          (is (true? (:branch-kept r)))
          (is (not (.exists (io/file (:path w1)))))
          (is (str/includes? (git (:path (first (call "repo.list" {}))) "branch") "one"))))
      (testing "force removes a dirty one"
        (call "worktree.remove" {:repo "proj" :name "two" :force true})
        (is (= [] (call "worktree.list" {}))))
      (testing "an unknown name lists the known ones"
        (let [d (thrown #(call "worktree.remove" {:repo "proj" :name "gone"}))]
          (is (= :worktree/not-found (:type d)))
          (is (= [] (:known d)))))
      (testing "a worktree whose directory vanished is listed missing and removable"
        (let [w (call "worktree.start" {:repo "proj" :branch "three"})]
          (doseq [f (reverse (file-seq (io/file (:path w))))] (.delete f))
          (is (= [{:name "three" :missing true}] (mapv #(select-keys % [:name :missing]) (call "worktree.list" {}))))
          (call "worktree.remove" {:repo "proj" :name "three"})
          (is (= [] (call "worktree.list" {}))))))))

(deftest repo-list-marks-bare-clones-and-filters-them-b27
  (tu/with-temp-home
    (let [{:keys [origin work]} (fixture (tu/temp-home))
          _ (call "repo.clone" {:url origin :name "proj"})
          _ (call "repo.attach" {:path work})
          rows (call "repo.list" {})
          by (into {} (map (juxt :name identity)) rows)]
      (testing "every row says whether it is bare"
        (is (true? (:bare (by "proj"))))
        (is (false? (:bare (by "work")))))
      (testing "bare filters"
        (is (= ["work"] (mapv :name (call "repo.list" {:bare false}))))
        (is (= ["proj"] (mapv :name (call "repo.list" {:bare true})))))
      (testing "what looks for files in a repository does not see the bare clone"
        (is (= ["work"] (mapv :name (repo/checkouts "w"))))
        (let [e (thrown #(deliberator.service.description/module-dir "w" {:repo "proj" :module "svc"}))]
          (is (= :service/repo-bare (:type e)))
          (is (str/includes? (:hint e) "worktree.start")))))))
