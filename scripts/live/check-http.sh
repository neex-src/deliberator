#!/usr/bin/env bash
# Live check for feature/http: the loop plus the service's API and a capture feeding a later block.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
curl -sf http://localhost:8085/health >/dev/null || { echo "consumer API not up (demo/up.sh consumer)"; exit 1; }
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 6
"$ROOT/scripts/live/shot.sh" 08-http-run-all >/dev/null
sleep 12
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" 9 \
  "^HTTP/1.1 200 OK$" "^content-type: application/json$" "^  \"orderId\": \"$ID\",$" "^  \"status\": \"PAID\",$" \
  "^# captured: status=PAID$" "^orders_with_status$" "http GET /orders/$ID -> 200" || exit 1
echo "ALL OK"
