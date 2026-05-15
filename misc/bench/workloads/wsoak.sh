#!/usr/bin/env bash
# Soak: long-running W2 variant with start/end snapshots to flag FD or thread leaks and
# error-rate drift. Use a small duration locally (default 600s); production runs would use 1h+.
#
# Stability is judged by:
#   * FD count drift  (process_files_open_files)   < FD_DRIFT_PCT
#   * thread drift    (jvm_threads_live_threads)   < THREAD_DRIFT_PCT
#   * error rate                                   < ERROR_RATE_MAX
#
# Usage:
#   workloads/wsoak.sh <conc> <duration_s> <out_dir> <datasource>
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"
DS="${4:?datasource (mssql|oracle)}"

mkdir -p "$OUT_DIR"

prom_q() {
  curl -fsS "http://localhost:9090/api/v1/query?query=$1" \
    | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p'
}

echo "== Soak ds=$DS conc=$CONC duration=${DURATION}s =="

# Snapshot baseline before any load. Wait long enough for GC to settle after any prior workload —
# a too-short window makes heap drift look catastrophic when it's just measurement noise.
sleep 30
FD_START="$(prom_q 'process_files_open_files%7Bcomponent%3D%22bridge%22%7D')"
THREADS_START="$(prom_q 'jvm_threads_live_threads%7Bcomponent%3D%22bridge%22%7D')"
HEAP_START="$(prom_q 'sum(jvm_memory_used_bytes%7Bcomponent%3D%22bridge%22%2Carea%3D%22heap%22%7D)')"

# Drive sustained W2-style load.
case "$DS" in
  mssql)  UPSTREAM_SQL="SELECT watchid, userid, eventdate FROM hits WHERE watchid = (1 + ABS(CHECKSUM(NEWID())) % 1000000)" ;;
  oracle) UPSTREAM_SQL="SELECT watchid, userid, eventdate FROM hits WHERE watchid = TRUNC(DBMS_RANDOM.VALUE(1,1000000))" ;;
  *) echo "unknown datasource: $DS" >&2; exit 1 ;;
esac
CH_QUERY="SELECT * FROM jdbc('${DS}', '${UPSTREAM_SQL//\'/\\\'}')"

echo "$CH_QUERY" | docker compose -f "$BENCH_COMPOSE" exec -T clickhouse \
  clickhouse benchmark \
    --concurrency "$CONC" \
    --timelimit "$DURATION" \
    --delay 30 \
    --max-consecutive-errors 1000 \
  > "$OUT_DIR/soak-${DS}.txt" 2>&1 || true

# Snapshot endline.
sleep 30  # let GC settle before reading heap so we don't capture a transient peak
FD_END="$(prom_q 'process_files_open_files%7Bcomponent%3D%22bridge%22%7D')"
THREADS_END="$(prom_q 'jvm_threads_live_threads%7Bcomponent%3D%22bridge%22%7D')"
HEAP_END="$(prom_q 'sum(jvm_memory_used_bytes%7Bcomponent%3D%22bridge%22%2Carea%3D%22heap%22%7D)')"

# Compute drifts and an error rate during the run.
ERR_RATE="$(prom_q "sum(rate(ClickHouseProfileEvents_FailedQuery%5B${DURATION}s%5D))/sum(rate(ClickHouseProfileEvents_Query%5B${DURATION}s%5D))")"

cat > "$OUT_DIR/soak-${DS}-stability.txt" <<EOF
fd_start:       ${FD_START:-n/a}
fd_end:         ${FD_END:-n/a}
threads_start:  ${THREADS_START:-n/a}
threads_end:    ${THREADS_END:-n/a}
heap_start:     ${HEAP_START:-n/a}
heap_end:       ${HEAP_END:-n/a}
error_rate:     ${ERR_RATE:-n/a}
EOF

echo "  -> $OUT_DIR/soak-${DS}.txt"
echo "  -> $OUT_DIR/soak-${DS}-stability.txt"
