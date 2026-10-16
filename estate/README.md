# estate

A small, generic AWS test bed that Deliberator runs against: service A (inbox queue to DynamoDB, publish to
EventBridge, ack endpoint) and service B (bus event to ack in A and a row in Postgres `acks`), a Node Lambda
logger, one Terraform module for Floci and real AWS. Java 21, Spring Boot 3.3.

- `bb estate:build|deploy|up|status|down [--ws W]`: Maven, `terraform apply` on Floci, services as runtime containers.
- Config chain: `application.properties` placeholders, then `terraform/modules/stack/taskdef.json.tftpl` (ECS `environment` and `secrets`), then SSM `/estate/...` and secrets `estate/api-key`, `estate/db-password`.
- Correlation: `X-Correlation-Id`, SQS attribute `correlation`, `detail.correlation`; on every log line as `[%X{correlation}]`.
- `APP_AUTO_PUBLISH` (default false): service A publishes to the bus as soon as it stores an inbox message; `bb estate:up --auto-publish` turns it on. Off, `POST /publish/{id}` does it.
- JDWP: A on 5005, B on 5006. Rebuild a service with `bb estate:build && bb estate:up`.
- `terraform/aws` is validated only, never applied.
- `bb acceptance [--ws W] [--down]` runs `estate/scenarios/order.scn` and `estate/scenarios/payloads/*.scn` against it.
