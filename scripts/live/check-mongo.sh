#!/usr/bin/env bash
# Live check for feature/mongo: the loop now ends with both event documents under the mongo block.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 7
"$ROOT/scripts/live/shot.sh" 09-mongo-run-all >/dev/null
sleep 12
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" 10 '^  "source": "kafka",$' '^  "kind": "order",$' '^  "source": "sqs",$' '^  "kind": "payment",$' '^(2 docs · [0-9]* ms)$' "mongo=ok" || exit 1
echo "ALL OK"
