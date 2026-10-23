(ns deliberator.tour-kinds-test
  "The `shell` and `op` kinds and `scenario.run continue`, through a real daemon on a temporary home. No
  runtime: the shell steps run bash on this machine, the op steps call operations that need no containers."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.daemon :as daemon]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu]))

(defmacro ^:private with-daemon [[h] & body]
  `(tu/with-short-home
     (let [~h (daemon/start! {:idle-minutes 0})]
       (try ~@body (finally ((:stop! ~h)))))))

(defmacro ^:private with-client [[c h] & body]
  `(let [~c (tc/connect (:endpoint ~h))]
     (try ~@body (finally (tc/close! ~c)))))

(defn- write-file [name text]
  (let [f (io/file (str (store/home)) "scn" name)]
    (io/make-parents f)
    (spit f text)
    (str f)))

(defn- run!
  "scenario.run of `text`; -> {:terminal :events :steps {n step-summary} :result-text}."
  [c name text & {:as args}]
  (let [{:keys [terminal events]} (tc/request c "scenario.run" (merge {:path (write-file name text)} args))
        res (or (:result terminal) (get-in terminal [:error :result]))
        run-dir (get-in res [:paths :run])
        run-edn (when run-dir (store/read-edn (io/file run-dir "run.edn")))]
    {:terminal terminal :events events
     :status (:status res)
     :steps (into {} (map (juxt :n identity)) (:steps run-edn))
     :run-dir run-dir
     :text (when run-dir (slurp (io/file run-dir "result.txt")))}))

(defn- step-file [{:keys [run-dir]} n fname]
  (let [d (first (filter #(str/starts-with? (.getName %) (format "%03d-" n)) (.listFiles (io/file run-dir "steps"))))]
    (slurp (io/file d fname))))

(deftest shell-kind
  (with-daemon [h]
    (with-client [c h]
      (tc/request c "session.start" {:key "T-1"})
      (testing "output, facts, JSON, the environment, request.sh and response.txt"
        (let [r (run! c "a.scn" (str "### shell hello\n# @assert out contains hi there\n# @assert lines == 2\n# @capture greeting = out\n"
                                     "echo hi there\necho \"ws=$DLB_WS session=$DLB_SESSION\"\n\n"
                                     "### shell json\n# @assert $.n == 3\n# @assert $.who == {{greeting}}\n"
                                     "echo '{\"n\": 3, \"who\": \"hi there\\nws=default session=T-1\"}'\n\n"
                                     "### shell home\n# @assert out == " (store/home) "\necho -n $DELIBERATOR_HOME\n"))]
          (is (= "ok" (:status r)) (:text r))
          (is (= 0 (get-in r [:steps 1 :facts "exit"])))
          (is (= "hi there\nws=default session=T-1" (get-in r [:steps 1 :facts "out"])))
          (is (= "echo hi there\necho \"ws=$DLB_WS session=$DLB_SESSION\"\n" (step-file r 1 "request.sh")))
          (is (= "hi there\nws=default session=T-1\n" (step-file r 1 "response.txt")))
          (is (some #(= {:event "line" :step 1 :text "hi there"} (select-keys % [:event :step :text])) (:events r))
              "lines stream as events while the script runs")))
      (testing "a non-zero exit is a step error with a hint, unless the step asserts on exit"
        (let [r (run! c "b.scn" "### shell fails\necho broken >&2\nexit 3\n\n### shell expected\n# @assert exit == 3\nexit 3\n")]
          (is (= "failed" (:status r)))
          (is (= :error (get-in r [:steps 1 :status])))
          (is (str/includes? (get-in r [:steps 1 :error :message]) "exit 3: broken"))
          (is (str/includes? (str (get-in r [:steps 1 :error :hint])) "# @assert exit == 3"))
          (is (= "broken\n" (step-file r 1 "response.txt")) "the output is kept on error")
          (is (= :ok (get-in r [:steps 2 :status])))))
      (testing "@cwd is relative to the scenario's directory"
        (let [d (io/file (str (store/home)) "scn" "sub")
              _ (.mkdirs d)
              r (run! c "c.scn" "### shell where\n# @cwd sub\n# @assert out contains /scn/sub\npwd\n")]
          (is (= "ok" (:status r)) (:text r))))
      (testing "a timeout kills the script and its children"
        (let [marker (str "dlb-shell-timeout-" (System/nanoTime))
              t0 (System/nanoTime)
              r (run! c "d.scn" (str "### shell slow\n# @timeout 1s\nsleep 30 # " marker "\n"))
              ms (quot (- (System/nanoTime) t0) 1000000)]
          (is (= :error (get-in r [:steps 1 :status])))
          (is (< ms 10000) (str "took " ms " ms"))
          (Thread/sleep 300)
          (is (not (str/includes? (:out (shell/sh "ps" "-eo" "args")) marker)) "no sleep left behind"))))))

(deftest op-kind
  (with-daemon [h]
    (with-client [c h]
      (tc/request c "session.start" {:key "T-1"})
      (let [src (write-file "Src.java" "class Src {\n  void f() {}\n  void g() {}\n}\n")]
        (testing "an operation as a step: ws defaulted, events, $ = the result, request/response persisted"
          (let [r (run! c "a.scn" (str "### op marker.add mark\n{\"file\": \"" src "\", \"line\": 2, \"logger\": \"Src\"}\n\n"
                                       "### op marker.list\n# @assert $[0].line == 2\n# @capture id = $[0].id\n\n"
                                       "### op session.current\n# @assert $.session.key == T-1\n"))]
            (is (= "ok" (:status r)) (:text r))
            (is (= "true" (get-in r [:steps 1 :facts "ok"])))
            (is (re-find #"\"ws\"\s*:\s*\"default\"" (step-file r 1 "request.json")))
            (is (str/includes? (step-file r 2 "response.json") "\"logger\""))))
        (testing "an operation error is a step error, unless the step asserts on it"
          (let [r (run! c "b.scn" (str "### op runs.show missing\n{\"run\": \"no-such-run\"}\n\n"
                                       "### op runs.show expected\n# @assert ok == false\n# @assert error.type == runs/not-found\n{\"run\": \"no-such-run\"}\n"))]
            (is (= "failed" (:status r)))
            (is (= :error (get-in r [:steps 1 :status])))
            (is (str/includes? (get-in r [:steps 1 :error :message]) "runs/not-found"))
            (is (= :ok (get-in r [:steps 2 :status])) (:text r))))
        (testing "the operations that would run the daemon into itself are not offered"
          (let [{:keys [terminal]} (tc/request c "scenario.run" {:path (write-file "c.scn" "### op daemon.stop\n")})]
            (is (false? (:ok terminal)))
            (is (str/includes? (str (get-in terminal [:error :message])) "op needs a verb: aws.describe"))
            (is (not (str/includes? (str (get-in terminal [:error :message])) "daemon.stop")))))))))

(deftest continue-carries-variables-and-captures
  (with-daemon [h]
    (with-client [c h]
      (tc/request c "session.start" {:key "T-1"})
      (let [text (str "# @var msg = ord-{{uuid}}\n\n"
                      "### shell first\n# @capture id = out\necho -n \"id-{{msg}}\"\n\n"
                      "### shell second\n# @assert out == {{id}}\necho -n \"id-{{msg}}\"\n")
            full (run! c "s.scn" text)
            msg (get-in full [:steps 1 :facts "out"])]
        (is (= "ok" (:status full)) (:text full))
        (testing "without continue, step 2 alone lacks the capture"
          (let [r (run! c "s.scn" text :steps [2])]
            (is (= "failed" (:status r)))
            (is (= :skipped (get-in r [:steps 2 :status])) (:text r))))
        (testing "with continue, step 2 alone sees the same msg and the capture of the latest run"
          (let [r (run! c "s.scn" text :steps [2] :continue true)]
            (is (= "ok" (:status r)) (:text r))
            (is (= msg (get-in r [:steps 2 :facts "out"])) "same {{msg}} as the full run, not a fresh uuid")))
        (testing "a continued run continues too, and a fresh full run starts a new story"
          (let [r (run! c "s.scn" text :steps [2] :continue true)]
            (is (= msg (get-in r [:steps 2 :facts "out"]))))
          (let [full2 (run! c "s.scn" text)
                r (run! c "s.scn" text :steps [2] :continue true)]
            (is (not= msg (get-in full2 [:steps 1 :facts "out"])))
            (is (= (get-in full2 [:steps 1 :facts "out"]) (get-in r [:steps 2 :facts "out"])))))))))
