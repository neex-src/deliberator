(ns deliberator.topology
  "Progressive topology (M3 piece 03): per environment, every service is `local` (its container on
  the runtime), `external {url}` (served from elsewhere), `off` (not available) or `fake {fake}` (served by an HTTP fake of the
  runtime, M6.6: recording the real service, or replaying a recording while the real one is not needed). Stored in
  runtime, M6.6: recording the real service, or replaying a recording while the real one is not needed). Stored in
  `environments/<env>.edn` under `:topology {\"<service>\" {:mode :external|:off [:url] [:log {:container}]}}`;
  no entry means local. The resolution itself (`service/<n>` targets, the typed `topology/service-off`) is
  `deliberator.env/target`; this namespace holds what needs the workspace: `show`, `set!`, `snapshot` (what a
  run records), and the rendering of dependents' environment entries for the runtime.

  A description names its dependencies as `:depends {\"SERVICE_A_URL\" \"a\"}` (env var -> service). The
  container runtime serves the workspace's `local` environment, so `render-spec` follows the topology of that
  one: external -> the variable leaves `:secrets` and becomes a plain `:env` entry (its `:container-url`, else the url with a loopback host turned into
  `host.containers.internal`, the host as a container sees it), off -> `http://<service>.off.invalid`, local ->
  untouched; fake -> the variable becomes a plain `:env` entry, the fake's container URL. A service whose mode there is not local is not
  desired as a container (`not-local`), except one served by a fake that is recording: the real service is the upstream of the fake."
  (:require [clojure.string :as str]
            [deliberator.env :as env]
            [deliberator.fakes :as fakes]
            [deliberator.service.description :as description]
            [deliberator.store :as store])
  (:import (java.net URI)))

(defn- fail [type msg & {:as data}] (throw (ex-info msg (assoc data :type type))))

(def modes #{"local" "external" "off" "fake"})

(defn- host-url
  "`url` with its port taken through the `runtime.edn :services <n> :ports` override (container port -> host port)."
  [ws n url]
  (let [ports (get-in (store/read-edn (store/path ws "runtime.edn")) [:services n :ports])]
    (if-let [[_ pre cp post] (and (seq ports) (string? url) (re-matches #"(?s)(\w+://[^/:]+:)(\d+)(.*)" url))]
      (str pre (get ports (Long/parseLong cp) cp) post)
      url)))

(defn services
  "name -> `{:mode kw [:url]}` for every described service and every service the environment names."
  [ws e]
  (into (sorted-map)
        (for [n (distinct (concat (description/names ws) (keys (:topology e))))
              :let [ent (env/service-entry e n)
                    mode (env/service-mode e n)]]
          [n (case mode
               :external (cond-> {:mode :external :url (:url ent)} (:container-url ent) (assoc :container-url (:container-url ent)))
               :off {:mode :off}
               :fake {:mode :fake :fake (:fake ent) :url (:url ent) :serving (fakes/serving ws (:fake ent))}
               (cond-> {:mode :local}
                 (get-in e [:targets (keyword "service" n)]) (assoc :url (cond->> (get-in e [:targets (keyword "service" n)])
                                  (= "local" (:name e)) (host-url ws n)))))])))

(defn snapshot
  "What a run records: `{:hash :services {name {:mode [:url]}}}`; the local container URL is left out (it is the
  runtime's, not part of the choice), the hash covers modes and external URLs."
  [ws e]
  (let [svcs (into (sorted-map) (for [[n m] (services ws e)] [n (if (= :local (:mode m)) {:mode :local} m)]))]
    {:hash (subs (store/sha256 (.getBytes (pr-str svcs) "UTF-8")) 0 16)
     :services svcs}))

(defn show
  "`{:env :services {name {:mode \"local\" [:url]}} :hash}` of environment `env-name`."
  [ws env-name]
  (let [e (env/load ws env-name)
        s (services ws e)]
    {:env (:name e)
     :services (into (sorted-map) (for [[n m] s] [n (update m :mode name)]))
     :hash (:hash (snapshot ws e))}))

(defn dependents
  "`[{:name :var}]`: the described services whose `:depends` names `svc`."
  [ws svc]
  (vec (for [d (description/list ws)
             [v n] (:depends d)
             :when (= n svc)]
         {:name (:name d) :var v})))

(defn set!
  "Sets the mode of service `svc` in environment `env-name`. `mode` is \"local\" (removes the entry), \"fake\" (served by the HTTP fake
  `fake`, default the service's name, which must be declared: `fake.record` or `fake.replay` first; the entry keeps the fake's urls), \"external\"
  (needs `url`, as the host reaches it; `container-url` is how containers reach it when that differs, default
  the url with a loopback host turned into `host.containers.internal`; `log-container` names the container whose
  log stands for the service's `log/service-<n>`) or
  \"off\". -> `show` plus `:dependents` (their container specs change at the next reconcile)."
  [ws env-name svc mode url {:keys [log-container container-url fake]}]
  (when-not (modes mode)
    (fail :bad-args (str "mode must be local, external, off or fake, not " (pr-str mode)) :problems ["mode"]))
  (when-not (description/read ws svc)
    (fail :service/not-found (str "no service " (pr-str svc) " in workspace " (store/ws-name ws))
          :known (description/names ws) :hint "dlb service list"))
  (when (and url (not= mode "external"))
    (fail :bad-args "url is only for mode external" :problems ["url"]))
  (when (and fake (not= mode "fake"))
    (fail :bad-args "fake is only for mode fake" :problems ["fake"]))
  (when (and url (= mode "fake"))
    (fail :bad-args "mode fake takes no url: it is the fake's" :problems ["url"]))
  (when (and (or log-container container-url) (not= mode "external"))
    (fail :bad-args "log-container and container-url are only for mode external" :problems ["log-container" "container-url"]))
  (when (and (= mode "external") (str/blank? url))
    (fail :bad-args "mode external needs a url" :problems ["url"] :hint "dlb topology set ENV SERVICE external http://host:port"))
  (let [e (env/load ws env-name)
        fname (or fake svc)
        decl (when (= mode "fake") (fakes/read ws fname))
        _ (when (and (= mode "fake") (not decl))
            (fail :fake/not-found (str "no fake " (pr-str fname) " in workspace " (store/ws-name ws))
                  :known (fakes/names ws) :hint (str "dlb fake record " fname " service/" svc "   (or dlb fake replay " fname " --from DIR)")))
        ent (case mode
              "fake" {:mode :fake :fake fname :url (fakes/host-url decl) :container-url (fakes/container-url ws decl)}
              "external" (cond-> {:mode :external :url (str/replace url #"/+$" "")}
                           container-url (assoc :container-url (str/replace container-url #"/+$" ""))
                           log-container (assoc :log {:container log-container}))
              "off" {:mode :off}
              nil)
        e (if ent
            (assoc-in e [:topology svc] ent)
            (let [t (dissoc (:topology e) svc)]
              (if (seq t) (assoc e :topology t) (dissoc e :topology))))]
    (env/save! ws e)
    (assoc (show ws env-name) :dependents (dependents ws svc))))

;; the runtime's view ----------------------------------------------------------------------------

(defn local-env
  "The `local` environment's topology as a pseudo record for `env/service-mode` (read raw, never creates anything)."
  [ws]
  {:topology (env/read-topology ws "local")})

(defn not-local
  "Names of the services the `local` environment serves from elsewhere or not at all: their containers are not desired. A service
  served by a fake is not desired only while the fake replays; while it records, the service is its upstream."
  [ws]
  (let [e (local-env ws)]
    (into #{} (remove #(let [m (env/service-mode e %)]
                         (or (= :local m)
                             (and (= :fake m) (not= :replay (fakes/serving ws (:fake (env/service-entry e %))))))))
          (keys (:topology e)))))

(defn container-mode
  "The mode of service `n` as far as its own container goes: `:local` when the container is desired (a service whose fake records
  included), else the topology mode (`:external`, `:off`, `:fake` while replaying)."
  [ws n]
  (if (contains? (not-local ws) n) (env/service-mode (local-env ws) n) :local))

(defn container-url
  "`url` as a container on the runtime's network reaches it: a loopback host is the host's name for containers."
  [url]
  (let [u (URI. url)
        h (.getHost u)]
    (if (#{"localhost" "127.0.0.1" "[::1]" "::1"} h)
      (str (.getScheme u) "://host.containers.internal" (when (pos? (.getPort u)) (str ":" (.getPort u))) (.getRawPath u))
      url)))

(defn render-spec
  "The runtime `spec` of a dependent with its `:depends` entries followed to the `local` environment's topology."
  [ws spec depends]
  (let [e (local-env ws)]
    (reduce (fn [spec [var svc]]
              (let [ent (env/service-entry e svc)]
                (case (:mode ent)
                  :external (-> spec (update :secrets #(dissoc (or % {}) var)) (assoc-in [:env var] (or (:container-url ent) (container-url (:url ent)))))
                  :off (-> spec (update :secrets #(dissoc (or % {}) var)) (assoc-in [:env var] (str "http://" svc ".off.invalid")))
                  :fake (-> spec (update :secrets #(dissoc (or % {}) var)) (assoc-in [:env var] (:container-url ent)))
                  spec)))
            spec depends)))
