#!/usr/bin/env bash
# fake-log.sh <orderId> [seconds] — plays the consumer: appends matching and noise lines to
# demo/logs/consumer.log every half second.
set -u
. "$(dirname "$0")/env.sh"
ID=$1; N=${2:-12}
F="$ROOT/demo/logs/consumer.log"; mkdir -p "$(dirname "$F")"
for i in $(seq 1 "$N"); do
  echo "$(date +%T.%3N) kafka received orderId=$ID customer=alice" >> "$F"; sleep 0.5
  echo "$(date +%T.%3N) housekeeping tick $i" >> "$F"; sleep 0.5
done
echo "$(date +%T.%3N) stored orderId=$ID pg=1 mongo=ok" >> "$F"
