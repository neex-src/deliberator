(ns deliberator.podman-test-support
  "Shared by the podman and runtime tests: the endpoint under test and the loud skip when the real
  Podman is not reachable (nothing here ever fakes a pass)."
  (:require [clojure.test :as t]
            [deliberator.floci-util :as fu]
            [deliberator.podman :as podman])
  (:import (java.net ServerSocket)
           (java.util UUID)))

(defn endpoint
  "`$DELIBERATOR_TEST_PODMAN_ENDPOINT` or the user's rootless socket."
  []
  (or (System/getenv "DELIBERATOR_TEST_PODMAN_ENDPOINT") (podman/default-endpoint)))

(defn up? [] (podman/available? (podman/client (endpoint))))

(defmacro when-podman
  "Runs body when Podman answers; otherwise reports a :skip naming the current test."
  [& body]
  `(if (up?)
     (do ~@body)
     (fu/skip! (fu/current-test) "runtime down: bb runtime:up")))

(def alpine-image "docker.io/library/alpine:3.20")

(defn rand-id [] (subs (str (UUID/randomUUID)) 0 8))

(defn free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))
