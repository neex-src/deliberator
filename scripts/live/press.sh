#!/usr/bin/env bash
# press.sh <line> <xdotool keys...> — go to a line in orders.tellij, then send keys.
set -u
. "$(dirname "$0")/env.sh"
LINE=$1; shift
W=$(win); [ -z "$W" ] && { echo "editor window not found"; exit 1; }
xdotool windowactivate --sync "$W"; sleep 0.5
xdotool key --clearmodifiers ctrl+g; sleep 0.8
xdotool key --clearmodifiers ctrl+a; xdotool type --delay 40 "$LINE"; sleep 0.3
xdotool key Return; sleep 0.8
for k in "$@"; do xdotool key --clearmodifiers "$k"; sleep 0.4; done
