#!/usr/bin/env bash
# W2 point lookup: clickhouse-benchmark drives CH, which calls the bridge, which queries the upstream
# DB for a single row. Exercises connection-pool reuse + /columns_info round-trip + 1-row serialization.
#
# Usage (from harness):
#   workloads/w2-point-lookup.sh <conc> <duration_s> <out_dir> <datasource>
#
# Notes:
#   - clickhouse-benchmark uses --iterations OR --timelimit. We use --timelimit for soak-like control.
#   - Random `watchid` parameter forces real lookups, not cached plans.
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"
DS="${4:?datasource (mssql|oracle)}"

mkdir -p "$OUT_DIR"

# Build the SQL. Oracle is case-insensitive but the bridge passes identifiers through; uppercase
# is safest. SQL Server is fine with the lowercase names used in init-mssql.sql.
case "$DS" in
  mssql)
    UPSTREAM_SQL="SELECT watchid, userid, eventdate, revenue FROM hits WHERE watchid = (1 + ABS(CHECKSUM(NEWID())) % 1000000)"
    ;;
  oracle)
    UPSTREAM_SQL="SELECT watchid, userid, eventdate, revenue FROM hits WHERE watchid = TRUNC(DBMS_RANDOM.VALUE(1,1000000))"
    ;;
  *) echo "unknown datasource: $DS" >&2; exit 1 ;;
esac

CH_QUERY="SELECT * FROM jdbc('${DS}', '${UPSTREAM_SQL//\'/\\\'}')"

echo "== W2 point-lookup ds=$DS conc=$CONC duration=${DURATION}s =="
echo "  query: $CH_QUERY"

# clickhouse-benchmark accepts the query on stdin and emits JSON with --json=path.
# Note: clickhouse-benchmark in CH 25.x removed --json. We capture text output and parse it.
# --randomize is a bare flag (no value). --max-consecutive-errors high so a few emulation
# stutters don't kill the run.
echo "$CH_QUERY" | docker compose -f "$BENCH_COMPOSE" exec -T clickhouse \
  clickhouse benchmark \
    --concurrency "$CONC" \
    --timelimit "$DURATION" \
    --delay 5 \
    --randomize \
    --max-consecutive-errors 1000 \
  > "$OUT_DIR/w2-point-${DS}.txt" 2>&1 || true

echo "  -> $OUT_DIR/w2-point-${DS}.txt"
