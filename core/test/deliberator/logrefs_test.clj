(ns deliberator.logrefs-test
  "`logs.refs`: the spans of a log that name code, resolved to files of the attached checkouts. The spans are pure; the
  resolution runs on real directories (one a git repository with an ignored build directory), real service descriptions, and logs
  written by the real `logs.fetch` (so the file names are the daemon's own, which is how the service of a log is found)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.env :as env]
            [deliberator.git :as git]
            [deliberator.logrefs :as logrefs]
            [deliberator.ops :as ops]
            [deliberator.service.description :as description]
            [deliberator.session :as session]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(defn- call [op args] (ops/call! op {} args))

(defn- thrown-type [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- put! [dir rel text]
  (let [f (io/file (str dir) rel)] (io/make-parents f) (spit f text) (str f)))

(defn- kinds [line] (mapv :kind (logrefs/spans line)))
(defn- text-of [line s] (subs line (:from s) (:to s)))

;; --- the spans ----------------------------------------------------------------------------------

(deftest spans-of-one-line
  (testing "a frame: the span is File.java:15, the class and the method are kept apart"
    (let [l "\tat com.acme.billing.Pricing.firstInvoiceCents(Pricing.java:15)"
          [s :as ss] (logrefs/spans l)]
      (is (= 1 (count ss)))
      (is (= {:kind :frame :class "com.acme.billing.Pricing" :method "firstInvoiceCents" :file-name "Pricing.java" :line 15} (select-keys s [:kind :class :method :file-name :line])))
      (is (= "Pricing.java:15" (text-of l s)))))
  (testing "a frame with a loader or module prefix, an inner class, a lambda, a library suffix"
    (is (= ["frame"] (map name (kinds "\tat app//com.acme.Pricing$Inner.lambda$run$0(Pricing.java:99) ~[classes/:na]"))))
    (is (= ["frame"] (map name (kinds "\tat java.base/java.util.ArrayList.get(ArrayList.java:427)"))))
    (is (= ["frame"] (map name (kinds "2026-11-05T10:00:00Z out \tat com.acme.Pricing.<init>(Pricing.java:7)"))) "a mirrored line"))
  (testing "a frame with no line is no span"
    (is (empty? (kinds "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)")))
    (is (empty? (kinds "\tat java.base/java.lang.Thread.run(Native Method)"))))
  (testing "the logback token"
    (let [l "2026-11-05T10:00:00.123+01:00 ERROR billing [c1] c.b.billing.Pricing:15 - boom"
          [s] (logrefs/spans l)]
      (is (= [:logger] (kinds l)))
      (is (= "c.b.billing.Pricing:15" (text-of l s)))
      (is (= 15 (:line s)))))
  (testing "plain text is no span; host:port - is the logback shape, the file system decides (see the resolution tests)"
    (is (= [:logger] (kinds "connected to localhost:5432 - ok")))
    (is (empty? (kinds "no position here"))))
  (testing "throwable headers: bare, Caused by, Suppressed, Exception in thread; a one-word line is no class"
    (let [a "java.lang.IllegalStateException: boom"
          b "Caused by: com.acme.PricingException: no price for LEGACY"
          c "Exception in thread \"main\" com.acme.Boot$Failure: x"
          d "\tSuppressed: com.acme.Other: y"]
      (is (= ["java.lang.IllegalStateException" "com.acme.PricingException" "com.acme.Boot$Failure" "com.acme.Other"]
             (mapv (comp :class first logrefs/spans) [a b c d])))
      (is (= "com.acme.PricingException" (text-of b (first (logrefs/spans b)))))
      (is (empty? (kinds "Error: boom")))
      (is (= :exception (:kind (first (logrefs/spans "2026-11-05T10:00:00Z err java.lang.IllegalStateException: boom")))) "a mirrored line")))
  (testing "Node frames, also inside the JSON stack of a Lambda error"
    (let [a "    at Runtime.handler (file:///var/task/index.mjs:42:7)"
          b "    at /var/task/lib/aws.mjs:3:11"
          c "{\"errorType\":\"Error\",\"stack\":[\"Error: boom\",\"    at Runtime.handler (file:///var/task/index.mjs:7:11)\",\"    at async Runtime.handleOnceNonStreaming (file:///var/runtime/index.mjs:1205:29)\"]}"]
      (is (= "/var/task/index.mjs:42:7" (text-of a (first (logrefs/spans a)))))
      (is (= {:path "/var/task/index.mjs" :line 42 :col 7} (select-keys (first (logrefs/spans a)) [:path :line :col])))
      (is (= "/var/task/lib/aws.mjs:3:11" (text-of b (first (logrefs/spans b)))))
      (is (= ["/var/task/index.mjs:7:11" "/var/runtime/index.mjs:1205:29"] (mapv #(text-of c %) (logrefs/spans c))))
      (is (empty? (kinds "    at node:internal/modules/cjs/loader:1228:14")))
      (is (empty? (kinds "see /var/task/index.mjs:42:7 for details")) "no `at` before it: not a frame")))
  (testing "a pathological token does not overflow the stack"
    (is (vector? (logrefs/spans (str "at " (str/join "." (repeat 20000 "ab")) "(X.java:1) " (str/join "." (repeat 20000 "ab")) ":12 - x"))))))

;; --- the resolution -----------------------------------------------------------------------------

(def pricing "package com.acme;\n\npublic class Pricing {\n}\n")

(defn- setup!
  "Two repositories with `com.acme.Pricing` (billing-repo is a git repository whose `target/` is ignored), a Spring service `billing`
  in billing-repo, node-lambda services `thrower` (/var/task) and `other` (/app) in lambdas. Returns the directories."
  []
  (let [base (io/file (str (store/home)) "src")
        billing (io/file base "billing-repo")
        other (io/file base "other-repo")
        lambdas (io/file base "lambdas")]
    (put! billing "src/main/java/com/acme/Pricing.java" pricing)
    (put! billing "src/main/java/com/acme/PricingException.java" "package com.acme;\npublic class PricingException extends RuntimeException {}\n")
    (put! billing "src/test/java/com/acme/Pricing.java" pricing)
    (put! billing "target/classes/com/acme/Pricing.java" "package com.ignored;\npublic class Pricing {}\n")
    (put! billing ".gitignore" "target/\n")
    (git/run billing ["init" "-q"])
    (put! other "src/main/java/com/acme/Pricing.java" pricing)
    (put! other "src/main/java/com/zeta/Pricing.java" "package com.zeta;\n\npublic class Pricing {\n}\n")
    (put! lambdas "functions/thrower/index.mjs" "export const handler = async () => { throw new Error('boom'); };\n")
    (put! lambdas "functions/thrower/lib/aws.mjs" "export const x = 1;\n")
    (put! lambdas "functions/other/index.mjs" "export const handler = async () => 1;\n")
    (doseq [d [billing other lambdas]] (call "repo.attach" {:path (str d)}))
    (description/write! "default" {:name "billing" :kind :spring-boot :repo "billing-repo" :module "." :port 8082
                                   :image "docker.io/library/eclipse-temurin:21-jre"})
    (description/write! "default" {:name "thrower" :kind :node-lambda :repo "lambdas" :module "functions/thrower"})
    (description/write! "default" {:name "other" :kind :node-lambda :repo "lambdas" :module "functions/other" :task-root "/app"})
    {:billing billing :other other :lambdas lambdas}))

(defn- fetched!
  "A log the way the daemon writes it: `logs.fetch` of the file target `log/<target>` holding `text`; -> the session file's path."
  [target text]
  (let [src (put! (store/home) (str "origin/" (str/replace target "/" "-") ".txt") text)
        e (env/load "default" "local")]
    (env/save! "default" (assoc-in e [:targets (keyword target)] {:file src}))
    (:path (call "logs.fetch" {:target target}))))

(defn- refs [path] (:refs (call "logs.refs" {:path path})))
(defn- repos [r] (mapv :repo (:targets r)))
(defn- by-text [rs t] (first (filter #(= t (:text %)) rs)))

(def spring-log
  (str/join "\n"
            ["2026-11-05T10:00:00.100+01:00 INFO  billing [c1] c.acme.Pricing:3 - first invoice"
             "2026-11-05T10:00:00.200+01:00 ERROR billing [c1] c.a.Pricing:3 - boom"
             "com.acme.PricingException: no price for LEGACY"
             "\tat com.acme.Pricing.firstInvoiceCents(Pricing.java:3)"
             "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)"
             "\tat java.base/java.lang.Thread.run(Thread.java:1583)"
             "Caused by: com.acme.PricingException: inner"
             "\t... 12 common frames omitted"
             "2026-11-05T10:00:00.300+01:00 WARN  billing [c1] c.z.Pricing:3 - another package"
             "2026-11-05T10:00:00.400+01:00 WARN  billing [c1] c.y.Pricing:3 - a package nobody has"
             "connected to localhost:5432 - ok"
             "2026-11-05T10:00:00.500+01:00 INFO  billing [] c.acme.Pricing:654 - Started Billing in 5.1 seconds"
             ""]))

(deftest the-service-repository-is-the-only-target
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (let [log (fetched! "log/service-billing" spring-log)
          r (call "logs.refs" {:path log})
          rs (:refs r)]
      (is (= "billing" (:service r)))
      (is (= 12 (:lines r)) "lines read")
      (testing "a frame: the file of the service's repository, main before test; the other repository's copy is not offered"
        (let [f (by-text rs "Pricing.java:3")]
          (is (= 4 (:n f)))
          (is (= "frame" (:kind f)))
          (is (= 3 (:line f)))
          (is (= ["billing-repo" "billing-repo"] (repos f)))
          (is (= ["src/main/java/com/acme/Pricing.java" "src/test/java/com/acme/Pricing.java"] (mapv :rel (:targets f))))
          (is (str/ends-with? (:file (first (:targets f))) "/billing-repo/src/main/java/com/acme/Pricing.java"))))
      (testing "the git repository's ignored target/ is not a source"
        (is (not-any? #(str/includes? (:file %) "/billing-repo/target/") (mapcat :targets rs))))
      (testing "logger:line, abbreviated as logback does"
        (is (= [1 2 9] (map :n (filter #(= "logger" (:kind %)) rs))))
        (is (= ["c.acme.Pricing:3" "c.a.Pricing:3" "c.z.Pricing:3"] (map :text (filter #(= "logger" (:kind %)) rs))))
        (is (= ["other-repo"] (repos (by-text rs "c.z.Pricing:3"))) "com.zeta.Pricing is only in the other repository: the fallback to the holders")
        (is (nil? (by-text rs "c.y.Pricing:3")) "no com.y.Pricing anywhere")
        (is (nil? (by-text rs "localhost:5432")) "host:port is the logback shape, but no file localhost.java exists"))
      (testing "the exception class and the Caused by class, no line"
        (let [e (filter #(= "exception" (:kind %)) rs)]
          (is (= [3 7] (map :n e)))
          (is (every? nil? (map :line e)))
          (is (= ["com.acme.PricingException"] (distinct (map :class e))))
          (is (str/ends-with? (:file (first (:targets (first e)))) "/billing-repo/src/main/java/com/acme/PricingException.java"))))
      (testing "a class in no attached repository is no ref; the count says how many"
        (is (not-any? #(str/includes? (:text %) "DispatcherServlet") rs))
        (is (not-any? #(str/includes? (:text %) "Thread.java") rs))
        (is (= 5 (:unresolved r)) "DispatcherServlet, Thread, c.y.Pricing, localhost, and Pricing:654 (the file has 4 lines)"))
      (testing "the span is where the text is"
        (doseq [x rs :let [line (nth (str/split spring-log #"\n" -1) (dec (:n x)))]]
          (is (= (:text x) (subs line (:from x) (:to x)))))))))

(deftest an-unattributed-log-offers-every-holder
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (let [log (fetched! "log/app" spring-log)
          r (call "logs.refs" {:path log})
          f (by-text (:refs r) "Pricing.java:3")]
      (is (nil? (:service r)))
      (is (= ["billing-repo" "billing-repo" "other-repo"] (repos f)) "attach order, main before test inside a repository")
      (testing "the other package's logger resolves to the repository that has it"
        (let [z (by-text (:refs r) "c.z.Pricing:3")]
          (is (= ["other-repo"] (repos z)))
          (is (= "src/main/java/com/zeta/Pricing.java" (:rel (first (:targets z))))))))))

(deftest a-log-of-another-repository-falls-back-to-all-holders
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (description/write! "default" {:name "elsewhere" :kind :spring-boot :repo "other-repo" :module "." :port 8083
                                   :image "docker.io/library/eclipse-temurin:21-jre"})
    (let [log (fetched! "log/service-elsewhere" "\tat com.acme.PricingException.<init>(PricingException.java:2)\n\tat com.acme.Pricing.f(Pricing.java:3)\n")
          rs (refs log)]
      (is (= ["other-repo"] (repos (by-text rs "Pricing.java:3"))) "other-repo's own copy wins for its own service")
      (is (= ["billing-repo"] (repos (by-text rs "PricingException.java:2"))) "only billing-repo holds that class: the fallback to the holders"))))

(deftest the-mirror-of-the-runtime-is-read-through-its-prefix
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (let [f (put! (store/home) "workspaces/default/logs/services/service-billing/2026-11-05.log"
                  (str "2026-11-05T10:00:00.1Z out 2026-11-05T10:00:00.100+01:00 ERROR billing [c1] c.acme.Pricing:3 - x\n"
                       "2026-11-05T10:00:00.2Z err \tat com.acme.Pricing.f(Pricing.java:3)\n"))
          r (call "logs.refs" {:path f})]
      (is (= "billing" (:service r)))
      (is (= ["logger" "frame"] (map :kind (:refs r))))
      (is (every? #(= ["billing-repo" "billing-repo"] (repos %)) (:refs r))))))

(deftest node-frames-map-through-the-description
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (let [stack (str "{\"errorType\":\"Error\",\"errorMessage\":\"boom\",\"stack\":[\"Error: boom\","
                     "\"    at Runtime.handler (file:///var/task/index.mjs:1:38)\","
                     "\"    at file:///var/task/lib/aws.mjs:1:1\","
                     "\"    at /var/task/missing.mjs:3:3\","
                     "\"    at async Runtime.handleOnceNonStreaming (file:///var/runtime/index.mjs:1205:29)\"]}\n")
          log (fetched! "log/service-thrower" stack)
          r (call "logs.refs" {:path log})
          rs (:refs r)]
      (is (= "thrower" (:service r)))
      (is (= ["/var/task/index.mjs:1:38" "/var/task/lib/aws.mjs:1:1"] (map :text rs)) "the file must exist in the module; /var/runtime is nobody's")
      (is (= ["node" "node"] (map :kind rs)))
      (is (= [1 1] (map :line rs)))
      (is (= [38 1] (map :col rs)))
      (is (= ["functions/thrower/index.mjs" "functions/thrower/lib/aws.mjs"] (map (comp :rel first :targets) rs)))
      (is (= ["lambdas"] (distinct (mapcat repos rs))))
      (is (= 2 (:unresolved r))))
    (testing "a log nobody owns tries every node-lambda: the frame is a target of each module that has the file (task-root per description)"
      (let [log (fetched! "log/app" "    at Runtime.handler (file:///var/task/index.mjs:1:1)\n    at Object.<anonymous> (/app/index.mjs:1:1)\n")
            rs (refs log)]
        (is (= [["functions/thrower/index.mjs"] ["functions/other/index.mjs"]] (map #(map :rel (:targets %)) rs)))))))

(deftest lines-and-errors
  (tu/with-temp-home
    (session/start! "default" "S1")
    (setup!)
    (testing "CRLF and CR end lines as an editor counts them"
      (let [log (fetched! "log/crlf" "one\r\n\tat com.acme.Pricing.f(Pricing.java:3)\r\ntwo\r\tat com.acme.Pricing.g(Pricing.java:3)\r")
            r (call "logs.refs" {:path log})]
        (is (= [2 4] (map :n (:refs r))))
        (is (= 4 (:lines r)))))
    (testing "a relative path is the session's logs/, a missing file is a typed error"
      (let [log (fetched! "log/rel" "\tat com.acme.Pricing.f(Pricing.java:3)\n")]
        (is (= 1 (count (refs (.getName (io/file log))))))
        (is (= :logs/not-found (thrown-type #(call "logs.refs" {:path "nope.log"}))))))
    (testing "no attached repository: nothing resolves, nothing breaks"
      (call "repo.detach" {:name "billing-repo"})
      (call "repo.detach" {:name "other-repo"})
      (call "repo.detach" {:name "lambdas"})
      (logrefs/clear-cache!)
      (let [log (fetched! "log/none" spring-log)
            r (call "logs.refs" {:path log})]
        (is (empty? (:refs r)))
        (is (pos? (:unresolved r)))))))
