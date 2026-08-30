#!/usr/bin/env bash
# Stop and remove every demo container.
podman rm -f tellij-pg tellij-kafka tellij-mongo tellij-sqs tellij-consumer tellij-echo 2>/dev/null
[ -f "$(dirname "$0")/logs/api.pid" ] && { kill "$(cat "$(dirname "$0")/logs/api.pid")" 2>/dev/null; rm -f "$(dirname "$0")/logs/api.pid"; }
echo "demo stack down"
