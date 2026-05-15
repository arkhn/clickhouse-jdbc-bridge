#!/usr/bin/env bash
# W1 micro-handshake: ApacheBench hammering the bridge's /ping endpoint.
# Exercises Vert.x request loop only, no JDBC. Establishes a ceiling for raw HTTP throughput.
#
# Run inside the bench-jdbc-bridge container (apache2-utils is preinstalled there).
#
# Usage (from harness):
#   workloads/w1-ping.sh <concurrency> <duration_s> <out_dir>
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"

mkdir -p "$OUT_DIR"

# ab's `-t` is the wall-clock bound (auto-caps -n at 50000 unless we raise it).
REQ_CAP=$(( CONC * DURATION * 5000 ))

echo "== W1 ping conc=$CONC duration=${DURATION}s req_cap=$REQ_CAP =="
docker compose -f "$BENCH_COMPOSE" exec -T jdbc-bridge \
  ab -k -c "$CONC" -t "$DURATION" -n "$REQ_CAP" -q -e "/tmp/w1-pct.csv" \
     http://localhost:9019/ping \
  > "$OUT_DIR/w1-ping.txt" 2>&1 || true

docker compose -f "$BENCH_COMPOSE" exec -T jdbc-bridge cat /tmp/w1-pct.csv \
  > "$OUT_DIR/w1-ping-percentiles.csv" 2>/dev/null || true

echo "  -> $OUT_DIR/w1-ping.txt"
