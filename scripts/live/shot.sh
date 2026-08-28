#!/usr/bin/env bash
# shot.sh <name> — screenshot the X display into build/live/<name>.png
set -u
. "$(dirname "$0")/env.sh"
ffmpeg -loglevel error -f x11grab -i "$DISPLAY" -frames:v 1 -y "$OUT/$1.png" && echo "$OUT/$1.png"
