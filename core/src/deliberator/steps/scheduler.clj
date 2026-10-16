(ns deliberator.steps.scheduler
  "Kind `scheduler`: read-back of EventBridge Scheduler. `### scheduler list [group]` calls
  `ListSchedules` (the optional target is a schedule group: an environment key such as `group/x`, or a
  `{{..}}`; a bare word would be the step's name; none means the default group); fact `count` and `$` = the `Schedules` array. `### scheduler get <name>` calls
  `GetSchedule` (the target is the schedule: an environment key, or the bare word on the step
  line, which the step grammar reads as the step's name and this kind takes as the schedule name; `# @group <g>` names its
  group when it is not the default); `$` is the schedule document. Facts: `count elapsed`
  (`count` on `list` only). Creating schedules is not a step kind: schedules are infrastructure."
  (:require [deliberator.scenario.kinds :as kinds]
            [deliberator.steps.aws :as sa]
            [deliberator.steps.common :as c]))

(defn run [ctx step]
  (case (:verb step)
    "list" (let [g (sa/target-value ctx step)
                 r (sa/call! ctx :scheduler :ListSchedules (cond-> {} g (assoc :GroupName g)))
                 res (sa/result ctx r "ListSchedules" {})
                 items (get (sa/parse-json (:response-bytes res)) "Schedules" [])]
             (assoc res :facts {"count" (count items) "elapsed" (sa/elapsed r)}
                    :body-json (vec items) :lines (count items)))
    "get" (let [n (or (sa/target-value ctx step) (:name step)
                     (sa/fail "scheduler get needs a schedule" "### scheduler get schedule/x, or ### scheduler get <schedule-name>"))
                r (sa/call! ctx :scheduler :GetSchedule (cond-> {:Name n} (c/dir step "group") (assoc :GroupName (c/dir step "group"))))
                res (sa/result ctx r "GetSchedule" {:facts {"elapsed" (sa/elapsed r)}})]
            (assoc res :body-json (sa/parse-json (:response-bytes res))))))

(def kind
  {:kind "scheduler" :verbs #{"list" "get"} :target :optional
   :directives [{:name "group" :hint "<group>" :doc "get: the schedule's group (default group when absent)"}]
   :facts [{:name "count" :type :number}]
   :capture-roots #{"$"} :concurrent? false
   :run run})

(kinds/register! kind)
