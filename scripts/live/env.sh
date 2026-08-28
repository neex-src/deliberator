# Shared by the live-test scripts. Source it.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export DISPLAY="${DISPLAY:-:0}"
OUT="$ROOT/build/live"
mkdir -p "$OUT"
WIN_NAME='orders.tellij'
win() { xdotool search --onlyvisible --name "$WIN_NAME" 2>/dev/null | head -1; }
