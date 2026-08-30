#!/usr/bin/env bash
# demo/up.sh [service…] — start the demo stack with podman on the host network. Idempotent.
# Services: pg kafka sqs mongo consumer api (default: all). api runs on the host (JDWP :5005). Everything listens on localhost exactly as the IDE sees it.
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

up_mongo() {
  if running tellij-mongo; then echo "tellij-mongo already running"; return; fi
  podman rm -f tellij-mongo >/dev/null 2>&1
  podman run -d --name tellij-mongo --network host docker.io/library/mongo:7 >/dev/null || { echo "podman run failed"; return 1; }
  for _ in $(seq 1 40); do
    [ "$(podman exec tellij-mongo mongosh --quiet --eval 'db.runCommand({ping:1}).ok' 2>/dev/null)" = "1" ] && { echo "tellij-mongo ready: mongodb://localhost:27017/demo"; return; }
    sleep 1
  done
  echo "tellij-mongo did not become ready"; podman logs --tail 20 tellij-mongo; return 1
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
      && { echo "tellij-consumer ready (log: demo/logs/consumer.log, http://localhost:8085/health)"; return; }
    sleep 1
  done
  echo "tellij-consumer did not report ready"; podman logs --tail 20 tellij-consumer; return 1
}

up_api() {
  up_pg || return 1
  ( cd "$DIR/../demo-api" && ./gradlew -q bootJar ) || { echo "api build failed"; return 1; }
  mkdir -p "$DIR/logs"
  # A pid file, never pkill -f: a pattern would match any shell whose command line mentions the jar.
  [ -f "$DIR/logs/api.pid" ] && { kill "$(cat "$DIR/logs/api.pid")" 2>/dev/null; sleep 1; rm -f "$DIR/logs/api.pid"; }
  # On the host, not in a container: the sandbox IDE attaches to :5005 and the sources match.
  nohup /usr/lib/jvm/java-17-openjdk/bin/java \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
    -jar "$DIR/../demo-api/build/libs/demo-api.jar" > "$DIR/logs/api.log" 2>&1 &
  echo $! > "$DIR/logs/api.pid"
  for _ in $(seq 1 60); do
    curl -sf http://localhost:8080/v3/api-docs >/dev/null 2>&1 && { echo "demo-api ready: http://localhost:8080 (jdwp :5005, log demo/logs/api.log)"; return; }
    sleep 1
  done
  echo "demo-api did not become ready"; tail -20 "$DIR/logs/api.log"; return 1
}

SERVICES=("$@"); [ ${#SERVICES[@]} -eq 0 ] && SERVICES=(pg kafka sqs mongo consumer api)
for s in "${SERVICES[@]}"; do
  case "$s" in
    pg) up_pg ;;
    kafka) up_kafka ;;
    sqs) up_sqs ;;
    mongo) up_mongo ;;
    consumer) up_consumer ;;
    api) up_api ;;
    *) echo "unknown service: $s (known: pg kafka sqs mongo consumer api)"; exit 2 ;;
  esac
done
