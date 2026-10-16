(ns deliberator.audit-pure-test
  "Audit of M1, layer 1 (no runtime): the odd inputs of docs/plans/m1-test-report.md that need no
  container. Parser corners, an empty scenario, CRLF files, `{{` that is not a variable, `@until` that
  cannot hold, a scenario outside the session and outside the repo, a 200 KB body through `http`, a
  run diffed against itself, a `:local` record with a `:profile` on disk. Tests tagged `^:known-bug`
  describe correct behaviour the product does not have yet; they are excluded from `clojure -M:test`
  and run with `clojure -M:test:known-bugs`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.fake-kind :as fake]
            [deliberator.scenario.engine :as engine]
            [deliberator.scenario.parse :as parse]
            [deliberator.scenario.render :as render]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.steps.http]
            [deliberator.test-util :as tu])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.file Files Path)
           (java.security MessageDigest)))

(defn- steps-of [text] (mapv (juxt :kind :line) (:steps (parse/parse text))))
(defn- body-of [step] (some-> (:body step) (String. "UTF-8")))
(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e)))))
(defn- head [s] (subs s 0 (min 300 (count s))))

(def local {:name "local" :kind :local})

(defn- run-text!
  "Writes `text` (a string or bytes) to a scenario file called `fname` under the temp home and runs it."
  [fname text & opts]
  (session/start! "w" "T-1")
  (let [f (io/file (str (store/home)) "scn" fname)]
    (io/make-parents f)
    (if (string? text) (spit f text) (with-open [o (io/output-stream f)] (.write o ^bytes text)))
    (let [res (apply engine/run! "w" (or (:env (apply hash-map opts)) local) (str f) :session "T-1"
                     (mapcat identity (dissoc (apply hash-map opts) :env)))]
      (assoc res :text (slurp (get-in res [:paths :result]))))))

(defn- run-edn [res] (store/read-edn (io/file (get-in res [:paths :run]) "run.edn")))
(defn- n-runs []
  (let [d (io/file (str (session/dir "w" "T-1")) "runs")] (if (.exists d) (count (.listFiles d)) 0)))

;; --- parser corners ------------------------------------------------------------------------------

(deftest a-bare-triple-hash-line-is-body-inside-and-outside-a-string
  (testing "inside a quoted string of a JSON body"
    (let [{:keys [steps]} (parse/parse "### fake a\n{\"k\": \"before\n###\nafter\"}\n### fake b\n")]
      (is (= ["a" "b"] (map #(or (:name %) (:target %)) steps)))
      (is (= "{\"k\": \"before\n###\nafter\"}\n" (body-of (first steps))))))
  (testing "a line that is exactly ### (no kind) is never a step header, string or not"
    (let [{:keys [steps]} (parse/parse "### fake a\nline one\n###\nline two\n### fake b\n")]
      (is (= 2 (count steps)))
      (is (= "line one\n###\nline two\n" (body-of (first steps))))))
  (testing "### with trailing blanks, #### and ###kind are body too"
    (let [{:keys [steps]} (parse/parse "### fake a\n###   \n####x\n###kind\n### fake b\n")]
      (is (= 2 (count steps)))
      (is (= "###   \n####x\n###kind\n" (body-of (first steps)))))))

(deftest a-stray-quote-in-a-non-json-body-must-not-swallow-later-steps
  ;; find-heads tracks JSON strings in every body. A `{` followed by a `"` opens a "string" that only
  ;; an odd number of later quotes closes; every ### line in between is silently body. No error, steps lost.
  (testing "SQL with a brace and a double quote inside a single-quoted literal, then a lone quote later"
    (is (= [["sql" 1] ["wait" 3] ["sql" 5]]
           (steps-of "### sql db/main\nselect '{\"a' as x\n### wait\n# @for 1s\n### sql db/main\nselect 'it\"s'\n")))))

(deftest a-scenario-without-steps-is-refused-before-anything-happens
  (doseq [[what text] [["empty file" ""] ["only header" "# @env local\n# @var a = 1\n"]
                       ["only comments" "// nothing\n# plain\n\n"]]]
    (tu/with-temp-home
      (let [e (thrown #(run-text! "none.scn" text))]
        (is (= :scenario/parse (:type e)) what)
        (is (str/includes? (:message e) "no steps") what)
        (is (zero? (n-runs)) (str what ": no run directory was created"))))))

(deftest crlf-scenario-files-run-like-lf-ones
  (fake/reset-state!)
  (tu/with-temp-home
    (let [res (run-text! "crlf.scn"
                         (str "# @env local\r\n# @var who = world\r\n\r\n"
                              "### fake one\r\n# @set msg=hello {{who}}\r\n# @assert msg == hello world\r\n"
                              "{\"a\": 1}\r\n\r\n### fake two\r\n# @assert len == 3\r\n"))
          step1 (io/file (get-in res [:paths :run]) "steps")
          dir1 (first (filter #(str/starts-with? (.getName %) "001-") (.listFiles step1)))]
      (is (= :ok (:status res)) (:text res))
      (is (= 0 (:failed-checks res)) (:text res))
      (testing "the body keeps its CRLF, the directive values carry no \\r"
        (is (= "{\"a\": 1}\r\n" (slurp (io/file dir1 "response.json"))))
        (is (not (str/includes? (:text res) "world\r")))))))

(deftest braces-that-are-not-variables
  (fake/reset-state!)
  (testing "an unknown {{name}} is a plan error before any step runs, naming the known variables"
    (tu/with-temp-home
      (let [e (thrown #(run-text! "v.scn" "### fake a\nX-Template: {{notavar}}\n"))]
        (is (= :scenario/unknown-var (:type e)))
        (is (str/includes? (:message e) "unknown variable {{notavar}}"))
        (is (str/includes? (:message e) "known: correlation"))
        (is (empty? @fake/trace) "nothing ran")
        (is (zero? (n-runs))))))
  (testing "text that cannot be a variable name passes through untouched"
    (tu/with-temp-home
      (let [res (run-text! "v.scn" "### fake a\nA {{a b}} B {{ }} C {{\n")]
        (is (= :ok (:status res)))
        (is (str/includes? (:text res) "A {{a b}} B {{ }} C {{")))))
  (testing "a captured value that looks like a variable is inserted verbatim, never expanded again"
    (tu/with-temp-home
      (let [res (run-text! "v.scn"
                           (str "### fake a\n# @capture c = $.v\n{\"v\": \"\\u007b\\u007bnosuch}}\"}\n"
                                "### fake b\n# @assert len == 17\nvalue={{c}}\n"))]
        (is (= :ok (:status res)) (:text res))
        (is (str/includes? (:text res) "value={{nosuch}}"))))))

;; --- @until ---------------------------------------------------------------------------------------

(deftest until-that-cannot-hold-ends-at-the-window-and-fails-readably
  (fake/reset-state!)
  (tu/with-temp-home
    (let [t0 (System/currentTimeMillis)
          res (run-text! "u.scn" "### fake a\n# @until n > 1000 within 2s\n### fake after\n")
          ms (- (System/currentTimeMillis) t0)
          step (first (:steps (run-edn res)))]
      (is (= :failed (:status res)))
      (is (<= 1200 ms 4500) (str "took " ms " ms for a 2 s window"))
      (is (<= 3 (count (filter #(= [:start 1] %) @fake/trace)) 5) "polls every 500 ms")
      (is (str/includes? (:text res) "✗ until n > 1000"))
      (is (re-find #"FAILED 1/1" (:text res)))
      (is (= :ok (:status (second (:steps (run-edn res))))) "the next step still ran")
      (is (= :failed (:status step))))))

;; --- scenario files outside the session, outside the repo ------------------------------------------

(deftest a-scenario-file-anywhere-runs-and-its-run-lives-in-the-session
  (fake/reset-state!)
  (tu/with-temp-home
    (session/start! "w" "T-1")
    (let [outside (io/file (System/getProperty "user.dir") "target" "audit-outside" "dir with spaces" "Zażółć (1).scn")]
      (io/make-parents outside)
      (spit outside "### fake a\n")
      (let [res (engine/run! "w" local (str outside) :session "T-1")
            run (get-in res [:paths :run])]
        (is (= :ok (:status res)))
        (is (str/starts-with? run (str (session/dir "w" "T-1") "/runs/")) "the run is inside the session")
        (is (re-matches #"[A-Za-z0-9._-]+" (.getName (io/file run))) "the run directory name is made safe")
        (is (= (str outside) (get-in (run-edn res) [:scenario :path])) "the original path is recorded")
        (testing "the scenario text is persisted with the run (persist first)"
          (is (some #(str/includes? (slurp %) "### fake a") (filter #(.isFile %) (file-seq (io/file run))))))))))

(deftest a-body-file-outside-the-scenario-directory-is-read-as-written
  ;; by design (decision 10: absolute and ../ paths allowed); the test pins the behaviour
  (fake/reset-state!)
  (tu/with-temp-home
    (let [other (io/file (System/getProperty "user.dir") "target" "audit-outside" "payload.bin")]
      (io/make-parents other)
      (spit other "outside\n\n\n")
      (let [res (run-text! "b.scn" (str "### fake a\n# @body " (.getAbsolutePath other) "\n"))]
        (is (= :ok (:status res)))
        (is (str/includes? (:text res) "outside\n\n\n") "byte-exact, trailing blank lines kept")))))

;; --- 200 KB through http -------------------------------------------------------------------------

(defn- sha-hex [^bytes bs]
  (apply str (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- start-hash-server []
  (let [s (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext s "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex
                              body (.readAllBytes (.getRequestBody ex))
                              big? (= "/big" (.getPath (.getRequestURI ex)))
                              out (if big?
                                    (.getBytes (apply str (repeat 3000 "0123456789abcdef0123456789abcdef\n")) "UTF-8")
                                    (.getBytes (json/write-str {:len (alength body) :sha (sha-hex body)}) "UTF-8"))]
                          (.add (.getResponseHeaders ex) "Content-Type" (if big? "text/plain" "application/json"))
                          (.sendResponseHeaders ex 200 (alength out))
                          (.write (.getResponseBody ex) out)
                          (.close ex)))))
    (.start s)
    s))

(defn- nlink [^java.io.File f] (Files/getAttribute (.toPath f) "unix:nlink" (make-array java.nio.file.LinkOption 0)))

(deftest http-carries-a-200kb-body-and-a-96kb-response-byte-exact
  (tu/with-temp-home
    (let [srv (start-hash-server)
          base (str "http://127.0.0.1:" (.getPort (.getAddress srv)))
          line "{\"sku\":\"a-1\",\"qty\":2,\"pad\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}\n"
          payload (apply str (repeat (quot 204800 (count line)) line))
          pbytes (.getBytes ^String payload "UTF-8")
          env (assoc local :targets {:service/echo base})]
      (try
        (let [f (io/file (str (store/home)) "payload.json")
              _ (spit f (str "POST /up\nContent-Type: application/json\n\n" payload))
              inline (run-text! "inline.scn"
                                (str "### http service/echo post\n# @assert $.len == " (alength pbytes) "\n"
                                     "# @assert $.sha == " (sha-hex pbytes) "\nPOST /up\nContent-Type: application/json\n\n" payload)
                                :env env :resolve-target #(env/target env (keyword %)))
              file (run-text! "file.scn"
                              (str "### http service/echo post\n# @assert $.len == " (alength pbytes) "\n"
                                   "# @assert $.sha == " (sha-hex pbytes) "\n# @body " (.getAbsolutePath f) "\n")
                              :env env :resolve-target #(env/target env (keyword %)))
              big (run-text! "big.scn" "### http service/echo big\n# @assert bytes == 99000\n# @assert status == 200\nGET /big\n"
                             :env env :resolve-target #(env/target env (keyword %)))]
          (is (>= (alength pbytes) 200000))
          (is (= :ok (:status inline)) (head (:text inline)))
          (testing "the whole request is on disk, as one hard link into raw/"
            (let [d (first (filter #(str/starts-with? (.getName %) "001-")
                                   (.listFiles (io/file (get-in inline [:paths :run]) "steps"))))
                  rq (io/file d "request.http")]
              (is (>= (.length rq) 204800))
              (is (>= (nlink rq) 2) "step file and raw blob are the same inode")))
          (is (= :ok (:status big)) (head (:text big)))
          (testing "result.txt shows the response, however large"
            (is (str/includes? (:text big) "0123456789abcdef")))
          (testing "a body from @body: the same bytes arrive"
            (is (= :ok (:status file)) (head (:text file)))))
        (finally (.stop srv 0))))))

;; --- diff against itself, env record on disk -------------------------------------------------------

(deftest a-run-diffed-against-itself-is-empty
  (fake/reset-state!)
  (tu/with-temp-home
    (let [res (run-text! "d.scn" "### fake a\n# @assert len == 3\n### fake b\n# @assert len == 99\n")
          dir (io/file (get-in res [:paths :run]))]
      (is (= :failed (:status res)))
      (is (str/blank? (render/diff dir dir)) "failed checks do not make a run differ from itself")
      (is (str/blank? (render/diff (.toPath dir) (.toPath dir)))))))

(deftest a-local-environment-file-with-a-profile-is-refused-on-load
  (tu/with-temp-home
    (store/write-edn! (store/path "w" "environments" "local.edn")
                      (assoc env/default-local :aws {:endpoint "http://localhost:4566" :region "eu-central-1"
                                                     :account "000000000000" :profile "some-sso"
                                                     :access-key "x" :secret-key "y"}))
    (let [e (thrown #(env/load "w" "local"))]
      (is (= :env/invalid (:type e)) (pr-str e))
      (is (some #(str/includes? (str %) "profile") (:problems e)) (pr-str e)))))

;; --- the raw store under contention ----------------------------------------------------------------

(deftest parallel-puts-of-the-same-bytes-leave-one-intact-blob-and-one-sidecar
  (tu/with-temp-home
    (let [bs (.getBytes (apply str (repeat 20000 "contended ")) "UTF-8")
          rs (doall (map deref (doall (for [i (range 32)]
                                        (future (store/raw-put! "w" bs (tu/meta-for {:op (str "put " i)})))))))
          sha (:sha (first rs))
          m (store/raw-meta "w" sha)]
      (is (= 1 (count (set (map :sha rs)))))
      (is (<= 1 (count (filter :new? rs))) "at least one writer created it (a race may let several claim it)")
      (is (java.util.Arrays/equals bs (store/raw-get "w" sha)))
      (is (= sha (:sha m)))
      (is (str/starts-with? (:op m) "put ") "one well-formed sidecar, written by one of the writers")
      (is (= 2 (count (.listFiles (.getParentFile (.toFile (store/raw-path "w" sha)))))) "blob and sidecar, no temp files left"))))

(deftest a-long-scenario-file-name-still-gets-a-run-directory
  ;; run-dir! builds `<yyyymmdd-hhmmss>-<file stem>`; a stem of ~245 characters (legal for the file) makes the
  ;; directory name exceed 255 bytes: the run dies with the JDK's FileSystemException instead of a short name.
  (fake/reset-state!)
  (tu/with-temp-home
    (let [fname (str (apply str (repeat 240 "a")) ".scn")
          res (try (run-text! fname "### fake a\n") (catch Throwable e e))]
      (is (map? res) (str "threw " (some-> res class) " " (some-> res ex-message (subs 0 80))))
      (when (map? res) (is (= :ok (:status res)))))))

(deftest on-created-sees-the-run-before-the-first-event
  ;; F3: a registry fed from :on-created holds the run by the time `run.started` is emitted
  (fake/reset-state!)
  (tu/with-temp-home
    (session/start! "w" "T-1")
    (let [f (io/file (str (store/home)) "scn" "oc.scn")
          seen (atom nil)
          sink (engine/collecting-sink)
          _ (io/make-parents f)
          _ (spit f "### fake a\n")
          run (engine/start! "w" local (str f) :session "T-1" :sink sink
                             :on-created (fn [r] (reset! seen {:run r :events (count (engine/events sink))})))]
      @run
      (is (= (:run-id run) (:run-id (:run @seen))))
      (is (zero? (:events @seen)) "nothing had been emitted when the creator got the run")
      (is (= "run.started" (name (:event (first (engine/events sink)))))))))

(deftest mark-orphans-closes-dead-runs-and-leaves-live-ones
  (tu/with-temp-home
    (session/start! "w" "T-1")
    (let [mk (fn [id owner]
               (let [d (session/run-dir! "w" "T-1" id)]
                 (store/write-edn! (.resolve d "run.edn")
                                   (cond-> {:run-id id :status :running :session "T-1" :started (store/now)}
                                     owner (assoc :owner owner)))
                 d))
          me (let [h (java.lang.ProcessHandle/current)]
               {:pid (.pid h) :process-start (str (.orElse (.startInstant (.info h)) nil))})
          live (mk "live" me)
          dead (mk "dead" {:pid 2147483646 :process-start "2020-01-01T00:00:00Z"})
          reused (mk "reused" (assoc me :process-start "2020-01-01T00:00:00Z"))
          none (mk "noowner" nil)
          done (let [d (mk "done" me)] (store/write-edn! (.resolve d "run.edn") {:run-id "done" :status :ok}) d)
          marked (set (map str (engine/mark-orphans!)))]
      (is (= #{(str dead) (str reused) (str none)} marked))
      (is (= :running (:status (store/read-edn (.resolve live "run.edn")))))
      (is (= :ok (:status (store/read-edn (.resolve done "run.edn")))))
      (let [r (store/read-edn (.resolve dead "run.edn"))]
        (is (= :interrupted (:status r)))
        (is (some? (:finished r)))
        (is (re-find #"RUN interrupted" (slurp (.toFile (.resolve dead "result.txt")))))))))

(deftest a-long-session-key-is-an-invalid-name
  (let [e (thrown #(store/safe-name (apply str (repeat 300 "k"))))]
    (is (= :store/invalid-name (:type e)))
    (is (< (count (:message e)) 200) "the message does not repeat the whole name")))
