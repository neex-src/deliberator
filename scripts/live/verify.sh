#!/usr/bin/env bash
# verify.sh <expected-result-count> [grep patterns that must match...]
set -u
. "$(dirname "$0")/env.sh"
F="$WB"
EXPECT=$1; shift
N=$(grep -c '^#> result' "$F")
STATUS=0
[ "$N" = "$EXPECT" ] && echo "ok: $N result section(s)" || { echo "FAIL: $N result sections, expected $EXPECT"; STATUS=1; }
grep -q ' · running' "$F" && { echo "FAIL: a section is still running — wait_done first"; STATUS=1; }
grep -q ' · ERROR' "$F" && { echo "FAIL: ERROR section present"; grep -A3 ' · ERROR' "$F"; STATUS=1; }
# FAILED (an assertion) is a result, not an error: reported, never fatal here.
grep -q ' · FAILED' "$F" && echo "note: $(grep -c ' · FAILED' "$F") section(s) with failed assertions"

for p in "$@"; do grep -q -- "$p" "$F" && echo "ok: /$p/" || { echo "FAIL: /$p/ not found"; STATUS=1; }; done
exit $STATUS
