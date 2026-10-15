(ns deliberator.runtime
  "The local runtime, reconciled lazily. Contract: `(ensure! ws)` reconciles `runtime.edn`
  (network, images, containers labelled `deliberator.workspace=<ws>` and `deliberator.role=<role>`),
  idempotent, emitting `runtime.pulling`, `runtime.starting`, `runtime.ready`; `(status ws) ->
  {:containers [{:role :name :state :since :health}] :podman {...}}`; `(down! ws)`;
  `(logs ws role opts)`. Health per role: Floci `GET /_floci/health`, Postgres `pg_isready` via
  exec, services `GET /actuator/health`. `:podman-socket true` mounts the runtime's own socket
  into the container (Floci's Lambda needs it).")
