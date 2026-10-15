(ns deliberator.scenario.vars
  "Variables. Contract: `(snapshot header) -> vars` evaluates `@var`s top-down with builtins
  `uuid`, `now`, `now.epoch`, `correlation` (run id) and `env.X`; `(substitute text vars) -> text`
  throws `{:type :scenario/unknown-var :name :known}`; captures overlay the snapshot.")
