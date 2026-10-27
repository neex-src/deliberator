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
| **Repository** | A repository the workspace knows: an attached checkout (`repo.attach`: name, path) or a bare clone made by `repo.clone <url>` under `repos/<name>.git` (`:bare true`, the url, the default branch). | `workspace.edn :repositories`, `repos/<name>.git` |
| **Worktree** | A disposable checkout of a repository: `worktree.start <repo> [branch]` fetches and branches from the latest main with no questions, `worktree.list|remove`. Git is its only state. "Open PR" (M5.5) makes one the same way. | `worktrees/<repo>/<name>/` |
| **Session** | A unit of work, usually a ticket key. Owns every scenario, run, query, fetched log, config snapshot and scratch file made while it is current. Started and ended explicitly, never deleted, searchable. | `sessions/<key>/` |
| **Environment** | Where operations go: `local` (Floci + Podman, fake credentials, an endpoint that may only be this machine or a private network: never AWS) or a remote (`dev`, `preprod`, …: a profile; read-only unless the record says `:read-only false`, then every mutation needs a confirmation). Also names the logical targets a scenario refers to (service base URLs, queues, tables, log sources, Postgres DSN). | `environments/<env>.edn` |
| **Runtime** | Declarative desired state of the local infrastructure: Podman endpoint, network, Floci, Postgres, service containers. Reconciled lazily. | `runtime.edn` |
| **Service** | A deployable unit (Spring Boot service, Node Lambda): repository, build, container spec, config chain, log pattern, ports. | `services/<name>.edn` |
| **Topology** | Per environment and service: `local` (container on the runtime), `external {url}` or `off` (decision 46; `fake` is M6, planned). Switching one service and rerunning the same scenario is "bringing it in". | `environments/<env>.edn :topology` |
| **Topology** | Per environment and service: `local` (container on the runtime), `external {url}`, `off` or `fake {fake}` (M6.6: the service is served by an HTTP fake that records it or replays a recording). Switching one service and rerunning the same scenario is "bringing it in". | `environments/<env>.edn :topology` |
| **Topology** | Per environment and service: `local` (container on the runtime), `external {url}`, `off` or `fake {fake}` (M6.6: the service is served by an HTTP fake that records it or replays a recording). Switching one service and rerunning the same scenario is "bringing it in". | `environments/<env>.edn :topology` |
| **Scenario** | A plain text file of ordered steps (`.scn`, §8), committed in a repository or ad hoc in a session. | the file |
| **Run** | One execution of a scenario: every input and output raw, timings, correlation ids, a rendered text result. Diffable against any other run. | `sessions/<key>/runs/<ts>-<scenario>/` |
| **Artifact** | Raw bytes fetched or produced, content-addressed, with metadata. Everything else references artifacts. | `raw/sha256/…` |
| **Cached view** | The latest fetched state of something remote with its age. Two exist today: the config snapshot (parameters and secret metadata, `config.refresh`) and what is deployed (ECS services with their task definition revision and image tags, Lambda versions, CodeDeploy deployments; `deployed.refresh`, `deployed.show` answers from it with its age). | `config/<env>.edn`, `cache/<env>/deployed.edn` |
| **Marker** | A source position (file, line) whose log lines are highlighted after a run. | `markers.edn` |
| **Fake** | A runtime container that stands in for a dependency: the HTTP record/replay proxy records a service's traffic under the session and replays it later, scrubbed copies may be committed to a repository. The identity fake is a proposal. | `fakes/<name>.edn`, recordings in `sessions/<key>/fakes/<name>/<stamp>/` |
| **Provider** | An external process contributing operations through the protocol (§6). Built in M6.5. | `<home>/providers/<name>.edn` |
| **Fake** | A runtime container that stands in for a dependency: the HTTP record/replay proxy records a service's traffic under the session and replays it later, scrubbed copies may be committed to a repository. The identity fake is a proposal. | `fakes/<name>.edn`, recordings in `sessions/<key>/fakes/<name>/<stamp>/` |
| **Provider** | An external process contributing operations through the protocol (§6). Built in M6.5. | `<home>/providers/<name>.edn` |

## 3. On-disk layout

`$DELIBERATOR_HOME`, default `~/.local/share/deliberator`:

```
daemon/
  lock  deliberatord.sock  endpoint  daemon.log  daemon.out
workspaces/<ws>/
  workspace.edn  runtime.edn  markers.edn
  audit.log                     remote calls made while no session was current
  environments/<env>.edn        services/<name>.edn        config/<env>.edn   (the config snapshot, with its age)
  cache/<env>/deployed.edn      cache/<env>/deployed.txt   (what is deployed, fetched-at; `deployed.refresh` writes, `deployed.show` reads)
  fakes/<name>.edn              an HTTP fake: mode record|replay|stopped, host port, upstream, the recording in use   fakes/.runtime/   the staged proxy script
  project/                      the IDE project directory of the workspace
  services/<name>/classes/      staged classes for hot reload
  raw/sha256/<2>/<62>           raw/sha256/<2>/<62>.meta.edn
  raw/follow/                   a follow in progress, adopted into raw/ when it ends
  logs/services/<service>/<date>.log   logs/build/   logs/reload/
  repos/<name>.git              bare clones (repo.clone); git fetches into refs/remotes/origin/*
  worktrees/<repo>/<name>/      worktrees (worktree.start); the branch outlives the directory
  github.edn                    optional: where the GitHub token comes from (never the token), the API base
  terraform-plugin-cache/
  sessions/<key>/
    session.edn  notes/  audit.log   (every remote call: time, env, op, bytes, confirmation)
    scenarios/*.scn
    logs/<target>-<ts>.log      one file per fetch or follow, hard-linked into raw/
    logs/insights-<name>-<ts>.txt   a Logs Insights answer as text, the bytes scanned in its header
    ci/runs-<repo>-<workflow>-<ts>.txt   <repo>-<run>-<job>-<id>.log   diff-<repo>-<red>-vs-<green>-<job>.diff   requests.log
                                GitHub Actions as text (ci.runs, ci.log|tail, ci.diff); every API response is a blob in raw/ first
    queries/<name>.insights     the text of a named Insights query     queries/<name>.scn   a named query: a one-step scenario
    config/                     config snapshots taken while the session was current
    fakes/<name>/<stamp>/NNNNN.json   one recorded exchange per file, raw, written by the fake before it answers
    runs/<yyyymmdd-hhmmss>-<scenario>/
      run.edn        scenario path + sha, env, vars, topology, status, step summaries, timings
      scenario.scn   the file as it was run
      result.txt     the rendered run: what the editor shows, what `cat` and `diff` read
      steps/NNN-<kind>-<name>/
        step.edn     facts, asserts, captures, correlation id, timings, raw refs
        request.*    response.*    display.txt    the bytes, byte-exact (hard links into raw/)
```

Built in M6.5: `providers/<name>.edn` and `logs/providers/<name>.log`. Cached views so far: `config/<env>.edn` and `cache/<env>/deployed.edn`; a named query is a
convention, not a concept: a one-step `.scn` under the session's `queries/` and `dlb runs list --scenario <name>` as its
history.

Principles applied: every fetched byte lands in `raw/` before parsing (principle 1); `result.txt`
and every view are derived and regenerable (`dlb runs render`); nothing is named `scratch_37`
(principle 4); everything is text or EDN (principle 3). `.meta.edn` records source, operation,
environment, time, size and content type, so a raw blob is always explainable. The exceptions to
raw persistence are recorded ones: secret values (`GetSecretValue`, decrypted parameters) are
persisted with the value fields `<redacted>` on any environment, metadata and refs kept (decision
49); Podman `inspect` bodies of a container started with real secrets have `Config.Env` scrubbed;
`terraform.drift` persists an allowlisted extract of `terraform show -json`, never the document,
which holds the state's secret values (B1).

## 4. Sessions, environments, security

**Sessions.** `session.start <key>` makes the folder and sets the workspace's current session;
every operation records under the current one; `session.end` closes it (runs can still be read
and re-rendered). Scenarios committed in a repository are run *from* a session: the run lands
in the session, the file stays where it is, and `run.edn` records the path and sha.

**Environments.** The schema has two shapes and the daemon refuses anything else (`env/validate`):

```clojure
{:name "local" :kind :local
 :aws {:endpoint "http://localhost:4566" :region "eu-central-1" :account "000000000000"
       :credentials {:access-key-id "test" :secret-access-key "test"}}
 :targets {:service/a "http://localhost:8081" :queue/inbox "estate-inbox"
           :table/messages "estate-messages" :bus/main "estate-bus"
           :log/service-a {:container "deliberator-default-service-a"} :db/main "jdbc:postgresql://…"}}

{:name "dev" :kind :remote :read-only true
 :aws {:profile "sso-dev" :region "eu-central-1" :account "000000000000"}
 :targets {:service/a "https://…" :log/service-a {:cloudwatch "/ecs/service-a"} …}}
```

A `:local` environment cannot name a profile and must name an endpoint whose host is this machine or a private
network: loopback, an RFC 1918 / link-local / CGNAT address, a bare container name (`floci`), or
`*.internal|*.local|*.localhost|*.lan|*.home.arpa|*.test`. AWS's own domains (`amazonaws.com`, `amazonaws.com.cn`,
`api.aws`) and any other public name are refused at load time, with the reason. Its clients are built from the
record only, never from `~/.aws` or ambient `AWS_*` variables. The account id of the local record is Floci's own `000000000000` until the workspace names the real one
(`workspace.open --account 000000000000`, or `env.save` of `local`): the runtime then starts Floci with `FLOCI_DEFAULT_ACCOUNT_ID`,
so the ARNs of the emulator and of Terraform applied onto it carry the real account and hardcoded ARNs of a real repository line up
.
A `:remote` environment takes its credentials from `:profile` (static keys from the shared credentials file, else
`aws configure export-credentials` for SSO/assume-role profiles); `:read-only` defaults to true; an
`:endpoint` is accepted on it only so tests can point a remote-shaped record at the emulator. Scenarios name targets
(`service/a`, `queue/inbox`), not hosts, so the same file runs against any environment. Every operation that loads a
remote environment (`scenario.run`, `config.refresh`, `deployed.refresh`, `aws.discover`, `terraform.drift`, `logs.fetch`,
`logs.insights`, `service.start --secrets-from`) first asks STS who the profile is and refuses when the account is not the record's
(`account-mismatch`; once per session, environment, account and profile in a daemon's life).
A remote record is authored, not hand-written: `dlb env create <name> --profile P --account A --region R [--read-only false]`
(`env.create`; `env.save` takes the whole record) asks STS the same question before it writes and writes nothing on a
mismatch or a profile that cannot log in (`--check false` for offline authoring); `env.remove` deletes a record (never
`local`; what was fetched through it stays). Credentials from the CLI bridge are refreshed by the wall clock five minutes
before the expiry the CLI reports (a sleeping laptop does not stop a clock); when the CLI fails the still valid ones are
kept and its own words go into the error with the `aws sso login` hint.

**Operation classes.** Every AWS operation the core issues is listed once with a class, `:read` or `:mutate`
(`deliberator.env.ops/table`); an operation that is not in the table is `:mutate`, except that for the services only the
generic `aws` kind reaches (s3 sns states kms kinesis monitoring cloudformation iam) `Get*`/`List*`/`Describe*`/`Head*`
count as reads. Against a `:remote` environment every `:mutate` call is gated by `aws/invoke!`, which every AWS call
goes through:

- `:read-only true` (the default): the call is **refused flat**, no token, nothing runs; the error says to set
  `:read-only false` in the record if mutations are wanted there.
- `:read-only false`: the call is refused with a **confirmation token** bound to the exact workspace, environment,
  account, service, operation and request; the client re-sends the same request carrying the token (the CLI prints the
  `--confirm` line, the IDE asks), the token is single use and lives ten minutes in the daemon's memory. A step whose one
  mutation has no request before it runs (`sqs receive @delete`: the receipt handles come from the receive) is confirmed
  up front for its summary (`DeleteMessageBatch <queue>`) and the consumed token becomes a 60 s single-use pass for
  that one call (`aws/gate!`).

Every attempt, run or refused, is one EDN line in the session's `audit.log` (`:at :env :account :service :op :class
:outcome :confirmation :reason :bytes :status :request-sha :response-sha`). Local calls are neither gated nor audited.
The switch that lets an LLM agent see mutating remote operations only when a workspace enables them is planned (brief
§4.H), not built.

**What a remote run persists.** Principle 1 holds for remote calls too: the serialised request and the raw response
bytes go to `raw/` before anything is decoded, and the step links the same blobs as `request.edn` and
`response.<json|xml>`. Secret values are the exception: on a remote environment (and on any environment when the caller
asks, `:redact-secrets`) the persisted request and response have their secret members replaced by `<redacted>`
(`&lt;redacted&gt;` inside an XML body). Two layers decide what is secret: a table per operation (Secrets Manager values,
SSM values fetched with decryption, KMS plaintext, Cognito tokens; `aws/response-redactions`, `request-redactions`) and a
name rule over every member of every redacted blob (`SecretAccessKey`, `SessionToken`, `Plaintext`, `*Password`, `*Secret`,
`SecretString`, `SecretBinary`, `PrivateKey`, `AccessToken`, `IdToken`, `RefreshToken`: what STS and IAM return, and
whatever service nobody classified yet). `:persist-secrets` keeps the values; the decoded map handed to the caller always
has them. `GetSecretValue` on a local environment is stored in full.

**Local only: `shell`, `op`, `terraform.local`.** The three ways a scenario or an operation reaches outside the daemon's
own AWS client run only against a `:local` environment and cannot reach real AWS through it:

- a `shell` step gets the daemon's environment **scrubbed**: no `AWS_*`, `AWS_CONFIG_FILE` and
  `AWS_SHARED_CREDENTIALS_FILE` at `/dev/null`, IMDS and the pager off, and the run's local record exported as
  `AWS_ENDPOINT_URL`, the fake `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` and `AWS_DEFAULT_REGION`, so `aws sqs
  list-queues` in a step answers from the emulator (tour chapter 9);
- an `op` step is refused on a remote environment and when its body names one (`env`, `secrets-from`, `name` of an
  `env.*` operation), reads included;
- `terraform.local` runs terraform with the same scrub (no `AWS_*`, empty config file) against a root whose provider
  blocks point at the emulator: its own (the estate's Floci root) or through the override file it writes next to the root's
  `.tf` files (`deliberator_override.tf.json`); a root the override cannot cover is refused.

**`sqs receive` on a remote environment is a peek**: `VisibilityTimeout 0`, nothing deleted (the remote question is what
is in the queue, never consuming it); `# @delete` is the consuming receive, one mutation confirmed before the receive as
above. On a local environment a receive deletes what it received unless `# @keep`.

**Secrets for local runs.** Default: the local Floci is seeded with the real parameter and secret *names* and fake
values (Terraform does this from the same module). Fetching real values is a separate gated flow (`service.start
--secrets-from <env>`, two confirmations) and the values go into the container's environment through the
Podman API call only: never into `runtime.edn`, logs, the raw store or the run directory; the fetching calls are
persisted redacted and the container's `podman inspect` is scrubbed before it is persisted.

## 5. The daemon

`deliberatord` is one process per `$DELIBERATOR_HOME`, started by whoever needs it first: the
plugin when a project opens (on the IDE's JBR), or `dlb` when a command finds no socket (on the
`java` the user has). Lifecycle:

- lock file + unix domain socket under `daemon/`; `endpoint` holds a `tcp://127.0.0.1:<port>`
  fallback for hosts where the socket is unusable;
- `hello` handshake carries client kind and expected core version; on `compatible: false` the client
  restarts the daemon with the jar it ships only when that jar is newer and no run is in flight,
  otherwise it fails with `daemon/incompatible` and a hint;
- idle shutdown after a configurable quiet period; the runtime's containers outlive the daemon;
- each request runs on a virtual thread; a run is a supervised group of step threads, cancelled
  by `cancel` or by the client's connection closing if it asked for that;
- development: `cljdev` runs an nREPL in `core/`, the daemon is started from the REPL and the
  IDE connects to the same socket, so the core changes while the IDE stays open.

AWS clients are memoised in the daemon per environment and service; Podman is a client map per
call over the configured endpoint; `sql` opens a connection per step (no pool, no health check).

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

Operations are `area.verb`: `workspace.*`, `session.*`, `env.*`, `runtime.*`, `service.*`,
`topology.*`, `config.*`, `scenario.*`, `runs.*`, `raw.*`, `repo.*`, `logs.*`, `marker.*`, `aws.*`,
`terraform.*`, `fake.*`, `provider.*`, `daemon.*`, plus `op.describe`, which returns every operation with its argument
schema and class. The CLI's help and the IDE's operations form are generated from `op.describe`,
so an operation exists in both the moment the core has it; an agent's tool list generated the same
way (§4.H of the brief) is planned, not built. Planned families, not built: `worktree.*`, `pr.*`,
`ci.*` (M5). `provider.*` (list, stop) and the providers' own `<name>.<op>` exist since M6.5. Terraform: `terraform.local` (plan, apply, destroy, override), `terraform.graph`, `terraform.apply-all`, `terraform.locate`, `terraform.drift`. There is no `query.*` family and none is planned: a named query is a one-step `.scn` under the session's `queries/`. Results are
small by rule: a summary plus paths; full data is on disk.

**Providers** (M6.5, built) speak the same framing on stdio. `<home>/providers/<name>.edn`
names the command; the daemon starts it on demand, asks `op.describe`, exposes its operations
as `<name>.<op>` (in `op.describe`, `dlb`, the IDE), streams its events through, restarts it on the next call after a crash and stops it
with the daemon (`provider.list`, `provider.stop`). the user's REPL-based infrastructure catalog plugs in
here; the core knows nothing about it beyond this file. The sample is `providers/sample/sample.bb`.

## 7. Runtime control

`runtime.edn` is desired state:

```clojure
{:podman {:endpoint "unix:///run/user/1000/podman/podman.sock"}   ; or tcp://host:port
 :network "deliberator"
 :containers
 {:floci    {:image "docker.io/floci/floci:2.3.0" :ports {4566 4566}
             :env {"FLOCI_SERVICES_ECS_MOCK" "true"} :podman-socket true}
  :postgres {:image "docker.io/library/postgres:17" :ports {5432 5432} :init "estate/sql/"}}}
```

Service roles are not in this file: they are derived at `ensure!` time from `services/<name>.edn`
; `runtime.edn :services` may override a service's host ports.

The reconciler talks to the Podman REST API (libpod endpoints, Docker-compatible ones where
libpod lacks something) *(spike: the libpod and docker-compat APIs answered over the unix socket
and over `tcp://127.0.0.1` from `java.net.http`, no client library needed)*. Containers carry
labels `deliberator.workspace` and `deliberator.role`, so status and cleanup never guess by
name. Health: HTTP for Floci (`/_floci/health` lists every service's state), `pg_isready -h 127.0.0.1` (TCP, so not the image's temporary init server) for
Postgres, the actuator for services. `runtime.ensure` and `service.start|restart` reconcile; a
scenario run does not (a cold `dlb run` fails on connection errors until `dlb runtime ensure` or
`bb estate:up`); a warm ensure costs nothing. Logs of every container are mirrored to
`logs/services/` on demand and tailed from there.

The endpoint is the user's rootless Podman socket (`unix://`, the default) or a `tcp://` Podman
service. Floci's own container-backed services (Lambda, ECS without mock) get the runtime's
Podman socket mounted, as the estate already does.

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

- Step kinds: `http`, `sqs send|receive`, `eventbridge put`, `lambda invoke`, `dynamodb
  get|query|scan`, `sql`, `logs` (file, container, CloudWatch), `scheduler list|get`, `wait`,
  `shell`, `op` (any daemon operation) and `aws` (any `service.Operation`); `scenario.kinds` lists
  them as data. Every kind declares its directives, facts and capture roots; the engine owns
  `@assert`, `@capture`, `@until`, `@timeout`, `@body`, `@name`; `@env` and `@var` are header
  directives. A kind may give one of its verbs another shape (`:verb-kinds`: its own target rule, directives, facts and
  `:run`): `logs` tails a log for a window, `logs insights` runs a Logs Insights query (`@group`, `@since`, `@to`; the body is
  the query) and puts the bytes it scanned in its facts and its text.
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

## 9. IntelliJ integration points *(built in M2 and M3, each behind a fixture test; decisions 31–39, 47, 50, 52, 53)*

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
  pattern after a run; logpoints (non-suspending logging breakpoints) are the platform's own under
  the debugger.
- **Config chain.** A `PsiReferenceContributor` from `${placeholder}` in properties/yaml to the
  task-definition template entry and from `valueFrom` to the parameter; a `LocalInspectionTool`
  asks the daemon whether the parameter exists in the selected environment (cached view, with
  age). Literal values resolve; template expressions show raw.
- **Services.** A run-configuration type delegating to `service.start`, remote debug on the
  container's JDWP port, "Reload Changed Classes" against a JBR with
  `-XX:+AllowEnhancedClassRedefinition` *(research: JBR 21 and 25 ship it; the flag is present in
  the local JBR 25.0.4)*, HotswapAgent in-process with its Spring plugin, container restart as the fallback (decision 43, measured on JBR 21 and 25; DevTools dropped).
- **Tool window** "Deliberator": Workspace (sessions, runs, runtime status, environment selector),
  Services and AWS contents. Every item is also an action and a CLI command.

## 10. Milestones and the spikes that gate them

| Milestone | Delivers | Gate spikes | Status (2026-10-25) |
|---|---|---|---|
| M1 core + CLI | store, sessions, environments, runtime (Floci + Postgres), scenario engine with the M1 kinds, daemon, protocol, `dlb`; estate deployed to Floci | done 2026-10-16: aws-api against Floci for SQS, DynamoDB, SSM, Secrets, EventBridge → SQS, Logs, ECS mock, STS; Podman API over unix and TCP; Clojure on JBR 25 | done |
| M2 IDE layer | daemon lifecycle from the plugin, attach/detach, `.scn` editor with inline runs and diff, logs + markers, tool window | attach Gradle/Maven/plain dir without reopen on 2026.2; plugin starts the uberjar on `java.home` | done |
| M3 services | service containers, topology switching, hot reload, config chain navigation and inspection, secrets gating | DCEVM reload into a container JVM on Java 21 and 25 | done headless 2026-10-16; the GUI checks of `docs/demo-m3.md` §9 partly walked |
| M4 remote | SSO environments, read-only inspection, deployed versions, Logs Insights with bytes scanned, named queries, cached views | SSO profile use from the daemon; Insights statistics shape | built in core and CLI (2026-10-26): `:remote` records with profile credentials and `env.create`, confirmation tokens, `audit.log`, remote reads and drift in the explorer, `config.refresh`, `deployed.refresh|show`, `logs.insights` (bytes scanned in every result; the `logs insights` step), named queries as `queries/<name>.scn`; the IDE views (Environments, exception to logs) and real SSO by hand are open (roadmap §3) |
| M5 CI/CD + PRs | Actions job logs as text, poll-tail, fail-vs-success diff, local pipeline (`act` + Floci CodeDeploy), PR worktrees with checks | `act` on Podman; what Floci does with a CodeDeploy deployment | not started |
| M6 Terraform + fakes | override files, scrubbed wrapper, cross-repo apply order, resource → container links; record/replay HTTP fakes, identity-fake extension shape | Terraform AWS provider 6.x against Floci for the estate module | M6a built in core and CLI (2026-10-26): override file per root, workspace account id in Floci, `terraform.graph` and `apply-all`, `terraform.locate`, `service.list` links; gate passed (AWS provider 6.x applies the estate module on Floci); the explorer wiring is the IDE's, fakes (M6b) not built (roadmap §4, §6) |

The fake-external-systems category (brief §4.I) is designed as: a fake is a runtime container
declared like a service plus a provider that records and replays; recordings are scrubbed before
they may be committed; the identity fake issues its own tokens from a local key and serves its
JWKS, with bad-token templates. Its detailed design is a later session.
| M6 Terraform + fakes | override files, scrubbed wrapper, cross-repo apply order, resource → container links; record/replay HTTP fakes, identity-fake extension shape | Terraform AWS provider 6.x against Floci for the estate module | M6a built in core and CLI (2026-10-26): override file per root, workspace account id in Floci, `terraform.graph` and `apply-all`, `terraform.locate`, `service.list` links; gate passed (AWS provider 6.x applies the estate module on Floci); the explorer wiring is the IDE's, M6b (2026-10-27): the provider protocol and the HTTP record/replay fake with the topology mode `fake` built in core and CLI; the identity fake is a proposal (roadmap §4, §6) |

The fake-external-systems category (brief §4.I): the HTTP fake is built: a fake is a runtime container declared in
| M6 Terraform + fakes | override files, scrubbed wrapper, cross-repo apply order, resource → container links; record/replay HTTP fakes, identity-fake extension shape | Terraform AWS provider 6.x against Floci for the estate module | M6a built in core and CLI (2026-10-26): override file per root, workspace account id in Floci, `terraform.graph` and `apply-all`, `terraform.locate`, `service.list` links; gate passed (AWS provider 6.x applies the estate module on Floci); the explorer wiring is the IDE's, M6b (2026-10-27): the provider protocol and the HTTP record/replay fake with the topology mode `fake` built in core and CLI; the identity fake is a proposal (roadmap §4, §6) |

The fake-external-systems category (brief §4.I): the HTTP fake is built: a fake is a runtime container declared in
`fakes/<name>.edn` and derived into the role `fake/<name>` the way a service is (a babashka record/replay reverse proxy in the babashka
image); `fake.record` persists every exchange raw under the session, `fake.replay` serves a recording (the committed, scrubbed copy of
`fake.commit` too), the topology mode `fake` points a service's dependents at it. The identity fake (tokens from a local key, a JWKS,
bad-token templates) is a proposal only: a fake role plus a provider on the protocol of §6; its detailed design is a
session with the user.
