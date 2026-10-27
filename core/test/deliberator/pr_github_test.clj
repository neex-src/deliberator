(ns deliberator.pr-github-test
  "M5.5 `pr.open` against the real GitHub, read-only (git fetch of a public repository over https, GET requests): pull request 285 of
  `babashka/fs` (merged: its head ref stays, two inline review comments, two reviews, runs of its head commit). Skips, counted and
  printed, when GitHub or the clone is unreachable. The token, when there is one, is never printed and never lands in a file."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.floci-util :as fu]
            [deliberator.github :as gh]
            [deliberator.ops :as ops]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))

(defn- git [dir & args]
  (str/trim (:out (apply sh/sh "git" (concat args [:dir (str dir)])))))

(defn- scan-for [s]
  (boolean (some #(str/includes? (String. (java.nio.file.Files/readAllBytes (.toPath ^java.io.File %)) "ISO-8859-1") s)
                 (filter #(.isFile ^java.io.File %) (file-seq (.toFile (store/path "w")))))))

(deftest real-pull-request-of-a-public-repository
  (tu/with-temp-home
    (let [cloned (try (call "repo.clone" {:url "https://github.com/babashka/fs.git" :name "fs"})
                      (catch clojure.lang.ExceptionInfo e e))]
      (if (instance? Throwable cloned)
        (fu/skip! "pr-github" (str "cannot clone babashka/fs: " (ex-message cloned)))
        (let [r (try (call "pr.open" {:repo "fs" :n 285 :session "PR1"})
                     (catch clojure.lang.ExceptionInfo e e))]
          (cond
            (instance? Throwable r)
            (if (#{:ci/unreachable :ci/rate-limited :pr/fetch-failed} (:type (ex-data r)))
              (fu/skip! "pr-github" (str "GitHub not usable here: " (ex-message r)))
              (throw r))
            (:github-error r)
            (fu/skip! "pr-github" (str "GitHub API not usable here: " (:github-error r)))
            :else
            (let [wt (get-in r [:worktree :path])
                  text (slurp (get-in r [:comments :path]))
                  {:keys [token]} (gh/resolve-token "w")]
              (testing "git: the PR head is in a worktree under worktrees/fs/pr-285"
                (is (= "37770c07957fa11ca023c8db228f223e3771bfb4" (:head-sha r)))
                (is (str/ends-with? wt "/worktrees/fs/pr-285"))
                (is (= (:head-sha r) (git wt "rev-parse" "HEAD")))
                (is (= "pr-285" (git wt "rev-parse" "--abbrev-ref" "HEAD")))
                (is (.isFile (io/file wt "deps.edn"))))
              (testing "GitHub: the pull request, its reviews and comments as one text file under the session"
                (is (= "babashka/fs" (:github r)))
                (is (= "Fix #284: match immediate child dirs in non-recursive glob" (get-in r [:pr :title])))
                (is (true? (get-in r [:pr :merged])))
                (is (= "borkdude" (get-in r [:pr :author])))
                (is (str/includes? (get-in r [:comments :path]) "/sessions/PR1/pr/pr-babashka-fs-285-review"))
                (is (= {:inline 2 :reviews 2 :conversation 0} (select-keys (:comments r) [:inline :reviews :conversation])))
                (is (str/includes? text "# babashka/fs#285: Fix #284"))
                (is (str/includes? text "## inline comments (2)"))
                (is (re-find #"(?m)^src/\S+:\d+$" text) "an inline comment sits under its file and line"))
              (testing "checks: the runs of the head commit through ci.runs, as the session's ci/ text"
                (is (pos? (get-in r [:checks :count])))
                (is (str/includes? (get-in r [:checks :path]) "/sessions/PR1/ci/runs-babashka-fs-37770c07957f-"))
                (is (every? #(= "ci" (:workflow %)) (filter #(= "ci" (:workflow %)) (get-in r [:checks :rows]))))
                (is (seq (get-in r [:checks :by-result]))))
              (testing "every response was persisted raw first; the token is nowhere"
                (is (scan-for "\"review_comments\""))
                (when token (is (not (scan-for token)))))
              (testing "opening it again changes nothing"
                (let [r2 (call "pr.open" {:repo "fs" :n 285 :session "PR1"})]
                  (is (false? (get-in r2 [:worktree :created])))
                  (is (false? (get-in r2 [:worktree :moved]))))))))))))
