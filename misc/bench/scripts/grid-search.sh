#!/usr/bin/env bash
# Two-phase grid search for full-scan W3 workload at c=10.
#
#   Phase 1: GC profile × Xmx, with batch_size=16384 fetch_size=16384.
#            Restarts the bridge for each cell (Xmx/GC need recreate).
#   Phase 2: batch_size × fetch_size under Phase 1's winning GC + Xmx.
#            Bridge stays up; only the per-query parameters change.
#
# Each cell runs `clickhouse benchmark --concurrency 10 --timelimit 30s`
# against `SELECT TOP 1000000 ... FROM hits` via mssql.
#
# Output:
#   results/<label>/results.csv   (one row per cell)
#   results/<label>/<cell>.txt    (raw bench output)
#   results/<label>/summary.md    (ranked tables)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$SCRIPT_DIR/docker-compose.yml"
OVERRIDE="$SCRIPT_DIR/docker-compose.grid.yml"
LABEL="${1:-grid-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT_DIR="$SCRIPT_DIR/results/$LABEL"
mkdir -p "$OUT_DIR"

# Bridge JAR path (volume-mounted so force-recreate doesn't lose our local build).
BRIDGE_JAR="$(cd "$SCRIPT_DIR/../.." && pwd)/target/clickhouse-jdbc-bridge-1.0.3-shaded.jar"

# ---------------------------------------------------------- profiles

# Returns three lines, each "name|opts" — Xmx is substituted in.
profiles_for() {
  local xmx="$1"
  cat <<EOF
current|-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -Xms${xmx} -Xmx${xmx} -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled -XX:G1ReservePercent=20
g1-tuned|-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
zgc|-Xms${xmx} -Xmx${xmx} -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
EOF
}

# ---------------------------------------------------------- helpers

reconfigure_bridge() {
  local gc_name="$1" jvm_opts="$2" xmx="$3"
  cat > "$OVERRIDE" <<EOF
services:
  jdbc-bridge:
    environment:
      JDBC_BRIDGE_JVM_OPTS: "${jvm_opts} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/oom.hprof -Xlog:gc*:file=/app/logs/gc-${gc_name}-${xmx}.log:tags,time,uptime,level"
    deploy:
      resources:
        limits: { cpus: "4", memory: 12G }
    volumes:
      - ${BRIDGE_JAR}:/app/clickhouse-jdbc-bridge-shaded.jar:ro
EOF
  echo ">>> reconfiguring bridge: gc=$gc_name xmx=$xmx"
  docker compose -f "$COMPOSE" -f "$OVERRIDE" up -d --no-deps --force-recreate jdbc-bridge >/dev/null 2>&1 || true
  for i in $(seq 1 60); do
    curl -fsS http://localhost:9019/ping >/dev/null 2>&1 && {
      # Also verify the upstream DB is still reachable from the bridge — if a
      # previous cell OOMed sqlserver via Docker memory pressure, /ping would
      # still succeed but every benchmark would return 0.
      if docker compose -f "$COMPOSE" -f "$OVERRIDE" ps sqlserver --format '{{.Status}}' 2>/dev/null | grep -q "healthy"; then
        return 0
      fi
      echo "    !! sqlserver not healthy — refusing to run this cell"
      return 1
    }
    sleep 1
  done
  echo "    !! bridge did not come up healthy within 60s"
  return 1
}

prom_q() {
  curl -fsS "http://localhost:9090/api/v1/query?query=$1" \
    | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p'
}

run_combo() {
  local gc_name="$1" xmx="$2" batch="$3" fetch="$4"
  local query="SELECT count() AS n, round(sum(revenue), 2) AS s FROM jdbc('mssql?batch_size=${batch}&fetch_size=${fetch}', 'SELECT TOP 1000000 watchid, userid, revenue FROM hits')"
  local out="$OUT_DIR/${gc_name}_xmx${xmx}_b${batch}_f${fetch}.txt"
  echo "  -> gc=$gc_name xmx=$xmx batch=$batch fetch=$fetch"

  echo "$query" | docker compose -f "$COMPOSE" -f "$OVERRIDE" exec -T clickhouse \
    clickhouse benchmark --concurrency 10 --timelimit 30 --delay 10 \
    --max-consecutive-errors 1000 > "$out" 2>&1 || true

  local last queries qps p99 heap_max gc_pause
  last="$(grep '^localhost:' "$out" | tail -1 || true)"
  if [[ -z "$last" ]]; then
    queries=0; qps=0; p99=0
  else
    queries="$(echo "$last" | sed -nE 's/.*queries: ([0-9.]+).*/\1/p')"
    qps="$(echo "$last" | sed -nE 's/.*QPS: ([0-9.]+).*/\1/p')"
    p99="$(awk '/^99%/{p=$2} END{print (p?p:0)}' "$out")"
  fi
  heap_max="$(prom_q 'max_over_time(sum(jvm_memory_used_bytes%7Bjob%3D%22jdbc-bridge%22%2Carea%3D%22heap%22%7D)%5B45s%3A%5D)')"
  gc_pause="$(prom_q 'sum(rate(jvm_gc_pause_seconds_sum%7Bjob%3D%22jdbc-bridge%22%7D%5B30s%5D))')"

  echo "$gc_name,$xmx,$batch,$fetch,${queries:-0},${qps:-0},${p99:-0},${heap_max:-0},${gc_pause:-0}" >> "$OUT_DIR/results.csv"
  echo "       qps=$qps p99=${p99}s heap=${heap_max:-?}B"
}

# ---------------------------------------------------------- run

echo "gc,xmx,batch,fetch,queries,qps,p99_s,heap_max_b,gc_pause_per_s" > "$OUT_DIR/results.csv"

# ============================================================ Phase 1
echo "==== PHASE 1: GC × Xmx (batch=16384, fetch=16384) ===="
XMX_VALUES=(1g 2g 4g 8g 10g)
for xmx in "${XMX_VALUES[@]}"; do
  while IFS='|' read -r gc_name jvm_opts; do
    [[ -z "$gc_name" ]] && continue
    if reconfigure_bridge "$gc_name" "$jvm_opts" "$xmx"; then
      run_combo "$gc_name" "$xmx" 16384 16384
    else
      echo "$gc_name,$xmx,16384,16384,0,0,0,0,0" >> "$OUT_DIR/results.csv"
    fi
  done < <(profiles_for "$xmx")
done

# Pick the highest-QPS row from Phase 1. Filter out cells with qps==0 (likely failed
# to come up healthy or an upstream died); otherwise sort -rn would rank them
# arbitrarily after the real rows.
best="$(awk -F, 'NR>1 && $3==16384 && $4==16384 && ($6+0)>0 {print}' "$OUT_DIR/results.csv" | sort -t, -k6,6 -rn | head -1)"
BEST_GC="$(echo "$best" | cut -d, -f1)"
BEST_XMX="$(echo "$best" | cut -d, -f2)"
echo ""
echo "==== Phase 1 winner: gc=$BEST_GC xmx=$BEST_XMX ===="
echo ""

# ============================================================ Phase 2
echo "==== PHASE 2: batch × fetch (gc=$BEST_GC xmx=$BEST_XMX) ===="
while IFS='|' read -r gc_name jvm_opts; do
  [[ "$gc_name" == "$BEST_GC" ]] || continue
  reconfigure_bridge "$gc_name" "$jvm_opts" "$BEST_XMX"
done < <(profiles_for "$BEST_XMX")

BATCH_VALUES=(1024 4096 16384 65535)
FETCH_VALUES=(4096 16384 65535)
for batch in "${BATCH_VALUES[@]}"; do
  for fetch in "${FETCH_VALUES[@]}"; do
    # skip the cell already measured in Phase 1
    if [[ "$batch" == "16384" && "$fetch" == "16384" ]]; then
      continue
    fi
    run_combo "$BEST_GC" "$BEST_XMX" "$batch" "$fetch"
  done
done

# ============================================================ Summary
{
  echo "# Grid search: $LABEL"
  echo ""
  echo "Workload: \`SELECT TOP 1000000 ... FROM hits\` via mssql, c=10, 30s per cell."
  echo "Bridge container limit: 10 GB."
  echo "Host: 10.7 GB Docker, $(uname -srm)."
  echo ""
  echo "## Phase 1 — GC × Xmx (batch=16384, fetch=16384)"
  echo ""
  echo "| gc | xmx | qps | p99 (s) | heap_max (MB) | gc_pause (s/s) |"
  echo "|---|---|---|---|---|---|"
  awk -F, 'NR>1 && $3==16384 && $4==16384 {printf "| %s | %s | %.2f | %.3f | %.0f | %.4f |\n", $1, $2, $6, $7, $8/1024/1024, $9}' "$OUT_DIR/results.csv"
  echo ""
  echo "Phase 1 winner: **gc=$BEST_GC xmx=$BEST_XMX**"
  echo ""
  echo "## Phase 2 — batch × fetch (gc=$BEST_GC xmx=$BEST_XMX)"
  echo ""
  echo "| batch | fetch | qps | p99 (s) | heap_max (MB) | gc_pause (s/s) |"
  echo "|---|---|---|---|---|---|"
  awk -F, -v gc="$BEST_GC" -v xmx="$BEST_XMX" 'NR>1 && $1==gc && $2==xmx {printf "| %s | %s | %.2f | %.3f | %.0f | %.4f |\n", $3, $4, $6, $7, $8/1024/1024, $9}' "$OUT_DIR/results.csv"
  echo ""
  best_overall="$(awk -F, 'NR>1{print}' "$OUT_DIR/results.csv" | sort -t, -k6 -rn | head -1)"
  echo "## Overall winner"
  echo ""
  echo "\`\`\`"
  echo "$best_overall" | awk -F, '{printf "gc        = %s\nxmx       = %s\nbatch     = %s\nfetch     = %s\nqps       = %s\np99 (s)   = %s\nheap (MB) = %d\ngc (s/s)  = %s\n", $1, $2, $3, $4, $6, $7, $8/1024/1024, $9}'
  echo "\`\`\`"
} > "$OUT_DIR/summary.md"

echo ""
echo ">>> done."
echo ">>> CSV:     $OUT_DIR/results.csv"
echo ">>> summary: $OUT_DIR/summary.md"
cat "$OUT_DIR/summary.md"
