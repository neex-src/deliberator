# estate

A small, generic AWS test bed that Deliberator runs against: service A (inbox queue to DynamoDB, publish to
EventBridge, ack endpoint) and service B (bus event to ack in A and a row in Postgres `acks`), a Node Lambda
logger, one Terraform module for Floci and real AWS. Java 21, Spring Boot 3.3.

- `bb estate:build|deploy|up|status|down [--ws W]`: Maven, `terraform apply` on Floci, services as runtime containers (derived from their descriptions).
- Each service module carries a `deliberator-service.edn` (its description); `bb estate:up` attaches this repository, adopts them (`service.adopt`) and starts them (`service.start`); `dlb service list|stop|start|restart|build`.
- Config chain: `application.properties` placeholders, then `terraform/modules/stack/taskdef.json.tftpl` (ECS `environment` and `secrets`), then SSM `/estate/...` and secrets `estate/api-key`, `estate/db-password`.
- Correlation: `X-Correlation-Id`, SQS attribute `correlation`, `detail.correlation`; on every log line as `[%X{correlation}]`.
- `APP_AUTO_PUBLISH` (default false): service A publishes to the bus as soon as it stores an inbox message; `bb estate:up --auto-publish` turns it on. Off, `POST /publish/{id}` does it.
- JDWP: A on 5005, B on 5006. Rebuild a service with `bb estate:build && bb estate:up`.
- The chain (brief §6): `POST /schedule` on the HTTP API (`api/main` in the local environment, Floci serves it at `<endpoint>/_aws/execute-api/<id>/%24default`) or a direct `lambda invoke function/scheduler-create` creates a one-off EventBridge Scheduler schedule (group `estate-chain`); when it fires, `ecs-scale` sets the desired count of the ECS service `estate-service-a` (deployment controller `CODE_DEPLOY`, M5 deploys to it) and puts the SSM parameter `/estate/chain/desired-count`. `ANY /log` on the same API goes to the `logger` Lambda. `order.scn` runs it end to end (`estate/lambda/{logger,scheduler-create,ecs-scale}`, `terraform/modules/stack/chain.tf`).
- `terraform/aws` is applied only to a sandbox AWS account for tests (`-var prefix=m4chain`, optionally `-var with_ecs=true`), then destroyed; it never has the CODE_DEPLOY service (needs an ALB).
- `bb acceptance [--ws W] [--down]` runs `estate/scenarios/order.scn` and `estate/scenarios/payloads/*.scn` against it.
