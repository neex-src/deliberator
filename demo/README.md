# Demo stack

`orders.tellij` is the workbench; the stack it talks to runs in podman on the host network.

```bash
./up.sh            # Postgres (tellij-pg): localhost:5432, db demo, user demo / demo
./status.sh
./down.sh
```

- `tellij-pg` runs with `log_statement=all`, so `podman logs tellij-pg` shows every statement —
  the `### logs pg` block tails exactly that, filtered by the current `{{orderId}}`.
- `pg-init/schema.sql` creates the `orders` table on first start.
- `tellij-env.json` holds `PG_PASSWORD` for `{{env.PG_PASSWORD}}` in the `@connect` line.
- `logs/consumer.log` is where the (future) consumer app writes; `../scripts/live/fake-log.sh`
  plays it for now.

Peek into the database from the shell: `podman exec tellij-pg psql -U demo -c 'select * from orders'`.
