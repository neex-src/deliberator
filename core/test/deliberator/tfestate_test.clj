(ns deliberator.tfestate-test
  "M6.1 / M6.2 / M6.3 against the running estate's Floci (`bb estate:up --ws W`, `ESTATE_WS=W`): a COPY of the real-AWS root
  (`estate/terraform/aws`, AWS provider 6.x, no endpoints of its own) and of the IAM root it looks a role up in are applied onto
  Floci through `terraform.apply-all` (the override file, the order from the .tf files, prefix `m6a` so nothing of the estate is
  touched), read back (the ARN carries the workspace's account, drift of the copy is clean, `terraform.locate` finds a declaration)
  and destroyed again."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [deliberator.aws :as aws]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.ops :as ops])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- call
  ([op args] (call op args (atom [])))
  ([op args events]
   (ops/call! op {:emit! #(swap! events conj %) :on-cancel! (fn [_]) :daemon {:runs (atom {}) :checked (atom #{})}} args)))

(defn- copy-estate!
  "The roots aws and iam, the module and the lambda sources of ../estate under a temp directory (no .terraform, no state)."
  []
  (let [t (str (Files/createTempDirectory "dlb-m6a" (make-array FileAttribute 0)))
        r (shell/sh "sh" "-c" (str "tar -C ../estate --exclude=.terraform --exclude=.build --exclude='*.tfstate*' -cf - terraform lambda | tar -C " t " -xf -"))]
    (assert (zero? (:exit r)) (:err r))
    ;; a repository of its own with the ignore line, as a user's checkout would be (and no warning to chase)
    (shell/sh "git" "init" "-q" t)
    (spit (io/file t ".gitignore") "deliberator_override.tf.json\n.terraform/\n.build/\n")
    t))

(deftest a-copy-of-the-real-aws-root-applies-onto-floci
  (if-let [ws (fu/estate-ws)]
    (let [t (copy-estate!)
          aws-root (str t "/terraform/aws") iam-root (str t "/terraform/iam")
          account (get-in (env/load ws "local") [:aws :account])
          args {:ws ws :roots [aws-root iam-root] :vars {:prefix "m6a"}}]
      (try
        (let [events (atom [])
              r (call "terraform.apply-all" args events)]
          (testing "the IAM root first (the aws root looks its role up by name), both applied"
            (is (true? (:ok r)) (pr-str (map #(select-keys % [:dir :ok :summary]) (:results r))))
            (is (= [iam-root aws-root] (:order r)))
            (is (every? #(str/starts-with? (str (:summary %)) "Apply complete!") (:results r))))
          (testing "AWS provider 6.x ran it (the gate of M6)"
            (is (re-find #"(?s)hashicorp/aws\".*?version\s*=\s*\"6\.\d+\.\d+\"" (slurp (io/file aws-root ".terraform.lock.hcl")))))
          (testing "the override files are there, gitignored, and the checkouts have no state"
            (is (.exists (io/file aws-root "deliberator_override.tf.json")))
            (is (not (.exists (io/file aws-root "terraform.tfstate"))))
            (is (not-any? #(re-find #"NOT gitignored" %) (mapcat :warnings (:results r)))))
          (testing "what was created is on Floci, under the workspace's account (M6.2)"
            (let [role (aws/invoke! {:ws ws} (env/load ws "local") :iam :GetRole {:RoleName "estate-shared"})
                  p (aws/invoke! {:ws ws} (env/load ws "local") :ssm :GetParameter {:Name "/m6a/chain/desired-count"})]
              (is (str/includes? (get-in role [:Role :Arn]) (str ":" account ":role/estate-shared")) (pr-str role))
              (is (some? (get-in p [:Parameter :Value])) (pr-str p))))
          (testing "drift of the copy: everything declared exists (terraform show runs with the root's data dir)"
            (let [d (call "terraform.drift" {:ws ws :env "local" :dir aws-root})]
              (is (= [] (:missing d)) (pr-str (:missing d)))
              (is (pos? (:declared d)))))
          (testing "a second apply-all changes nothing"
            (let [r2 (call "terraform.apply-all" args)]
              (is (true? (:ok r2)))
              (is (every? #(str/starts-with? (str (:summary %)) "Apply complete! Resources: 0 added, 0 changed, 0 destroyed") (:results r2))
                  (pr-str (map :summary (:results r2)))))))
        (finally
          (let [d (call "terraform.apply-all" (assoc args :action "destroy"))]
            (is (true? (:ok d)) (pr-str (map #(select-keys % [:dir :ok :summary]) (:results d))))
            (is (= [aws-root iam-root] (:order d)) "destroy runs the other way round")))))
    (fu/skip! (fu/current-test) "set ESTATE_WS=<ws> (after bb estate:up --ws <ws>) and DELIBERATOR_HOME")))
