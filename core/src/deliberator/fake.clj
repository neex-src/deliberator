(ns deliberator.fake
  "The operations of the HTTP fake: `fake.record`, `fake.replay`, `fake.stop`, `fake.list`, `fake.commit`. The declaration,
  the runtime role and the recordings' layout are `deliberator.fakes`; the proxy itself is `deliberator/fake/http_fake.bb`, run in a
  container; the scrub rules are `deliberator.fake.scrub`; the topology mode `fake` is `deliberator.topology`.

  `record!` declares (or updates) the fake, starts a NEW recording under the session and reconciles the runtime, so the proxy forwards to
  the upstream and persists every exchange; `replay!` points the fake at a recording and reconciles (a service whose topology mode is
  `fake` stops while the fake replays); `stop!` removes the container, detaches the topology and reconciles; `commit!` copies a recording
  into an attached repository, scrubbed."
  (:refer-clojure :exclude [list])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.fake.scrub :as scrub]
            [deliberator.fakes :as fakes]
            [deliberator.repo :as repo]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.store :as store]
            [deliberator.topology :as topology]))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(defn- emit [sink m] (when sink (sink m)))

(defn upstream-url
  "`service/<n>` or an http(s) URL -> the URL as the fake's container reaches it."
  [ws upstream]
  (cond
    (str/starts-with? (str upstream) "service/")
    (let [n (subs upstream 8)
          d (or (description/read ws n)
                (fail :service/not-found (str "no service " (pr-str n) " in workspace " (store/ws-name ws))
                      :known (description/names ws) :hint "dlb service list"))]
      (when-not (:port d) (fail :fake/bad-upstream (str "service " n " has no port") :hint "give the upstream as a URL"))
      (str "http://" (store/container-name ws (runtime/role-name (description/role n))) ":" (:port d)))
    (re-matches #"https?://[^/\s]+(:\d+)?(/\S*)?" (str upstream))
    (str/replace (topology/container-url (str upstream)) #"/+$" "")
    :else (fail :fake/bad-upstream (str "upstream must be service/<name> or an http(s) URL, not " (pr-str upstream))
                :hint "dlb fake record NAME service/a   or   dlb fake record NAME http://host:port")))

(defn- exchanges [dir] (count (fakes/exchange-files dir)))

(defn- attached
  "`[{:env :service}]` of the topology entries of environment local that name fake `name`."
  [ws name]
  (vec (for [[svc ent] (sort-by key (env/read-topology ws "local"))
             :when (and (= :fake (:mode ent)) (= name (:fake ent)))]
         {:env "local" :service svc})))

(defn- view [ws decl]
  (let [serving (fakes/serving ws (:name decl))]
    (cond-> {:name (:name decl) :mode (name (or (:mode decl) :stopped)) :port (:port decl)
             :url (fakes/host-url decl) :container-url (fakes/container-url ws decl)
             :container (fakes/container-name ws (:name decl))
             :attached (attached ws (:name decl))}
      (:upstream decl) (assoc :upstream (:upstream decl))
      (:recording decl) (assoc :recording (:recording decl) :exchanges (exchanges (:recording decl)))
      (:session decl) (assoc :session (:session decl))
      serving (assoc :serving (name serving)))))

(defn- reconcile! [ws sink]
  (runtime/ensure! ws {:sink sink}))

(defn record!
  "Declares fake `name` (`upstream`: `service/<n>` or a URL) and starts a new recording in `session`'s directory; the proxy
  forwards everything to the upstream and persists each exchange as it passes. -> the fake's view."
  [ws name upstream {:keys [session port sink]}]
  (let [name (store/safe-name name)
        up (upstream-url ws upstream)
        old (fakes/read ws name)
        port (or port (:port old) (fakes/pick-port ws))
        dir (fakes/new-recording-dir ws session name)
        decl (fakes/write! ws {:name name :mode :record :port port :upstream up :recording dir :session (store/safe-name session)
                               :scrub (:scrub old)
                               :declared-at (str (store/now))})]
    (reconcile! ws sink)
    (emit sink {:event "fake.started" :name name :mode "record" :recording dir})
    (view ws decl)))

(defn- resolve-recording
  "`from` (an absolute directory, a recording id of fake `name`, or nil = the newest of the workspace's sessions) -> the directory."
  [ws name from]
  (let [recs (fakes/recordings ws name)
        dir (cond
              (nil? from) (:dir (last recs))
              (str/starts-with? (str from) "/") (str from)
              :else (:dir (first (filter #(= (str from) (:id %)) recs))))]
    (when-not (and dir (.isDirectory (io/file dir)))
      (fail :fake/no-recording
            (if from (str "no recording " (pr-str from) " of fake " name) (str "fake " name " has no recording in this workspace"))
            :known (mapv :id recs)
            :hint "dlb fake record NAME service/x first, or pass --from DIR (a directory of NNNNN.json files, e.g. a committed copy)"))
    (when (zero? (exchanges dir))
      (fail :fake/empty-recording (str "the recording " dir " holds no exchange (NNNNN.json)") :hint "record some traffic through the fake first"))
    dir))

(defn replay!
  "Points fake `name` at a recording (see `resolve-recording`) and reconciles: the container is recreated in replay mode. A service whose
  topology mode is `fake` for this fake stops while it replays. Declares the fake when it does not exist yet (a committed recording from
  a repository). -> the fake's view."
  [ws name {:keys [from port sink]}]
  (let [name (store/safe-name name)
        dir (resolve-recording ws name from)
        old (fakes/read ws name)
        decl (fakes/write! ws (-> (or old {:name name})
                                  (assoc :mode :replay :recording dir :declared-at (str (store/now)))
                                  (assoc :port (or port (:port old) (fakes/pick-port ws)))
                                  (dissoc :session)))]
    (reconcile! ws sink)
    (emit sink {:event "fake.started" :name name :mode "replay" :recording dir})
    (view ws decl)))

(defn stop!
  "Removes the container of fake `name`, keeps its declaration and recordings, sets the topology entries that named it back to local and
  reconciles (the dependents are recreated against the real service). -> `{:name :stopped :detached}`."
  [ws name {:keys [sink]}]
  (let [name (store/safe-name name)
        decl (or (fakes/read ws name)
                 (fail :fake/not-found (str "no fake " (pr-str name) " in workspace " (store/ws-name ws))
                       :known (fakes/names ws) :hint "dlb fake list"))
        detached (attached ws name)]
    (doseq [{:keys [service]} detached] (topology/set! ws "local" service "local" nil {}))
    (fakes/write! ws (assoc decl :mode :stopped))
    (let [c (runtime/remove-role! ws (fakes/role-label name))]
      (when c (emit sink {:event "fake.stopped" :name name :container c}))
      (reconcile! ws sink)
      (cond-> {:name name :stopped true :detached detached}
        c (assoc :container c)))))

(defn list
  "`[view + recordings]` of every declared fake."
  [ws]
  (vec (for [d (fakes/list ws)]
         (assoc (view ws d) :recordings (mapv #(select-keys % [:id :session :dir :exchanges]) (fakes/recordings ws (:name d)))))))

;; commit --------------------------------------------------------------------------------------------

(defn- repo-path [ws repo]
  (if (str/starts-with? (str repo) "/")
    (do (when-not (.isDirectory (io/file repo)) (fail :fake/bad-repo (str repo " is not a directory")))
        (str repo))
    (or (:path (first (filter #(= (str repo) (:name %)) (repo/list ws))))
        (fail :repo/not-found (str "no attached repository " (pr-str repo))
              :known (mapv :name (repo/list ws)) :hint "dlb repo attach PATH, or pass the absolute path of the checkout"))))

(defn commit!
  "Copies a recording of fake `name` (`recording`: id or directory, default the one the fake serves, else the newest) into repository
  `repo` (an attached name or an absolute path) under `dir` (default `fakes/<name>`), scrubbed by the default rules plus the fake's
  declared `:scrub`. Writes `scrub-report.txt` (rule -> count, never a value). The raw recording stays under the session.
  -> `{:name :dir :exchanges :report :from}`."
  [ws name repo {:keys [recording dir]}]
  (let [name (store/safe-name name)
        decl (fakes/read ws name)
        from (resolve-recording ws name (or recording (when (= :replay (:mode decl)) (:recording decl))))
        root (repo-path ws repo)
        rel (or dir (str "fakes/" name))
        _ (when (or (str/starts-with? rel "/") (some #{".."} (str/split rel #"/")))
            (fail :bad-args "dir must be a relative path inside the repository" :problems ["dir"]))
        out (io/file root rel)
        sc (scrub/scrubber (:scrub decl))
        files (fakes/exchange-files from)]
    (.mkdirs out)
    (doseq [^java.io.File old (.listFiles out) :when (re-matches #"\d+\.json" (.getName old))] (.delete old))
    (doseq [^java.io.File f files]
      (let [ex (json/read-str (slurp f) :key-fn keyword)]
        (spit (io/file out (.getName f)) (str (json/write-str (scrub/scrub-exchange sc ex) :indent true :escape-slash false) "\n"))))
    (let [rep (scrub/report sc)]
      (spit (io/file out "scrub-report.txt")
            (str "scrubbed copy of recording " (.getName (io/file from)) " of fake " name " by deliberator fake.commit\n"
                 (count files) " exchanges\n"
                 (if (seq rep) (str/join "\n" (for [[k n] rep] (str k ": " n))) "no rule matched") "\n"
                 "values are never listed here; the raw recording is under the session\n"))
      {:name name :from from :dir (str out) :exchanges (count files) :report rep})))
