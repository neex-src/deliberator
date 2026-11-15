(ns deliberator.ecsrecord
  "A workspace service bound to an ECS service of the `local` environment (cicd-aws piece 02): the
  binding, and the service's own record of what the watch (`deliberator.ecsfollow`) saw and did.

  The binding is the description's `:ecs {:cluster \"acme\" :service \"acme-billing\"}`. The record is
  `<ws>/ecs/<service>.edn`: `{:cluster :service :desired :taskdef <arn> :revision \"fam:3\" :image :environment :secrets
  :applied {:desired :taskdef} :seen-at :action :error}`. `:desired`, `:taskdef` and the revision's entries are what the
  ECS record said when last read; `:applied` is what the container side was last brought to (the watch acts on a
  difference to it, so a daemon restart changes nothing); `:action` is the last thing done, with its reason.

  This namespace holds only what the runtime, the service ops and the watch share: reading and writing the record, the
  per-workspace lock that serialises the watch's reconciling with a start or stop by hand, the services at desired 0 (not
  desired as containers) and the description with the followed revision's environment and secrets laid over it."
  (:require [clojure.string :as str]
            [deliberator.store :as store]))

(defn binding-of
  "`{:cluster :service}` of a description bound to an ECS service, else nil."
  [desc]
  (let [e (:ecs desc)]
    (when (and (map? e) (string? (:cluster e)) (string? (:service e))) (select-keys e [:cluster :service]))))

(defn parse-binding
  "\"cluster/service\" -> `{:cluster :service}`; \"none\" -> `:none`; anything else nil."
  [s]
  (let [s (str/trim (str s))]
    (if (= "none" s)
      :none
      (let [[c n & more] (str/split s #"/")]
        (when (and (seq c) (seq n) (empty? more)) {:cluster c :service n})))))

(defn path [ws name] (store/path ws "ecs" (str (store/safe-name name) ".edn")))

(defn read-record [ws name] (store/read-edn (path ws name)))

(defn write-record! [ws name rec] (store/write-edn! (path ws name) rec))

(defonce ^:private locks (atom {}))

(defn lock-of
  "The monitor of workspace `ws`: the watch's reconciling and a start or stop by hand never run at once."
  [ws]
  (let [k (store/ws-name ws)]
    (get (swap! locks update k #(or % (Object.))) k)))

(defn zero-services
  "Names of the described services bound to an ECS service whose record says desired 0: their containers are not desired."
  [ws descs]
  (into #{} (comp (filter binding-of)
                  (filter #(= 0 (:desired (read-record ws (:name %))))))
        descs))

(defn deployed?
  "True when the service record says the container runs the revision's image (piece 02b)."
  [rec]
  (boolean (and (:deployed rec) (not (:held rec)))))

(defn revision-text
  "`revision 3 · <image> (deployed)` and its two other forms (decisions 86, 116), nil without a revision."
  [rec kind]
  (when-let [r (:revision rec)]
    (str "revision " (last (str/split r #":")) (when (:image rec) (str " · " (:image rec)))
         (when (:image rec)
           (cond (deployed? rec) " (deployed)"
                 (:held rec) " (in the runtime's store, held on the dev container until the next revision)"
                 :else (str " (not used: not in the runtime's store, "
                            (if (= :container kind) "the description's image runs)" "dev container runs)")))))))

(defn hint
  "What to do from here, for the row: deployed -> the way back."
  [rec name]
  (when (deployed? rec)
    (str "dlb service dev " name " returns to the dev container; it holds until the next revision")))

(defn- host-port-of
  "The host port the runtime allotted to `desc`'s port (`runtime.edn :services <name> :ports`), else its pinned one, else its port."
  [ws desc]
  (or (get-in (store/read-edn (store/path ws "runtime.edn")) [:services (:name desc) :ports (:port desc)])
      (:host-port desc) (:port desc)))

(defn overlay
  "`desc` as the ECS record says it runs. A deployed revision (its image is in the runtime's store) is a plain image
  container whatever the kind: the revision's image, command, entry point, environment and secret references, the description's `:port`
  on its allotted host port, aliases and health path (a Spring service without one: `/actuator/health`), no hot-swap agent, no JDWP.
  Otherwise the followed revision's environment and secrets are laid over the dev container: a Spring service takes
  them whole as its `:taskdef` entries, a `:container` merges the environment over its `:env`. No record: `desc`."
  [ws desc]
  (let [rec (when (binding-of desc) (read-record ws (:name desc)))]
    (cond
      (not (and rec (map? (:environment rec)))) desc
      (deployed? rec)
      (cond-> {:name (:name desc) :kind :container :ecs (:ecs desc)
               :image (:image rec) :port (:port desc) :host-port (host-port-of ws desc)
               :env (:environment rec) :secrets (:secrets rec) :image-id (:image-id rec)
               :health (or (:health desc) (when (= :spring-boot (:kind desc)) "/actuator/health"))
               :deployed true}
        (seq (:command rec)) (assoc :cmd (:command rec))
        (seq (:entrypoint rec)) (assoc :entrypoint (:entrypoint rec))
        (seq (:aliases desc)) (assoc :aliases (:aliases desc)))
      (= :container (:kind desc)) (update desc :env #(into (or % {}) (:environment rec)))
      :else (update desc :taskdef #(assoc (or % {}) :environment (:environment rec) :secrets (or (:secrets rec) {}))))))
