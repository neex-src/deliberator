(ns deliberator.ide-ops-test
  "Layer 1 of the m2 test plan: the core operations added for the IDE (`scenario.kinds`, `scenario.parse`,
  `runs.list scenario`, `repo.*`, `logs.fetch`, `marker.*`, `logs.match`), called through `ops/call!` on a
  temporary home. Pure and file-based tests run in pass A; the container and CloudWatch fetches need the
  estate (Floci at `floci-util/url`, container `deliberator-$ESTATE_WS-floci`) and skip loudly without it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu :refer [deftest-floci]]
            [deliberator.logfetch :as logfetch]
            [deliberator.markers :as markers]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]
            [deliberator.steps.http] [deliberator.steps.logs] [deliberator.steps.sql] [deliberator.steps.wait]
            [deliberator.steps.sqs] [deliberator.steps.eventbridge] [deliberator.steps.lambda]
            [deliberator.steps.dynamodb] [deliberator.steps.scheduler]))

(defn call [op args] (ops/call! op {} args))

(defn thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn write! [dir name text]
  (let [f (io/file (str dir) name)] (io/make-parents f) (spit f text) (str f)))

;; --- describe ---------------------------------------------------------------------------------

(deftest describe-lists-the-new-ops-with-order
  (let [by (into {} (map (juxt :op identity)) (ops/describe))]
    (doseq [[op order class] [["scenario.kinds" ["ws"] "read"]
                              ["scenario.parse" ["path" "text" "ws"] "read"]
                              ["repo.attach" ["path" "kind" "ws"] "mutate"]
                              ["repo.detach" ["name" "ws"] "mutate"]
                              ["repo.list" ["ws"] "read"]
                              ["logs.fetch" ["target" "env" "since" "for" "follow" "filter" "session" "ws"] "mutate"]
                              ["logs.match" ["path" "markers" "session" "ws"] "read"]
                              ["marker.add" ["file" "line" "logger" "ws"] "mutate"]
                              ["marker.remove" ["file" "line" "ws"] "mutate"]
                              ["marker.list" ["file" "ws"] "read"]]]
      (testing op
        (is (= order (:order (by op))))
        (is (= class (:class (by op))))
        (is (not (str/blank? (:doc (by op)))))))
    (is (= ["session" "scenario" "limit" "ws"] (take 4 (cons "session" (remove #{"session"} (:order (by "runs.list")))))) "runs.list keeps ws last")
    (is (some #{"scenario"} (:order (by "runs.list"))))
    (is (= "ws" (last (:order (by "runs.list")))))))

;; --- scenario.kinds ---------------------------------------------------------------------------

(deftest kinds-lists-every-registered-kind
  (let [ks (call "scenario.kinds" {})
        by (into {} (map (juxt :kind identity)) ks)]
    (is (vector? ks))
    (is (= (sort (keys (deliberator.scenario.kinds/registry))) (map :kind ks)))
    (doseq [k ["http" "sql" "logs" "wait" "sqs" "eventbridge" "lambda" "dynamodb" "scheduler"]]
      (is (contains? by k) k))
    (testing "sqs: verbs, directives of the kind and the engine's, facts with the implicit elapsed"
      (let [sqs (by "sqs")]
        (is (every? string? (:verbs sqs)))
        (is (contains? (set (:verbs sqs)) "send"))
        (is (some #{"attr"} (map :name (:directives sqs))))
        (is (every? (set (map :name (:directives sqs))) ["assert" "capture" "until" "timeout" "body" "name"]))
        (is (= #{true} (set (map :engine (filter #(#{"assert" "capture"} (:name %)) (:directives sqs))))))
        (is (= false (:engine (first (filter #(= "attr" (:name %)) (:directives sqs))))))
        (is (contains? (set (map :name (:facts sqs))) "elapsed"))))
    (testing "logs: concurrent, filter repeatable, target required"
      (let [l (by "logs")]
        (is (:concurrent l))
        (is (= "required" (:target l)))
        (is (:repeatable (first (filter #(= "filter" (:name %)) (:directives l)))))
        (is (= #{"lines" "gaps" "elapsed"} (set (map :name (:facts l)))))))
    (testing "everything is plain data"
      (is (= ks (clojure.edn/read-string (pr-str ks)))))))

;; --- scenario.parse ---------------------------------------------------------------------------

(def scn "# @env local
# @var who = world

### wait pause
# @for 100ms

### http api
# @assert status == 200
{\"a\": 1,
 \"b\": 2}

### logs log/app
# @for 1s
")

(deftest parse-text-equals-file
  (tu/with-temp-home
    (let [f (write! (store/home) "scn/a.scn" scn)
          from-file (call "scenario.parse" {:path f})
          from-text (call "scenario.parse" {:text scn})]
      (is (= from-file from-text))
      (is (= [] (:errors from-text)))
      (is (= {:env "local" :vars [{:name "who" :value "world" :line 2}]} (:header from-text)))
      (is (= [1 2 3] (map :index (:steps from-text))))
      (let [[w h l] (:steps from-text)]
        (is (= {:kind "wait" :name "pause" :line 4 :end-line 6} (select-keys w [:kind :name :line :end-line])))
        (is (= [{:name "for" :value "100ms" :line 5}] (:directives w)))
        (is (= 0 (:body-lines w)))
        (is (= {:kind "http" :name "api" :line 7 :end-line 11 :body-lines 2 :body-line 9}
               (select-keys h [:kind :name :line :end-line :body-lines :body-line])))
        (is (= {:kind "logs" :target "log/app" :line 12 :end-line 13}  (select-keys l [:kind :target :line :end-line])))))))

(deftest parse-errors-are-data
  (let [r (call "scenario.parse" {:text "### nosuchkind\n"})]
    (is (= [] (:steps r)))
    (is (= 1 (count (:errors r))))
    (is (= 1 (get-in r [:errors 0 :line])))
    (is (str/includes? (get-in r [:errors 0 :message]) "unknown step kind")))
  (let [r (call "scenario.parse" {:text "### wait x\n# @bogus 1\n"})]
    (is (= 2 (get-in r [:errors 0 :line])))))

(deftest parse-needs-input-and-resolves-relative-paths
  (tu/with-temp-home
    (is (= :bad-args (thrown-type #(call "scenario.parse" {}))))
    (is (= :session/none (thrown-type #(call "scenario.parse" {:path "rel.scn"}))))
    (session/start! "default" "S1")
    (is (= :scenario/not-found (thrown-type #(call "scenario.parse" {:path "rel.scn"}))))
    (write! (session/dir "default" "S1") "scenarios/rel.scn" "### wait w\n")
    (is (= 1 (count (:steps (call "scenario.parse" {:path "rel.scn"})))))
    (testing "text with a path: body files resolve against the path"
      (write! (store/home) "scn/body.json" "{}")
      (let [r (call "scenario.parse" {:text "### http POST /x\n# @body body.json\n" :path (str (store/home) "/scn/a.scn")})]
        (is (= [] (:errors r)))
        (is (= "body.json" (get-in r [:steps 0 :body-file])))))))

;; --- runs.list scenario -----------------------------------------------------------------------

(deftest runs-list-filters-by-scenario
  (tu/with-temp-home
    (session/start! "default" "S1")
    (doseq [[ts scenario] [["20261016-100000-order" "/w/estate/scenarios/order.scn"]
                           ["20261016-100100-other" "/w/estate/scenarios/other.scn"]
                           ["20261016-100200-order" "/w/estate/scenarios/order.scn"]
                           ["20261016-100300-order" "/elsewhere/order.scn"]
                           ["20261016-100400-reorder" "/w/reorder.scn"]]]
      (store/write-edn! (io/file (str (session/dir "default" "S1")) "runs" ts "run.edn")
                        {:run-id ts :status :ok :started (store/now) :scenario {:path scenario}}))
    (let [ids #(mapv :run-id (:runs (call "runs.list" (merge {} %))))]
      (is (= 5 (count (ids {}))))
      (is (= ["20261016-100300-order" "20261016-100200-order" "20261016-100000-order"] (ids {:scenario "order"})) "by name, newest first")
      (is (= (ids {:scenario "order"}) (ids {:scenario "order.scn"})))
      (is (= ["20261016-100200-order" "20261016-100000-order"] (ids {:scenario "/w/estate/scenarios/order.scn"})) "by full path")
      (is (= ["20261016-100200-order" "20261016-100000-order"] (ids {:scenario "scenarios/order.scn"})) "by path suffix")
      (is (= ["20261016-100400-reorder"] (ids {:scenario "reorder"})) "a name is not a substring")
      (is (= [] (ids {:scenario "nothing"})))
      (is (= ["20261016-100300-order"] (ids {:scenario "order" :limit 1})) "limit applies after the filter"))))

;; --- repo.* -----------------------------------------------------------------------------------

(deftest repo-round-trip-and-kinds
  (tu/with-temp-home
    (call "workspace.open" {})
    (let [root (io/file (str (store/home)) "checkouts")
          gradle (io/file root "svc-g") maven (io/file root "svc-m") plain (io/file root "svc-p")
          both (io/file root "svc-b") ktl (io/file root "svc-k")]
      (doseq [d [gradle maven plain both ktl]] (.mkdirs d))
      (spit (io/file gradle "build.gradle") "") (spit (io/file maven "pom.xml") "")
      (spit (io/file both "pom.xml") "") (spit (io/file both "settings.gradle.kts") "")
      (spit (io/file ktl "build.gradle.kts") "")
      (is (= [] (call "repo.list" {})))
      (let [g (call "repo.attach" {:path (str gradle)})]
        (is (= {:name "svc-g" :kind "gradle"} (select-keys g [:name :kind])))
        (is (= (str (.toRealPath (.toPath gradle) (make-array java.nio.file.LinkOption 0))) (:path g))))
      (is (= "maven" (:kind (call "repo.attach" {:path (str maven)}))))
      (is (= "plain" (:kind (call "repo.attach" {:path (str plain)}))))
      (is (= "gradle" (:kind (call "repo.attach" {:path (str both)}))) "gradle wins")
      (is (= "gradle" (:kind (call "repo.attach" {:path (str ktl)}))))
      (is (= ["svc-g" "svc-m" "svc-p" "svc-b" "svc-k"] (map :name (call "repo.list" {}))) "attach order")
      (is (every? #(instance? java.time.Instant (:attached-at %)) (call "repo.list" {})))
      (testing "persisted in workspace.edn :repositories, other keys survive"
        (is (= 5 (count (:repositories (store/read-edn (store/path "default" "workspace.edn"))))))
        (is (contains? (store/read-edn (store/path "default" "workspace.edn")) :environments)))
      (testing "again: idempotent; an explicit kind updates"
        (is (= 5 (do (call "repo.attach" {:path (str maven)}) (count (call "repo.list" {})))))
        (is (= "plain" (:kind (call "repo.attach" {:path (str maven) :kind "plain"}))))
        (is (= "plain" (:kind (first (filter #(= "svc-m" (:name %)) (call "repo.list" {})))))))
      (testing "detach: the entry only, files stay"
        (is (= "svc-p" (get-in (call "repo.detach" {:name "svc-p"}) [:removed :name])))
        (is (= 4 (count (call "repo.list" {}))))
        (is (.isDirectory plain))
        (is (= :repo/not-found (thrown-type #(call "repo.detach" {:name "svc-p"})))))
      (testing "typed errors"
        (is (= :repo/path-not-found (thrown-type #(call "repo.attach" {:path (str root "/nope")}))))
        (spit (io/file root "afile") "x")
        (is (= :repo/not-a-directory (thrown-type #(call "repo.attach" {:path (str root "/afile")}))))
        (is (= :repo/bad-kind (thrown-type #(call "repo.attach" {:path (str gradle) :kind "ant"}))))
        (let [other (io/file (str (store/home)) "other" "svc-g")]
          (.mkdirs other)
          (is (= :repo/name-taken (thrown-type #(call "repo.attach" {:path (str other)})))))))))

(deftest repo-workspaces-are-separate
  (tu/with-temp-home
    (call "workspace.open" {:ws "b"})
    (let [d (io/file (str (store/home)) "r1")]
      (.mkdirs d)
      (call "repo.attach" {:path (str d) :ws "a"})
      (is (= 1 (count (call "repo.list" {:ws "a"}))))
      (is (= [] (call "repo.list" {:ws "b"}))))))

;; --- markers and logs.match -------------------------------------------------------------------

(deftest markers-round-trip
  (tu/with-temp-home
    (call "workspace.open" {})
    (is (= [] (call "marker.list" {})))
    (let [r (call "marker.add" {:file "/src/A.java" :line 42 :logger "com.acme.ServiceA"})]
      (is (= [{:id "m1" :file "/src/A.java" :line 42 :logger "com.acme.ServiceA"}] r)))
    (call "marker.add" {:file "/src/B.java" :line 7})
    (is (= [{:id "m1" :file "/src/A.java" :line 42 :logger "com.acme.ServiceA"}
            {:id "m2" :file "/src/B.java" :line 7}]
           (call "marker.list" {})))
    (testing "add is idempotent per file+line, a logger updates it, ids are not reused"
      (is (= 2 (count (call "marker.add" {:file "/src/A.java" :line 42}))))
      (is (= "com.acme.ServiceA" (:logger (first (call "marker.list" {})))))
      (call "marker.add" {:file "/src/A.java" :line 42 :logger "com.acme.Renamed"})
      (is (= "com.acme.Renamed" (:logger (first (call "marker.list" {})))))
      (is (= "m1" (:id (first (call "marker.list" {})))))
      (call "marker.remove" {:file "/src/B.java" :line 7})
      (is (= "m3" (:id (last (call "marker.add" {:file "/src/C.java" :line 1}))))))
    (is (= ["/src/C.java"] (map :file (call "marker.list" {:file "/src/C.java"}))))
    (testing "remove answers the rest and persists in markers.edn"
      (is (= ["/src/C.java"] (map :file (call "marker.remove" {:file "/src/A.java" :line 42}))))
      (is (= ["/src/C.java"] (map :file (call "marker.remove" {:file "/src/nothing" :line 1}))) "removing nothing keeps the rest"))))

(def log-lines
  ["2026-10-16T10:15:00.123+02:00 INFO  service-a [c1] c.a.ServiceA:42 - handling order"
   "2026-10-16T10:15:00.124+02:00 INFO  service-a [c1] c.a.ServiceA:43 - another line"
   "2026-10-16T10:15:00.125+02:00 INFO  service-a [c1] com.other.ServiceA:42 - same tail, other class"
   "2026-10-16T10:15:00.126+02:00 ERROR service-a [c1] c.a.s.OrderRepo:10 - boom"
   "java.lang.IllegalStateException: boom"
   "\tat com.acme.svc.OrderRepo.save(OrderRepo.java:10)"
   "\tat com.acme.svc.OrderRepo$Inner.run(OrderRepo.java:99)"
   "2026-10-16T10:15:00.127+02:00 INFO  service-a [] ServiceA:42 - logger{0} form"
   "2026-10-16T10:15:00.128+02:00 INFO  service-a [c1] c.a.ServiceAB:42 - a longer class name"
   "connected to localhost:5432 - ok"
   "no position here"])

(def ms
  [{:id "m1" :file "/src/main/java/com/acme/ServiceA.java" :line 42 :logger "com.acme.ServiceA"}
   {:id "m2" :file "/src/main/java/com/acme/svc/OrderRepo.java" :line 10 :logger "com.acme.svc.OrderRepo"}
   {:id "m3" :file "/src/main/java/com/acme/Nolog.java" :line 42}])

(deftest match-lines-pure
  (let [r (markers/match-lines ms log-lines)]
    (testing "abbreviated logger, same line: yes; other line: no"
      (is (some #{{:n 1 :marker-id "m1"}} r))
      (is (not-any? #(= 2 (:n %)) r)))
    (testing "a different class with the same tail never matches (com.other.ServiceA vs c.a.ServiceA)"
      (is (not-any? #(= 3 (:n %)) r)))
    (testing "a longer class name sharing the prefix never matches"
      (is (not-any? #(= 9 (:n %)) r)))
    (testing "package abbreviations: c.a.s.OrderRepo matches com.acme.svc.OrderRepo, and not com.acme.svc2"
      (is (some #{{:n 4 :marker-id "m2"}} r))
      (is (empty? (markers/match-lines [{:id "x" :file "O.java" :line 10 :logger "com.zeta.svc.OrderRepo"}] [(nth log-lines 3)])))
      (is (empty? (markers/match-lines [{:id "x" :file "O.java" :line 10 :logger "com.acme.other.OrderRepo"}] [(nth log-lines 3)]))
          "c.a.s. does not abbreviate 'other'"))
    (testing "stack frames carry class and line; inner classes count as their outer class"
      (is (some #{{:n 6 :marker-id "m2"}} r))
      (is (not-any? #(= 7 (:n %)) r) "line 99 is not marked"))
    (testing "logger{0}: class name alone"
      (is (some #{{:n 8 :marker-id "m1"}} r)))
    (testing "a marker without logger compares the file's base name"
      (is (not-any? #(= "m3" (:marker-id %)) r) "Nolog is not in the log")
      (is (= [{:n 1 :marker-id "m9"}]
             (markers/match-lines [{:id "m9" :file "/x/ServiceA.java" :line 42}] [(first log-lines)]))))
    (testing "host:port and plain text are not positions"
      (is (not-any? #(#{10 11} (:n %)) r)))
    (is (= 4 (count r)))))

(deftest logs-match-op
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [f (write! (session/dir "default" "S1") "logs/app.log" (str (str/join "\n" log-lines) "\n"))]
      (doseq [m ms] (call "marker.add" (select-keys m [:file :line :logger])))
      (let [r (call "logs.match" {:path f})]
        (is (= [1 4 6 8] (mapv :n (:lines r))))
        (is (= ["m1" "m2" "m2" "m1"] (mapv :marker-id (:lines r))))
        (is (= (count log-lines) (dec (:scanned r))) "trailing newline: one empty tail"))
      (testing "restricted to marker ids, relative path in the session's logs/"
        (is (= [4 6] (mapv :n (:lines (call "logs.match" {:path "app.log" :markers ["m2"]}))))))
      (is (= :logs/not-found (thrown-type #(call "logs.match" {:path "nope.log"})))))))

;; --- logs.fetch on a file ---------------------------------------------------------------------

(defn- file-env! [ws target-file]
  (env/save! ws (assoc-in (env/load ws "local") [:targets :log/app] {:file target-file})))

(deftest fetch-file-target-persists-raw-and-links
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "app.log" "one CORR-1\n  cont\ntwo CORR-2\nthree CORR-1\n")
          _ (file-env! "default" src)
          r (call "logs.fetch" {:target "log/app"})]
      (testing "the whole file, a session file hard-linked from the raw blob"
        (is (= {:lines 4 :source "file" :target "log/app"} (select-keys r [:lines :source :target])))
        (is (= (slurp src) (slurp (:path r))))
        (is (str/includes? (:path r) "/sessions/S1/logs/log-app-"))
        (is (str/ends-with? (:path r) ".log"))
        (is (= (:bytes r) (count (slurp src))))
        (is (java.nio.file.Files/isSameFile (.toPath (io/file (:path r))) (store/raw-path "default" (:sha r))))
        (is (= :file (:source (store/raw-meta "default" (:sha r))))))
      (testing "filter: whole entries kept; the raw blob still has everything"
        (let [f (call "logs.fetch" {:target "log/app" :filter "CORR-2"})]
          (is (= "two CORR-2\n" (slurp (:path f))))
          (is (= 1 (:lines f)))
          (is (not= (:sha r) (:sha f)))
          (is (some? (store/raw-meta "default" (:sha r))))
          (is (not= (:path r) (:path f)) "same second, other content: a suffixed name")
          (let [c (call "logs.fetch" {:target "log/app" :filter "CORR-1"})]
            (is (= "one CORR-1\n  cont\nthree CORR-1\n" (slurp (:path c))) "continuation lines stay with their entry")
            (is (= 3 (:lines c))))
          (is (= "" (slurp (:path (call "logs.fetch" {:target "log/app" :filter "/nomatch\\d/"})))))))
      (testing "an absolute path is a target too; an unknown target is a typed error"
        (is (= (slurp src) (slurp (:path (call "logs.fetch" {:target src})))))
        (is (= :env/unknown-target (thrown-type #(call "logs.fetch" {:target "log/nope"}))))
        (is (= :logs/bad-arg (thrown-type #(call "logs.fetch" {:target "log/app" :since "yesterday"})))))
      (testing "no session: typed error"
        (store/write-edn! (store/path "default" "workspace.edn") (assoc (store/read-edn (store/path "default" "workspace.edn")) :current-session nil))
        (is (= :session/none (thrown-type #(call "logs.fetch" {:target "log/app"}))))
        (is (some? (call "logs.fetch" {:target "log/app" :session "S1"})))))))

(deftest fetch-file-for-listens-live
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "live.log" "old line\n")
          _ (file-env! "default" src)
          w (future (Thread/sleep 400) (spit src "new line\n" :append true))
          r (call "logs.fetch" {:target "log/app" :for "1500ms"})]
      @w
      (is (= "old line\nnew line\n" (slurp (:path r))) "history plus what arrived"))))

;; --- logs.fetch streams and follows ------------------------------------------------

(defn- wait-until [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 25) (recur))))))

(defn- session-logs [ws sess]
  (vec (sort (map #(.getName ^java.io.File %) (.listFiles (io/file (str (session/dir ws sess)) "logs"))))))

(deftest follow-streams-lines-and-leaves-one-file
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "live.log" "old line\n")
          _ (file-env! "default" src)
          events (atom [])
          cancelled (atom false)
          ctx {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)}
          res (future (ops/call! "logs.fetch" ctx {:target "log/app" :follow true}))]
      (try
        (testing "the history and each new line arrive as events while the follow runs"
          (is (wait-until #(= ["old line"] (map :text @events)) 3000))
          (spit src "second\n" :append true)
          (let [t0 (System/currentTimeMillis)]
            (is (wait-until #(= ["old line" "second"] (map :text @events)) 3000))
            (is (< (- (System/currentTimeMillis) t0) 1500) "within a moment, not at the end of a window"))
          (is (= [1 2] (map :n @events)))
          (is (every? #(and (= "logs.line" (:event %)) (= "log/app" (:target %)) (string? (:at %))) @events)))
        (testing "while it runs there is ONE file and it already holds the lines"
          (is (= 1 (count (session-logs "default" "S1"))))
          (is (wait-until #(= "old line\nsecond\n" (slurp (io/file (str (session/dir "default" "S1")) "logs" (first (session-logs "default" "S1"))))) 2000)))
        (spit src "third\n" :append true)
        (is (wait-until #(= 3 (count @events)) 3000))
        (finally (reset! cancelled true)))
      (let [r (deref res 5000 ::timeout)]
        (testing "cancelled: the file is the blob, the result says follow"
          (is (map? r))
          (is (true? (:follow r)))
          (is (= 3 (:lines r)))
          (is (= "old line\nsecond\nthird\n" (slurp (:path r))))
          (is (= 1 (count (session-logs "default" "S1"))) "still one file")
          (is (java.nio.file.Files/isSameFile (.toPath (io/file (:path r))) (store/raw-path "default" (:sha r))) "one inode")
          (is (not (.canWrite (io/file (:path r)))))
          (is (= (:bytes r) (count "old line\nsecond\nthird\n")))
          (is (empty? (.listFiles (.toFile (store/path "default" "raw" "follow")))) "no work file or sidecar left"))))))

(deftest follow-with-a-filter-keeps-the-raw-and-the-kept
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "live.log" "a CORR-1\n  cont\nb CORR-2\n")
          _ (file-env! "default" src)
          events (atom [])
          cancelled (atom false)
          res (future (ops/call! "logs.fetch" {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)}
                                 {:target "log/app" :for "until-cancelled" :filter "CORR-1"}))]
      (is (wait-until #(= ["a CORR-1" "  cont"] (map :text @events)) 3000))
      (spit src "c CORR-1\n" :append true)
      (is (wait-until #(= 3 (count @events)) 3000))
      (reset! cancelled true)
      (let [r (deref res 5000 ::timeout)
            raw-sha (store/sha256 (.getBytes "a CORR-1\n  cont\nb CORR-2\nc CORR-1\n" "UTF-8"))]
        (is (= "a CORR-1\n  cont\nc CORR-1\n" (slurp (:path r))))
        (is (= 3 (:lines r)))
        (is (= (:sha r) (store/sha256 (.getBytes "a CORR-1\n  cont\nc CORR-1\n" "UTF-8"))))
        (is (some? (store/raw-meta "default" raw-sha)) "the unfiltered follow is in the raw store too")
        (is (= 1 (count (session-logs "default" "S1"))))))))

(deftest a-window-streams-events-and-keeps-its-result
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "live.log" "old line\n")
          _ (file-env! "default" src)
          events (atom [])
          r (ops/call! "logs.fetch" {:emit! #(swap! events conj %)} {:target "log/app" :for "300ms"})
          quiet (call "logs.fetch" {:target "log/app" :for "300ms"})]
      (is (= ["old line"] (map :text @events)))
      (is (= #{:path :lines :bytes :sha :target :source} (set (keys r))) "the result shape did not change")
      (is (= (dissoc quiet :path) (dissoc r :path))))))

(deftest a-follow-with-bytes-already-in-the-store-links-to-them
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "live.log" "same\n")
          _ (file-env! "default" src)
          once (call "logs.fetch" {:target "log/app"})
          cancelled (atom false)
          events (atom [])
          res (future (ops/call! "logs.fetch" {:emit! #(swap! events conj %) :cancelled? #(deref cancelled)} {:target "log/app" :follow true}))
          _ (wait-until #(seq @events) 3000)
          _ (reset! cancelled true)
          r (deref res 5000 nil)]
      (is (= (:sha once) (:sha r)))
      (is (java.nio.file.Files/isSameFile (.toPath (io/file (:path r))) (store/raw-path "default" (:sha r))))
      (is (not= (:path once) (:path r))))))

(deftest an-unfinished-follow-is-adopted-when-its-owner-is-gone
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [logs-dir (io/file (str (session/dir "default" "S1")) "logs")
          _ (.mkdirs logs-dir)
          answer (io/file logs-dir "log-app-20261016-000000.log")
          fdir (.toFile (store/path "default" "raw" "follow"))
          _ (.mkdirs fdir)
          _ (spit answer "half\nwritten\n")
          side (io/file fdir "dead0001.edn")
          m {:source :file :op "logs.fetch log/app (follow)" :env :local :at (store/now) :content-type "text/plain"}
          _ (store/write-edn! (.toPath side) {:work (str answer) :answer (str answer) :filtered? false :target "log/app"
                                              :raw-meta m :answer-meta m :owner {:pid 2147483000 :process-start nil}
                                              :started (store/now) :sidecar (str side)})
          live (io/file fdir "live0002.edn")
          _ (store/write-edn! (.toPath live) {:work "x" :answer "x" :owner {:pid (.pid (java.lang.ProcessHandle/current))}})
          adopted (logfetch/adopt-orphans! (store/home))]
      (is (= [(str answer)] adopted))
      (is (java.nio.file.Files/isSameFile (.toPath answer) (store/raw-path "default" (store/sha256 (.getBytes "half\nwritten\n" "UTF-8")))))
      (is (not (.exists side)))
      (is (.exists live) "a follow whose owner is alive is not touched"))))

(deftest fetch-then-match-end-to-end
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "svc.log" (str (str/join "\n" log-lines) "\n"))
          _ (file-env! "default" src)
          f (call "logs.fetch" {:target "log/app"})]
      (call "marker.add" {:file "/p/ServiceA.java" :line 42 :logger "com.acme.ServiceA"})
      (is (= [1 8] (mapv :n (:lines (call "logs.match" {:path (:path f)}))))))))

;; --- estate-gated: container and CloudWatch ---------------------------------------------------

(deftest-floci fetch-cloudwatch-target
  (tu/with-temp-home
    (let [ws "default"
          env0 (fu/local-env)
          group (str "/test/fetch-" (random-uuid))
          env1 (assoc-in env0 [:targets :log/cw] {:cloudwatch group})
          ctx {:ws ws}
          ok! (fn [r] (is (not (aws/error? r)) (pr-str r)) r)]
      (env/save! ws env1)
      (session/start! ws "S1")
      (ok! (aws/invoke! ctx env1 :logs :CreateLogGroup {:logGroupName group}))
      (ok! (aws/invoke! ctx env1 :logs :CreateLogStream {:logGroupName group :logStreamName "s1"}))
      (ok! (aws/invoke! ctx env1 :logs :PutLogEvents
                        {:logGroupName group :logStreamName "s1"
                         :logEvents (mapv (fn [m] {:timestamp (System/currentTimeMillis) :message m})
                                          ["2026 INFO [c1] c.a.ServiceA:42 - FETCH-A" "2026 INFO other" "FETCH-B"])}))
      (let [r (call "logs.fetch" {:target "log/cw"})]
        (is (= "cloudwatch" (:source r)))
        (is (= 3 (:lines r)))
        (is (= :aws (:source (store/raw-meta ws (:sha r))))))
      (let [r (call "logs.fetch" {:target "log/cw" :filter "FETCH-"})]
        (is (= 2 (:lines r)))
        (is (not (str/includes? (slurp (:path r)) "other"))))
      (is (= 0 (:lines (call "logs.fetch" {:target "log/cw" :since "2s" :filter "NOPE"})))))))

(deftest-floci fetch-container-target
  (let [ews (fu/estate-ws)
        cname (when ews (store/container-name ews "floci"))]
    (if-not ews
      (fu/skip! "fetch-container-target" "ESTATE_WS not set (needs the estate's floci container)")
      (tu/with-temp-home
        (let [ws "default"]
          (env/save! ws (assoc-in (fu/local-env) [:targets :log/floci] {:container cname}))
          (session/start! ws "S1")
          (let [r (call "logs.fetch" {:target "log/floci"})]
            (is (= "container" (:source r)))
            (is (pos? (:lines r)) "floci logs something at startup")
            (is (= :podman (:source (store/raw-meta ws (:sha r)))))
            (is (= (:bytes r) (count (.getBytes (slurp (:path r)) "UTF-8"))))
            (is (pos? (:lines (call "logs.fetch" {:target "log/floci" :for "300ms"}))) "history plus a short listen")))))))
