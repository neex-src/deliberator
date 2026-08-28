#!/usr/bin/env bash
# Live check for feature/events: the real loop. Needs the IDE (run-ide.sh) and the full stack
# (demo/up.sh) plus tellij-echo.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
"$ROOT/demo/status.sh"
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All against the real stack"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 5
"$ROOT/scripts/live/shot.sh" 07-events-run-all >/dev/null
sleep 12
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" 7 \
  "^sent topic=orders partition=0 offset=[0-9]* key=$ID bytes=" \
  "kafka received orderId=$ID customer=alice amount=42.5" \
  "stored orderId=$ID pg=1 source=kafka" \
  "^$ID *| alice *| 42.5 *| NEW *| kafka *| 20" \
  "^sent queue=payments messageId=[0-9a-f-]* md5=" \
  "^$ID *| PAID$" \
  "execute <unnamed>: insert into orders" \
  "update orders set status" \
  "^tick " || exit 1
echo "== psql agrees:"; podman exec tellij-pg psql -U demo -Atc "select id, status, source from orders where id='$ID'"
echo "ALL OK"
