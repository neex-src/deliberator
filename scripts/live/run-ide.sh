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
# JDK 17 for the demo-api project: seed it into the sandbox's JDK table (cloned from the entry
# that is there) so the Gradle import and the project SDK resolve without a click.
JT="$SB/config/options/jdk.table.xml"
if [ -f "$JT" ] && ! grep -q 'name value="17"' "$JT"; then
  python3 - "$JT" <<'PY'
import sys, re, copy, xml.etree.ElementTree as ET
p = sys.argv[1]; t = ET.parse(p); root = t.getroot()
table = root.find(".//component[@name='ProjectJdkTable']")
src = next((j for j in table.findall('jdk') if j.find('type').get('value') == 'JavaSDK'), None)
if src is not None:
    j = copy.deepcopy(src)
    j.find('name').set('value', '17'); j.find('homePath').set('value', '/usr/lib/jvm/java-17-openjdk')
    v = j.find('version'); v is not None and v.set('value', '17')
    for e in j.iter():
        for k, val in list(e.attrib.items()):
            if 'java-' in val and 'openjdk' in val: e.set(k, re.sub(r'java-\d+-openjdk', 'java-17-openjdk', val))
    table.append(j); t.write(p, encoding='UTF-8', xml_declaration=False); print("seeded JDK 17")
PY
fi
rm -f "$PROJECT_DIR/.idea/workspace.xml"
TELLIJ_OPEN="$PROJECT_DIR" nohup ./gradlew runIde > "$OUT/runIde.log" 2>&1 &
echo "gradle pid $!"
for i in $(seq 1 60); do
  sleep 5
  if [ -n "$(xdotool search --onlyvisible --name "$PROJECT_WIN" 2>/dev/null | head -1)" ]; then echo "project window up after $((i*5))s"; break; fi
done
# Gradle import of demo-api runs on open; give it time before asking for a file.
sleep 30
"$ROOT/scripts/live/shot.sh" 00-import >/dev/null
W=$(xdotool search --onlyvisible --name "$PROJECT_WIN" | head -1)
[ -z "$W" ] && { echo "no IDE window"; tail -20 "$OUT/runIde.log"; exit 1; }
xdotool windowactivate --sync "$W"; sleep 1
xdotool key --clearmodifiers ctrl+shift+n; sleep 1.5
xdotool type --delay 40 'orders.tellij'; sleep 1.5
xdotool key Return; sleep 3
[ -n "$(win)" ] && echo "orders.tellij open" || echo "WARNING: orders.tellij window title not found"
