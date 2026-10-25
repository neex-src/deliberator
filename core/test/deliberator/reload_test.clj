(ns deliberator.reload-test
  "Service reload without the estate: the staging sha diff, the HotswapAgent log scan, the compiler's error lines, the
  hotswap description (kind, derived reload command, exploded role, command line, mounts), the JDWP client against a fake
  JDWP server. The live story is `reload_estate_test` (pass B)."
  (:require [clojure.edn :as edn]
            [deliberator.ops]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.jdwp :as jdwp]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.service.reload :as reload]
            [deliberator.service.staging :as staging]
            [deliberator.test-util :as tu])
  (:import (java.io DataInputStream DataOutputStream)
           (java.net ServerSocket)
           (java.time Instant)))

(defn- write! [dir rel s] (let [f (io/file dir rel)] (io/make-parents f) (spit f s) f))

(deftest staging-copies-only-what-differs
  (let [root (tu/temp-home) seed (io/file root "seed") stage (io/file root "stage")]
    (write! seed "estate/a/Api.class" "one") (write! seed "estate/a/Pub.class" "pub") (write! seed "application.properties" "p=1")
    (testing "first sync: everything is new"
      (is (= #{"estate/a/Api.class" "estate/a/Pub.class" "application.properties"} (set (:changed (staging/sync! seed stage)))))
      (is (= (staging/sha-map seed) (staging/sha-map stage))))
    (testing "a rewritten file with the same bytes is no change, and is not touched"
      (let [f (io/file stage "estate/a/Pub.class") before (.lastModified f)]
        (Thread/sleep 20)
        (write! seed "estate/a/Pub.class" "pub")
        (is (= {:changed [] :removed []} (staging/sync! seed stage)))
        (is (= before (.lastModified f)))))
    (testing "one changed, one vanished, one new"
      (write! seed "estate/a/Api.class" "two") (write! seed "estate/a/New.class" "n") (.delete (io/file seed "application.properties"))
      (let [d (staging/sync! seed stage)]
        (is (= ["estate/a/Api.class" "estate/a/New.class"] (sort (:changed d))))
        (is (= ["application.properties"] (:removed d)))
        (is (= "two" (slurp (io/file stage "estate/a/Api.class"))))
        (is (not (.exists (io/file stage "application.properties"))))
        (is (empty? (filter #(str/ends-with? (.getName %) staging/tmp-suffix) (file-seq stage))) "no temp file is left")))
    (testing "an unbuilt module never wipes the staging dir"
      (is (= :service/not-built (:type (ex-data (try (staging/sync! (io/file root "nope") stage) (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest agent-lines-are-read
  (let [at (fn [s] (Instant/parse (str "2026-10-16T12:00:" s "Z")))
        ev (fn [s t] {:at (at s) :stream :stdout :text t})
        evs [(ev "01.000" "2026 INFO other line")
             (ev "02.000" "HOTSWAP AGENT: 12:00:02.000 RELOAD (org.hotswap.agent.config.PluginManager) - Reloading classes [estate.a.Api, estate.a.Pub] (autoHotswap)")
             (ev "03.500" "HOTSWAP AGENT: 12:00:03.500 INFO (org.hotswap.agent.plugin.spring.reload.SpringBeanReload) - add new spring bean 'fresh' into X")]
        a (reload/scan-agent evs)]
    (is (= ["estate.a.Api" "estate.a.Pub"] (:redefined a)))
    (is (= ["fresh"] (:beans a)))
    (is (= (at "03.500") (:last-at a)))
    (is (nil? (:error a)))
    (is (= 2 (count (:lines a))))
    (is (not (:settled a)) "a new bean is a change: the long quiet applies")
    (is (some? (:error (reload/scan-agent [(ev "04.000" "HOTSWAP AGENT: 12:00:04.000 ERROR (org.hotswap.agent.config.PluginManager) - boom")]))))))

(deftest spring-with-nothing-to-recreate-settles-the-reload
  ;; the lines of a body change on the estate (2026-10-25): RELOAD, then only "no change, ignore reloading" (B10)
  (let [ev (fn [s t] {:at (Instant/parse (str "2026-10-25T08:59:" s "Z")) :stream :stdout :text (str "HOTSWAP AGENT: " t)})
        reload (ev "37.036" "08:59:37.036 RELOAD (org.hotswap.agent.config.PluginManager) - Reloading classes [estate.a.Api] (autoHotswap)")
        none (fn [s] (ev s "INFO (org.hotswap.agent.plugin.spring.reload.SpringBeanReload) - no change, ignore reloading 'org.springframework.beans.factory.support.DefaultListableBeanFactory@57b63253'"))
        bean (ev "38.700" "INFO (org.hotswap.agent.plugin.spring.reload.SpringBeanReload) - reload spring bean 'api'")]
    (is (not (:settled (reload/scan-agent [reload]))) "Spring has not answered yet")
    (is (:settled (reload/scan-agent [reload (none "38.662") (none "38.785")])))
    (is (not (:settled (reload/scan-agent [reload (none "38.662") bean]))) "a bean was re-created")
    (is (not (:settled (reload/scan-agent [(none "36.000") reload]))) "an answer to an earlier RELOAD does not count")
    (is (not (:settled (reload/scan-agent [(none "38.662")]))) "no RELOAD at all")))

(deftest compiler-lines
  (let [r (reload/compile-errors ["[ERROR] COMPILATION ERROR : "
                                  "[ERROR] /w/Api.java:[22,30] ';' expected"
                                  "[ERROR] /w/B.java:[3,4] cannot find symbol"
                                  "[ERROR]   symbol:   class Foo"
                                  "[ERROR] /w/Api.java:[22,30] ';' expected"
                                  "[ERROR] After correcting the problems, you can resume the build with the command"
                                  "[ERROR]   mvn <args> -rf :service-a"])]
    (is (= ["/w/Api.java:[22,30] ';' expected" "/w/B.java:[3,4] cannot find symbol" "  symbol:   class Foo"] (:lines r)))
    (is (= [{:file "/w/Api.java" :line 22 :col 30 :message "';' expected"} {:file "/w/B.java" :line 3 :col 4 :message "cannot find symbol"}]
           (:errors r)))))

;; description ------------------------------------------------------------------------------------

(def base {:name "a" :kind :spring-boot :port 8081 :jdwp 5005 :main "estate.a.ServiceA" :repo nil
           :build {:cmd ["mvn" "-q" "-DskipTests" "-pl" "service-a" "-am" "package"] :dir ".." :artifact "target/a.jar" :java 21}})

(deftest image-kind-follows-the-image
  (is (= :hotswap (description/image-kind base)))
  (is (= :hotswap (description/image-kind (assoc base :image description/hotswap-image))))
  (is (= :plain (description/image-kind (assoc base :image description/plain-image))))
  (is (= :plain (description/image-kind (assoc base :image-kind :plain))))
  (testing "the hotswap image needs a main class"
    (is (= :service/invalid (:type (ex-data (try (description/validate (dissoc base :main)) (catch clojure.lang.ExceptionInfo e e))))))
    (is (some? (description/validate (assoc (dissoc base :main) :image description/plain-image)))))
  (testing "image.edn and the core default name the same image"
    (is (= description/hotswap-image (:image (edn/read-string (slurp "../estate/images/jbr21/image.edn")))))))

(deftest reload-command-is-derived-from-the-build
  (let [rc (description/reload-config base)]
    (is (= ["mvn" "-o" "-q" "-DskipTests" "-pl" "service-a" "-am" "compile"] (:cmd rc)))
    (is (= ".." (:dir rc)))
    (is (= ["dependency:copy-dependencies" "-DincludeScope=runtime"] (:deps-args rc))))
  (is (= ["x"] (:cmd (description/reload-config (assoc base :reload {:cmd ["x"] :modules ["common"]})))))
  (is (= ["common"] (:modules (description/reload-config (assoc base :reload {:modules ["common"]}))))))

(deftest hotswap-role
  (let [root (tu/temp-home) ws "hw"
        mod (io/file root "repo" "service-a")
        _ (.mkdirs (io/file mod "target" "classes")) _ (.mkdirs (io/file root "repo" "common" "target" "classes"))
        _ (write! mod "target/dependency/spring.jar" "jar")
        desc (assoc base :module (str mod) :reload {:modules ["common"]} :java-opts ["-Xmx256m"]
                    :taskdef {:environment {"E" "1"}})]
    (binding [deliberator.store/*home* (io/file root "home")]
      (let [spec (description/->spec ws desc)
            d (runtime/desired ws {:network "n"} :service/a spec)]
        (is (nil? (:jar spec)))
        (is (= description/hotswap-image (:image d)))
        (is (= ["/app/classes" "/app/classes-common"] (map :mount (get-in spec [:exploded :dirs]))))
        (testing "command: enhanced redefinition, the agent, opens, JDWP, classpath with the staged dirs first"
          (let [cmd (:command d)]
            (is (= "java" (first cmd)))
            (is (some #{"-XX:+AllowEnhancedClassRedefinition"} cmd))
            (is (some #{"-javaagent:/opt/hotswap/hotswap-agent.jar=autoHotswap=true"} cmd))
            (is (some #{"--add-opens=java.base/java.lang=ALL-UNNAMED"} cmd))
            (is (some #{"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"} cmd))
            (is (some #{"-Xmx256m"} cmd))
            (is (= ["-cp" "/opt/hotswap/conf:/app/classes:/app/classes-common:/app/dependency/*" "estate.a.ServiceA"] (take-last 3 cmd)))))
        (testing "mounts: staging rw, dependency ro"
          (let [m (into {} (map (juxt :destination identity)) (:mounts d))]
            (is (some #{"rw"} (:options (m "/app/classes"))))
            (is (str/ends-with? (:source (m "/app/classes")) "/services/a/classes"))
            (is (str/ends-with? (:source (m "/app/classes-common")) "/services/a/classes-common"))
            (is (= (str mod "/target/dependency") (:source (m "/app/dependency"))))
            (is (some #{"ro"} (:options (m "/app/dependency"))))
            (is (nil? (m "/app/app.jar")))))
        (testing "the classpath changing recreates the container; the staged classes do not"
          (write! mod "target/dependency/more.jar" "jar2")
          (is (not= (:hash d) (:hash (runtime/desired ws {:network "n"} :service/a (description/->spec ws desc)))))))
      (testing "a plain description keeps the jar role"
        (let [plain (description/->spec ws (assoc desc :image description/plain-image))]
          (is (some? (:jar plain))) (is (nil? (:exploded plain))))))))

;; jdwp -------------------------------------------------------------------------------------------

(defn- fake-jdwp
  "A JDWP server on a free port: knows class `Lestate/a/Api;` (id 77), answers Redefine with `redefine-error`."
  [redefine-error]
  (let [ss (ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        got (promise)]
    (future
      (try
        (with-open [s (.accept ss)]
          (let [in (DataInputStream. (.getInputStream s)) out (DataOutputStream. (.getOutputStream s))]
            (.readFully in (byte-array 14)) (.write out (.getBytes "JDWP-Handshake")) (.flush out)
            (loop []
              (let [len (.readInt in) id (.readInt in) _ (.readByte in) set (.readUnsignedByte in) cmd (.readUnsignedByte in)
                    data (byte-array (- len 11)) _ (.readFully in data)
                    reply (fn [err body] (.writeInt out (+ 11 (alength ^bytes body))) (.writeInt out id) (.writeByte out 0x80)
                            (.writeShort out err) (.write out ^bytes body) (.flush out))
                    ints (fn [& xs] (let [b (java.io.ByteArrayOutputStream.) o (DataOutputStream. b)] (doseq [x xs] (.writeInt o x)) (.toByteArray b)))]
                (case [set cmd]
                  [1 7] (reply 0 (ints 8 8 8 8 8))
                  [1 2] (let [sig (String. data 4 (- (alength data) 4) "UTF-8")]
                          (if (= sig "Lestate/a/Api;")
                            (let [b (java.io.ByteArrayOutputStream.) o (DataOutputStream. b)]
                              (.writeInt o 1) (.writeByte o 1) (.writeLong o 77) (.writeInt o 4) (reply 0 (.toByteArray b)))
                            (reply 0 (ints 0))))
                  [1 18] (do (deliver got (vec data)) (reply redefine-error (byte-array 0))))
                (recur)))))
        (catch Exception _ nil)))
    {:port (.getLocalPort ss) :got got :close #(.close ss)}))

(deftest jdwp-client-redefines-loaded-classes
  (let [root (tu/temp-home)]
    (write! root "estate/a/Api.class" "bytes") (write! root "estate/a/NotLoaded.class" "x")
    (testing "accepted"
      (let [{:keys [port got close]} (fake-jdwp 0)
            r (jdwp/redefine! "127.0.0.1" port (str root) ["estate/a/Api.class" "estate/a/NotLoaded.class"])]
        (is (:ok r))
        (is (= ["Lestate/a/Api;"] (:redefined r)))
        (is (= ["estate/a/NotLoaded.class"] (:skipped r)))
        (is (= 1 (nth (deref got 2000 nil) 3)) "one class in the RedefineClasses call")
        (close)))
    (testing "refused by the JVM"
      (let [{:keys [port close]} (fake-jdwp 64)
            r (jdwp/redefine! "127.0.0.1" port (str root) ["estate/a/Api.class"])]
        (is (false? (:ok r)))
        (is (= :schema-change-not-implemented (:error r)))
        (close)))
    (testing "nobody listens (or a debugger holds the port): a map, not a throw"
      (let [{:keys [port close]} (fake-jdwp 0)]
        (close)
        (let [r (jdwp/redefine! "127.0.0.1" port (str root) ["estate/a/Api.class"])]
          (is (false? (:ok r))) (is (string? (:exception r))))))))

(deftest reload-refuses-what-cannot-be-reloaded
  (tu/with-temp-home
    (let [call (fn [op args] (try (deliberator.ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)
                                  (catch clojure.lang.ExceptionInfo e (deliberator.ops/error-map e))))]
      (call "workspace.open" {:ws "w"})
      (is (= "workspace/not-found" (:type (call "service.reload" {:ws "nope" :name "a"}))))
      (is (= "service/not-found" (:type (call "service.reload" {:ws "w" :name "a"}))))
      (description/write! "w" (assoc base :module "/m/a"))
      (description/write! "w" {:name "l" :kind :node-lambda :build {:cmd ["npm" "ci"]} :module "/m/l"})
      (is (= "service/not-runnable" (:type (call "service.reload" {:ws "w" :name "l"}))))
      (is (= "service/not-running" (:type (call "service.reload" {:ws "w" :name "a"})))
          "no container of A is running in this workspace"))))
