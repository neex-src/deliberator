#!/usr/bin/env bash
# demo/up.sh [service…] — start the demo stack with podman on the host network. Idempotent.
# Services: pg kafka sqs consumer (default: all). Everything listens on localhost exactly as the IDE sees it.
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

up_kafka() {
  if running tellij-kafka; then echo "tellij-kafka already running"; return; fi
  podman rm -f tellij-kafka >/dev/null 2>&1
  # The image's default config is a single-node KRaft broker advertising localhost:9092.
  podman run -d --name tellij-kafka --network host docker.io/apache/kafka:3.9.1 >/dev/null || { echo "podman run failed"; return 1; }
  for _ in $(seq 1 60); do
    podman exec tellij-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && break
    sleep 1
  done
  podman exec tellij-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic orders --partitions 1 --replication-factor 1 >/dev/null 2>&1 \
    && echo "tellij-kafka ready: localhost:9092, topic orders" || { echo "tellij-kafka did not become ready"; podman logs --tail 20 tellij-kafka; return 1; }
}

up_sqs() {
  if running tellij-sqs; then echo "tellij-sqs already running"; return; fi
  podman rm -f tellij-sqs >/dev/null 2>&1
  podman run -d --name tellij-sqs --network host docker.io/softwaremill/elasticmq-native:latest >/dev/null || { echo "podman run failed"; return 1; }
  for _ in $(seq 1 30); do
    curl -s -o /dev/null "http://localhost:9324/" && { echo "tellij-sqs ready: http://localhost:9324 (ElasticMQ, any credentials)"; return; }
    sleep 1
  done
  echo "tellij-sqs did not become ready"; return 1
}

up_consumer() {
  podman rm -f tellij-consumer >/dev/null 2>&1
  ( cd "$DIR/.." && ./gradlew -q :demo-consumer:installDist ) || { echo "consumer build failed"; return 1; }
  mkdir -p "$DIR/logs"
  podman run -d --name tellij-consumer --network host \
    -v "$DIR/../demo-consumer/build/install/demo-consumer:/app:ro" -v "$DIR/logs:/logs" \
    -e LOG_FILE=/logs/consumer.log \
    docker.io/library/eclipse-temurin:21-jre /app/bin/demo-consumer >/dev/null || { echo "podman run failed"; return 1; }
  for _ in $(seq 1 60); do
    podman logs tellij-consumer 2>/dev/null | grep -q 'kafka assigned' && podman logs tellij-consumer 2>/dev/null | grep -q 'sqs polling' \
      && { echo "tellij-consumer ready (log: demo/logs/consumer.log)"; return; }
    sleep 1
  done
  echo "tellij-consumer did not report ready"; podman logs --tail 20 tellij-consumer; return 1
}

SERVICES=("$@"); [ ${#SERVICES[@]} -eq 0 ] && SERVICES=(pg kafka sqs consumer)
for s in "${SERVICES[@]}"; do
  case "$s" in
    pg) up_pg ;;
    kafka) up_kafka ;;
    sqs) up_sqs ;;
    consumer) up_consumer ;;
    *) echo "unknown service: $s (known: pg kafka sqs consumer)"; exit 2 ;;
  esac
done
