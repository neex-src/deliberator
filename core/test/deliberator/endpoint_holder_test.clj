(ns deliberator.endpoint-holder-test
  "Review 2026-10-25 R31: a `local` environment whose endpoint port is published by a container that is not this
  workspace's runtime (a sandbox AWS account's Floci on 4566) talks to that emulator. `runtime/endpoint-holder` names the holder against a
  real container publishing a port (Podman only, pass A; a loud SKIP without it), and `env.check` turns it into a warning."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.ops :as ops]
            [deliberator.podman :as podman]
            [deliberator.podman-test-support :as sup :refer [when-podman]]
            [deliberator.runtime :as runtime]
            [deliberator.store :as store]
            [deliberator.test-util :as tu]))

(deftest the-holder-of-a-published-port-is-named-and-env-check-warns
  (when-podman
   (tu/with-temp-home
     (let [c (podman/client (sup/endpoint))
           port (sup/free-port)
           ws "holder"
           other-ws "someone-else"
           cname (str "deliberator-test-holder-" (sup/rand-id))
           _ (when-not (podman/image-exists? c sup/alpine-image) (podman/pull! c sup/alpine-image))
           {:keys [id]} (podman/create! c {"name" cname "image" sup/alpine-image "command" ["sleep" "60"]
                                           "labels" {"deliberator.workspace" other-ws "deliberator.role" "floci"}
                                           "portmappings" [{"host_port" port "container_port" 4566}]})]
       (try
         (podman/start! c id)
         (ops/call! "workspace.open" {} {:ws ws})
         (store/write-edn! (runtime/config-path ws) (assoc-in (runtime/default-config) [:podman :endpoint] (sup/endpoint)))
         (testing "endpoint-holder"
           (is (= {:port port :container cname :ours? false} (runtime/endpoint-holder ws (str "http://localhost:" port))))
           (is (= {:port port :container cname :ours? false} (runtime/endpoint-holder ws (str "http://127.0.0.1:" port "/"))))
           (is (= {:port port :container cname :ours? true} (runtime/endpoint-holder other-ws (str "http://localhost:" port)))
               "the workspace whose label the container carries owns it")
           (is (nil? (runtime/endpoint-holder ws (str "http://localhost:" (sup/free-port)))) "a port nobody publishes")
           (is (nil? (runtime/endpoint-holder ws (str "http://floci.internal:" port))) "only local hosts are looked at")
           (is (nil? (runtime/endpoint-holder ws "not a url"))))
         (testing "env.check of a local environment pointed at that port warns and names the container"
           (env/save! ws (assoc-in (env/load ws "local") [:aws :endpoint] (str "http://localhost:" port)))
           (with-redefs [aws/check (fn [_ e] {:ok? true :account (get-in e [:aws :account]) :expected (get-in e [:aws :account])})]
             (let [r (ops/call! "env.check" {} {:ws ws :name "local"})]
               (is (true? (:ok r)))
               (is (= cname (:endpoint-held-by r)))
               (is (str/includes? (:warning r) (str "port " port)))
               (is (str/includes? (:warning r) cname))
               (is (str/includes? (:warning r) "not this workspace's runtime")))
             (env/save! ws (assoc-in (env/load ws "local") [:aws :endpoint] (str "http://localhost:" (sup/free-port))))
             (let [r (ops/call! "env.check" {} {:ws ws :name "local"})]
               (is (nil? (:warning r)) "no holder, no warning")
               (is (not (contains? r :endpoint-held-by))))))
         (finally (podman/remove! c id)))))))
