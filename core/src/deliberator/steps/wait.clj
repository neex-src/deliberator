(ns deliberator.steps.wait
  "Kind `wait`: the only intentional sleep. `### wait` with `# @for 2s` (default 1s); fact `elapsed`
  (implicit, measured by the engine). Its default timeout is an hour so `@for` is the only clock."
  (:require [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.common :as c]))

(defn run [_ctx step]
  (let [ms (c/duration-dir step "for" 1000)]
    (Thread/sleep (long ms))
    {:display (str "waited " ms " ms")}))

(def kind
  {:kind "wait" :verbs #{nil} :target :none
   :directives [{:name "for" :hint "<duration>" :values ["1s" "2s" "5s"] :doc "how long to wait"}]
   :facts [] :capture-roots #{} :concurrent? false
   :default-timeout-ms 3600000
   :run run})

(kinds/register! kind)
