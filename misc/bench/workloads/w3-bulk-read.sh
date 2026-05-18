#!/usr/bin/env bash
# W3 bulk read: CH aggregates over N rows fetched through the bridge from the upstream DB.
# This is the historical OOM workload — the bridge has to hold/stream a large result set in
# RowBinary format while ClickHouse pulls and aggregates. Watch jvm_memory_used_bytes during
# the run to see whether the bridge streams or buffers.
#
# Usage:
#   workloads/w3-bulk-read.sh <conc> <duration_s> <out_dir> <datasource> <row_limit>
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"
DS="${4:?datasource (mssql|oracle)}"
LIMIT="${5:?row limit (e.g. 100000 or 'all')}"

mkdir -p "$OUT_DIR"

case "$DS" in
  mssql)
    if [[ "$LIMIT" == "all" ]]; then
      UPSTREAM_SQL="SELECT watchid, userid, revenue FROM hits"
    else
      UPSTREAM_SQL="SELECT TOP $LIMIT watchid, userid, revenue FROM hits"
    fi
    ;;
  oracle)
    # Oracle returns identifiers in UPPERCASE unless they are quoted. CH is case-sensitive,
    # so we alias each column with a quoted lowercase identifier to keep ClickHouse happy.
    if [[ "$LIMIT" == "all" ]]; then
      UPSTREAM_SQL='SELECT watchid AS "watchid", userid AS "userid", revenue AS "revenue" FROM hits'
    else
      UPSTREAM_SQL='SELECT watchid AS "watchid", userid AS "userid", revenue AS "revenue" FROM hits WHERE ROWNUM <= '"$LIMIT"
    fi
    ;;
  *) echo "unknown datasource: $DS" >&2; exit 1 ;;
esac

# Aggregate query lets us validate row count and forces every row to flow through the bridge,
# but ClickHouse can drop everything except the counter (cheap on the CH side; the cost is
# the bridge's serialization path which is what we want to measure).
CH_QUERY="SELECT count() AS n, round(sum(revenue), 2) AS total FROM jdbc('${DS}', '${UPSTREAM_SQL//\'/\\\'}')"

echo "== W3 bulk-read ds=$DS limit=$LIMIT conc=$CONC duration=${DURATION}s =="
echo "  query: $CH_QUERY"

echo "$CH_QUERY" | docker compose -f "$BENCH_COMPOSE" exec -T clickhouse \
  clickhouse benchmark \
    --concurrency "$CONC" \
    --timelimit "$DURATION" \
    --delay 10 \
    --max-consecutive-errors 1000 \
  > "$OUT_DIR/w3-bulk-${DS}-${LIMIT}.txt" 2>&1 || true

# Sample heap during the run (post-hoc query against Prometheus, last DURATION+10s window)
LOOKBACK="$((DURATION + 10))s"
HEAP_MAX="$(curl -fsS "http://localhost:9090/api/v1/query?query=max_over_time(sum(jvm_memory_used_bytes%7Bcomponent%3D%22bridge%22%2Carea%3D%22heap%22%7D)%5B${LOOKBACK}%3A%5D)" \
  | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p')"
HEAP_MEAN="$(curl -fsS "http://localhost:9090/api/v1/query?query=avg_over_time(sum(jvm_memory_used_bytes%7Bcomponent%3D%22bridge%22%2Carea%3D%22heap%22%7D)%5B${LOOKBACK}%3A%5D)" \
  | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p')"
GC_PAUSE="$(curl -fsS "http://localhost:9090/api/v1/query?query=sum(rate(jvm_gc_pause_seconds_sum%7Bcomponent%3D%22bridge%22%7D%5B${LOOKBACK}%5D))" \
  | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)"\].*/\1/p')"

cat > "$OUT_DIR/w3-bulk-${DS}-${LIMIT}-jvm.txt" <<EOF
bridge heap (B) max:   ${HEAP_MAX:-n/a}
bridge heap (B) mean:  ${HEAP_MEAN:-n/a}
bridge gc pause (s/s): ${GC_PAUSE:-n/a}
EOF

echo "  -> $OUT_DIR/w3-bulk-${DS}-${LIMIT}.txt"
echo "  -> $OUT_DIR/w3-bulk-${DS}-${LIMIT}-jvm.txt"
