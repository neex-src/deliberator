(ns deliberator.audit-daemon-jar-test
  "Audit of M1, layer 3 through the uberjar (`bb core:build` first): what a run looks like on disk when
  the daemon is stopped under it. SIGTERM must finalise the run and remove endpoint, socket and lock
  (`daemon/stop!` derefs a `Run` with a timeout, which `Run` does not implement: ClassCastException);
  SIGKILL cannot finalise anything, so the next daemon on the home should not keep claiming that run
  is running. Both were known bugs (F2, F4), fixed in piece 13."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.store :as store]
            [deliberator.test-client :as tc]
            [deliberator.test-util :as tu])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def jar (io/file (System/getProperty "user.dir") "target" "deliberatord.jar"))

(defn- spawn [& args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into [(str (System/getProperty "java.home") "/bin/java") "-jar" (str jar)] args))
             (.redirectErrorStream true))
        proc (.start pb)
        q (LinkedBlockingQueue.)]
    (doto (Thread. ^Runnable (fn [] (with-open [r (io/reader (.getInputStream proc))]
                                      (doseq [l (line-seq r)] (.put q l))
                                      (.put q ::eof))))
      (.setDaemon true) (.start))
    {:proc proc :lines q}))

(defn- banner-endpoint [{:keys [^LinkedBlockingQueue lines]}]
  (second (re-find #"listening on (\S+)" (str (.poll lines 60 TimeUnit/SECONDS)))))

(defn- home [_tag] (tu/short-home))

(defn- start-long-run!
  "Daemon + a 60 s wait run; returns {:d :ep :home :c :run-id}."
  [h]
  (let [d (spawn "--home" (str h) "--idle-minutes" "5")
        ep (banner-endpoint d)
        c (tc/connect ep)
        f (io/file h "long.scn")]
    (spit f "### wait long\n# @for 60s\n")
    (tc/request c "session.start" {:key "K-1"})
    (let [id (tc/send! c "scenario.run" {:path (str f)})
          started (tc/recv c 20000)]
      {:d d :ep ep :home h :c c :id id :run-id (:run-id started)})))

(defn- run-status [h run-id]
  (:status (store/read-edn (io/file h "workspaces" "default" "sessions" "K-1" "runs" run-id "run.edn"))))

(deftest sigterm-under-a-running-run-finalises-it
  (if-not (.isFile jar)
    (is false "run `bb core:build` before the jar tests")
    (let [h (home "t")
          {:keys [d c run-id]} (start-long-run! h)]
      (try
        (is (= :running (run-status h run-id)) "run.edn exists from the first moment")
        (.destroy ^Process (:proc d))
        (is (.waitFor ^Process (:proc d) 20 TimeUnit/SECONDS) "the daemon exits on SIGTERM")
        (is (contains? #{:cancelled :interrupted} (run-status h run-id)) (str "run.edn says " (run-status h run-id)))
        (is (.isFile (io/file h "workspaces" "default" "sessions" "K-1" "runs" run-id "result.txt")) "result.txt written")
        (is (not (.exists (io/file h "daemon" "endpoint"))) "endpoint removed")
        (is (not (.exists (io/file h "daemon" "deliberatord.sock"))) "socket removed")
        (finally (tc/close! c) (.destroyForcibly ^Process (:proc d)) (tu/release-home! h))))))

(deftest a-killed-daemons-run-is-not-running-for-ever
  (if-not (.isFile jar)
    (is false "run `bb core:build` before the jar tests")
    (let [h (home "k")
          {:keys [d c run-id]} (start-long-run! h)
          d2 (atom nil)]
      (try
        (.destroyForcibly ^Process (:proc d))
        (.waitFor ^Process (:proc d) 20 TimeUnit/SECONDS)
        (let [n (spawn "--home" (str h) "--idle-minutes" "5")
              ep (banner-endpoint n)]
          (reset! d2 n)
          (is (some? ep) "a new daemon starts on a home whose lock holder was killed")
          (when ep
            (let [c2 (tc/connect ep)
                  row (first (filter #(= run-id (:run-id %)) (:runs (:result (:terminal (tc/request c2 "runs.list" {:session "K-1"}))))))]
              (is (some? row))
              (is (not= "running" (:status row)) "nothing is running this run any more")
              (tc/close! c2))))
        (finally (tc/close! c) (.destroyForcibly ^Process (:proc d)) (some-> @d2 :proc ^Process (.destroyForcibly)) (tu/release-home! h))))))
