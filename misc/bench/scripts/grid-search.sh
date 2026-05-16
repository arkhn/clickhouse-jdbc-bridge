#!/usr/bin/env bash
# 5-phase tuning grid for the bridge against a SQL Server full-scan workload.
#
#   P1: GC profile sweep    (7 profiles, fixed Xmx=4g + defaults)
#   P2: Xmx sweep           (under winning GC)
#   P3: batch × fetch × max_block_size sweep (under winning GC + Xmx)
#   P4: concurrency sweep   (under winning JVM + params)
#   P5: workload-type sweep (point / 100k / 1M scan, under winning everything)
#
# Output:
#   results/<label>/results.csv         one row per cell
#   results/<label>/cells/<cell>.txt    raw clickhouse-benchmark output
#   results/<label>/summary.md          ranked tables
#
# Force C locale so awk's printf doesn't comma-truncate the summary tables.
export LC_ALL=C
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$SCRIPT_DIR/docker-compose.yml"
OVERRIDE="$SCRIPT_DIR/docker-compose.grid.yml"
LABEL="${1:-grid-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT_DIR="$SCRIPT_DIR/results/$LABEL"
mkdir -p "$OUT_DIR"

BRIDGE_JAR="$(cd "$SCRIPT_DIR/../.." && pwd)/target/clickhouse-jdbc-bridge-1.0.3-shaded.jar"
CELL_DURATION=30
CELL_DELAY=10

# ----------------------------------------------------------- GC profiles

# Each line is "name|opts" — Xmx is substituted in.
gc_profile_opts() {
  local name="$1" xmx="$2"
  case "$name" in
    current)      echo "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -Xms${xmx} -Xmx${xmx} -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled -XX:G1ReservePercent=20" ;;
    g1-tuned)     echo "-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError" ;;
    g1-pause50)   echo "-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError" ;;
    g1-pause200)  echo "-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError" ;;
    g1-no-pretouch) echo "-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled -XX:+ExitOnOutOfMemoryError" ;;
    g1-default)   echo "-Xms${xmx} -Xmx${xmx} -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" ;;
    zgc)          echo "-Xms${xmx} -Xmx${xmx} -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError" ;;
    *)            echo "" ; return 1 ;;
  esac
}

# ----------------------------------------------------------- helpers

reconfigure_bridge() {
  local gc_name="$1" xmx="$2"
  local jvm_opts
  jvm_opts="$(gc_profile_opts "$gc_name" "$xmx")" || return 1

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
  echo ">>> reconfigure gc=$gc_name xmx=$xmx"
  docker compose -f "$COMPOSE" -f "$OVERRIDE" up -d --no-deps --force-recreate jdbc-bridge >/dev/null 2>&1 || true
  for i in $(seq 1 60); do
    if curl -fsS --max-time 2 http://localhost:9019/ping >/dev/null 2>&1; then
      if docker compose -f "$COMPOSE" -f "$OVERRIDE" ps sqlserver --format '{{.Status}}' 2>/dev/null | grep -q "healthy"; then
        return 0
      fi
      echo "    !! sqlserver unhealthy — skipping cell"
      return 1
    fi
    sleep 1
  done
  echo "    !! bridge didn't come up healthy in 60s"
  return 1
}

prom_q() {
  curl -fsS "http://localhost:9090/api/v1/query?query=$1" 2>/dev/null \
    | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p'
}

# Build the CH query for a given workload + datasource overrides.
ch_query_for() {
  local workload="$1" batch="$2" fetch="$3" max_block="$4"
  local ds="mssql?batch_size=${batch}&fetch_size=${fetch}&max_block_size=${max_block}"
  case "$workload" in
    point)
      echo "SELECT * FROM jdbc('${ds}', 'SELECT watchid, userid, eventdate, revenue FROM hits WHERE watchid = (1 + ABS(CHECKSUM(NEWID())) % 1000000)')"
      ;;
    small)
      echo "SELECT count() AS n, round(sum(revenue), 2) AS s FROM jdbc('${ds}', 'SELECT TOP 100000 watchid, userid, revenue FROM hits')"
      ;;
    full)
      echo "SELECT count() AS n, round(sum(revenue), 2) AS s FROM jdbc('${ds}', 'SELECT TOP 1000000 watchid, userid, revenue FROM hits')"
      ;;
  esac
}

run_cell() {
  local phase="$1" gc="$2" xmx="$3" batch="$4" fetch="$5" max_block="$6" conc="$7" workload="$8"
  local cell="${phase}_${gc}_xmx${xmx}_b${batch}_f${fetch}_mb${max_block}_c${conc}_w${workload}"
  local out="$OUT_DIR/cells/${cell}.txt"
  mkdir -p "$OUT_DIR/cells"
  local query
  query="$(ch_query_for "$workload" "$batch" "$fetch" "$max_block")"
  echo "  -> $phase gc=$gc xmx=$xmx batch=$batch fetch=$fetch mb=$max_block c=$conc w=$workload"

  echo "$query" | docker compose -f "$COMPOSE" -f "$OVERRIDE" exec -T clickhouse \
    clickhouse benchmark --concurrency "$conc" --timelimit "$CELL_DURATION" \
    --delay "$CELL_DELAY" --max-consecutive-errors 1000 \
    > "$out" 2>&1 || true

  local last queries qps p99 heap_max gc_pause
  last="$(grep '^localhost:' "$out" | tail -1 || true)"
  if [[ -z "$last" ]]; then
    queries=0; qps=0; p99=0
  else
    queries="$(echo "$last" | sed -nE 's/.*queries: ([0-9.]+).*/\1/p')"
    qps="$(echo "$last" | sed -nE 's/.*QPS: ([0-9.]+).*/\1/p')"
    p99="$(awk '/^99%/{p=$2} END{print (p?p:0)}' "$out")"
  fi
  local lookback=$((CELL_DURATION + 15))
  heap_max="$(prom_q "max_over_time(sum(jvm_memory_used_bytes%7Bjob%3D%22jdbc-bridge%22%2Carea%3D%22heap%22%7D)%5B${lookback}s%3A%5D)")"
  gc_pause="$(prom_q "sum(rate(jvm_gc_pause_seconds_sum%7Bjob%3D%22jdbc-bridge%22%7D%5B${CELL_DURATION}s%5D))")"

  echo "$phase,$gc,$xmx,$batch,$fetch,$max_block,$conc,$workload,${queries:-0},${qps:-0},${p99:-0},${heap_max:-0},${gc_pause:-0}" >> "$OUT_DIR/results.csv"
  echo "       qps=$qps p99=${p99}s heap=${heap_max:-?}B"
}

# Find best row from the CSV matching a phase + an optional grep filter,
# sorted by QPS desc, first non-zero row wins.
pick_best() {
  local phase="$1"
  awk -F, -v phase="$phase" 'NR>1 && $1==phase && ($10+0)>0 {print}' "$OUT_DIR/results.csv" \
    | sort -t, -k10,10 -rn | head -1
}

# ============================================================ run

echo "phase,gc,xmx,batch,fetch,max_block,concurrency,workload,queries,qps,p99_s,heap_max_b,gc_pause_per_s" > "$OUT_DIR/results.csv"

# -------------------------------- Phase 1: GC profiles (Xmx=4g, defaults, c=10, full scan)
echo "==== PHASE 1: GC profiles (Xmx=4g, batch=4096 fetch=16384 mb=65535 c=10 full) ===="
PHASE1_GCS=(current g1-tuned g1-pause50 g1-pause200 g1-no-pretouch g1-default zgc)
for gc in "${PHASE1_GCS[@]}"; do
  if reconfigure_bridge "$gc" "4g"; then
    run_cell P1 "$gc" "4g" 4096 16384 65535 10 full
  else
    echo "P1,$gc,4g,4096,16384,65535,10,full,0,0,0,0,0" >> "$OUT_DIR/results.csv"
  fi
done

best="$(pick_best P1)"
BEST_GC="$(echo "$best" | cut -d, -f2)"
echo ""; echo "Phase 1 winner: gc=$BEST_GC"; echo ""

# -------------------------------- Phase 2: Xmx (best GC, defaults, c=10, full scan)
echo "==== PHASE 2: Xmx sweep (gc=$BEST_GC) ===="
for xmx in 1g 2g 4g 8g; do
  if reconfigure_bridge "$BEST_GC" "$xmx"; then
    run_cell P2 "$BEST_GC" "$xmx" 4096 16384 65535 10 full
  else
    echo "P2,$BEST_GC,$xmx,4096,16384,65535,10,full,0,0,0,0,0" >> "$OUT_DIR/results.csv"
  fi
done

best="$(pick_best P2)"
BEST_XMX="$(echo "$best" | cut -d, -f3)"
echo ""; echo "Phase 2 winner: xmx=$BEST_XMX"; echo ""

# -------------------------------- Phase 3: batch × fetch × max_block (best GC+Xmx, c=10, full)
echo "==== PHASE 3: batch × fetch × max_block (gc=$BEST_GC xmx=$BEST_XMX) ===="
reconfigure_bridge "$BEST_GC" "$BEST_XMX" || true
BATCH_VALUES=(1024 4096 16384 65535)
FETCH_VALUES=(4096 16384 65535)
MAX_BLOCK_VALUES=(16384 65535 262144)
for batch in "${BATCH_VALUES[@]}"; do
  for fetch in "${FETCH_VALUES[@]}"; do
    for mb in "${MAX_BLOCK_VALUES[@]}"; do
      run_cell P3 "$BEST_GC" "$BEST_XMX" "$batch" "$fetch" "$mb" 10 full
    done
  done
done

best="$(pick_best P3)"
BEST_BATCH="$(echo "$best" | cut -d, -f4)"
BEST_FETCH="$(echo "$best" | cut -d, -f5)"
BEST_MB="$(echo "$best" | cut -d, -f6)"
echo ""; echo "Phase 3 winner: batch=$BEST_BATCH fetch=$BEST_FETCH mb=$BEST_MB"; echo ""

# -------------------------------- Phase 4: concurrency sweep (best everything, full)
echo "==== PHASE 4: concurrency sweep ===="
for conc in 1 4 10 25 50; do
  run_cell P4 "$BEST_GC" "$BEST_XMX" "$BEST_BATCH" "$BEST_FETCH" "$BEST_MB" "$conc" full
done

# -------------------------------- Phase 5: workload-type sensitivity (best everything, c=10)
echo "==== PHASE 5: workload-type sweep ===="
for wl in point small full; do
  run_cell P5 "$BEST_GC" "$BEST_XMX" "$BEST_BATCH" "$BEST_FETCH" "$BEST_MB" 10 "$wl"
done

# ============================================================ summary

{
  echo "# Grid search v2: $LABEL"
  echo ""
  echo "Cell duration: ${CELL_DURATION}s. Concurrency target c=10 unless varied in Phase 4."
  echo ""
  echo "## Phase 1 — GC profile (Xmx=4g, defaults, c=10, full scan)"
  echo ""
  echo "| gc | qps | p99 (s) | heap_max (MB) | gc_pause (s/s) |"
  echo "|---|---|---|---|---|"
  awk -F, 'NR>1 && $1=="P1" {printf "| %s | %.2f | %.3f | %.0f | %.4f |\n", $2, $10, $11, $12/1024/1024, $13}' "$OUT_DIR/results.csv"
  echo ""
  echo "Winner: **gc=$BEST_GC**"
  echo ""
  echo "## Phase 2 — Xmx sweep (gc=$BEST_GC)"
  echo ""
  echo "| xmx | qps | p99 (s) | heap_max (MB) | gc_pause (s/s) |"
  echo "|---|---|---|---|---|"
  awk -F, 'NR>1 && $1=="P2" {printf "| %s | %.2f | %.3f | %.0f | %.4f |\n", $3, $10, $11, $12/1024/1024, $13}' "$OUT_DIR/results.csv"
  echo ""
  echo "Winner: **xmx=$BEST_XMX**"
  echo ""
  echo "## Phase 3 — batch × fetch × max_block (gc=$BEST_GC xmx=$BEST_XMX)"
  echo ""
  echo "| batch | fetch | max_block | qps | p99 (s) | heap_max (MB) |"
  echo "|---|---|---|---|---|---|"
  awk -F, 'NR>1 && $1=="P3" {printf "| %s | %s | %s | %.2f | %.3f | %.0f |\n", $4, $5, $6, $10, $11, $12/1024/1024}' "$OUT_DIR/results.csv" \
    | sort -t'|' -k5,5 -rn
  echo ""
  echo "Top 5:"
  awk -F, 'NR>1 && $1=="P3" {print $4","$5","$6","$10","$11}' "$OUT_DIR/results.csv" \
    | sort -t, -k4,4 -rn | head -5 | awk -F, '{printf "  - batch=%s fetch=%s mb=%s → qps=%.2f p99=%.3fs\n", $1, $2, $3, $4, $5}'
  echo ""
  echo "Winner: **batch=$BEST_BATCH fetch=$BEST_FETCH max_block=$BEST_MB**"
  echo ""
  echo "## Phase 4 — concurrency sweep (full scan)"
  echo ""
  echo "| c | qps | p99 (s) | heap_max (MB) | gc_pause (s/s) |"
  echo "|---|---|---|---|---|"
  awk -F, 'NR>1 && $1=="P4" {printf "| %s | %.2f | %.3f | %.0f | %.4f |\n", $7, $10, $11, $12/1024/1024, $13}' "$OUT_DIR/results.csv"
  echo ""
  echo "## Phase 5 — workload-type sensitivity (c=10)"
  echo ""
  echo "| workload | qps | p99 (s) | heap_max (MB) |"
  echo "|---|---|---|---|"
  awk -F, 'NR>1 && $1=="P5" {printf "| %s | %.2f | %.3f | %.0f |\n", $8, $10, $11, $12/1024/1024}' "$OUT_DIR/results.csv"
  echo ""
  echo "## Overall winner"
  echo ""
  echo '```'
  echo "gc        = $BEST_GC"
  echo "xmx       = $BEST_XMX"
  echo "batch     = $BEST_BATCH"
  echo "fetch     = $BEST_FETCH"
  echo "max_block = $BEST_MB"
  echo '```'
} > "$OUT_DIR/summary.md"

echo ""
echo ">>> done. summary: $OUT_DIR/summary.md"
cat "$OUT_DIR/summary.md"
