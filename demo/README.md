# Demo stack

The workbench is `../demo-api/workbench/orders.tellij` (inside the Spring service's project, which
is what the sandbox IDE opens); the stack it talks to runs in podman on the host network, plus the
Spring service itself on the host.

```bash
./up.sh                  # everything: pg kafka sqs mongo consumer api
./up.sh pg kafka         # or a subset
./status.sh
./down.sh
```

| container | what | where |
|---|---|---|
| `tellij-pg` | Postgres 16, `log_statement=all` | `localhost:5432`, db `demo`, user `demo`/`demo` |
| `tellij-kafka` | apache/kafka 3.9 single-node KRaft, topic `orders` | `localhost:9092` |
| `tellij-sqs` | ElasticMQ (SQS-compatible), queue `payments` (created by the consumer) | `http://localhost:9324` |
| `tellij-mongo` | MongoDB 7; the consumer writes every event it receives into `demo.events` | `mongodb://localhost:27017/demo` |
| `demo-api` (host JVM) | `../demo-api`: Spring Boot 3, `OrdersApi` interface with the annotation wall, `OrdersController` bare; JDWP on :5005 | `http://localhost:8080`, `/v3/api-docs`, log `logs/api.log` |
| `tellij-consumer` | `../demo-consumer` (Kotlin): Kafka `orders` → INSERT, SQS `payments` → UPDATE status; `GET /orders/{id}`, `GET /health` | logs to `logs/consumer.log`, `http://localhost:8085` |

- `tellij-pg` runs with `log_statement=all`, so `podman logs tellij-pg` shows every statement —
  the `### logs pg` block tails exactly that, filtered by the current `{{orderId}}`.
- `pg-init/schema.sql` creates the `orders` table on first start.
- `tellij-env.json` holds `PG_PASSWORD` for `{{env.PG_PASSWORD}}` in the `@connect` line.
- `logs/consumer.log` is written by the consumer container; `../scripts/live/fake-log.sh` can play
  it without the stack.

Peek into the database from the shell: `podman exec tellij-pg psql -U demo -c 'select * from orders'`.
