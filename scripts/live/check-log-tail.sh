#!/usr/bin/env bash
# Live check for feature/log-tail. Needs the IDE up (run-ide.sh) and the tellij-echo container:
#   podman run -d --name tellij-echo docker.io/library/alpine:3.20 sh -c 'while true; do echo "tick $(date +%T)"; sleep 1; done'
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
line() { grep -n "^### $1" "$F" | head -1 | cut -d: -f1; }
: > "$ROOT/demo/logs/consumer.log"

echo "== 1. kafka orders"
"$ROOT/scripts/live/press.sh" $(( $(line 'kafka orders') + 2 )) ctrl+Return; sleep 2.5
ID=$(grep -o 'orderId=ord-[0-9a-f]*' "$F" | head -1 | cut -d= -f2); echo "orderId=$ID"
"$ROOT/scripts/live/verify.sh" 1 "simulated send topic=orders" || exit 1

echo "== 2. logs consumer streams only matching lines (8 s window)"
"$ROOT/scripts/live/fake-log.sh" "$ID" 6 &
"$ROOT/scripts/live/press.sh" $(( $(line 'logs consumer') + 1 )) ctrl+Return; sleep 4
"$ROOT/scripts/live/shot.sh" 03-log-tail-streaming >/dev/null
sleep 6; wait
"$ROOT/scripts/live/verify.sh" 2 "kafka received orderId=$ID customer=alice" "stored orderId=$ID pg=1 mongo=ok" " · window 8 s" || exit 1
grep -q housekeeping "$F" && { echo "FAIL: unfiltered line leaked"; exit 1; } || echo "ok: noise filtered out"

echo "== 3. Esc stops a running tail"
"$ROOT/scripts/live/fake-log.sh" "$ID" 8 &
"$ROOT/scripts/live/press.sh" $(( $(line 'logs consumer') + 1 )) ctrl+Return; sleep 2.5
xdotool key --clearmodifiers Escape; sleep 2; wait
"$ROOT/scripts/live/verify.sh" 2 "#> result logs consumer · .* · stopped" || exit 1

echo "== 4. podman tail of tellij-echo"
"$ROOT/scripts/live/press.sh" $(( $(line 'logs echo') + 1 )) ctrl+Return; sleep 6.5
"$ROOT/scripts/live/verify.sh" 3 "^tick [0-9:]*$" " · window 4 s" || exit 1

echo "== 5. Run All: tails armed first, everything streams"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 3
"$ROOT/scripts/live/shot.sh" 04-run-all-tails >/dev/null
sleep 9
"$ROOT/scripts/live/verify.sh" 4 "topic=orders" "topic=payments" "^tick " || exit 1
echo "ALL OK"
