(ns deliberator.secrets-estate-test
  "`service.start --secrets-from local2` against the running estate (`bb estate:up --ws W`, `ESTATE_WS=W`, `DELIBERATOR_HOME`;
  without them a SKIP line). The environment `local2` is eu-west-1 of the same Floci, seeded by `bb estate:deploy`
  with an `estate/api-key` of its own.

  1. Without tokens nothing starts: the container still carries the local fake, the home holds no trace of the local2 value.
  2. One token is not enough (the second answer is another `confirm-required`); a reused, foreign or altered token is refused.
  3. Both tokens: `podman inspect` shows the local2 value in the container's environment, the label `deliberator.secrets-from`
     is set, and `grep -r <value> <home>` finds NOTHING: no raw blob (the persisted `GetSecretValue`/`GetParameter` responses are
     `<redacted>`), no meta, no log, no run, not even after the daemon's own `podman inspect` of that container was persisted.
  4. A plain `service.start` afterwards puts the local fakes back."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cognitect.aws.client.api :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]))

(defn- call [op args]
  (ops/call! op {:emit! (fn [_]) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args))

(defn- error-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ops/error-map e))))

(defn- secret-in
  "The api-key of environment `env-name`, read straight with an aws-api client: nothing here goes through `aws/invoke!`,
  so the test itself leaves no copy of the value in the home."
  [ws env-name]
  (:SecretString (aws/invoke (env/aws-client (env/load ws env-name) :secretsmanager)
                             {:op :GetSecretValue :request {:SecretId "estate/api-key"}})))

(defn- client [ws] (podman/client (get-in (runtime/read-config ws) [:podman :endpoint])))   ; no :ws -> nothing persisted by the test

(defn- container-env [ws]
  (into {} (for [e (get-in (podman/inspect (client ws) (str "deliberator-" ws "-service-a")) [:Config :Env])
                 :let [[k v] (str/split e #"=" 2)]]
             [k v])))

(defn- files-containing
  "Every file under `dir` whose bytes contain `needle`."
  [dir needle]
  (let [n (.getBytes ^String needle "UTF-8")
        hit? (fn [^bytes bs] (let [s (String. bs "ISO-8859-1")] (str/includes? s (String. n "ISO-8859-1"))))]
    (vec (for [^java.io.File f (file-seq (io/file dir)) :when (.isFile f)
               :when (try (hit? (java.nio.file.Files/readAllBytes (.toPath f))) (catch Exception _ false))]
           (str f)))))

(deftest two-tokens-then-real-values-and-no-trace
  (if-let [ws (fu/estate-ws)]
    (let [local (secret-in ws "local")
          local2 (secret-in ws "local2")
          start (fn [& {:as m}] (error-of #(call "service.start" (merge {:ws ws :name "a" :secrets-from "local2"} m))))]
      (is (and local local2 (not= local local2)) "the two environments hold different api keys")
      (try
        (testing "no confirmation: nothing is fetched, the container is untouched"
          (let [c0 (get (container-env ws) "API_KEY")
                e1 (start)]
            (is (= local c0))
            (is (= "confirm-required" (:type e1)))
            (is (= local (get (container-env ws) "API_KEY")))
            (is (empty? (files-containing (str (store/home)) local2)))))
        (testing "token 1 alone: the answer is the second confirm-required, still nothing started"
          (let [e1 (start)
                e2 (start :confirm (:token e1))]
            (is (= "confirm-required" (:type e2)))
            (is (= 2 (:step e2)))
            (is (not= (:token e1) (:token e2)))
            (is (not= (:summary e1) (:summary e2)))
            (is (= local (get (container-env ws) "API_KEY")))
            (testing "token 1 again (reused) and a token with a changed character are refused"
              (is (= "service/confirmation-refused" (:type (start :confirm (:token e1)))))
              (is (= "service/confirmation-refused" (:type (start :confirm (str (:token e2) "x"))))))
            (testing "a token of one environment is no good for another"
              (is (= "service/confirmation-refused" (:type (start :secrets-from "local" :confirm (:token e2))))))))
        (testing "both tokens: the value is in the container's environment and nowhere in the home"
          (let [e1 (start)
                e2 (start :confirm (:token e1))
                r (call "service.start" {:ws ws :name "a" :secrets-from "local2" :confirm (:token e2)})]
            (is (= "running" (:status r)))
            (let [env (container-env ws)]
              (is (= local2 (get env "API_KEY")) "podman inspect shows the real value")
              (is (not= local (get env "API_KEY"))))
            (is (= "local2" (get-in (podman/inspect (client ws) (str "deliberator-" ws "-service-a")) [:Config :Labels :deliberator.secrets-from])))
            (is (true? (:drift (first (call "service.status" {:ws ws :name "a"})))) "a later plain ensure would put the fakes back: drift says so")
            ;; the daemon's own inspect of that container, persisted by principle 1, must not hold the value either
            (podman/inspect (podman/client (get-in (runtime/read-config ws) [:podman :endpoint]) :ws ws) (str "deliberator-" ws "-service-a"))
            (call "runtime.status" {:ws ws})
            (is (= [] (files-containing (str (store/home)) local2)) "grep -r <value> <home> finds nothing")
            (testing "what was persisted instead: the redacted responses of the two calls"
              (let [metas (for [^java.io.File f (file-seq (io/file (str (store/home) "/workspaces/" ws "/raw")))
                                :when (str/ends-with? (.getName f) ".meta.edn")
                                :let [m (store/read-edn (.getPath f))]
                                :when (and (= :aws (:source m)) (= "local2" (:env m)))]
                            m)]
                (is (seq metas) "the calls are on record (metadata)")
                (is (some #(= "GetSecretValue" (:op %)) metas))
                (is (some #(= "GetParameter" (:op %)) metas))
                (doseq [m metas :when (= :response (:role m))]
                  (is (str/includes? (String. ^bytes (store/raw-get ws (:sha m)) "UTF-8") "<redacted>")
                      (str (:op m) " response is redacted")))))))
        (finally
          (call "service.start" {:ws ws :name "a"})))
      (testing "a plain start brings the local fake back"
        (is (= local (get (container-env ws) "API_KEY")))
        (is (= "running" (:status (first (call "service.status" {:ws ws :name "a"})))))))
    (fu/skip! (fu/current-test) "no ESTATE_WS (bb estate:up --ws W)")))
