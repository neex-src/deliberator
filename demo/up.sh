#!/usr/bin/env bash
# demo/up.sh [service…] — start the demo stack with podman on the host network. Idempotent.
# Services: pg (default). Everything listens on localhost exactly as the IDE sees it.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
running() { podman ps --format '{{.Names}}' | grep -qx "$1"; }

up_pg() {
  if running tellij-pg; then echo "tellij-pg already running"; return; fi
  podman rm -f tellij-pg >/dev/null 2>&1
  podman run -d --name tellij-pg --network host \
    -e POSTGRES_USER=demo -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=demo \
    -v "$DIR/pg-init:/docker-entrypoint-initdb.d:ro" \
    docker.io/library/postgres:16 -c log_statement=all >/dev/null || { echo "podman run failed"; return 1; }
  # -h localhost: the init phase runs a socket-only server; only the real one answers on TCP.
  for _ in $(seq 1 40); do
    podman exec tellij-pg pg_isready -h localhost -U demo -q 2>/dev/null && { echo "tellij-pg ready: jdbc:postgresql://localhost:5432/demo (demo/demo)"; return; }
    sleep 1
  done
  echo "tellij-pg did not become ready"; podman logs --tail 20 tellij-pg; return 1
}

for s in "${@:-pg}"; do
  case "$s" in
    pg) up_pg ;;
    *) echo "unknown service: $s (known: pg)"; exit 2 ;;
  esac
done
