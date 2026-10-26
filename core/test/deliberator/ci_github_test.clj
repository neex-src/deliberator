(ns deliberator.ci-github-test
  "M5.2 and M5.3 against the real GitHub, read-only, on a public repository that has both failed and green runs of the same
  workflow and job: `babashka/fs`, workflow `ci`. The logs of a run expire after 90 days, so the test walks the repository's
  recent failed runs until one can be diffed; it skips (counted, printed) when GitHub is unreachable, there is no token
  (a job's log download needs one) or no failed run is left to try. The token is never printed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.floci-util :as fu]
            [deliberator.github :as gh]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(def repo "babashka/fs")

(defn- call [op args] (ops/call! op {} (merge {:ws "w"} args)))

(defn call* [op args events]
  (ops/call! op {:emit! #(swap! events conj %) :cancelled? (constantly false)} (merge {:ws "w"} args)))

(defn- type-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- scan-for
  "True when any file under the workspace holds `s`."
  [s]
  (boolean (some #(str/includes? (String. (java.nio.file.Files/readAllBytes (.toPath ^java.io.File %)) "ISO-8859-1") s)
                 (filter #(.isFile ^java.io.File %) (file-seq (.toFile (store/path "w")))))))

(deftest real-github-runs-log-tail-and-diff
  (tu/with-temp-home
    (session/start! "w" "GH1")
    (let [{:keys [token source]} (gh/resolve-token "w")
          runs (try (call "ci.runs" {:repo repo :workflow "ci" :status "failure" :limit 15})
                    (catch clojure.lang.ExceptionInfo e (if (#{:ci/unreachable :ci/rate-limited :ci/auth-required} (:type (ex-data e))) e (throw e))))]
      (cond
        (instance? Throwable runs)
        (fu/skip! "real-github" (str "GitHub not usable here: " (ex-message runs)))
        (nil? token)
        (fu/skip! "real-github" "no token (GITHUB_TOKEN, GH_TOKEN or gh auth token): a job's log download needs one")
        :else
        (do
          (testing "ci.runs: the failed runs of the workflow, as a text file, from a raw blob"
            (is (pos? (:count runs)))
            (is (every? #(= "failure" (:conclusion %)) (:rows runs)))
            (is (every? #(= "ci" (:workflow %)) (:rows runs)))
            (is (= source (:auth runs)))
            (is (re-find #"(?m)^\d+\s+#\d+\s+failure\s" (slurp (:path runs))))
            (is (str/includes? (String. ^bytes (store/raw-get "w" (:raw runs)) "UTF-8") "\"workflow_runs\"")))
          (let [diff (some (fn [row]
                             (try (call "ci.diff" {:repo repo :run (str (:id row))})
                                  (catch clojure.lang.ExceptionInfo e
                                    (when-not (#{:ci/not-found :ci/no-green-run :ci/no-failed-job :ci/not-failed :ci/no-job :ci/no-jobs :ci/log-not-available}
                                               (:type (ex-data e)))
                                      (throw e))
                                    nil)))
                           (:rows runs))]
            (if-not diff
              (fu/skip! "real-github" "none of the recent failed runs has a log and an earlier green run left to diff")
              (let [{:keys [red green]} diff
                    job-id (str (:job-id red))
                    log (call "ci.log" {:repo repo :run (:run red) :job job-id})]
                (testing "ci.log: the failed job's log as text, the raw response persisted first"
                  (is (= "failure" (:conclusion log)))
                  (is (> (:lines log) 50))
                  (let [txt (slurp (:path log))]
                    (is (not (str/includes? txt "\r")))
                    (is (re-find #"(?m)^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+Z " txt) "the runner's timestamps are in the text")
                    (is (str/includes? txt "##[error]")))
                  (is (<= 100 (count (store/raw-get "w" (:sha log)))))
                  (is (= "text/plain" (:content-type (store/raw-meta "w" (:sha log))))))
                (testing "ci.diff: red against the last green run of the same job, normalised"
                  (is (false? (:equal diff)))
                  (is (= (:job red) (:job green)) "the same job name")
                  (is (not= (:run red) (:run green)))
                  (is (re-find #"(?m)^\+##\[error\]" (slurp (:path diff))) "the failure marker is in the red run only")
                  (is (not (re-find #"(?m)^[ +-]\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{7}Z" (slurp (:path diff)))) "no raw runner timestamps are left")
                  (is (str/starts-with? (slurp (:path diff)) (first (str/split (:diff diff) #"\n\.\.\. \d+ more lines" 2))) "the file is the diff (the answer cuts it at 300 lines)")
                  (is (pos? (:added diff))))
                (testing "ci.tail of a completed job: step lines, then the whole log, one file"
                  (let [events (atom [])
                        t (call* "ci.tail" {:repo repo :run (:run red) :job job-id} events)]
                    (is (true? (:follow t)))
                    (is (= (count @events) (:lines t)))
                    (is (> (:lines t) (:lines log)) "the log plus the lines of ours")
                    (is (= (last (str/split-lines (slurp (:path log)))) (:text (last @events))))
                    (is (str/starts-with? (:text (first @events)) "[deliberator] step 1 "))))
                (testing "the token is nowhere in the workspace"
                  (is (not (scan-for token))))
                (testing "a missing run is a typed error, not a crash"
                  (is (= :ci/not-found (type-of #(call "ci.log" {:repo repo :run "1"})))))))))))))
