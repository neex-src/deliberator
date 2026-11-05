(ns deliberator.generate-test
  "`scenario.generate` and `workspace.universal` with a fixture model (test/fixtures/fixture-model.bb):
  nothing here calls a real model. The universal JSON is read from the workspace, the answer is checked and retried once,
  everything is on disk before it is parsed."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.daemon]   ; registers every step kind
            [deliberator.generate :as generate]
            [deliberator.ops :as ops]
            [deliberator.scenario.parse :as parse]
            [deliberator.scenario.kinds :as kinds]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]
            [deliberator.universal :as universal]
            [deliberator.workspace :as workspace]))

(defn- call
  ([op args] (call op args nil))
  ([op args events]
   (ops/call! op {:emit! (fn [e] (when events (swap! events conj e))) :on-cancel! (fn [_]) :alive? (constantly true)
                  :cancelled? (constantly false) :daemon {:runs (atom {}) :checked (atom #{})}}
              args)))

(def good "### sqs send queue/inbox send-order\n# @attr id={{uuid}}\n{\"orderId\": \"{{uuid}}\"}\n\n### dynamodb get table/messages acked\n# @until $.Item.status.S == ACKED within 10s\n{\"Key\": {\"id\": {\"S\": \"{{uuid}}\"}}}\n")
(def bad-target "### sqs send queue/inboxx send-order\n{}\n")
(def bad-kind "### sqz send queue/inbox send-order\n{}\n")

(def scn "# @env local\n\n### ai orders\nSend an order to the inbox, wait until it is acked.\n\n### wait later\n# @for 1s\n")

(defn- dir! [& parts] (doto (apply io/file parts) .mkdirs))

(defn- setup!
  "A workspace with a session, a repository that carries a skill file and a Terraform file, the fixture model configured
  with `answers`; -> {:dir fixture-dir :file scenario-file :repo repo}."
  [answers & {:keys [fmt]}]
  (call "workspace.open" {:ws "default"})
  (call "session.start" {:key "G-1" :ws "default"})
  (let [base (dir! (System/getProperty "user.dir") "target" "gen-fixtures" (str (java.util.UUID/randomUUID)))
        fx (dir! base "fx")
        repo (dir! base "repo")
        file (io/file base "orders.scn")]
    (doseq [[i a] (map-indexed vector answers)] (spit (io/file fx (format "answer-%d.txt" (inc i))) a))
    (dir! repo "deliberator" "skills")
    (spit (io/file repo "deliberator" "skills" "orders.md") "# How orders are sent\nAn order is a JSON document with orderId.\n")
    (dir! repo "infra")
    (spit (io/file repo "infra" "main.tf") "resource \"aws_sqs_queue\" \"inbox\" {\n  name = \"the-inbox\"\n}\n")
    (call "repo.attach" {:path (str repo) :ws "default"})
    (spit file scn)
    (workspace/save! (assoc (workspace/load "default")
                            :ai {:command ["bb" (.getAbsolutePath (io/file "test/fixtures/fixture-model.bb"))]
                                 :env (cond-> {"FIXTURE_DIR" (str fx)} fmt (assoc "FIXTURE_FORMAT" fmt))
                                 :format (if (= fmt "text") :text :claude-json)
                                 :budget-ms 60000}))
    {:dir fx :file file :repo repo}))

(defn- gen [{:keys [file]} & {:as extra}]
  (call "scenario.generate" (merge {:path (str file) :name "orders" :ws "default" :session "G-1"} extra)))

(defn- run-dir [res] (io/file (get-in res [:paths :run])))

(deftest the-universal-json-is-generated-from-the-workspace
  (tu/with-temp-home
    (let [{:keys [repo]} (setup! [good])
          r (call "workspace.universal" {:ws "default" :env "local"})
          f (io/file (:path r))
          u (json/read-str (slurp f))
          lines (str/split-lines (slurp f))]
      (is (.isFile f))
      (is (= (str (store/path "default" "universal.json")) (:path r)))
      (is (= ["universal" "workspace" "generated" "environment" "targets" "topology" "services" "repositories" "skills" "terraform" "aws"
              "engine-directives" "step-kinds" "operations" "variables" "unavailable"]
             (map second (re-seq #"(?m)^  \"([^\"]+)\":" (slurp f)))) "in the documented order")
      (testing "the environment: every target, with the environment's own names"
        (is (= "local" (get-in u ["environment" "name"])))
        (is (contains? (get u "targets") "queue/inbox")))
      (testing "kinds: the inert ones are not offered, a big verb list is a count and prefixes"
        (let [ks (into {} (map (juxt #(get % "kind") identity)) (get u "step-kinds"))]
          (is (not (contains? ks "ai")))
          (is (contains? ks "sqs"))
          (is (> (get-in ks ["aws" "verbs-count"]) 40))
          (is (nil? (get-in ks ["aws" "verbs"])))
          (is (contains? (set (get-in ks ["aws" "verb-prefixes"])) "sqs"))))
      (testing "operations: what the op kind offers, not scenario.run or scenario.generate"
        (let [ops* (set (map #(get % "op") (get u "operations")))]
          (is (contains? ops* "scenario.parse"))
          (is (not-any? ops* ["scenario.run" "scenario.generate" "daemon.stop"]))
          (is (contains? ops* "workspace.universal"))))
      (testing "repositories, their skill file (copied next to the JSON) and their Terraform"
        (is (= [(.getName repo)] (map #(get % "name") (get u "repositories"))))
        (is (= [{"repo" (.getName repo) "file" "orders.md" "path" (str "skills/" (.getName repo) "/orders.md") "title" "How orders are sent"}]
               (map #(select-keys % ["repo" "file" "path" "title"]) (get u "skills"))))
        (is (.isFile (io/file (.getParentFile f) "skills" (.getName repo) "orders.md")))
        (is (= [{"kind" "resource" "type" "aws_sqs_queue" "name" "inbox" "ident" "the-inbox" "file" "main.tf" "line" 1 "dir" "infra"}]
               (map #(select-keys % ["kind" "type" "name" "ident" "file" "line" "dir"]) (get u "terraform")))))
      (testing "a source that is not there is named, not silently empty"
        (is (contains? (get u "unavailable") "aws") "the local endpoint of a test home is a closed port: aws.discover cannot answer")
        (is (= (:unavailable r) (get u "unavailable"))))
      (testing "one entry per line, so a Grep returns whole entries"
        (is (some #(str/starts-with? (str/trim %) "\"queue/inbox\": \"estate-inbox\"") lines))
        (is (some #(str/includes? % "\"kind\":\"sqs\"") lines))))))

(deftest a-good-answer-lands-under-the-prose
  (tu/with-temp-home
    (let [ctx (setup! [good])
          events (atom [])
          before (slurp (:file ctx))
          r (call "scenario.generate" {:path (str (:file ctx)) :name "orders" :ws "default" :session "G-1"} events)
          after (slurp (:file ctx))
          d (parse/describe after :kinds (kinds/registry))]
      (is (= "ok" (:status r)))
      (is (= 1 (:attempts r)))
      (is (= 2 (:steps r)))
      (is (true? (:wrote r)) "no text given: the file is rewritten")
      (testing "the file: prose, marker with model/tokens/cost/run, steps, end marker, then what was after"
        (is (empty? (:errors d)) (pr-str (:errors d)))
        (is (= ["ai" "sqs" "dynamodb" "wait"] (map :kind (:steps d))))
        (is (str/includes? after "### ai orders\nSend an order to the inbox, wait until it is acked.\n\n#> generated by fixture-haiku · 3 004 tokens in, 10 out · $0.0123 · "))
        (is (str/includes? after (str " · " (:run r) "\n### sqs send queue/inbox send-order")))
        (is (str/includes? after "#< end\n\n### wait later"))
        (is (not= before after)))
      (testing "events"
        (is (= [:generate.started :generate.attempt :generate.checked :generate.finished] (map :event @events))))
      (testing "everything is in the run folder"
        (let [dir (run-dir r)]
          (is (= (str (store/path "default" "sessions" "G-1" "generate" (.getName dir))) (str dir)))
          (doseq [f ["source.scn" "prompt-1.md" "model-1.out" "model-1.err" "answer-1.scn" "usage-1.edn" "check-1.edn" "generated.scn" "generate.edn"
                     "context/universal.json" (str "context/skills/" (.getName (:repo ctx)) "/orders.md")]]
            (is (.exists (io/file dir f)) f))
          (is (= (slurp (io/file dir "source.scn")) before) "the document as it was")
          (let [g (store/read-edn (.toPath (io/file dir "generate.edn")))]
            (is (= :ok (:status g)))
            (is (= "fixture-session-1" (:session-id g)))
            (is (= 3004 (get-in g [:usage :input])))
            (is (= (:run r) (:run g))))
          (is (not (.canWrite (io/file dir "model-1.out"))) "the model's output was adopted into the raw store, read-only")))
      (testing "what the model saw: a prompt whose first line is the task, the folder as its working directory"
        (let [seen (slurp (io/file (:dir ctx) "seen-1.md"))
              cwd (slurp (io/file (:dir ctx) "cwd-1.txt"))]
          (is (str/starts-with? seen "Write Deliberator steps for the block orders of orders.scn: Send an order to the inbox"))
          (is (str/includes? seen "# Writing Deliberator scenario steps"))
          (is (str/includes? seen "Send an order to the inbox, wait until it is acked."))
          (is (str/includes? seen "# @env local"))
          (is (str/ends-with? (first (str/split-lines cwd)) "/context"))
          (is (some #{"universal.json"} (str/split-lines cwd)))
          (is (some #{(str "skills/" (.getName (:repo ctx)) "/orders.md")} (str/split-lines cwd))))))))

(deftest generating-again-replaces
  (tu/with-temp-home
    (let [ctx (setup! [good "### wait only\n# @for 1s\n"])]
      (gen ctx)
      (let [once (slurp (:file ctx))]
        (gen ctx)
        (let [twice (slurp (:file ctx))
              d (parse/describe twice :kinds (kinds/registry))]
          (is (= ["ai" "wait" "wait"] (map :kind (:steps d))) "the second answer replaced the first")
          (is (= 1 (count (filter #(str/starts-with? % "#> generated") (str/split-lines twice)))))
          (is (not= once twice)))))))

(deftest a-wrong-answer-is-sent-back-once
  (tu/with-temp-home
    (let [ctx (setup! [bad-target good])
          r (gen ctx)]
      (is (= "ok" (:status r)))
      (is (= 2 (:attempts r)))
      (is (str/includes? (:marker r) "2 attempts"))
      (let [dir (run-dir r)
            seen2 (slurp (io/file (:dir ctx) "seen-2.md"))]
        (is (.exists (io/file dir "prompt-2.md")))
        (is (.exists (io/file dir "answer-2.scn")))
        (is (str/includes? seen2 "## Your previous answer"))
        (is (str/includes? seen2 "queue/inboxx"))
        (is (re-find #"- line 1: unknown target :queue/inboxx" seen2) "the checker's error, with a line of the answer")
        (testing "usage is the sum of both calls"
          (let [g (store/read-edn (.toPath (io/file dir "generate.edn")))]
            (is (= 6008 (get-in g [:usage :input])))
            (is (= 30 (get-in g [:usage :output])))))))))

(deftest two-wrong-answers-fail-and-change-nothing
  (doseq [[bad rx] [[bad-target #"unknown target"] [bad-kind #"unknown step kind"]]]
    (tu/with-temp-home
      (let [ctx (setup! [bad bad])
            before (slurp (:file ctx))
            e (try (gen ctx) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :generate/invalid (:type (ex-data e))))
        (is (re-find rx (ex-message e)))
        (is (seq (:errors (ex-data e))))
        (is (= before (slurp (:file ctx))) "the file is untouched")
        (let [dir (io/file (:run (ex-data e)))]
          (is (.exists (io/file dir "answer-1.scn")))
          (is (.exists (io/file dir "answer-2.scn")))
          (is (= :invalid (:status (store/read-edn (.toPath (io/file dir "generate.edn")))))))))))

(deftest the-answer-is-kept-before-it-is-parsed
  (tu/with-temp-home
    (let [ctx (setup! [good] :fmt "garbage")
          e (try (gen ctx) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :generate/bad-model-output (:type (ex-data e))))
      (let [dir (io/file (:run (ex-data e)))]
        (is (= "this is not json <<<" (slurp (io/file dir "model-1.out"))) "the bytes the command printed")
        (is (.exists (io/file dir "prompt-1.md")))
        (is (= :failed (:status (store/read-edn (.toPath (io/file dir "generate.edn"))))))))))

(deftest a-text-command-has-no-usage-and-we-do-not-invent-one
  (tu/with-temp-home
    (let [ctx (setup! ["```\n" good "```\n"] :fmt "text")
          r (gen ctx)]
      (is (= "ok" (:status r)) "the code fence is taken off")
      (is (nil? (:usage r)))
      (is (str/includes? (:marker r) "usage not reported"))
      (is (not (str/includes? (:marker r) "$")))
      (is (empty? (:errors (parse/describe (slurp (:file ctx)) :kinds (kinds/registry))))))))

(deftest an-editor-buffer-is-not-written-but-returned
  (tu/with-temp-home
    (let [ctx (setup! [good])
          buffer (str scn "\n### wait unsaved\n# @for 1s\n")
          r (gen ctx :text buffer)]
      (is (false? (:wrote r)))
      (is (= scn (slurp (:file ctx))) "the file on disk is untouched")
      (is (= "#< end" (peek (:region r))))
      (is (str/starts-with? (first (:region r)) "#> generated by fixture-haiku"))
      (testing "the region is what the editor puts in"
        (let [d (parse/describe buffer :kinds (kinds/registry))
              out (generate/splice buffer (first (:steps d)) (:region r))]
          (is (empty? (:errors (parse/describe out :kinds (kinds/registry)))))
          (is (str/includes? out "unsaved")))))))

(deftest refusals
  (tu/with-temp-home
    (let [ctx (setup! [good])]
      (testing "no such block"
        (let [e (try (call "scenario.generate" {:path (str (:file ctx)) :name "nope" :ws "default" :session "G-1"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :generate/no-such-block (:type (ex-data e))))
          (is (re-find #"orders" (ex-message e)))))
      (testing "a block without prose"
        (spit (:file ctx) "### ai empty\n\n### wait w\n")
        (is (= :generate/empty-prose (:type (ex-data (try (call "scenario.generate" {:path (str (:file ctx)) :name "empty" :ws "default" :session "G-1"}) nil
                                                          (catch clojure.lang.ExceptionInfo e e)))))))
      (testing "an agent client is refused: it writes steps itself"
        (let [e (try (ops/call! "scenario.generate" {:conn (atom {:client "agent"})} {:path (str (:file ctx)) :name "orders" :ws "default" :session "G-1"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :agent/human-only (:type (ex-data e))))))
      (testing "the op kind does not offer it (no recursion from a step)"
        (is (not (contains? (:verbs (kinds/get-kind "op")) "scenario.generate")))))))

(deftest the-command-line-keeps-the-hooks-and-the-folder-boundary
  (let [c (:command generate/default-ai)
        has (fn [& xs] (boolean (some #{(vec xs)} (partition (count xs) 1 c))))]
    (testing "read-only tools, and those inside the folder only"
      (is (has "--tools" "Read,Grep,Glob"))
      (is (has "--allowedTools" "Read(./**)"))
      (is (has "--permission-mode" "dontAsk")))
    (testing "no flag that switches the machine's hooks or the transcript off (reus records the call)"
      (is (not-any? #{"--bare" "--safe-mode" "--restricted" "--no-session-persistence"} c)))
    (is (has "--model" "haiku"))
    (is (has "--output-format" "json"))))
