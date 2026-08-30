#!/usr/bin/env bash
# Live check for feature/assertions: the committed asserts all pass; one deliberate failure marks
# its section FAILED while everything after it still runs.
set -u
. "$(dirname "$0")/env.sh"
F="$WB"
cp "$F" "$F.bak"
# a failing assert on the http block, injected for this run only
sed -i 's|^# @assert \$.status == "PAID"$|# @assert $.status == "PAID"\n# @assert $.status == "NEW"|' "$F"
grep -q '@assert \$.status == "NEW"' "$F" || { echo "injection failed"; exit 1; }
"$ROOT/scripts/live/press.sh" 1 ctrl+alt+shift+BackSpace ctrl+s; sleep 1.5
"$ROOT/scripts/live/verify.sh" 0 || exit 1
echo "== Run All with assertions"
"$ROOT/scripts/live/press.sh" 1 ctrl+shift+Return; sleep 6
"$ROOT/scripts/live/shot.sh" 17-assertions >/dev/null
wait_done || { mv "$F.bak" "$F"; exit 1; }
STATUS=0; "$ROOT/scripts/live/verify.sh" "$(grep -c '^###' "$WB")" '^✓ offset >= 0$' '^✓ lines >= 2$' '^✓ rows == 1$' '^✓ col.status == "PAID"$' '^✓ status == 200$' '^✓ \$.status == "PAID"$' '^✗ \$.status == "NEW" (was "PAID")$' ' · FAILED 1/4$' '^✓ docs == 2$' '^✓ doc.kind == order$' '^tick ' || STATUS=1
grep -c '^✓' "$F" | xargs echo "passing checks:"
mv "$F.bak" "$F"
[ "$STATUS" = 0 ] && echo "ALL OK (demo file restored)" || { echo "FAILED (demo file restored)"; exit 1; }
