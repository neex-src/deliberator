(ns deliberator.ciworkflow
  "A GitHub Actions workflow file as data (piece 03), read with a real YAML parser (clj-yaml): the jobs in `needs` order, their
  steps, the events the workflow listens to and the `workflow_dispatch` inputs (type, default, options, required). `act -l` is only a cross-check in a
  test, never the source.

  `(parse-text text)` -> `{:name :events [\"push\" ...] :inputs [{:name :type :default :description :options :required}] :jobs [{:id :name :needs [..]
  :stage n :steps [{:name :id :uses :run :if}]}] :refs {:secrets [..] :vars [..]}}`. YAML 1.1 reads the key `on` as the boolean `true`; both are taken."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn- ->vec [x] (cond (nil? x) [] (sequential? x) (vec x) :else [x]))

(defn- on-of [m] (let [v (some #(when (contains? m %) (get m %)) [:on true "on" (keyword "true")])] v))

(defn- events [on]
  (cond (nil? on) [] (string? on) [on] (keyword? on) [(name on)]
        (sequential? on) (mapv #(if (keyword? %) (name %) (str %)) on)
        (map? on) (mapv #(if (keyword? %) (name %) (str %)) (keys on))
        :else []))

(defn- inputs [on]
  (let [wd (when (map? on) (get on :workflow_dispatch))
        in (when (map? wd) (get wd :inputs))]
    (vec (for [[k v] in]
           (let [v (if (map? v) v {})]
             {:name (name k) :type (some-> (:type v) str) :default (when (contains? v :default) (let [d (:default v)] (when (some? d) (str d))))
              :description (:description v) :options (when (:options v) (mapv str (:options v))) :required (boolean (:required v))})))))

(defn- stages [jobs]
  (let [by-id (into {} (map (juxt :id identity)) jobs)
        memo (atom {})
        stage (fn stage [id seen]
                (or (@memo id)
                    (let [j (by-id id)
                          s (if (or (nil? j) (seen id)) 0
                                (if (empty? (:needs j)) 0 (inc (apply max (map #(stage % (conj seen id)) (:needs j))))))]
                      (swap! memo assoc id s) s)))]
    (mapv #(assoc % :stage (stage (:id %) #{})) jobs)))

(defn- steps [j]
  (vec (for [s (->vec (:steps j))]
         {:name (some-> (:name s) str) :id (some-> (:id s) str) :uses (some-> (:uses s) str) :run (some-> (:run s) str/trim) :if (some-> (:if s) str)})))

(defn- refs [text re] (vec (sort (distinct (map second (re-seq re text))))))

(defn parse-text
  [text]
  (let [m (yaml/parse-string text)
        _ (when-not (map? m) (throw (ex-info "a workflow file is a YAML map" {:type :ci/bad-workflow})))
        on (on-of m)
        jobs (vec (for [[id j] (:jobs m)]
                    {:id (name id) :name (or (some-> (:name j) str) (name id)) :needs (mapv str (->vec (:needs j))) :steps (steps j)
                     :runs-on (some-> (:runs-on j) str)}))
        ordered (sort-by (juxt :stage) (stages jobs))]
    {:name (or (some-> (:name m) str) nil)
     :events (events on)
     :inputs (inputs on)
     :jobs (vec ordered)
     :refs {:secrets (refs text #"secrets\.([A-Za-z_][A-Za-z0-9_]*)") :vars (refs text #"\bvars\.([A-Za-z_][A-Za-z0-9_]*)")}}))

(defn parse-file [path] (parse-text (slurp (str path))))
