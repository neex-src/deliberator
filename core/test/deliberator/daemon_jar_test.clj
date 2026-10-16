(ns deliberator.daemon-jar-test
  "Layer 3 through the uberjar (`bb core:build` first): the real process, its lock, its idle
  shutdown and a request over its socket. The jar is run with the `java` of the test JVM."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def jar (io/file (System/getProperty "user.dir") "target" "deliberatord.jar"))

(defn- java [] (str (System/getProperty "java.home") "/bin/java"))

(defn- spawn
  "Starts the jar with `args`; -> {:proc :lines (queue of output lines, ::eof at the end)}."
  [& args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into [(java) "-jar" (str jar)] args)) (.redirectErrorStream true))
        proc (.start pb)
        q (LinkedBlockingQueue.)]
    (doto (Thread. ^Runnable (fn [] (with-open [r (io/reader (.getInputStream proc))]
                                      (doseq [l (line-seq r)] (.put q l))
                                      (.put q ::eof))))
      (.setDaemon true) (.start))
    {:proc proc :lines q}))

(defn- next-line [{:keys [^LinkedBlockingQueue lines]} ms] (.poll lines ms TimeUnit/MILLISECONDS))

(deftest the-uberjar-is-a-daemon
  (is (.isFile jar) "run `bb core:build` before the jar test")
  (when (.isFile jar)
    (let [v (spawn "--version")]
      (is (= "deliberatord 0.1.0-m1" (next-line v 60000)))
      (is (zero? (.waitFor ^Process (:proc v)))))
    (let [home (tu/short-home)
          d (spawn "--home" (str home) "--idle-minutes" "0.1")
          banner (next-line d 60000)
          ep (second (re-find #"listening on (\S+)" (str banner)))]
      (try
        (is (some? ep) (str "no banner: " banner))
        (when ep
          (is (str/starts-with? ep "unix://"))
          (is (= ep (str/trim (slurp (io/file home "daemon" "endpoint")))))
          (testing "a request over the socket"
            (let [c (tc/connect ep)]
              (try
                (let [r (:result (:terminal (tc/request c "hello" {:client "jar-test"})))]
                  (is (= "0.1.0-m1" (:version r)))
                  (is (= (.pid ^Process (:proc d)) (:pid r))))
                (is (= 48 (count (:result (:terminal (tc/request c "op.describe" {}))))))
                (tc/request c "session.start" {:key "J-1"})
                (let [f (io/file home "w.scn")
                      _ (spit f "### wait one\n# @for 100ms\n")
                      {:keys [events terminal]} (tc/request c "scenario.run" {:path (str f)})]
                  (is (= "ok" (get-in terminal [:result :status])) "all kinds are registered in the AOT jar")
                  (is (= "run.started" (:event (first events)))))
                (finally (tc/close! c)))))
          (testing "a second instance on the same home refuses with exit code 3"
            (let [d2 (spawn "--home" (str home))
                  out (next-line d2 60000)]
              (is (str/includes? (str out) "already running"))
              (is (= 3 (.waitFor ^Process (:proc d2))))))
          (testing "the first is still serving after the refusal"
            (let [c (tc/connect ep)]
              (try (is (true? (:ok (:terminal (tc/request c "hello" {}))))) (finally (tc/close! c)))))
          (testing "idle shutdown with --idle-minutes 0.1"
            (is (zero? (if (.waitFor ^Process (:proc d) 40 TimeUnit/SECONDS) (.exitValue ^Process (:proc d)) -1)))
            (is (not (.exists (io/file home "daemon" "endpoint"))))
            (is (not (.exists (io/file home "daemon" "deliberatord.sock"))))))
        (finally (.destroy ^Process (:proc d)) (tu/release-home! home))))))
