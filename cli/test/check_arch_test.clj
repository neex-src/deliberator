(ns check-arch-test
  "`bb check:arch` forbidden names: the list comes from the owner's private config, a machine marked as the owner's fails loudly
  without it, any other machine skips with a message. No name is in the repository: the tests make up their own."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [check-arch]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- env-of [m] (fn [k] (get m k)))

(defn- with-config-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "dlb-arch-"}))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(deftest the-list-is-read-from-the-config-dir
  (with-config-dir
    (fn [dir]
      (spit (str dir "/forbidden-names.txt") "# a comment\nAcmeCorp\n\n  Initech  \n")
      (let [s (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir}))]
        (is (= :ok (:state s)))
        (is (= ["AcmeCorp" "Initech"] (:names s)))
        (is (= (str dir "/forbidden-names.txt") (:list s))))
      (testing "the env var adds to it"
        (is (= ["AcmeCorp" "Initech" "Globex"]
               (:names (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir "DELIBERATOR_FORBIDDEN_NAMES" "Globex, Initech"})))))))))

(deftest the-config-dir-follows-xdg-then-home
  (is (= "/x/y/deliberator" (check-arch/config-dir (env-of {"XDG_CONFIG_HOME" "/x/y"}))))
  (is (= "/c" (check-arch/config-dir (env-of {"DELIBERATOR_CONFIG_DIR" "/c" "XDG_CONFIG_HOME" "/x/y"}))))
  (is (= (str (System/getProperty "user.home") "/.config/deliberator") (check-arch/config-dir (env-of {})))))

(deftest a-machine-marked-as-the-owners-fails-without-the-list
  (with-config-dir
    (fn [dir]
      (spit (str dir "/owner-machine") "")
      (let [s (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir}))]
        (is (= :fail (:state s)))
        (is (str/includes? (:message s) "forbidden-names.txt")))
      (testing "an empty list is a missing list"
        (spit (str dir "/forbidden-names.txt") "# nothing\n\n")
        (is (= :fail (:state (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir}))))))
      (testing "with the list it checks"
        (spit (str dir "/forbidden-names.txt") "AcmeCorp\n")
        (is (= :ok (:state (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir})))))))))

(deftest any-other-machine-skips-with-a-message
  (with-config-dir
    (fn [dir]
      (let [s (check-arch/forbidden-state (env-of {"DELIBERATOR_CONFIG_DIR" dir}))]
        (is (= :skip (:state s)))
        (is (str/includes? (:message s) "skipped"))
        (is (str/includes? (:message s) "owner-machine"))))))

(deftest bb-check-arch-end-to-end
  (with-config-dir
    (fn [dir]
      (let [run (fn [] (p/shell {:out :string :err :string :continue true :extra-env {"DELIBERATOR_CONFIG_DIR" dir}} "bb" "check:arch"))]
        (let [r (run)]
          (is (zero? (:exit r)))
          (is (re-find #"forbidden-names check skipped" (:out r))))
        (spit (str dir "/owner-machine") "")
        (let [r (run)]
          (is (= 1 (:exit r)) "marked, no list: loud")
          (is (re-find #"arch FAILED: this machine is marked" (:out r)))
          (is (re-find #"(?m)^arch violations: 1" (:out r))))
        (spit (str dir "/forbidden-names.txt") (str "no-such" "-name-" "anywhere" "\n"))
        (let [r (run)]
          (is (zero? (:exit r)))
          (is (re-find #"arch ok: \d+ files checked \(1 forbidden names\)" (:out r))))))))
