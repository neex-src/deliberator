(ns deliberator.ports-test
  "Per-workspace host ports (m3 05b): two workspaces of one home get different host ports for Floci, Postgres and their services
  without anyone pinning them. Uses real sockets on the host (an occupied port is a bound ServerSocket) and no container."
  (:require [clojure.test :refer [deftest is testing]]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.test-util :as tu])
  (:import (java.net InetAddress InetSocketAddress ServerSocket)))

(defn- bound
  "A ServerSocket on a free loopback port."
  []
  (doto (ServerSocket.) (.bind (InetSocketAddress. (InetAddress/getByName "127.0.0.1") 0))))

(defn- free-block
  "A port P such that P, P+100 and P+200 are free right now (so the tests do not depend on what runs on the sandbox)."
  []
  (let [free? (fn [p] (try (with-open [s (ServerSocket.)] (.bind s (InetSocketAddress. (InetAddress/getByName "127.0.0.1") (int p))) true)
                           (catch java.io.IOException _ false)))]
    (first (filter #(every? free? [% (+ % 100) (+ % 200)]) (range 30000 31000 7)))))

(def ^:private desc
  {:name "a" :kind :spring-boot :main "x.A" :port 8081 :jdwp 5005 :health "/h"
   :build {:cmd ["true"] :dir "." :artifact "target/a.jar" :java 21}})

(deftest the-second-workspace-gets-the-next-free-ports-of-the-home
  (tu/with-temp-home
    (let [fp (free-block) pg (+ fp 3)
          pinned {:containers {:floci {:ports {4566 fp}} :postgres {:ports {5432 pg}}}}]
      (store/write-edn! (runtime/config-path "first") pinned)
      (testing "claimed ports are the other workspaces'"
        (is (= #{fp pg} (runtime/claimed-ports "second")))
        (is (= #{} (runtime/claimed-ports "first"))))
      (testing "the default config of a workspace without runtime.edn avoids them, and a pinned one is never changed"
        (let [cfg (runtime/allocate-default-ports "second" {:containers {:floci {:ports {4566 fp}} :postgres {:ports {5432 pg}}}})]
          (is (= (+ fp 100) (get-in cfg [:containers :floci :ports 4566])) "the first workspace holds the preferred port: +100")
          (is (= (+ pg 100) (get-in cfg [:containers :postgres :ports 5432]))))
        (is (= pinned (store/read-edn (runtime/config-path "first")))))
      (testing "a port something listens on is skipped as well"
        (with-open [s (bound)]
          (let [busy (.getLocalPort s)
                cfg (runtime/allocate-default-ports "third" {:containers {:floci {:ports {4566 busy}}}})]
            (is (not= busy (get-in cfg [:containers :floci :ports 4566])))
            (is (= (+ busy 100) (get-in cfg [:containers :floci :ports 4566])))))))))

(deftest services-are-pinned-per-workspace-on-the-first-ensure
  (tu/with-temp-home
    (let [fp (free-block)
          c (#'runtime/connect-client "second" (runtime/default-config))
          a (assoc desc :port fp :jdwp (+ fp 3))]
      (description/write! "first" a)
      (description/write! "second" a)
      (store/write-edn! (runtime/config-path "first") {:containers {} :services {"a" {:ports {fp fp (+ fp 3) (+ fp 3)}}}})
      (store/write-edn! (runtime/config-path "second") {:containers {}})
      (let [cfg (#'runtime/allocate-service-ports! c "second" (runtime/read-config "second"))]
        (is (= {fp (+ fp 100) (+ fp 3) (+ fp 103)} (get-in cfg [:services "a" :ports])) "first holds fp and fp+3: second gets +100")
        (is (= cfg (runtime/read-config "second")) "written down: it stays")
        (is (= cfg (#'runtime/allocate-service-ports! c "second" cfg)) "a second ensure changes nothing"))
      (testing "the specs follow the pins, container ports stay"
        (let [specs (runtime/declared-containers "second" (runtime/read-config "second"))]
          (is (= {fp (+ fp 100) (+ fp 3) (+ fp 103)} (:ports (get specs :service/a))))))
      (testing "a pin the user wrote is kept"
        (store/write-edn! (runtime/config-path "third") {:containers {} :services {"a" {:ports {fp 39999}}}})
        (description/write! "third" a)
        (let [cfg (#'runtime/allocate-service-ports! c "third" (runtime/read-config "third"))]
          (is (= 39999 (get-in cfg [:services "a" :ports fp])))
          (is (not= 39999 (get-in cfg [:services "a" :ports (+ fp 3)]))))))))
