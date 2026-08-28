#!/usr/bin/env bash
# Launch the sandbox IDE on demo/ with orders.tellij open, and wait until the editor is visible.
# The sandbox config is seeded from inliner's (already past the first-run dialogs) when absent.
set -u
. "$(dirname "$0")/env.sh"
cd "$ROOT"
pkill -f '[i]dea-sandbox' 2>/dev/null; pkill -f 'sandbox/[t]ellij' 2>/dev/null; sleep 2
SB="$ROOT/.intellijPlatform/sandbox/tellij/IU-2026.2.1"
SEED="$HOME/projects/inliner/.intellijPlatform/sandbox/inliner/IU-2026.2.1/config"
if [ ! -d "$SB/config" ] && [ -d "$SEED" ]; then
  mkdir -p "$SB"; cp -r "$SEED" "$SB/config"; rm -rf "$SB/config/plugins"
  echo "seeded sandbox config from inliner"
fi
TELLIJ_OPEN="$ROOT/demo" nohup ./gradlew runIde > "$OUT/runIde.log" 2>&1 &
echo "gradle pid $!"
for i in $(seq 1 60); do
  sleep 5
  if [ -n "$(xdotool search --onlyvisible --name 'demo' 2>/dev/null | head -1)" ]; then echo "project window up after $((i*5))s"; break; fi
done
sleep 8
W=$(xdotool search --onlyvisible --name 'demo' | head -1)
[ -z "$W" ] && { echo "no IDE window"; tail -20 "$OUT/runIde.log"; exit 1; }
xdotool windowactivate --sync "$W"; sleep 1
xdotool key --clearmodifiers ctrl+shift+n; sleep 1.5
xdotool type --delay 40 'orders.tellij'; sleep 1.5
xdotool key Return; sleep 3
[ -n "$(win)" ] && echo "orders.tellij open" || echo "WARNING: orders.tellij window title not found"
