(ns estate-real
  "The estate on a real account (switch `run_services` of estate/terraform/aws): `bb estate:real-images --prefix P --profile PROFILE [--tag T] [--only a|b]`
  builds the two service images with Podman from estate/services (the Dockerfiles do the Maven build) and pushes them to the ECR repositories
  `<prefix>/service-a` and `<prefix>/service-b` that Terraform created. The registry login goes to a throwaway auth file under .scratch/, removed afterwards;
  the profile is the AWS CLI's (shared credentials file), no key is read or printed here."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- opts [args]
  (loop [[k v & more :as a] args m {}]
    (if (empty? a) m
        (recur more (assoc m (keyword (str/replace k #"^--" "")) v)))))

(defn- sh! [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str (first args) " failed: " (str/trim (str (:err r) (:out r)))) {:args (take 3 args)})))
    (str/trim (:out r))))

(defn images [args]
  (let [{:keys [prefix profile tag only] :or {tag "latest"}} (opts args)
        _ (when-not (and prefix profile) (throw (ex-info "usage: bb estate:real-images --prefix P --profile PROFILE [--tag T] [--only a|b]" {})))
        auth (str (fs/absolutize ".scratch/ecr-auth.json"))]
    (try
      (doseq [k (if only [only] ["a" "b"])
              :let [repo-name (str prefix "/service-" k)
                    uri (sh! "aws" "--profile" profile "ecr" "describe-repositories" "--repository-names" repo-name
                             "--query" "repositories[0].repositoryUri" "--output" "text")
                    registry (first (str/split uri #"/"))
                    ref (str uri ":" tag)]]
        (println "build" ref)
        (p/shell "podman" "build" "-q" "-f" (str "estate/services/service-" k "/Dockerfile") "-t" ref "estate/services")
        (let [pw (sh! "aws" "--profile" profile "ecr" "get-login-password")]
          (p/shell {:in pw} "podman" "login" "--username" "AWS" "--password-stdin" "--authfile" auth registry))
        (println "push" ref)
        (p/shell "podman" "push" "-q" "--authfile" auth ref))
      (finally (fs/delete-if-exists auth)))))
