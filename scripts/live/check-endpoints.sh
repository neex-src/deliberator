#!/usr/bin/env bash
# Live check for feature/java-endpoints: Alt+Shift+E on OrdersController.create shows the summary.
set -u
. "$(dirname "$0")/env.sh"
SRC="$ROOT/demo-api/src/main/java/com/neex/demo/api/OrdersController.java"
LOG=$(ls -t "$ROOT"/.intellijPlatform/sandbox/tellij/IU-*/log/idea.log | head -1)
W=$(xdotool search --onlyvisible --name "$PROJECT_WIN" | head -1); [ -z "$W" ] && { echo "no IDE window"; exit 1; }
xdotool windowactivate --sync "$W"; sleep 0.5
xdotool key --clearmodifiers ctrl+shift+n; sleep 1.5; xdotool type --delay 40 'OrdersController.java'; sleep 1.5; xdotool key Return; sleep 3
L=$(grep -n 'create(@Valid' "$SRC" | cut -d: -f1)
xdotool key --clearmodifiers ctrl+g; sleep 0.8; xdotool key ctrl+a; xdotool type --delay 40 "$((L + 1))"; xdotool key Return; sleep 1
BEFORE=$(grep -c 'tellij endpoint:' "$LOG")
xdotool key --clearmodifiers alt+shift+e; sleep 2.5
"$ROOT/scripts/live/shot.sh" 20-endpoint-summary >/dev/null
AFTER=$(grep -c 'tellij endpoint:' "$LOG")
[ "$AFTER" -gt "$BEFORE" ] && echo "ok: summary opened" || { echo "FAIL: no summary logged"; exit 1; }
LINE=$(grep 'tellij endpoint:' "$LOG" | tail -1 | sed 's/.*tellij endpoint: //')
echo "plugin says: $LINE"
API=$(curl -s http://localhost:8080/v3/api-docs | jq -r '.paths."/api/orders".post.summary')
[[ "$LINE" == "POST /api/orders → 201 OrderResponse (summary: $API)" ]] && echo "ok: matches /v3/api-docs ($API)" || { echo "FAIL: api-docs says '$API'"; exit 1; }
xdotool key Escape; sleep 1
"$ROOT/scripts/live/shot.sh" 21-endpoint-summary-closed >/dev/null
echo "ALL OK"
