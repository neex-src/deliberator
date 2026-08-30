#!/usr/bin/env bash
# Live check for feature/demo-api: the service answers the way the workbench will rely on.
set -u
. "$(dirname "$0")/env.sh"
"$ROOT/demo/up.sh" api || exit 1
R="$OUT/api-response.json"; ID="ord-check-$RANDOM"
code() { curl -s -o "$R" -w '%{http_code}' "$@"; }
echo "== 201 create"
C=$(code -X POST -H 'Content-Type: application/json' -H "X-Trace: $ID" -D "$OUT/api-headers.txt" \
  -d "{\"orderId\":\"$ID\",\"customer\":\"alice\",\"amount\":42.5,\"items\":[{\"sku\":\"A-1\",\"qty\":2}]}" http://localhost:8080/api/orders)
[ "$C" = 201 ] && [ "$(jq -r .status "$R")" = NEW ] && grep -qi "^x-trace: $ID" "$OUT/api-headers.txt" && echo "ok: 201 NEW, X-Trace echoed" || { echo "FAIL: $C $(cat "$R")"; exit 1; }
echo "== 400 validation"
C=$(code -X POST -H 'Content-Type: application/json' -d '{"orderId":"x","amount":-1,"items":[{"sku":"","qty":0}]}' http://localhost:8080/api/orders)
[ "$C" = 400 ] && [ "$(jq -r '.errors | map(.field) | join(",")' "$R")" = "amount,customer,items[0].qty,items[0].sku" ] && echo "ok: 400 with field errors: $(jq -c '.errors | map(.field)' "$R")" || { echo "FAIL: $C $(cat "$R")"; exit 1; }
echo "== 400 unreadable"
C=$(code -X POST -H 'Content-Type: application/json' -d '{"orderId": ' http://localhost:8080/api/orders)
[ "$C" = 400 ] && echo "ok: 400 unreadable body" || { echo "FAIL: $C"; exit 1; }
echo "== 409 duplicate"
C=$(code -X POST -H 'Content-Type: application/json' -d "{\"orderId\":\"$ID\",\"customer\":\"bob\",\"amount\":1}" http://localhost:8080/api/orders)
[ "$C" = 409 ] && echo "ok: 409" || { echo "FAIL: $C"; exit 1; }
echo "== 200 verbose / 404"
C=$(code "http://localhost:8080/api/orders/$ID?verbose=true")
[ "$C" = 200 ] && [ "$(jq -r '.items | length' "$R")" = 1 ] && [ "$(jq -r .source "$R")" = api ] && echo "ok: 200 with 1 item, source=api" || { echo "FAIL: $C $(cat "$R")"; exit 1; }
C=$(code "http://localhost:8080/api/orders/nope"); [ "$C" = 404 ] && echo "ok: 404" || { echo "FAIL: $C"; exit 1; }
echo "== api-docs"
curl -s http://localhost:8080/v3/api-docs | jq -r '.paths."/api/orders".post.summary' | grep -q 'Create an order' && echo "ok: /v3/api-docs has the summary" || { echo "FAIL: api-docs"; exit 1; }
grep -q "created orderId=$ID" "$ROOT/demo/logs/api.log" && echo "ok: api.log has the created line" || { echo "FAIL: api.log"; exit 1; }
echo "ALL OK"
