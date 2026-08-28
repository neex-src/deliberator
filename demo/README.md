# Demo stack

`orders.tellij` is the workbench; the stack it talks to runs in podman on the host network.

```bash
./up.sh                  # everything: pg kafka sqs consumer
./up.sh pg kafka         # or a subset
./status.sh
./down.sh
```

| container | what | where |
|---|---|---|
| `tellij-pg` | Postgres 16, `log_statement=all` | `localhost:5432`, db `demo`, user `demo`/`demo` |
| `tellij-kafka` | apache/kafka 3.9 single-node KRaft, topic `orders` | `localhost:9092` |
| `tellij-sqs` | ElasticMQ (SQS-compatible), queue `payments` (created by the consumer) | `http://localhost:9324` |
| `tellij-consumer` | `../demo-consumer` (Kotlin): Kafka `orders` → INSERT, SQS `payments` → UPDATE status | logs to `logs/consumer.log` |

- `tellij-pg` runs with `log_statement=all`, so `podman logs tellij-pg` shows every statement —
  the `### logs pg` block tails exactly that, filtered by the current `{{orderId}}`.
- `pg-init/schema.sql` creates the `orders` table on first start.
- `tellij-env.json` holds `PG_PASSWORD` for `{{env.PG_PASSWORD}}` in the `@connect` line.
- `logs/consumer.log` is written by the consumer container; `../scripts/live/fake-log.sh` can play
  it without the stack.

Peek into the database from the shell: `podman exec tellij-pg psql -U demo -c 'select * from orders'`.
