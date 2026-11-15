(ns deliberator.ecsfollow
  "The local runtime follows the local ECS service (cicd-aws piece 02). On Floci ECS is a record: a Lambda that
  scales a service to 0 or a pipeline that registers a revision and calls `UpdateService` changes the record and nothing else.
  For a workspace service bound to an ECS service of the `local` environment (`:ecs {:cluster :service}` in its description,
  `deliberator.ecsrecord`) the record is the truth, and this watch makes the container follow it.

  `(start! {:home :log :touch!})` runs one thread per daemon: every second (every 5 s while Floci does not answer) it reads
  ONE `DescribeServices` per cluster per workspace that has bound services. The read is QUIET: through the
  `local` environment's client (never a remote one), the response bytes go to the raw store only when their sha differs from
  the last one persisted for that cluster, no request blob, no audit line (local calls are never audited); the calls a human or
  a scenario makes stay on `aws/invoke!`. The set of workspaces is rescanned every 10 s and on `(poke!)`.

  It acts on the DIFFERENCE between the record and what it last applied (`:applied` in `<ws>/ecs/<service>.edn`, so a daemon
  restart changes nothing): desired 0 and the container runs -> stop it (`service.stopped`, reason `ecs desired 0`), it then is not
  desired and `runtime.ensure` leaves it stopped; desired 0 -> 1 or more -> `ensure!` for that role (`service.started`; one container
  whatever the number); a new task definition -> its container definition is fetched (`aws/invoke!`, rare), its environment and
  secrets become the service's spec and `ensure!` recreates the container (`service.started`, reason `ecs revision fam:N`). The
  first sight of a record is a baseline: nothing is started that nobody started. A failure is written to the record (`:error`) and
  retried at most every 10 s. `(subscribe! f)` hands every event to `f` (the op `service.events`)."
  (:require [clojure.string :as str]
            [cognitect.aws.client.api :as cog]
            [deliberator.aws :as aws]
            [deliberator.ecsrecord :as rec]
            [deliberator.env :as env]
            [deliberator.runtime :as runtime]
            [deliberator.service.description :as description]
            [deliberator.store :as store])
  (:import (java.io InputStream)
           (java.nio.file Files LinkOption)))

(def ^:private poll-ms 1000)
(def ^:private away-ms 5000)
(def ^:private rescan-ms 10000)
(def ^:private retry-ms 10000)

;; events --------------------------------------------------------------------------------------

(defonce ^:private subscribers (atom {}))

(defn subscribe!
  "Calls `(f event)` for every event of the watch until `(unsubscribe! id)`; -> the id."
  [f]
  (let [id (gensym "sub")] (swap! subscribers assoc id f) id))

(defn unsubscribe! [id] (swap! subscribers dissoc id))

(defn- emit! [ws m]
  (doseq [f (vals @subscribers)] (try (f (assoc m :ws (store/ws-name ws))) (catch Throwable _ nil))))

;; the quiet read ------------------------------------------------------------------------------

(defonce ^:private persisted (atom {}))   ; [ws cluster] -> sha of the last response persisted

(defn- body-bytes ^bytes [r]
  (let [b (some-> r meta :http-response :body)]
    (if (instance? InputStream b) (.readAllBytes ^InputStream b) (byte-array 0))))

(defn describe-services
  "`{:status :ok :services {name {:desired n :taskdef arn}}}` for the ECS `services` of `cluster` in the `local` environment of `ws`,
  `{:status :no-cluster}` or `{:status :unreachable}`. Quiet: see the namespace docstring."
  [ws cluster services]
  (let [e (env/load ws "local")
        r (env/with-client ws e :ecs #(cog/invoke % {:op :DescribeServices :request {:cluster cluster :services (vec services)}}))]
    (if (:cognitect.anomalies/category r)
      {:status (if (re-find #"ClusterNotFound" (pr-str (dissoc r :cognitect.aws.client.impl/throwable :cognitect.aws/throwable)))
                 :no-cluster :unreachable)}
      (let [body (body-bytes r)
            sha (store/sha256 body)
            k [(store/ws-name ws) cluster]]
        (when (not= sha (get @persisted k))
          (store/raw-put! ws body {:source :aws :op "DescribeServices" :service "ecs" :role :response :class :read :env "local"
                                   :at (store/now) :content-type "application/x-amz-json-1.1"})
          (swap! persisted assoc k sha))
        {:status :ok
         :services (into {} (for [s (:services r)] [(:serviceName s) {:desired (:desiredCount s) :taskdef (:taskDefinition s)}]))}))))

;; one service ---------------------------------------------------------------------------------

(defonce ^:private retry-at (atom {}))   ; [ws service] -> ms before which a failed action is not tried again

(defn- revision-of [arn] (some-> arn (str/split #"/") last))

(defn- fetch-revision
  "The record entries of the container of task definition `arn` that service `d` runs (`:taskdef :container`, else the first)."
  [ws d arn]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :ecs :DescribeTaskDefinition {:taskDefinition arn})]
    (when (aws/error? r) (throw (ex-info (str "DescribeTaskDefinition " arn ": " (or (:message r) (:category r))) {:type :ecs/describe-failed})))
    (let [cds (get-in r [:taskDefinition :containerDefinitions])
          cd (or (some #(when (= (get-in d [:taskdef :container]) (:name %)) %) cds) (first cds))]
      {:image (:image cd)
       :environment (into (sorted-map) (map (fn [x] [(:name x) (:value x)])) (:environment cd))
       :secrets (into (sorted-map) (map (fn [x] [(:name x) (:valueFrom x)])) (:secrets cd))})))

(defn- reconcile!
  "Brings service `d` (a bound description) to what its live ECS record `live` says; -> nil."
  [ws d live log]
  (let [n (:name d) b (rec/binding-of d)
        role (runtime/role-name (description/role n))
        old (or (rec/read-record ws n) {})
        desired (:desired live) td (:taskdef live)
        applied (:applied old)
        rev? (and td (not= td (:taskdef old)))
        key [(store/ws-name ws) n]
        now (System/currentTimeMillis)]
    (when (> now (get @retry-at key 0))
      (try
        (let [r1 (cond-> (assoc old :cluster (:cluster b) :service (:service b) :desired desired)
                   rev? (-> (merge (fetch-revision ws d td)) (assoc :taskdef td :revision (revision-of td))))
              _ (when (not= r1 old) (rec/write-record! ws n r1))
              ctr (runtime/role-container ws role)
              running? (= "running" (:State ctr))
              up? (and applied (= 0 (:desired applied)) (pos? desired))
              rev-act? (and applied (not= (:taskdef applied) td))
              act (cond
                    (zero? desired) (when running?
                                      (runtime/stop-role! ws role)
                                      (emit! ws {:event "service.stopped" :name n :container (first (:Names ctr)) :reason "ecs desired 0"})
                                      "stopped: ecs desired 0")
                    (or up? rev-act?)
                    (let [why (if up? (str "ecs desired " desired) (str "ecs revision " (revision-of td)))]
                      (runtime/ensure! ws {:only [role] :sink #(emit! ws %)})
                      (emit! ws {:event "service.started" :name n :container (some-> (runtime/role-container ws role) :Names first) :reason why})
                      (str "started: " why)))
              r2 (cond-> (assoc r1 :applied {:desired desired :taskdef td} :error nil)
                   act (assoc :action act :acted-at (str (store/now))))
              r2 (if (:error r2) r2 (dissoc r2 :error))]
          (when (not= r2 r1) (rec/write-record! ws n r2))
          (when act (log "ecs follow" (store/ws-name ws) n act)))
        (catch Throwable t
          (swap! retry-at assoc key (+ now retry-ms))
          (log "ecs follow failed" (store/ws-name ws) n (ex-message t))
          (try (rec/write-record! ws n (assoc (or (rec/read-record ws n) old) :error (ex-message t))) (catch Throwable _ nil)))))))

;; the watch -----------------------------------------------------------------------------------

(defn- bound [ws]
  (filterv #(and (rec/binding-of %) (description/runnable? %)) (description/list ws)))

(defn- workspaces [home]
  (let [d (.resolve ^java.nio.file.Path home "workspaces")]
    (if (Files/isDirectory d (make-array LinkOption 0))
      (with-open [st (Files/list d)]
        (vec (sort (map #(str (.getFileName ^java.nio.file.Path %)) (iterator-seq (.iterator st))))))
      [])))

(defn tick!
  "One pass over workspace `ws`: reads each cluster once and reconciles each bound service. -> `:ok` when a record answered,
  `:away` when Floci did not."
  [ws log]
  (let [ds (bound ws)]
    (reduce (fn [res [cluster cds]]
              (let [r (describe-services ws cluster (map (comp :service rec/binding-of) cds))]
                (case (:status r)
                  :ok (do (doseq [d cds :let [live (get (:services r) (:service (rec/binding-of d)))] :when live]
                            (locking (rec/lock-of ws) (reconcile! ws d live log)))
                          (if (= :away res) :away :ok))
                  :no-cluster res
                  :away)))
            nil (group-by (comp :cluster rec/binding-of) ds))))

(defonce ^:private scan-at (atom 0))

(defn poke!
  "A service was bound or the runtime changed: the next tick rescans the workspaces."
  [] (reset! scan-at 0))

(defn start!
  "Starts the watch of `home` (a Path) in a daemon thread. `log` is the daemon's logger, `touch!` marks daemon activity.
  -> a function that stops it."
  [{:keys [home log touch!]}]
  (let [stop? (atom false)
        t (Thread.
           ^Runnable
           (fn []
             (binding [store/*home* home]
               (loop [watched [] next-at {}]
                 (when-not @stop?
                   (let [now (System/currentTimeMillis)
                         watched (if (>= now @scan-at)
                                   (do (reset! scan-at (+ now rescan-ms))
                                       (try (vec (filter #(try (seq (bound %)) (catch Throwable _ nil)) (workspaces home)))
                                            (catch Throwable _ watched)))
                                   watched)
                         next-at (reduce (fn [m ws]
                                           (if (>= now (get m ws 0))
                                             (let [r (try (tick! ws log) (catch Throwable e (log "ecs tick failed" ws (ex-message e)) :away))]
                                               (when (and touch! (= :ok r)) (touch!))
                                               (assoc m ws (+ now (if (= :away r) away-ms poll-ms))))
                                             m))
                                         next-at watched)]
                     (try (Thread/sleep ^long poll-ms) (catch InterruptedException _ nil))
                     (recur watched next-at)))))))]
    (.setName t "ecsfollow")
    (.setDaemon t true)
    (.start t)
    (fn [] (reset! stop? true) (.interrupt t))))

;; by hand -------------------------------------------------------------------------------------

(defn live
  "`{:desired n :taskdef arn}` of the ECS service bound by `b` as the `local` environment says NOW (a read through `aws/invoke!`,
  persisted: it belongs to a start or stop by a human); nil when there is no record or Floci does not answer."
  [ws b]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :ecs :DescribeServices {:cluster (:cluster b) :services [(:service b)]})
        s (when-not (aws/error? r) (first (:services r)))]
    (when s {:desired (:desiredCount s) :taskdef (:taskDefinition s)})))

(defn set-desired!
  "A start or stop by hand writes the record: `UpdateService` desiredCount `n` through `aws/invoke!` (persisted), then the
  service's record says it was applied, so the watch does not act on it a second time. Call under `(rec/lock-of ws)`."
  [ws name b n live]
  (let [r (aws/invoke! {:ws ws} (env/load ws "local") :ecs :UpdateService {:cluster (:cluster b) :service (:service b) :desiredCount n})]
    (when (aws/error? r)
      (throw (ex-info (str "UpdateService " (:cluster b) "/" (:service b) " desiredCount " n ": " (or (:message r) (:category r)))
                      {:type :service/ecs-update-failed :hint "is the ECS service in the local environment's Floci?"})))
    (let [old (or (rec/read-record ws name) {})]
      (rec/write-record! ws name (-> old (assoc :cluster (:cluster b) :service (:service b) :desired n
                                                :applied {:desired n :taskdef (or (:taskdef live) (get-in old [:applied :taskdef]))}
                                                :action (str "by hand: desired " n) :acted-at (str (store/now)))
                                     (dissoc :error))))))
