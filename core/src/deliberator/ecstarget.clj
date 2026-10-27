(ns deliberator.ecstarget
  "B38: a target that is the CURRENT address of an ECS service's task. A Fargate task gets a new address
  whenever it is replaced (a deploy, a scale, a crash), so the address is never written into the record: the record holds
  where to look, and every use of the target looks.

      :service/a {:ecs {:cluster \"rt1028\" :service \"rt1028-service-a\" :port 8081}}
      :db/main   {:ecs {:cluster \"rt1028\" :service \"rt1028-service-b\" :port 5432 :address :private}
                  :format \"jdbc:postgresql://{host}:{port}/estate?user=estate\"}

  `resolve-target` turns such a value into a string; any other target value is returned as it is. The string is `:format`
  with `{host}` and `{port}` replaced, default `http://{host}:{port}` (just the host when there is no `:port`).

  How: `ecs:ListTasks` (RUNNING tasks of the service), `ecs:DescribeTasks`, the newest RUNNING task by `startedAt` (during a
  deploy the new task, as soon as it runs), and its address. `:address :public` (the default) is the public IP of the task's
  network interface (`ec2:DescribeNetworkInterfaces`, a permission the reader policies lack: the error names it);
  `:address :private` is the `privateIPv4Address` the task itself reports, for a Deliberator inside the VPC. Reads only, through
  `deliberator.aws/invoke!` (persisted raw, audited like any read). No cache: a step that uses the target asks again."
  (:require [clojure.string :as str]
            [deliberator.aws :as aws]))

(defn spec?
  "True when the target value `v` is an ECS service spec."
  [v] (and (map? v) (map? (:ecs v))))

(defn- fail [msg hint data]
  (throw (ex-info msg (merge {:type :ecs/target :hint hint} data))))

(defn- call! [ctx env service op request what]
  (let [r (aws/invoke! ctx env service op request)]
    (if (aws/error? r)
      (fail (str what ": " (or (:message r) (:code r) (some-> (:category r) name)))
            (or (:hint r) (str "the call " (name service) ":" (name op) " failed"))
            {:aws (dissoc r :raw)})
      r)))

(defn- millis [t]
  (cond (instance? java.util.Date t) (.getTime ^java.util.Date t)
        (instance? java.time.Instant t) (.toEpochMilli ^java.time.Instant t)
        (number? t) (long (* 1000 t))
        :else 0))

(defn newest-running
  "The RUNNING task of `tasks` (DescribeTasks entries) that started last, nil when none runs."
  [tasks]
  (->> tasks
       (filter #(= "RUNNING" (:lastStatus %)))
       (sort-by (comp - millis :startedAt))
       first))

(defn task-interface
  "`{:eni \"eni-..\" :private \"10.0.0.5\"}` from the ElasticNetworkInterface attachment of a task."
  [task]
  (let [d (->> (:attachments task)
               (filter #(= "ElasticNetworkInterface" (:type %)))
               (mapcat :details)
               (into {} (map (fn [{:keys [name value]}] [name value]))))]
    {:eni (get d "networkInterfaceId") :private (get d "privateIPv4Address")}))

(defn- field [m & ks] (some #(get m %) ks))

(defn public-ip
  "The public IP in a DescribeNetworkInterfaces response (member names as aws-api's ec2 client spells them)."
  [r]
  (some (fn [ni] (field (field ni :Association :association) :PublicIp :publicIp))
        (field r :NetworkInterfaces :networkInterfaceSet)))

(defn host-of
  "The host of the service's current task: `{:host :task :started}` or a thrown `:ecs/target` error."
  [ctx env {:keys [cluster service address] :or {address :public}}]
  (when-not (and (string? cluster) (string? service))
    (fail "an :ecs target needs :cluster and :service" "{:ecs {:cluster \"c\" :service \"s\" :port 8080}}" {}))
  (let [where (str "service " service " in cluster " cluster)
        arns (:taskArns (call! ctx env :ecs :ListTasks {:cluster cluster :serviceName service :desiredStatus "RUNNING"}
                               (str "cannot list the tasks of " where)))
        tasks (when (seq arns)
                (:tasks (call! ctx env :ecs :DescribeTasks {:cluster cluster :tasks (vec arns)}
                               (str "cannot describe the tasks of " where))))
        task (newest-running tasks)]
    (when-not task
      (fail (str where " has no running task")
            (str "scale it up (desired count > 0) and wait for the task to run; " (count arns) " task(s) are listed"
                 (when (seq tasks) (str ", " (str/join ", " (distinct (map :lastStatus tasks))))))
            {:service service :cluster cluster}))
    (let [{:keys [eni private]} (task-interface task)
          host (case (keyword address)
                 :private private
                 :public (do (when-not eni (fail (str "the task of " where " reports no network interface")
                                                 "a Fargate task has an ElasticNetworkInterface attachment once it runs; try again in a moment" {}))
                             (let [ip (public-ip (call! ctx env :ec2 :DescribeNetworkInterfaces {:NetworkInterfaceIds [eni]}
                                                        (str "cannot read the public address of the task of " where)))]
                               (or ip (fail (str "the task of " where " has no public IP")
                                            "its network interface has no association: assignPublicIp is DISABLED; use :address :private from inside the VPC" {}))))
                 (fail (str "unknown :address " (pr-str address)) ":public or :private" {}))]
      (when-not (string? host)
        (fail (str "the task of " where " reports no " (name address) " address") "the task is not attached to a network yet; try again" {}))
      {:host host :task (:taskArn task) :started (:startedAt task)})))

(defn render
  "The target string of host `host` for `spec` (the whole target value)."
  [spec host]
  (let [port (get-in spec [:ecs :port])
        fmt (or (:format spec) (when port "http://{host}:{port}") "{host}")]
    (-> fmt (str/replace "{host}" host) (str/replace "{port}" (str port)))))

(defn resolve-target
  "The target value `v` of environment `env` as a string when it is an ECS spec (looked up now), else `v` itself. `ctx` is
  `{:ws .. :session ..}`."
  [ctx env v]
  (if (spec? v)
    (render v (:host (host-of ctx env (:ecs v))))
    v))
