# Shared by the live-test scripts. Source it.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export DISPLAY="${DISPLAY:-:0}"
OUT="$ROOT/build/live"
mkdir -p "$OUT"
WIN_NAME='orders.tellij'
# The workbench under test: lives inside the demo service's project (what the sandbox IDE opens).
WB="${WB:-$ROOT/demo-api/workbench/orders.tellij}"
PROJECT_DIR="${PROJECT_DIR:-$ROOT/demo-api}"
PROJECT_WIN="${PROJECT_WIN:-demo-api}"
win() { xdotool search --onlyvisible --name "$WIN_NAME" 2>/dev/null | head -1; }

# Wait until the saved workbench holds a result for every block and none is still running —
# the session saves the file when it finishes, so the file on disk is the completion signal.
wait_done() {
  local blocks; blocks=$(grep -c '^###' "$WB")
  for _ in $(seq 1 "${1:-120}"); do
    sleep 1
    [ "$(grep -c '^#> result' "$WB")" = "$blocks" ] && ! grep -q ' · running' "$WB" && return 0
  done
  echo "timed out waiting for the run to finish"; return 1
}
