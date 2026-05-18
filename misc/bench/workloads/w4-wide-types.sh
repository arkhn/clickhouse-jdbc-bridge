#!/usr/bin/env bash
# W4 wide-types: exercises every JDBC type conversion path. Result set is small (10k rows) but
# each row pulls every type the bridge knows how to map. CPU-bound on the bridge's TypeUtils
# rather than on raw row volume.
#
# Usage:
#   workloads/w4-wide-types.sh <conc> <duration_s> <out_dir> <datasource>
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"
DS="${4:?datasource (mssql|oracle)}"

mkdir -p "$OUT_DIR"

# Aggregate so CH doesn't have to materialize the whole result. Sum/count cover all numeric
# columns; max() over text fields keeps them in the pipe.
case "$DS" in
  mssql)
    # Skip c_uniqueidentifier — still a JDBC vendor extension the bridge can't map.
    UPSTREAM_SQL="SELECT id, c_tinyint, c_smallint, c_int, c_bigint, c_decimal, c_float, c_real, c_money, c_bit, c_char, c_varchar, c_nvarchar, c_date, c_time, c_datetime, c_datetime2, c_datetimeoffset, c_binary FROM wide_types"
    ;;
  oracle)
    # Quote identifiers for lowercase pass-through to CH. Interval & tz types are still
    # OracleType-only and remain a gap — capture them in a follow-up.
    UPSTREAM_SQL='SELECT id AS "id", c_number AS "c_number", c_bignum AS "c_bignum", c_decimal AS "c_decimal", c_float AS "c_float", c_double AS "c_double", c_char AS "c_char", c_varchar2 AS "c_varchar2", c_nvarchar2 AS "c_nvarchar2", c_date AS "c_date", c_ts AS "c_ts" FROM wide_types'
    ;;
  *) echo "unknown datasource: $DS" >&2; exit 1 ;;
esac

CH_QUERY="SELECT count() FROM jdbc('${DS}', '${UPSTREAM_SQL//\'/\\\'}')"

echo "== W4 wide-types ds=$DS conc=$CONC duration=${DURATION}s =="
echo "$CH_QUERY" | docker compose -f "$BENCH_COMPOSE" exec -T clickhouse \
  clickhouse benchmark \
    --concurrency "$CONC" \
    --timelimit "$DURATION" \
    --delay 10 \
    --max-consecutive-errors 1000 \
  > "$OUT_DIR/w4-wide-${DS}.txt" 2>&1 || true

echo "  -> $OUT_DIR/w4-wide-${DS}.txt"
