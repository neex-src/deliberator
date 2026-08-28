#!/usr/bin/env bash
# Live check for feature/result-folding: fold state is visual — screenshots are the evidence.
set -u
. "$(dirname "$0")/env.sh"
F="$ROOT/demo/orders.tellij"
line() { grep -n "$1" "$F" | head -1 | cut -d: -f1; }
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 20
"$ROOT/scripts/live/verify.sh" 10 || exit 1
M=$(line '^#> result mongo'); echo "mongo result header at line $M"
"$ROOT/scripts/live/press.sh" $((M + 3)) Escape; sleep 1.5
"$ROOT/scripts/live/shot.sh" 10-esc-folds-mongo >/dev/null
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+minus; sleep 1.5
"$ROOT/scripts/live/shot.sh" 11-collapse-all >/dev/null
xdotool key --clearmodifiers ctrl+alt+shift+equal; sleep 1.5
"$ROOT/scripts/live/shot.sh" 12-expand-all >/dev/null
W=$(win); xdotool windowactivate --sync "$W"; xdotool mousemove 1900 700 click 3; sleep 1.5
"$ROOT/scripts/live/shot.sh" 13-context-menu >/dev/null
xdotool key Escape
echo "shots done"
