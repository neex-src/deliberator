(ns deliberator.scenario.kinds
  "The kind registry. A kind is a map `{:kind \"http\" :verbs #{nil} :directives [{:name :hint
  :values}] :facts [{:name :type}] :capture-roots #{\"$\"} :concurrent? false :run (fn [ctx step])}`.
  `run` receives `ctx` (`:env :vars :session :run-dir :step-dir :sink :correlation :deadline`) and
  the parsed step, performs the operation through `deliberator.aws`/`podman`/`sql`, writes request
  and response into `:step-dir`, returns `{:facts {...} :body-json x-or-nil :lines n}`. Concurrent
  kinds (`logs`) also get `:arm` before the run. Each kind lives in one namespace under
  `deliberator.steps.*`; the engine owns `@assert @capture @until @timeout @body @name`.")
