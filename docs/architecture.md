# Deliberator — architecture

Deliberator is one IntelliJ plugin for deliberating a microservice + event-driven AWS estate.
IntelliJ is the kernel (editor, indexing, analysis, debugger), Podman plus the Floci emulator are
the BIOS (the runtime every local piece runs on), Deliberator is user space: workflows, commands,
persistence, views. Operations on the system are system calls against an *environment*, never
IDE concerns, so every one of them exists headlessly first and gets an editor surface second.

This document is top-down: concepts and boundaries, then the technology each boundary rests on.
Facts marked *(spike)* were verified on 2026-10-16 against Floci 2.3.0, Podman 6.1.4 and the
IDE's bundled JetBrains Runtime 25; facts marked *(research)* come from documentation and are
retired by a spike in the milestone that depends on them.

## 1. Three layers, one plugin

```
 ide/   Kotlin IntelliJ plugin        thin: editor, gutter, inlays, tool window, attach/detach,
        (com.neex.deliberator)        run configurations. Talks only to the daemon.
 cli/   babashka `dlb`                thin: argv → one request, prints the summary, exits.
 core/  Clojure `deliberatord`        everything: domain model, store, sessions, environments,
                                      runtime control, scenario engine, AWS and Podman calls,
                                      providers. Runs as its own JVM, speaks the protocol (§6).
 estate/  two Spring Boot services, Node Lambdas, Terraform, Postgres, scenarios: the test bed
          and the acceptance suite, deployable to Floci and to a personal AWS sandbox.
```

Rules the build enforces: `core/` never references `com.intellij`; `ide/` never references
`cognitect.aws`, `software.amazon`, Podman or JDBC; `cli/` contains no business logic. The
plugin zip carries the core as an uberjar and starts it on the IDE's own JetBrains Runtime
(`java.home`), so no extra JDK is needed on any machine *(spike: Clojure 1.12 starts on JBR 25.0.4
in 0.6 s)*. One repository, one plugin id, one distribution.

## 2. Domain model

| Concept | What it is | Persisted as |
|---|---|---|
| **Workspace** | The long-lived context: attached repositories, environments, runtime, topology, sessions. One IDE project is one workspace's window. | `workspaces/<ws>/workspace.edn` |
| **Repository** | An entry in the local store: name, remote, bare clone, default branch. | `repos/<name>.git` |
| **Worktree** | A disposable checkout of a repository at a branch or PR head; "start working" and "open PR" each make one and touch nothing else. | `worktrees/<repo>/<name>/` |
| **Session** | A unit of work, usually a ticket key. Owns every scenario, run, query, fetched log, config snapshot and scratch file made while it is current. Started and ended explicitly, never deleted, searchable. | `sessions/<key>/` |
| **Environment** | Where operations go: `local` (Floci + Podman, fake credentials, structurally unable to reach AWS) or a remote (`dev`, `preprod`, …: SSO profile, read-only by default). Also names the logical targets a scenario refers to (service base URLs, queues, tables, log sources, Postgres DSN). | `environments/<env>.edn` |
| **Runtime** | Declarative desired state of the local infrastructure: Podman endpoint, network, Floci, Postgres, service containers. Reconciled lazily. | `runtime.edn` |
| **Service** | A deployable unit (Spring Boot service, Node Lambda): repository, build, container spec, config chain, log pattern, ports. | `services/<name>.edn` |
| **Topology** | Per service: `local` (container on the runtime), `remote <env>` or `fake`. Switching one service and rerunning the same scenario is "bringing it in". | `topology.edn` |
| **Scenario** | A plain text file of ordered steps (`.scn`, §8), committed in a repository or ad hoc in a session. | the file |
| **Run** | One execution of a scenario: every input and output raw, timings, correlation ids, a rendered text result. Diffable against any other run. | `sessions/<key>/runs/<ts>-<scenario>/` |
| **Artifact** | Raw bytes fetched or produced, content-addressed, with metadata. Everything else references artifacts. | `raw/sha256/…` |
| **Cached view** | The latest fetched state of something remote (deployed version, config, parameters) with its age. | `cache/<env>/<kind>/<key>.edn` |
| **Marker** | A source position (file, line) whose log lines are highlighted after a run. | `markers.edn` |
| **Provider** | An external process contributing operations through the protocol (§6). | `providers/<name>.edn` |

## 3. On-disk layout

`$DELIBERATOR_HOME`, default `~/.local/share/deliberator`:

```
daemon/
  deliberatord.sock  endpoint  daemon.log  version
workspaces/<ws>/
  workspace.edn  runtime.edn  topology.edn  markers.edn
  environments/<env>.edn        services/<name>.edn        providers/<name>.edn
  repos/<name>.git              worktrees/<repo>/<name>/
  raw/sha256/<2>/<62>           raw/sha256/<2>/<62>.meta.edn
  cache/<env>/<kind>/<key>.edn
  logs/services/<service>/<date>.log
  sessions/<key>/
    session.edn  notes.md
    scenarios/*.scn
    runs/<yyyymmdd-hhmmss>-<scenario>/
      run.edn        scenario path + sha, env, vars, status, step summaries, timings
      result.txt     the rendered run: what the editor shows, what `cat` and `diff` read
      steps/NNN-<kind>-<name>/
        step.edn     facts, asserts, captures, correlation id, timings, raw refs
        request.*    response.*     the bytes, byte-exact (hard links into raw/)
    queries/<name>.{sql,dql,insights}
    logs/<env>/<source>/<ts>.log
    fetched/<env>/<kind>/<name>-<ts>.{json,txt}
    audit.log      every remote call: time, env, op, bytes, confirmation
```

Principles applied: every fetched byte lands in `raw/` before parsing (principle 1); `result.txt`
and every view are derived and regenerable (`dlb runs render`); nothing is named `scratch_37`
(principle 4); everything is text or EDN (principle 3). `.meta.edn` records source, operation,
environment, time, size and content type, so a raw blob is always explainable. Secret values are
the one exception to raw persistence: `GetSecretValue` and decrypted parameters from a remote
environment are stored redacted unless the call was made with the explicit persist flag.

## 4. Sessions, environments, security

**Sessions.** `session.start <key>` makes the folder and sets the workspace's current session;
every operation records under the current one; `session.end` closes it (runs can still be read
and re-rendered). Scenarios committed in a repository are run *from* a session: the run lands
in the session, the file stays where it is, and `run.edn` records the path and sha.

**Environments.** The schema has two shapes and the daemon refuses anything else:

```clojure
{:name "local" :kind :local
 :aws {:endpoint "http://localhost:4566" :region "eu-central-1" :account "123456789012"
       :credentials {:access-key-id "test" :secret-access-key "test"}}
 :targets {:service/a "http://localhost:8081" :queue/inbox "estate-inbox"
           :table/messages "estate-messages" :bus/main "estate-bus"
           :log/service-a {:container "estate-service-a"} :db/main "jdbc:postgresql://…"}}

{:name "dev" :kind :remote :read-only true
 :aws {:profile "sso-dev" :region "eu-central-1" :account "123456789012"}
 :targets {:service/a "https://…" :log/service-a {:cloudwatch "/ecs/service-a"} …}}
```

A `:local` environment cannot name a profile and must name an endpoint; its clients are built
from the record only, never from `~/.aws` or ambient `AWS_*` variables. The fake account id
matches the real one so hardcoded ARNs line up. Scenarios name targets (`service/a`,
`queue/inbox`), not hosts, so the same file runs against any environment.

**Operation classes.** Every AWS operation the core can issue is listed once with a class,
`:read` or `:mutate`. Against a `:remote` environment a `:mutate` call is refused with a
confirmation token; the client re-sends the same request carrying the token, and the call and
its confirmation go to `audit.log`. The CLI and the IDE show the same confirmation; an LLM agent
sees mutating remote operations only when the workspace enables them.

**Secrets for local runs.** Default: the local Floci is seeded with the real parameter and secret
*names* and fake values (Terraform does this from the same module). Fetching real values is a
separate `:mutate`-class flow with its own confirmation, and the values go into the container's
environment through the Podman API call only: never into `runtime.edn`, logs, the raw store or
the run directory.

**Terraform.** `dlb tf <env> <args>` runs terraform with a scrubbed process environment (no
`AWS_*`, an empty `AWS_CONFIG_FILE`), the per-repository gitignored override file (provider
endpoints and backend pointed at Floci, `skip_*` validation flags, path-style S3) and fake keys.
Real AWS is unreachable from that process by construction.

## 5. The daemon

`deliberatord` is one process per `$DELIBERATOR_HOME`, started by whoever needs it first: the
plugin when a project opens (on the IDE's JBR), or `dlb` when a command finds no socket (on the
`java` the user has). Lifecycle:

- lock file + unix domain socket under `daemon/`; `endpoint` holds a `tcp://127.0.0.1:<port>`
  fallback for hosts where the socket is unusable;
- `hello` handshake carries client kind and expected core version; a mismatch makes the client
  restart the daemon with the jar it ships;
- idle shutdown after a configurable quiet period; the runtime's containers outlive the daemon;
- each request runs on a virtual thread; a run is a supervised group of step threads, cancelled
  by `cancel` or by the client's connection closing if it asked for that;
- development: `cljdev` runs an nREPL in `core/`, the daemon is started from the REPL and the
  IDE connects to the same socket, so the core changes while the IDE stays open.

Long-lived clients (AWS per environment, Podman, JDBC pools) live in the daemon and are reused
across runs; health is checked on first use after an error.

## 6. The protocol

Newline-delimited JSON over the socket. Every request has an `id`; the daemon streams zero or
more `event` lines and exactly one terminal line:

```json
{"id":"c7","op":"scenario.run","args":{"path":"estate/scenarios/order.scn","env":"local"}}
{"id":"c7","event":"run.started","run":"sessions/T-123/runs/20261016-101502-order"}
{"id":"c7","event":"step.started","step":1,"kind":"http","name":"create-order"}
{"id":"c7","event":"line","step":5,"text":"2026-10-16T10:15:03 INFO ... c-7f3a stored"}
{"id":"c7","event":"step.finished","step":1,"status":"ok","facts":{"status":201,"elapsed":84}}
{"id":"c7","ok":true,"result":{"status":"ok","steps":9,"failed":0,
  "paths":{"run":"…/20261016-101502-order","result":"…/result.txt"}}}
{"id":"c8","ok":false,"error":{"type":"confirm-required","token":"k9…",
  "summary":"PutParameter /app/flag on dev (account 1234…)"}}
```

Operations are `area.verb`: `session.*`, `env.*`, `runtime.*`, `service.*`, `scenario.*`,
`runs.*`, `raw.*`, `repo.*`, `worktree.*`, `pr.*`, `logs.*`, `query.*`, `ci.*`, `tf.*`,
`provider.*`, plus `op.describe`, which returns every operation with its argument schema and
class. The CLI's help, the IDE's actions and an agent's tool list are all generated from
`op.describe`, so an operation exists in all three the moment the core has it (§4.H of the
brief). Results are small by rule: a summary plus paths; full data is on disk.

**Providers** speak the same framing on stdio. `providers/<name>.edn` names the command; the
daemon starts it on demand, asks `op.describe`, and exposes its operations as `<name>.<op>`,
streaming its events through. the user's REPL-based infrastructure catalog plugs in here; the core
knows nothing about it beyond this file.

## 7. Runtime control

`runtime.edn` is desired state:

```clojure
{:podman {:endpoint "unix:///run/user/1000/podman/podman.sock"}   ; or tcp://host:port, ssh://user@host
 :network "deliberator"
 :containers
 {:floci    {:image "docker.io/floci/floci:2.3.0" :ports {4566 4566}
             :env {"FLOCI_SERVICES_ECS_MOCK" "true"} :podman-socket true}
  :postgres {:image "docker.io/library/postgres:17" :ports {5432 5432} :init "estate/sql/"}
  :service/a {:from :service "a"}}}                                 ; rendered from services/a.edn
```

The reconciler talks to the Podman REST API (libpod endpoints, Docker-compatible ones where
libpod lacks something) *(spike: the libpod and docker-compat APIs answered over the unix socket
and over `tcp://127.0.0.1` from `java.net.http`, no client library needed)*. Containers carry
labels `deliberator.workspace` and `deliberator.role`, so status and cleanup never guess by
name. Health: HTTP for Floci (`/_floci/health` lists every service's state), `pg_isready` for
Postgres, the actuator for services. Any operation that targets the `local` environment calls
`runtime.ensure` first; a cold start costs seconds, a warm one nothing. Logs of every container
are mirrored to `logs/services/` on demand and tailed from there.

Machine profiles: on Linux the endpoint is the user's Podman socket. On the Mac the runtime
host is the existing Podman machine, inside it a privileged container with systemd and nested
rootless Podman runs Floci and the services; the plugin's endpoint is that inner Podman's API
over TCP or SSH *(research; retired by the M3 spike on the Mac)*. Floci's own container-backed
services (Lambda, ECS without mock) get the runtime's Podman socket mounted, as the estate
already does.

## 8. Scenarios and runs

The format keeps what the PoC proved and moves wiring into the
environment:

```
# @env local
# @var orderId = ord-{{uuid}}

### http create-order
# @assert status == 201
# @capture id = $.orderId
POST {{service/a}}/orders
Content-Type: application/json

{ "orderId": "{{orderId}}" }

### sqs send queue/inbox
# @attr id={{orderId}}
# @body ./payloads/01-simple.json

### dynamodb get table/messages
# @until $.Item.status.S == "ACKED" within 10s
{ "id": {"S": "{{orderId}}"} }

### logs log/service-a
# @filter {{correlation}}
# @for 10s
# @assert lines >= 2
```

- Step kinds in M1: `http`, `sqs send|receive`, `eventbridge put`, `lambda invoke`,
  `dynamodb get|query|scan`, `sql`, `logs` (file, container, later CloudWatch), `scheduler
  list|get`, `wait`. Every kind declares its directives, facts and capture roots; the engine owns
  `@assert`, `@capture`, `@until`, `@timeout`, `@body`, `@env`.
- Every step gets a correlation id (`X-Correlation-Id` header, `correlation` message attribute,
  `detail.correlation` on events), recorded in `step.edn`; `{{correlation}}` is the run's id,
  `{{step.correlation}}` the step's. Log steps filter by it exactly.
- Bodies are byte-exact, inline or from a file; assertions mark and continue; `@until` polls the
  step until an assertion holds or the window ends.
- A run writes `run.edn`, `steps/`, raw refs and `result.txt`; `runs.diff a b` diffs the two
  `result.txt` files with volatile fields (ids, timestamps, elapsed) normalised.

The engine is one function from (scenario, environment, session) to a run directory, with a
sink interface the daemon streams through; the CLI runner and the IDE gutter call the same
operation.

## 9. IntelliJ integration points *(research; M2 retires each with a fixture test)*

- **Workspace window.** The IDE project for a workspace is a near-empty project directory under
  the workspace; repositories are attached to it, never opened as projects. Gradle builds via
  `GradleSettings.linkProject` + `ExternalSystemUtil.refreshProject`, Maven via
  `MavenProjectsManager.addManagedFiles`, plain directories (Terraform) via the Workspace Model
  (`ModuleEntity` + `ContentRootEntity`); detaching is the inverse. Indexing covers the new root
  only; no restart. The Marketplace "Multi-Project Workspace" plugin has no public API and is not
  a dependency.
- **`.scn` editor.** Own language: a lexer for colours, a flat PSI only to host folding and
  completion per language (the PoC found a plain-text PSI is not ours), gutter run icons, the
  latest run's `result.txt` sections shown as fold regions or block inlays under each step,
  derived from the run directory and refreshed by daemon events. Run history per scenario and
  diff of two runs through the IDE diff viewer.
- **Logs.** Fetched logs open as text files; a correlation filter action; markers are gutter
  marks stored in `markers.edn` and matched against `logger`/line metadata from the service's log
  pattern after a run; non-suspending logging breakpoints are investigated when a service is
  attached under the debugger (M3).
- **Config chain.** A `PsiReferenceContributor` from `${placeholder}` in properties/yaml to the
  task-definition template entry and from `valueFrom` to the parameter; a `LocalInspectionTool`
  asks the daemon whether the parameter exists in the selected environment (cached view, with
  age). Literal values resolve; template expressions show raw.
- **Services.** A run-configuration type delegating to `service.start`, remote debug on the
  container's JDWP port, "Reload Changed Classes" against a JBR with
  `-XX:+AllowEnhancedClassRedefinition` *(research: JBR 21 and 25 ship it; the flag is present in
  the local JBR 25.0.4)*, HotswapAgent optional, Spring DevTools fallback.
- **Tool window** "Deliberator": sessions, runs, runtime status, environment selector. Every item
  is also an action and a CLI command.

## 10. Milestones and the spikes that gate them

| Milestone | Delivers | Gate spikes |
|---|---|---|
| M1 core + CLI | store, sessions, environments, runtime (Floci + Postgres), scenario engine with the M1 kinds, daemon, protocol, `dlb`; estate deployed to Floci | done 2026-10-16: aws-api against Floci for SQS, DynamoDB, SSM, Secrets, EventBridge → SQS, Logs, ECS mock, STS; Podman API over unix and TCP; Clojure on JBR 25 |
| M2 IDE layer | daemon lifecycle from the plugin, attach/detach, `.scn` editor with inline runs and diff, logs + markers, tool window | attach Gradle/Maven/plain dir without reopen on 2026.2; plugin starts the uberjar on `java.home` |
| M3 services | service containers, topology switching, hot reload, config chain navigation and inspection, secrets gating | DCEVM reload into a container JVM on Java 21 and 25; Mac nested-Podman endpoint |
| M4 remote | SSO environments, read-only inspection, deployed versions, Logs Insights with bytes scanned, named queries, cached views | SSO profile use from the daemon; Insights statistics shape |
| M5 CI/CD + PRs | Actions job logs as text, poll-tail, fail-vs-success diff, local pipeline (`act` + Floci CodeDeploy), PR worktrees with checks | `act` on Podman; what Floci does with a CodeDeploy deployment |
| M6 Terraform + fakes | override files, scrubbed wrapper, cross-repo apply order, resource → container links; record/replay HTTP fakes, identity-fake extension shape | Terraform AWS provider 6.x against Floci for the estate module |

The fake-external-systems category (brief §4.I) is designed as: a fake is a runtime container
declared like a service plus a provider that records and replays; recordings are scrubbed before
they may be committed; the identity fake issues its own tokens from a local key and serves its
JWKS, with bad-token templates. Its detailed design is a later session.
