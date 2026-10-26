(ns deliberator.remote-util
  "A `:remote`-shaped environment that points at the emulator: the only way to run remote code paths (account
  check, class gate, audit, redaction) in a test without AWS. `with-remote-at-floci` binds a fresh home with a credentials
  file holding the profile, saves the record under workspace `default` and gives the body the record and the local record
  of the same Floci (for fixtures: a local environment is not gated)."
  (:require [clojure.java.io :as io]
            [deliberator.env :as env]
            [deliberator.floci-util :as fu]
            [deliberator.test-util :as tu]))

(defn remote-record [name read-only?]
  (env/validate {:name name :kind :remote :read-only read-only?
                 :aws {:profile "dlbtest" :region "eu-central-1" :account "000000000000" :endpoint fu/url}
                 :targets {}}))

(defmacro with-remote-at-floci
  "`(with-remote-at-floci [remote local] body)`: `remote` is the record `dlbremote` (read-only), `local` the record of the
  same Floci as a `:local` environment."
  [[remote local] & body]
  `(tu/with-temp-home
     (let [f# (doto (java.io.File/createTempFile "dlb-creds" ".ini") (.deleteOnExit))]
       (spit f# "[dlbtest]\naws_access_key_id = test\naws_secret_access_key = test\n")
       (binding [env/*credentials-file* f#]
         (let [~remote (remote-record "dlbremote" true)
               ~local (fu/local-env)]
           (env/save! "default" ~remote)
           ~@body)))))
