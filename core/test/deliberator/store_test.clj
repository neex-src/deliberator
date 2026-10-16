(ns deliberator.store-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.store :as store]
            [deliberator.test-util :as t])
  (:import (java.nio.file Files)))

(deftest home-resolution
  (testing "the binding wins and paths hang under workspaces/<ws>"
    (t/with-temp-home
      (is (= (.toPath store/*home*) (store/home)))
      (is (= (.resolve (store/home) "workspaces/w/raw") (store/path "w" "raw")))
      (is (= (store/path "w") (store/path {:name "w"})))))
  (testing "names cannot escape"
    (doseq [bad ["../x" "a/b" ".hidden" ""]]
      (is (thrown? clojure.lang.ExceptionInfo (store/path bad))))))

(deftest raw-put-dedups-by-sha
  (t/with-temp-home
    (let [b (t/bytes-of "hello")
          r1 (store/raw-put! "w" b (t/meta-for))
          r2 (store/raw-put! "w" b (t/meta-for {:op "other"}))]
      (is (:new? r1))
      (is (false? (:new? r2)))
      (is (= (:sha r1) (:sha r2)))
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" (:sha r1)))
      (is (= 5 (:bytes r1)))
      (is (= "hello" (String. (store/raw-get "w" (:sha r1)) "UTF-8")))
      (testing "layout and first meta kept"
        (is (str/ends-with? (str (:path r1)) "raw/sha256/2c/f24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        (is (= "GET /x" (:op (store/raw-meta "w" (:sha r1)))))
        (is (= 2 (count (filter #(.isFile %) (file-seq (.toFile (store/path "w" "raw"))))))))
      (testing "blob is read-only"
        (is (not (Files/isWritable (:path r1))))))))

(deftest raw-meta-required-keys
  (t/with-temp-home
    (let [b (t/bytes-of "x")]
      (doseq [k [:source :op :env :at :content-type]]
        (testing (str "missing " k)
          (let [e (try (store/raw-put! "w" b (dissoc (t/meta-for) k)) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :store/invalid-meta (:type (ex-data e)))))))
      (is (thrown? clojure.lang.ExceptionInfo (store/raw-put! "w" b (t/meta-for {:source :nope}))))
      (is (thrown? clojure.lang.ExceptionInfo (store/raw-put! "w" b (t/meta-for {:at "now"}))))
      (testing "nothing was written for rejected puts"
        (is (not (.exists (.toFile (store/path "w" "raw"))))))
      (testing ":env may be nil"
        (is (:new? (store/raw-put! "w" b (t/meta-for {:source :file :env nil})))))
      (testing "sidecar carries :bytes :sha and the instant round-trips"
        (let [m (store/raw-meta "w" (store/sha256 b))]
          (is (= 1 (:bytes m)))
          (is (= (:at (t/meta-for)) (:at m)))
          (is (= (store/sha256 b) (:sha m))))))))

(deftest interrupted-put-is-repaired
  (t/with-temp-home
    (let [b (t/bytes-of "half")
          sha (store/sha256 b)]
      (store/write-bytes-atomic! (store/raw-path "w" sha) b)
      (is (nil? (store/raw-meta "w" sha)))
      (is (false? (:new? (store/raw-put! "w" b (t/meta-for)))))
      (is (some? (store/raw-meta "w" sha))))))

(deftest link-hard-links-and-is-idempotent
  (t/with-temp-home
    (let [{:keys [sha path]} (store/raw-put! "w" (t/bytes-of "body") (t/meta-for))
          target (io/file store/*home* "step" "response.txt")
          r (store/link! "w" sha target)]
      (is (:linked? r))
      (is (Files/isSameFile path (.toPath target)))
      (is (= "body" (slurp target)))
      (is (:linked? (store/link! "w" sha target)))
      (spit (io/file store/*home* "other.txt") "different")
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/link! "w" sha (io/file store/*home* "other.txt"))))
      (is (thrown? clojure.lang.ExceptionInfo (store/link! "w" (apply str (repeat 64 "0")) target))))))

(deftest write-edn-roundtrip
  (t/with-temp-home
    (let [p (io/file store/*home* "a" "b.edn")
          d {:a 1 :at (java.time.Instant/parse "2026-01-02T03:04:05Z") :v [1 "two" :three]}]
      (store/write-edn! p d)
      (is (= d (store/read-edn p)))
      (is (nil? (store/read-edn (io/file store/*home* "missing.edn")))))))

(deftest atomic-write-survives-dying-writer
  (t/with-temp-home
    (let [p (io/file store/*home* "state.edn")]
      (store/write-edn! p {:v 1})
      (testing "writer fails between temp write and rename"
        (binding [store/*before-rename* (fn [_] (throw (ex-info "boom" {})))]
          (is (thrown? clojure.lang.ExceptionInfo (store/write-edn! p {:v 2}))))
        (is (= {:v 1} (store/read-edn p)))
        (is (= 1 (count (filter #(str/ends-with? (.getName %) ".tmp") (.listFiles (.getParentFile p)))))))
      (testing "a real JVM halted between temp write and rename"
        (let [java (str (System/getProperty "java.home") "/bin/java")
              code (str "(require 'deliberator.store) (binding [deliberator.store/*before-rename* "
                        "(fn [_] (.halt (Runtime/getRuntime) 9))] "
                        "(deliberator.store/write-edn! " (pr-str (str p)) " {:v 3}))")
              proc (.start (ProcessBuilder. ^java.util.List
                                            [java "-cp" (System/getProperty "java.class.path")
                                             "clojure.main" "-e" code]))]
          (is (= 9 (.waitFor proc)))
          (is (= {:v 1} (store/read-edn p)))
          (is (= 2 (count (filter #(str/ends-with? (.getName %) ".tmp") (.listFiles (.getParentFile p)))))))))))

(deftest never-delete
  (testing "the persistence namespaces contain no deletion"
    (doseq [f ["store" "workspace" "session"]]
      (let [src (slurp (io/file (System/getProperty "user.dir") "src" "deliberator" (str f ".clj")))]
        (is (not (re-find #"Files/delete|deleteIfExists|delete-file|\.delete\b|deleteOnExit" src)) f)))))

(deftest raw-adopt-links-a-finished-file-into-the-store
  (t/with-temp-home
    (let [f (io/file (str (store/home)) "followed.log")
          _ (io/make-parents f)
          _ (spit f "line 1\nline 2\n")
          m {:source :file :op "logs.fetch x (follow)" :env nil :at (store/now) :content-type "text/plain"}
          r (store/raw-adopt! "w" f m)]
      (is (= (store/sha256 (.getBytes "line 1\nline 2\n" "UTF-8")) (:sha r)))
      (is (true? (:new? r)))
      (is (= 14 (:bytes r)))
      (is (java.nio.file.Files/isSameFile (.toPath f) (store/raw-path "w" (:sha r))) "one inode under two names")
      (is (not (.canWrite f)) "read-only like every blob")
      (is (= "line 1\nline 2\n" (String. (store/raw-get "w" (:sha r)) "UTF-8")))
      (is (= :file (:source (store/raw-meta "w" (:sha r)))))
      (testing "the same bytes again: not new, the first blob stays"
        (let [g (io/file (str (store/home)) "again.log")]
          (spit g "line 1\nline 2\n")
          (let [r2 (store/raw-adopt! "w" g m)]
            (is (false? (:new? r2)))
            (is (= (:sha r) (:sha r2)))
            (is (not (java.nio.file.Files/isSameFile (.toPath g) (store/raw-path "w" (:sha r)))))))))))
