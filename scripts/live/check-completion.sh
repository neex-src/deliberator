#!/usr/bin/env bash
# Live check for feature/completion: type `# @` on a new line in the logs block, screenshot the popup.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
L=$(grep -n '^# @for 6s' "$F" | head -1 | cut -d: -f1)
"$ROOT/scripts/live/press.sh" "$L" End Return; sleep 0.5
xdotool type --delay 80 '# @'; sleep 2.5
"$ROOT/scripts/live/shot.sh" 14-completion-directives >/dev/null
xdotool key Escape; sleep 0.3
xdotool type --delay 80 'fi'; sleep 2
"$ROOT/scripts/live/shot.sh" 15-completion-filter >/dev/null
xdotool key Return; sleep 0.5; xdotool type --delay 80 '{{'; sleep 2.5
"$ROOT/scripts/live/shot.sh" 16-completion-vars >/dev/null
xdotool key Escape; sleep 0.3
# undo the typing and save so the demo file stays clean
for _ in 1 2 3 4 5 6 7 8; do xdotool key ctrl+z; sleep 0.15; done
xdotool key ctrl+s; sleep 1
git -C "$ROOT" diff --quiet -- demo/orders.tellij && echo "demo file clean" || { echo "demo file changed:"; git -C "$ROOT" diff -- demo/orders.tellij | head; git -C "$ROOT" checkout -- demo/orders.tellij; }
