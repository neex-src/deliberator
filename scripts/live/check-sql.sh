#!/usr/bin/env bash
# Live check for feature/sql: needs the IDE (run-ide.sh), demo/up.sh pg, and tellij-echo.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
"$ROOT/demo/up.sh" pg || exit 1
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All: event → consumer tail → seed INSERT → SELECT → pg log tail → payment → echo tail"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 4
"$ROOT/scripts/live/shot.sh" 06-sql-run-all >/dev/null
sleep 10
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" 7 "^(1 row affected)$" "^$ID *| alice *| 42.5 *| workbench *| 20" "^(1 row · [0-9]* ms)$" "execute <unnamed>: insert into orders" "^	values (.$ID.," "execute <unnamed>: select id, customer" "topic=payments" "^tick " || exit 1
echo "== psql agrees:"; podman exec tellij-pg psql -U demo -Atc "select id, customer from orders where id='$ID'"
echo "ALL OK"
