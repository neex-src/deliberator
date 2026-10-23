(ns deliberator.awsmodel
  "The AWS service models that ship with aws-api, as data for the editor: what an operation's request looks like.
  `input` is a tree `{:type :required [..] :order [..] :members {name node} :member node :enum [..] :doc}`; a shape that
  contains itself is cut at `max-depth`, as is a very wide one (members beyond `max-members` are dropped, the
  `required` ones first kept)."
  (:require [cognitect.aws.service :as service]
            [deliberator.env.ops :as ops]))

(def ^:private max-depth 7)
(def ^:private max-members 120)

(defn- description [api]
  (try (service/service-description (name api)) (catch Throwable _ nil)))

(defn- node [shapes ref depth]
  (let [s (get shapes (keyword (:shape ref)))
        t (:type s)]
    (cond
      (nil? s) {:type "unknown"}
      (>= depth max-depth) {:type t :cut true}
      :else
      (case t
        "structure" (let [req (set (:required s))
                          ms (->> (:members s)
                                  (sort-by (fn [[k _]] [(if (contains? req (name k)) 0 1) (name k)]))
                                  (take max-members))]
                      {:type t :required (vec (:required s)) :order (mapv (comp name key) ms)
                       :members (into {} (for [[k v] ms] [(name k) (node shapes v (inc depth))]))})
        "list" {:type t :member (node shapes (:member s) (inc depth))}
        "map" {:type t :key (node shapes (:key s) (inc depth)) :value (node shapes (:value s) (inc depth))}
        (cond-> {:type t} (:enum s) (assoc :enum (vec (:enum s))))))))

(defn input
  "The input schema of `service`/`op`, nil when the service or the operation is not known."
  [service op]
  (let [api (ops/api-name service)]
    (when-let [d (description api)]
      (when-let [o (get-in d [:operations (keyword op)])]
        {:service (name api) :op op :class (name (ops/op-class api op))
         :input (if-let [in (:input o)] (node (:shapes d) in 0) {:type "structure" :members {}})}))))
