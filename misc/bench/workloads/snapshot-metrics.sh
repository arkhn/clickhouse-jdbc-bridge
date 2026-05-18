#!/usr/bin/env bash
# Snapshot the key metrics into <out_dir> after a workload completes.
# Captures: bridge /metrics raw, ClickHouse /metrics raw, container stats, GC log tail.
set -euo pipefail

OUT_DIR="${1:?out_dir}"
mkdir -p "$OUT_DIR/snapshots"

curl -fsS "http://localhost:9019/metrics" > "$OUT_DIR/snapshots/bridge-metrics.prom" || true
curl -fsS "http://localhost:9363/metrics" > "$OUT_DIR/snapshots/clickhouse-metrics.prom" || true

docker stats --no-stream --format '{{json .}}' \
  $(docker ps --filter "name=bench-" --format '{{.Names}}') \
  > "$OUT_DIR/snapshots/docker-stats.jsonl" 2>/dev/null || true

# GC log tail
docker compose -f "$BENCH_COMPOSE" exec -T jdbc-bridge \
  bash -c 'tail -n 200 /app/logs/gc.log 2>/dev/null || true' \
  > "$OUT_DIR/snapshots/bridge-gc.log" 2>/dev/null || true
