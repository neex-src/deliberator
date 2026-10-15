(ns deliberator.env
  "Environments. Contract: `(load ws name) -> env`, validated against the two shapes of
  `docs/architecture.md` section 4; invalid or mixed shapes throw `{:type :env/invalid :problems
  [...]}`. `(aws-client env service)` is an aws-api client memoised per `[env service]`: for `:local`
  built from the record's `:aws` only (`:endpoint-override`, basic credentials), for `:remote` from
  `:profile` (the SDK's profile and SSO chain). `(target env key) -> value` resolves `service/a`,
  `queue/inbox`, `table/messages`, `bus/main`, `log/service-a`, `db/main`, throwing
  `{:type :env/unknown-target}` with the known targets. `(op-class service op) -> :read | :mutate`
  from one table `deliberator.env.ops`; unknown ops are `:mutate`."
  (:refer-clojure :exclude [load]))
