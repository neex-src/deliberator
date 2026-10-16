(ns deliberator.audit-ide-ops-test
  "Audit of M2, layer 1 (the core ops added for the IDE), odd inputs the per-piece tests do not try: marker arguments that
  make no sense, error messages that quote non-ASCII text, a 5 000-step `scenario.parse`, a pathological log line and a
  200 000-line log through `markers/match-lines`, a bad regex filter, the same directory spelt three ways, `runs.list` with regex
  characters. Tests tagged `^:known-bug` describe behaviour the product does not have yet; they are excluded from
  `clojure -M:test` and run with `clojure -M:test:known-bugs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.markers :as markers]
            [deliberator.ops :as ops]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]
            [deliberator.steps.wait] [deliberator.steps.http] [deliberator.steps.logs]))

(defn call [op args] (ops/call! op {} args))
(defn thrown-type [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
(defn write! [dir name text] (let [f (io/file (str dir) name)] (io/make-parents f) (spit f text) (str f)))

;; --- markers: arguments that make no sense -----------------------------------------------------

(deftest ^:known-bug marker-add-refuses-a-line-below-one
  ;; F-mark: 0, negative and absurd lines are stored (and a relative or empty file name too); no log line can ever match them
  (tu/with-temp-home
    (doseq [line [0 -3]]
      (is (some? (thrown-type #(call "marker.add" {:file "/x/A.java" :line line}))) (str "line " line " must be refused with a typed error")))
    (is (= [] (call "marker.list" {})) "nothing was stored")))

(deftest ^:known-bug marker-add-refuses-a-relative-file
  (tu/with-temp-home
    (is (some? (thrown-type #(call "marker.add" {:file "rel/A.java" :line 3}))) "a marker file is the absolute path of the IDE's file")))

;; --- scenario.parse -----------------------------------------------------------------------------

(deftest ^:known-bug parse-error-messages-quote-non-ascii-text-intact
  ;; F-moji: the offending line is quoted from the latin-1 view of the bytes; the IDE shows it as an annotation
  (let [r (call "scenario.parse" {:text "é ü\n### wait\n"})
        msg (get-in r [:errors 0 :message])]
    (is (str/includes? msg "é ü") msg)))

(deftest parse-5000-steps-through-the-op
  (let [text (str "# @env local\n\n"
                  (apply str (for [i (range 1 5001)] (str "### wait s" i "\n# @for 1ms\n\n"))))
        t0 (System/nanoTime)
        r (call "scenario.parse" {:text text})
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (println (format "AUDIT scenario.parse of 5000 steps (%d chars): %.0f ms" (count text) ms))
    (is (= [] (:errors r)))
    (is (= 5000 (count (:steps r))))
    (is (= [1 5000] ((juxt (comp :index first) (comp :index last)) (:steps r))))
    (is (< ms 10000) (str ms " ms"))))

;; --- logs.match: pathological input ---------------------------------------------------------------

(def ^:private ms-fixture
  [{:id "m1" :file "/s/ServiceA.java" :line 42 :logger "com.acme.ServiceA"}
   {:id "m2" :file "/s/OrderRepo.java" :line 10 :logger "com.acme.svc.OrderRepo"}])

(defn- normal [i] (str "2026-10-16T10:00:00.000+02:00 INFO APP [c" i "] c.a.ServiceA:" (mod i 100) " - line " i))

(deftest match-lines-over-a-big-log-and-long-lines-without-dots
  (let [spaces (str (apply str (repeat 50000 "a. ")) "x:1")
        flat (apply str (repeat 100000 "x"))
        lines (into [spaces flat] (map normal (range 200000)))
        t0 (System/nanoTime)
        r (markers/match-lines ms-fixture lines)
        taken (/ (- (System/nanoTime) t0) 1e6)]
    (println (format "AUDIT match-lines over %d lines incl. two 100 KB lines: %.0f ms, %d hits" (count lines) taken (count r)))
    (is (= 2000 (count r)) "every 100th of the 200 000 normal lines is line 42")
    (is (< taken 20000) (str taken " ms"))))

(deftest ^:known-bug match-lines-survives-a-line-with-thousands-of-dotted-segments
  ;; F-so: one such line (>= ~2000 `a.a.a.` segments, a 4 KB token) makes `logger:line` regex recursion overflow the stack; the whole
  ;; `logs.match` answers `internal: StackOverflowError` and the IDE highlights nothing for that log
  (let [bad (str "x " (apply str (repeat 5000 "a.")) "b 42 - x")
        r (try (markers/match-lines ms-fixture [bad (normal 42)]) (catch StackOverflowError _ :stack-overflow))]
    (is (not= :stack-overflow r))
    (is (= [{:n 2 :marker-id "m1"}] r) "the normal line still matches")))

;; --- logs.fetch: odd arguments --------------------------------------------------------------------

(defn- file-env! [ws target-file]
  (env/save! ws (assoc-in (env/load ws "local") [:targets :log/app] {:file target-file})))

(deftest fetch-odd-arguments-are-typed-errors-or-sane-answers
  (tu/with-temp-home
    (session/start! "default" "S1")
    (let [src (write! (store/home) "app.log" "one A\r\n  cont\r\ntwo B\r\n")
          _ (file-env! "default" src)]
      (testing "a bad regex filter is a typed error, not a stack trace"
        (is (some? (thrown-type #(call "logs.fetch" {:target "log/app" :filter "/(unclosed/"})))))
      (testing "CRLF is normalised to LF in the fetched file AND in the raw blob it is linked from (rough edge F-crlf: the source bytes are not kept)"
        (is (= "one A\n  cont\ntwo B\n" (slurp (:path (call "logs.fetch" {:target "log/app"})))))
        (is (= "one A\n  cont\n" (slurp (:path (call "logs.fetch" {:target "log/app" :filter "A"}))))))
      (testing "a negative window is refused (typed, although as scenario/expr, not logs/bad-arg)"
        (is (some? (thrown-type #(call "logs.fetch" {:target "log/app" :for "-5s"})))))
      (testing "an empty file gives an empty log, not an error"
        (let [empty (write! (store/home) "empty.log" "")
              r (call "logs.fetch" {:target empty})]
          (is (= 0 (:lines r)))
          (is (= "" (slurp (:path r)))))))))

(deftest ^:known-bug fetch-of-a-path-that-is-not-a-file-is-an-error
  ;; F-nofile: a typo in the Fetch Logs dialog's target (or a directory) answers a successful, empty log
  (tu/with-temp-home
    (session/start! "default" "S1")
    (is (some? (thrown-type #(call "logs.fetch" {:target (str (store/home) "/no/such/file.log")}))) "missing file")
    (is (some? (thrown-type #(call "logs.fetch" {:target (str (store/home))}))) "directory")))

;; --- repo.attach: the same directory spelt three ways ----------------------------------------------

(deftest the-same-directory-spelt-three-ways-is-one-repository
  (tu/with-temp-home
    (let [d (io/file (str (store/home)) "co" "svc a")
          link (io/file (str (store/home)) "co" "link")]
      (.mkdirs d)
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath d) (make-array java.nio.file.attribute.FileAttribute 0))
      (call "repo.attach" {:path (str d)})
      (call "repo.attach" {:path (str d "/")})
      (call "repo.attach" {:path (str (io/file (str d) ".." "svc a"))})
      (call "repo.attach" {:path (str link)})
      (is (= ["svc a"] (map :name (call "repo.list" {}))) "a name with a space is kept, the spellings collapse")
      (is (= (str (.toRealPath (.toPath d) (make-array java.nio.file.LinkOption 0))) (:path (first (call "repo.list" {}))))))))

;; --- runs.list scenario with characters that are special in a regex ---------------------------------

(deftest runs-list-scenario-filter-is-literal
  (tu/with-temp-home
    (session/start! "default" "S1")
    (doseq [[ts p] [["20261016-100000-a" "/w/a.scn"] ["20261016-100100-b" "/w/my (x) [y].scn"]]]
      (store/write-edn! (io/file (str (session/dir "default" "S1")) "runs" ts "run.edn")
                        {:run-id ts :status :ok :started (store/now) :scenario {:path p}}))
    (let [ids #(mapv :run-id (:runs (call "runs.list" {:scenario %})))]
      (is (= [] (ids ".")) "a dot is not a wildcard")
      (is (= [] (ids "(")))
      (is (= [] (ids ".*")))
      (is (= ["20261016-100100-b"] (ids "/w/my (x) [y].scn"))))))
