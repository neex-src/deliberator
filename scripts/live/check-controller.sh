#!/usr/bin/env bash
# Live check for feature/controller-blocks: the two controller blocks run, then the JSON-key popup.
set -u
. "$(dirname "$0")/env.sh"
F="$WB"
curl -sf http://localhost:8080/v3/api-docs >/dev/null || { echo "demo-api not up (demo/up.sh api)"; exit 1; }
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 6
wait_done || exit 1
"$ROOT/scripts/live/shot.sh" 22-controller-blocks >/dev/null
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" "$(grep -c '^###' "$WB")" \
  "^POST http://localhost:8080/api/orders   (OrdersController.create ← OrdersApi)$" "^X-Trace: $ID$" "^HTTP/1.1 201 Created$" '^✓ status == 201$' '^✓ \$.status == "NEW"$' \
  "^# captured: ctrlOrder=$ID-ctrl$" "^GET http://localhost:8080/api/orders/$ID-ctrl?verbose=true   (OrdersController.get ← OrdersApi)$" '^✓ \$.items\[0\].qty == 2$' '^      "sku": "A-1",$' \
  "created orderId=$ID-ctrl" || exit 1
echo "== completion inside the body"
L=$(grep -n '"amount": 42.5,' "$F" | head -1 | cut -d: -f1)
"$ROOT/scripts/live/press.sh" "$L" End Return; sleep 0.5
xdotool type --delay 80 '"cu'; sleep 2.5
"$ROOT/scripts/live/shot.sh" 23-controller-completion >/dev/null
xdotool key Escape; sleep 0.3
for _ in 1 2 3 4 5; do xdotool key ctrl+z; sleep 0.15; done
xdotool key ctrl+s; sleep 1
grep -q '^"cu' "$F" && { echo "FAIL: typing leaked"; exit 1; } || echo "ok: body restored"
echo "ALL OK"
