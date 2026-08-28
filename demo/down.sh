#!/usr/bin/env bash
# Stop and remove every demo container.
podman rm -f tellij-pg tellij-kafka tellij-mongo tellij-sqs tellij-consumer tellij-echo 2>/dev/null
echo "demo stack down"
