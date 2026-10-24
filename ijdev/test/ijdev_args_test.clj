;; Run: bb ijdev/test/ijdev_args_test.clj  (from the repo root)
(require '[clojure.test :refer [deftest is run-tests]]
         '[babashka.fs :as fs])

(load-file (str (fs/path (fs/parent (fs/parent *file*)) "bin" "ijdev")))

(deftest gradle-args-split
  (is (= ["-PdlbHome=H" "-PdlbProject=P"] (gradle-args ["-PdlbHome=H" "-PdlbProject=P"])))
  (is (= ["-PdlbHome=H"] (gradle-args ["--" "-PdlbHome=H"])))
  (is (= ["--" "-Pa"] (gradle-args ["--" "--" "-Pa"])) "only one leading -- is dropped")
  (is (= [] (gradle-args [])))
  (is (= [] (gradle-args ["--"]))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
