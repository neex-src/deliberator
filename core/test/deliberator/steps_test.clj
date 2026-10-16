(ns deliberator.steps-test
  "The plain kinds (http, sql, logs, wait) end to end through the engine. Pure parts first; the
  http and file-log tests need no runtime (a local `com.sun.net.httpserver`, a temp file); the sql,
  container-log, CloudWatch and Floci-health tests run against the REAL runtime (Floci + Postgres
  with `log_statement=all` on host ports 14566 / 15432, brought up by the fixture in a throwaway
  workspace and removed afterwards) and skip loudly when Podman is not reachable."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup]
            [deliberator.runtime :as runtime]
            [deliberator.scenario.engine :as engine]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]
            [deliberator.steps.http :as http]
            [deliberator.steps.logs :as logs]
            [deliberator.steps.sql :as sql]
            [deliberator.steps.wait])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

;; --- harness ----------------------------------------------------------------------------------

(def ^:dynamic *rt* nil)   ; {:ws .. :env ..} while the runtime is up

(def floci-port 14566)
(def pg-port 15432)

(defn- runtime-config [id]
  (-> (runtime/default-config)
      (assoc-in [:podman :endpoint] (sup/endpoint))
      (assoc :network (str "deliberator-test-" id))
      (assoc-in [:containers :floci :ports] {4566 floci-port})
      (assoc-in [:containers :floci :name] (str "deliberator-test-" id "-floci"))
      (assoc-in [:containers :postgres :ports] {5432 pg-port})
      (assoc-in [:containers :postgres :name] (str "deliberator-test-" id "-postgres"))
      (assoc-in [:containers :postgres :command] ["postgres" "-c" "log_statement=all"])))

(defn- test-env [targets]
  (-> env/default-local
      (assoc-in [:aws :endpoint] (str "http://localhost:" floci-port))
      (update :targets merge targets)))

(use-fixtures :once
  (fn [f]
    (binding [store/*home* (tu/temp-home)]
      (if-not (sup/up?)
        (do (println "SKIPPED runtime tests of deliberator.steps-test: runtime down: bb runtime:up")
            (f))
        (let [id (sup/rand-id)
              ws (str "steps-" id)
              cfg (runtime-config id)
              c (podman/client (sup/endpoint) :ws ws)]
          (store/write-edn! (runtime/config-path ws) cfg)
          (try
            (runtime/ensure! ws)
            (binding [*rt* {:ws ws
                            :pg (get-in cfg [:containers :postgres :name])
                            :env (test-env {:db/main {:url (str "jdbc:postgresql://localhost:" pg-port "/deliberator")
                                                      :user "deliberator" :password "deliberator"}
                                            :service/floci (str "http://localhost:" floci-port)
                                            :log/pg {:container (get-in cfg [:containers :postgres :name])}
                                            :log/logger {:cloudwatch "/test/plain"}})}]
              (f))
            (finally
              (try (runtime/down! ws) (catch Exception e (println "down!" (ex-message e))))
              (try (podman/remove-network! c (:network cfg)) (catch Exception _)))))))))

(declare sql-exec!)

(defmacro when-runtime [& body]
  `(if *rt* (do ~@body)
       (println (str "SKIPPED " (some-> clojure.test/*testing-vars* first meta :name) ": runtime down: bb runtime:up"))))

(defn- write-scn! [text]
  (let [f (io/file (str (store/home)) "scn" (str (random-uuid) ".scn"))]
    (io/make-parents f) (spit f text) (str f)))

(defn- run-scn
  "Runs scenario `text` against `ws`/`env`; returns the run result plus `:text` (result.txt)."
  [ws env text & opts]
  (session/start! ws "T-1")
  (let [res (apply engine/run! ws env (write-scn! text) :session "T-1"
                   :resolve-target #(env/target env (keyword %)) opts)]
    (assoc res :text (slurp (get-in res [:paths :result])))))

(defn- step-edn [res n]
  (let [dir (io/file (get-in res [:paths :run]) "steps")
        d (first (filter #(str/starts-with? (.getName %) (format "%03d-" n)) (.listFiles dir)))]
    (assoc (store/read-edn (io/file d "step.edn")) :dir d)))

(defn- step-file [res n fname] (slurp (io/file (:dir (step-edn res n)) fname)))

(defmacro with-ws [[ws] & body]
  `(let [~ws (str "plain-" (sup/rand-id))] ~@body))

(defn- plain-env [targets] (test-env targets))

;; --- pure -------------------------------------------------------------------------------------

(deftest table-rendering
  (is (= (str "id | note   | n\n"
              "---+--------+-----\n"
              "1  | hello  | 7\n"
              "2  | NULL   | NULL\n"
              "3  | a\\nb c | 9")
         (sql/render-table ["id" "note" "n"] [["1" "hello" "7"] ["2" nil nil] ["3" "a\nb c" "9"]] false 50))
      "NULL literal, newline escaped, trailing blanks trimmed")
  (let [long (apply str (repeat 60 "x"))
        t (sql/render-table ["c"] [[long]] true 1)]
    (is (str/includes? t (str (apply str (repeat 39 "x")) "…")))
    (is (= 40 (count (second (str/split-lines t)))) "cell cut at 40 including the ellipsis")
    (is (str/ends-with? t "… more rows (limit 1, # @limit N to raise)")))
  (is (= "(1 row affected)" (sql/rows-affected 1)))
  (is (= "(0 rows affected)" (sql/rows-affected 0))))

(deftest entries-and-filters
  (is (= [["2026 LOG:  statement: insert" "  values (1)" "2026 DETAIL:  params" ]
          ["2026 LOG:  next"]]
         (logs/group-entries ["2026 LOG:  statement: insert" "  values (1)" "2026 DETAIL:  params" "2026 LOG:  next"])))
  (let [e ["header a" "\tat Foo.bar" "  id=77"]]
    (is (logs/entry-matches? [(logs/parse-filter "77")] e) "the filter sees the whole entry")
    (is (logs/entry-matches? [(logs/parse-filter "header") (logs/parse-filter "/Foo\\.\\w+/")] e))
    (is (not (logs/entry-matches? [(logs/parse-filter "header") (logs/parse-filter "nope")] e)) "AND")
    (is (logs/entry-matches? [] e)))
  (is (thrown? clojure.lang.ExceptionInfo (logs/parse-filter "/(/"))))

(deftest http-request-parsing
  (let [r (http/parse-request (.getBytes "\nPOST /a/b HTTP/1.1\nContent-Type: text/x\nX-A:  1 \n\nline1\nline2\n" "UTF-8"))]
    (is (= "POST" (:method r)))
    (is (= "/a/b" (:url r)))
    (is (= [["Content-Type" "text/x"] ["X-A" "1"]] (:headers r)))
    (is (= "line1\nline2\n" (String. ^bytes (:body r)))) "body is byte-exact, final newline included")
  (let [r (http/parse-request (.getBytes "http://h/x\n" "UTF-8"))]
    (is (= ["GET" "http://h/x" [] nil] ((juxt :method :url :headers :body) r))))
  (is (thrown? clojure.lang.ExceptionInfo (http/parse-request (.getBytes "GET /x\nnot a header\n\nbody" "UTF-8")))))

;; --- http against a local server ----------------------------------------------------------------

(defn- start-echo []
  (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext s "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              body (.readAllBytes (.getRequestBody ex))
                              path (.getPath (.getRequestURI ex))
                              hs (into (sorted-map) (for [[k v] (.getRequestHeaders ex)] [(str/lower-case k) (vec v)]))
                              [code ctype out]
                              (case path
                                "/echo" [201 "application/json"
                                         (.getBytes (json/write-str {:method (.getRequestMethod ex) :headers hs
                                                                     :body (String. body "UTF-8")
                                                                     :items [{:sku "a-1"} {:sku "b-2"}]}) "UTF-8")]
                                "/text" [200 "text/plain" (.getBytes "plain words\n" "UTF-8")]
                                "/bin" [200 "application/octet-stream" (byte-array [0 1 2 (unchecked-byte 255)])]
                                "/moved" [302 "text/plain" (.getBytes "elsewhere" "UTF-8")]
                                "/slow" (do (Thread/sleep 3000) [200 "text/plain" (.getBytes "late" "UTF-8")])
                                [404 "text/plain" (.getBytes "nope" "UTF-8")])]
                          (.add (.getResponseHeaders ex) "Content-Type" ctype)
                          (.add (.getResponseHeaders ex) "X-Multi" "a")
                          (.add (.getResponseHeaders ex) "X-Multi" "b")
                          (.sendResponseHeaders ex code (if (= "HEAD" (.getRequestMethod ex)) -1 (alength out)))
                          (when-not (= "HEAD" (.getRequestMethod ex)) (.write (.getResponseBody ex) out))
                          (.close ex)))))
    (.start s)
    s))

(deftest http-kind
  (tu/with-temp-home
    (let [srv (start-echo)
          base (str "http://127.0.0.1:" (.getPort (.getAddress srv)))
          env (assoc (test-env {:service/echo base}) :name "local")]
      (try
        (let [res (run-scn "h" env
                           (str "### http service/echo create\n"
                                "# @assert status == 201\n"
                                "# @assert header.content-type == application/json\n"
                                "# @assert header.x-multi == a, b\n"
                                "# @assert $.method == POST\n"
                                "# @assert $.items[1].sku == b-2\n"
                                "# @assert $.body contains id\n"
                                "# @capture sku = $.items[0].sku\n"
                                "POST /echo\nContent-Type: application/json\nContent-Length: 999\n\n{\"id\": 1}\n"
                                "\n### http text-abs\n# @assert bytes == 12\n# @assert status == 200\n"
                                "GET " base "/text\n"
                                "\n### http bin\n# @assert bytes == 4\nGET " base "/bin\n"
                                "\n### http moved\n# @assert status == 302\nGET " base "/moved\n"
                                "\n### http relative-without-base\n/echo\n"
                                "\n### http nothing-listens\nGET http://127.0.0.1:1/x\n"
                                "\n### http slow\n# @timeout 700ms\nGET " base "/slow\n"
                                "\n### http after\n# @assert status == 404\n# @assert bytes == 4\nGET {{service/echo}}/missing\n"))
              echo (step-edn res 1)]
          (is (= [:ok :ok :ok :ok :error :error :error :ok]
                 (mapv :status (:steps (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))))
              (:text res))
          (testing "correlation header is added, Content-Length dropped, body byte-exact"
            (let [j (json/read-str (step-file res 1 "response.json"))]
              (is (= [(:correlation echo)] (get-in j ["headers" "x-correlation-id"])))
              (is (= ["10"] (get-in j ["headers" "content-length"])) "the JDK computed it, not the 999 from the file")
              (is (= "{\"id\": 1}\n" (get j "body")))))
          (testing "request and response are persisted raw; headers are facts"
            (is (str/starts-with? (step-file res 1 "request.http") (str "POST " base "/echo HTTP/1.1\nContent-Type: application/json\nX-Correlation-Id: ")))
            (is (str/ends-with? (step-file res 1 "request.http") "\n\n{\"id\": 1}\n"))
            (is (= "application/json" (get-in echo [:facts "header.content-type"])))
            (is (= {"sku" "a-1"} {"sku" (get-in (step-edn res 1) [:captures "sku"])})))
          (testing "text and binary extensions"
            (is (.exists (io/file (:dir (step-edn res 2)) "response.txt")))
            (is (.exists (io/file (:dir (step-edn res 3)) "response.bin"))))
          (testing "errors carry a hint derived from the URL and environment"
            (is (str/includes? (get-in (step-edn res 5) [:error :message]) "no base") (:text res))
            (is (re-find #"nothing answers at 127.0.0.1:1 \(environment local\)" (get-in (step-edn res 6) [:error :hint]))
                (str (:error (step-edn res 6))))
            (is (some? (step-file res 6 "request.http")) "the request is kept on error")
            (is (str/includes? (get-in (step-edn res 7) [:error :message]) "timed out") (str (:error (step-edn res 7))))))
        (finally (.stop srv 0))))))

(deftest wait-kind
  (tu/with-temp-home
    (let [res (run-scn "w" (test-env {}) "### wait nap\n# @for 400ms\n### wait\n# @assert elapsed < 300ms\n# @for 100ms\n")
          st (step-edn res 1)]
      (is (= :ok (:status res)) (:text res))
      (is (<= 400 (get-in st [:facts "elapsed"]) 900))
      (is (str/includes? (:text res) "waited 400 ms")))))

;; --- logs against a file ---------------------------------------------------------------------

(deftest logs-file-kind
  (tu/with-temp-home
    (let [f (io/file (str (store/home)) "app.log")
          env (test-env {:log/app {:file (str f)}})]
      (io/make-parents f)
      (spit f "before the run mentions CORR-1\n")
      (let [writer (future
                     (Thread/sleep 500)
                     (spit f (str "2026 INFO other line\n"
                                  "2026 INFO order CORR-1 created\n"
                                  "  continuation of CORR-1 with detail\n"
                                  "2026 INFO order CORR-2 created\n"
                                  "2026 ERROR boom\n"
                                  "\tat Foo.bar(CORR-1)\n")
                          :append true)
                     (Thread/sleep 300)
                     (spit f "partial-without-newline CORR-1" :append true)
                     (Thread/sleep 300)
                     (spit f " finished\n" :append true))
            res (run-scn "l" env
                         (str "### logs log/app by-id\n# @filter CORR-1\n# @for 2s\n# @assert lines == 5\n"
                              "### logs log/app both\n# @filter ERROR\n# @filter /Foo\\.bar/\n# @for 2s\n# @assert lines == 2\n"
                              "### logs log/app default\n# @for 1s\n# @assert lines == 0\n"
                              "### logs nope/none\n"))]
        @writer
        (is (= [:ok :ok :ok] (take 3 (map :status (:steps (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))))) (:text res))
        (is (= (str "2026 INFO order CORR-1 created\n  continuation of CORR-1 with detail\n"
                    "2026 ERROR boom\n\tat Foo.bar(CORR-1)\n"
                    "partial-without-newline CORR-1 finished\n")
               (step-file res 1 "response.log"))
            "armed at the offset: the line from before the run is absent; entries verbatim")
        (is (= "2026 ERROR boom\n\tat Foo.bar(CORR-1)\n" (step-file res 2 "response.log")))
        (is (str/includes? (:text res) "(no lines matched"))))))

(deftest logs-unknown-target-is-a-step-error
  (tu/with-temp-home
    (let [res (run-scn "l" (test-env {}) "### logs nope/none\n")]
      (is (= :failed (:status res)))
      (is (str/includes? (:text res) "ERROR: unknown target :nope/none in environment local, known: ")))))

;; --- against the runtime -----------------------------------------------------------------------

(deftest http-against-floci-health
  (when-runtime
   (let [res (run-scn (:ws *rt*) (:env *rt*)
                      "### http health\n# @assert status == 200\n# @assert header.content-type contains json\nGET {{service/floci}}/_floci/health\n")]
     (is (= :ok (:status res)) (:text res))
     (is (str/includes? (:text res) "services")))))

(deftest sql-kind
  (when-runtime
   (let [res (run-scn (:ws *rt*) (:env *rt*)
                      (str "### sql db/main make\ndrop table if exists sqlk;\n"
                           "### sql db/main make2\n# @assert updated == 0\ncreate table sqlk (id int, note text, big text)\n"
                           "### sql db/main insert\n# @assert updated == 3\n"
                           "insert into sqlk values (1,'one',repeat('x',60)),(2,null,'a'||chr(10)||'b'),(3,'three','c')\n"
                           "### sql db/main all\n# @assert rows == 3\n# @assert col.id == 1\n# @assert col.big contains xxx\n# @capture first = col.note\n"
                           "select * from sqlk order by id\n"
                           "### sql db/main limited\n# @limit 2\n# @assert rows == 2\nselect id from sqlk order by id\n"
                           "### sql db/main exact\n# @limit 3\n# @assert rows == 3\nselect id from sqlk order by id\n"
                           "### sql db/main bad\nselect * from no_such_table\n"
                           "### sql db/main ttl\n# @timeout 1s\nselect pg_sleep(5)\n"))
         st #(step-edn res %)]
     (is (= [:ok :ok :ok :ok :ok :ok :error :error] (mapv :status (:steps (store/read-edn (io/file (get-in res [:paths :run]) "run.edn"))))) (:text res))
     (is (= "(3 rows affected)\n" (step-file res 3 "response.txt")))
     (let [t (str/split-lines (step-file res 4 "response.txt"))]
       (is (= "id | note  | big" (first t)))
       (is (= "---+-------+-----------------------------------------" (second t)))
       (is (= (str "1  | one   | " (apply str (repeat 39 "x")) "…") (nth t 2)))
       (is (= "2  | NULL  | a\\nb" (nth t 3)))
       (is (= 5 (count t))))
     (is (str/ends-with? (str/trim (step-file res 5 "response.txt")) "… more rows (limit 2, # @limit N to raise)"))
     (is (not (str/includes? (step-file res 6 "response.txt") "more rows")) "exactly limit rows: no trailer")
     (is (str/includes? (get-in (st 7) [:error :message]) "no_such_table"))
     (is (= "SQLSTATE 42P01" (get-in (st 7) [:error :hint])))
     (is (some? (step-file res 7 "request.sql")))
     (is (str/includes? (get-in (st 8) [:error :message]) "timed out"))
     (testing "a connection failure hints at the target, without the password"
       (let [bad (assoc-in (:env *rt*) [:targets :db/main] "jdbc:postgresql://localhost:1/x?user=u&password=secret")
             r (run-scn (:ws *rt*) bad "### sql db/main q\nselect 1\n")
             e (:error (step-edn r 1))]
         (is (str/includes? (:hint e) "target db/main"))
         (is (not (str/includes? (str e) "secret"))))))))

(deftest logs-container-kind
  (when-runtime
   (let [{:keys [ws env]} *rt*
         writer (future (Thread/sleep 800)
                        (sql-exec! env "select 'MARK-A'")
                        (sql-exec! env "select 'MARK-B', 1"))
         res (run-scn ws env
                      (str "### logs log/pg pg\n# @filter MARK-\n# @for 4s\n# @assert lines >= 2\n"
                           "### logs log/pg none\n# @filter NEVER-HAPPENS-" (random-uuid) "\n# @for 1s\n# @assert lines == 0\n"))]
     @writer
     (is (= :ok (:status res)) (:text res))
     (let [out (step-file res 1 "response.log")]
       (is (re-find #"execute <unnamed>: select 'MARK-A'" out))
       (is (re-find #"execute <unnamed>: select 'MARK-B', 1" out))))))

(deftest logs-cloudwatch-kind
  (when-runtime
   (let [{:keys [ws env]} *rt*
         ctx {:ws ws}
         group (str "/test/plain-" (sup/rand-id))
         env (assoc-in env [:targets :log/logger] {:cloudwatch group})
         ok! (fn [r] (is (not (aws/error? r)) (pr-str r)) r)]
     (ok! (aws/invoke! ctx env :logs :CreateLogGroup {:logGroupName group}))
     (ok! (aws/invoke! ctx env :logs :CreateLogStream {:logGroupName group :logStreamName "s1"}))
     (let [put! (fn [msgs] (ok! (aws/invoke! ctx env :logs :PutLogEvents
                                              {:logGroupName group :logStreamName "s1"
                                               :logEvents (mapv (fn [m] {:timestamp (System/currentTimeMillis) :message m}) msgs)})))
           writer (future (Thread/sleep 1200)
                          (put! ["START RequestId: abc" "handled CWTOKEN1 ok" "handled CW-DASH-2 ok" "unrelated"]))
           res (run-scn ws env (str "### logs log/logger lam\n# @filter CWTOKEN1\n# @for 4s\n# @assert lines == 1\n"
                                    "### logs log/logger dash\n# @filter CW-DASH-2\n# @for 4s\n# @assert lines == 1\n"))]
       @writer
       (is (= :ok (:status res)) (:text res))
       (is (= "handled CWTOKEN1 ok\n" (step-file res 1 "response.log")))
       (is (= "handled CW-DASH-2 ok\n" (step-file res 2 "response.log")))))))

(defn sql-exec! [env q]
  (let [{:keys [url user password]} (get-in env [:targets :db/main])]
    (with-open [c (java.sql.DriverManager/getConnection url user password)
                s (.createStatement c)]
      (.execute s q))))

(deftest plain-scenario-fixture
  (when-runtime
   (let [{:keys [ws env]} *rt*
         text (slurp (io/file "test/fixtures/plain.scn"))
         res (run-scn ws env text)]
     (spit (io/file "target" "plain.result.txt") (:text res))
     (is (= :ok (:status res)) (:text res))
     (is (= 6 (:steps res)))
     (is (= 0 (:failed-checks res)))
     (is (re-find #"(?m)^id +\| note +\| n +\| gone$" (:text res)))
     (is (str/includes? (:text res) "(1 row affected)"))
     (is (str/includes? (:text res) "captured: note=first note"))
     (is (re-find #"execute <unnamed>: insert into plain_t" (step-file res 1 "response.log"))))))
