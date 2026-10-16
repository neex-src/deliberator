(ns deliberator.audit-runtime-test
  "Audit of M1, layers 2 and 3 against the running estate (pass B of `bb core:test:all`): needs the
  Floci of `floci-util/url` (default http://localhost:14566) and, for the `sql` step, Postgres on
  15432; each test skips with a message when Floci is down. Resources are prefixed `dlbaudit-` and
  removed. What it adds to the per-kind tests: the raw store is complete and intact after every kind
  has run, a 200 KB SQS body, `@until` and cancel against a real long poll, a confirmed re-run
  through the daemon on a remote-shaped environment, a token that is used twice. Tests tagged
  `^:known-bug` describe behaviour the product does not have yet (`clojure -M:test:known-bugs`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.daemon :as daemon]
            [deliberator.daemon-test :as dt :refer [ok! with-client with-daemon write-file]]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.scenario.engine :as engine]
            [deliberator.session :as session]
            [deliberator.steps.dynamodb]
            [deliberator.steps.eventbridge]
            [deliberator.steps.http]
            [deliberator.steps.lambda]
            [deliberator.steps.logs]
            [deliberator.steps.scheduler]
            [deliberator.steps.sql]
            [deliberator.steps.sqs]
            [deliberator.steps.wait]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu])
  (:import (java.net InetSocketAddress Socket)
           (java.nio.file Files)
           (java.util UUID)))

(defn- pg-up? []
  (try (with-open [s (Socket.)] (.connect s (InetSocketAddress. "localhost" 15432) 500) true)
       (catch Exception _ false)))

(def ^:private pg-target
  {:url "jdbc:postgresql://localhost:15432/deliberator" :user "deliberator" :password "deliberator"})

(defn- audit-env
  "The local environment pointed at the test Floci, with the audit queue and the estate's resources."
  [q]
  (env/validate
   (fu/local-env {:targets {:queue/audit q :table/messages "estate-messages" :bus/main "estate-bus"
                            :function/logger "estate-logger" :db/main pg-target
                            :service/floci fu/url}})))

(defmacro with-queue
  "Binds `q` (a fresh queue name, created in the temp home's workspace `w`) and `e` (its environment)."
  [[q e] & body]
  `(tu/with-temp-home
     (let [~q (str "dlbaudit-" (subs (str (UUID/randomUUID)) 0 8))
           ~e (audit-env ~q)
           url# (:QueueUrl (aws/invoke! {:ws "w"} ~e :sqs :CreateQueue {:QueueName ~q}))]
       (try ~@body
            (finally (aws/invoke! {:ws "w"} ~e :sqs :DeleteQueue {:QueueUrl url#})
                     (env/close-clients!))))))

(defn- run-text! [e text & opts]
  (session/start! "w" "T-1")
  (let [f (io/file (str (store/home)) "scn" (str (random-uuid) ".scn"))]
    (io/make-parents f)
    (spit f text)
    (let [res (apply engine/run! "w" e (str f) :session "T-1"
                     :resolve-target #(env/target e (keyword %)) opts)]
      (assoc res :text (slurp (get-in res [:paths :result]))))))

(defn- head [s] (subs s 0 (min 400 (count s))))
(defn- run-edn [res] (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))
(defn- nlink [^java.io.File f] (Files/getAttribute (.toPath f) "unix:nlink" (make-array java.nio.file.LinkOption 0)))

;; --- the raw store after every kind ------------------------------------------------------------------

(defn- raw-files [ws]
  (let [root (io/file (str (store/path ws "raw" "sha256")))]
    (filter #(and (.isFile %) (not (str/ends-with? (.getName %) ".meta.edn"))) (file-seq root))))

(deftest-floci every-kind-leaves-an-intact-raw-store-and-linked-step-files
  (with-queue [q e]
    (let [res (run-text! e
                         (str "### sqs send queue/audit\n# @attr k=v\n{\"audit\": \"ü\"}\n\n"
                              "### sqs receive queue/audit\n# @until count == 1 within 10s\n# @wait 1s\n\n"
                              "### eventbridge put bus/main\n# @source audit\n# @detail-type t\n{\"k\":1}\n\n"
                              "### lambda invoke function/logger\n{\"audit\":true}\n\n"
                              "### dynamodb scan table/messages\n{\"Limit\": 1}\n\n"
                              "### scheduler list\n\n"
                              "### http\nGET {{service/floci}}/_floci/health\n\n"
                              (when (pg-up?) "### sql db/main\nselect 1 as one\n\n")
                              "### wait\n# @for 100ms\n"))
          n (count (:steps (run-edn res)))]
      (is (= :ok (:status res)) (head (:text res)))
      (testing "every blob: sidecar with the required keys, bytes match the sha, read-only"
        (let [blobs (raw-files "w")]
          (is (>= (count blobs) (* 2 (- n 1))) (str (count blobs) " blobs for " n " steps"))
          (doseq [f blobs
                  :let [sha (str (.getName (.getParentFile f)) (.getName f))
                        m (store/raw-meta "w" sha)]]
            (is (= sha (store/sha256 (Files/readAllBytes (.toPath f)))) (str "content matches its name " sha))
            (is (and (contains? store/sources (:source m)) (some? (:op m)) (contains? m :env)
                     (instance? java.time.Instant (:at m)) (some? (:content-type m)))
                (str "meta of " sha ": " (pr-str m)))
            (is (= (.length f) (:bytes m)))
            (is (not (.canWrite f)) (str "blob is read-only " sha)))))
      (testing "the meta names each kind's source"
        (is (= #{:aws :http :sql} (set (filter #{:aws :http :sql} (map #(:source (store/raw-meta "w" (str (.getName (.getParentFile %)) (.getName %)))) (raw-files "w")))))
            "aws, http and (with Postgres up) sql")
        (when-not (pg-up?) (println "NOTE: Postgres on :15432 is down, the sql step was not part of this run")))
      (testing "every request.* / response.* in the run is a hard link of a blob"
        (let [files (filter #(re-matches #"(request|response)\..+" (.getName %))
                            (file-seq (io/file (get-in res [:paths :run]) "steps")))]
          (is (>= (count files) (* 2 (- n 1))))
          (doseq [f files] (is (>= (nlink f) 2) (str "not a link: " f))))))))

;; --- 200 KB through sqs -----------------------------------------------------------------------------------

(deftest-floci sqs-carries-a-200kb-body-byte-exact
  (with-queue [q e]
    (let [line "{\"sku\":\"a-1\",\"qty\":2,\"pad\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}\n"
          big (apply str (repeat (quot 204800 (count line)) line))
          payload (str/trimr big)
          res (run-text! e (str "### sqs send queue/audit\n# @body " (let [f (io/file (str (store/home)) "big.json")] (spit f payload) (.getAbsolutePath f)) "\n\n"
                                "### sqs receive queue/audit\n# @until count == 1 within 10s\n# @wait 2s\n\n"))
          [send recv] (:steps (run-edn res))
          recv-dir (first (filter #(str/starts-with? (.getName %) "002-") (.listFiles (io/file (get-in res [:paths :run]) "steps"))))]
      (is (>= (count payload) 200000))
      (is (= :ok (:status send)) (head (:text res)))
      (testing "the message comes back whole: the receive response holds all of the body"
        (is (= :ok (:status recv)) (head (:text res)))
        (let [r (json/read-str (slurp (io/file recv-dir "response.json")))]
          (is (= payload (get-in r ["Messages" 0 "Body"]))))))))

;; --- @until and cancel against a real long poll --------------------------------------------------------------

(deftest-floci ^:known-bug until-that-cannot-hold-on-an-empty-queue-ends-near-its-window
  (with-queue [q e]
    (let [t0 (System/currentTimeMillis)
          res (run-text! e "### sqs receive queue/audit\n# @until count == 1 within 2s\n")
          ms (- (System/currentTimeMillis) t0)]
      (is (= :failed (:status res)))
      (is (re-find #"✗ until count == 1" (:text res)))
      (is (< ms 8000) (str "a 2 s window took " ms " ms (decision 17: @wait defaults to what is left of the deadline, "
                           "but the engine hides the @until window from kinds: 05b)")))))

(deftest-floci a-cancelled-run-ends-a-long-poll-promptly
  (with-queue [q e]
    (env/save! "default" e)
    (with-daemon [h {:idle-minutes 0}]
      (env/save! "default" e)
      (with-client [a h]
        (with-client [b h]
          (ok! a "session.start" {:key "T-1"})
          (let [path (write-file "poll.scn" "### sqs receive queue/audit\n# @wait 20s\n")
                id (tc/send! a "scenario.run" {:path path})
                started (tc/recv a)
                _ (is (= "run.started" (:event started)))
                _ (Thread/sleep 1500)
                t0 (System/currentTimeMillis)
                c (:result (ok! b "scenario.cancel" {:run-id (:run-id started)}))
                {:keys [terminal]} (tc/collect a id 30000)
                took (- (System/currentTimeMillis) t0)]
            (is (true? (:cancelled c)))
            (is (= "cancelled" (get-in terminal [:result :status])) (pr-str (:error terminal)))
            (is (< took 6000) (str "cancel took " took " ms to end a 20 s long poll"))))))))

;; --- the confirmation flow through the daemon ---------------------------------------------------------------

(defn- remote-env []
  (env/validate {:name "dlbdev" :kind :remote
                 :aws {:profile "dlbaudit" :region "eu-central-1" :account "000000000000" :endpoint fu/url}
                 :targets {:function/logger "estate-logger"}}))

(defmacro with-remote-daemon
  "A daemon whose threads see the credentials file (the binding must exist before `start!`), on a
  home where `default` has the remote-shaped environment `dlbdev`."
  [[h] & body]
  `(binding [store/*home* (dt/short-home)]
     (let [f# (io/file (str (store/home)) "credentials")]
       (spit f# "[dlbaudit]\naws_access_key_id = test\naws_secret_access_key = test\n")
       (binding [env/*credentials-file* f#]
         (env/save! "default" (remote-env))
         (let [~h (daemon/start! {:idle-minutes 0})]
           (try ~@body (finally ((:stop! ~h)))))))))

(deftest-floci a-confirmed-rerun-passes-with-an-identical-request-and-a-token-is-used-once
  ;; lambda invoke has no run id inside its request (payload as written), so the token stays valid
  ;; for the re-run; a second use of the same token is refused.
  (with-remote-daemon [h]
    (with-client [c h]
      (ok! c "session.start" {:key "T-1"})
      (let [path (write-file "inv.scn" "### lambda invoke function/logger\n{\"audit\":\"confirm\"}\n")
            first-try (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev"}))
            token (get-in first-try [:error :token])]
        (is (= "confirm-required" (get-in first-try [:error :type])) (pr-str first-try))
        (is (string? token))
        (let [second-try (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev" :confirm token :steps [1]}))]
          (is (true? (:ok second-try)) (pr-str (:error second-try)))
          (is (= "ok" (get-in second-try [:result :status])) (pr-str second-try)))
        (testing "the token cannot be used again"
          (let [third (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev" :confirm token :steps [1]}))
                failed (or (not (:ok third)) (not= "ok" (get-in third [:result :status])))]
            (is failed (pr-str third))))
        (testing "the audit log has the attempts, never the token"
          (let [log (slurp (io/file (str (session/dir "default" "T-1")) "audit.log"))]
            (is (>= (count (str/split-lines log)) 3))
            (is (not (str/includes? log token)))))))))

(deftest-floci ^:known-bug a-confirmed-rerun-of-sqs-send-passes
  ;; 08c in PROGRESS.md: the token is bound to the exact request and the request holds the run id
  ;; (the `correlation` attribute), so a re-run asks for a new token for ever.
  (let [q (str "dlbaudit-" (subs (str (UUID/randomUUID)) 0 8))]
      (with-remote-daemon [h]
        (env/save! "default" (assoc-in (remote-env) [:targets :queue/audit] q))
        (let [local (audit-env q)
              url (:QueueUrl (aws/invoke! {:ws "default"} local :sqs :CreateQueue {:QueueName q}))]
          (try
            (with-client [c h]
              (ok! c "session.start" {:key "T-1"})
              (let [path (write-file "send.scn" "### sqs send queue/audit\nhello\n")
                    t1 (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev"}))
                    token (get-in t1 [:error :token])
                    t2 (:terminal (tc/request c "scenario.run" {:path path :env "dlbdev" :confirm token :steps [1]}))]
                (is (= "confirm-required" (get-in t1 [:error :type])))
                (is (= "ok" (get-in t2 [:result :status])) (pr-str (or (:error t2) (:result t2))))))
            (finally (aws/invoke! {:ws "default"} local :sqs :DeleteQueue {:QueueUrl url})))))))
