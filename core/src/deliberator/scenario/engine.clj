(ns deliberator.scenario.engine
  "Runs a scenario. Contract: `(run! ws env scenario-path & {:keys [session sink steps]})`: parse,
  snapshot vars, arm concurrent steps, run sequential steps in order (each on its own virtual
  thread under the run's scope, cancellable), asserts (mark and continue), captures, write
  `step.edn` per step and `run.edn`, then `render/result!`. `@until` re-runs the step every 500 ms
  until its assert holds or the window ends; the final attempt's facts stand. The sink is a
  protocol with `event!`.")
