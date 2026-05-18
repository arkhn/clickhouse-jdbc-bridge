#!/usr/bin/env bash
# W5 mutations: ClickHouse inserts rows through the bridge via a CH-side JDBC engine table
# pointing at an upstream target. Each clickhouse-benchmark iteration issues an INSERT of
# $BATCH rows, stressing HikariCP's write path + prepared statement reuse on the bridge.
#
# Usage:
#   workloads/w5-mutation.sh <conc> <duration_s> <out_dir> <datasource> <batch_size>
set -euo pipefail

CONC="${1:?concurrency}"
DURATION="${2:?duration_s}"
OUT_DIR="${3:?out_dir}"
DS="${4:?datasource (mssql|oracle)}"
BATCH="${5:?batch_size}"

mkdir -p "$OUT_DIR"

cmp() { docker compose -f "$BENCH_COMPOSE" "$@"; }

echo "== W5 mutation ds=$DS batch=$BATCH conc=$CONC duration=${DURATION}s =="

# 1. Recreate the upstream target table (idempotent drop+create).
case "$DS" in
  mssql)
    cmp exec -T sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "Bench_passw0rd!" -No -d bench -Q "
      IF OBJECT_ID('dbo.bench_writes','U') IS NOT NULL DROP TABLE dbo.bench_writes;
      CREATE TABLE dbo.bench_writes (id BIGINT NOT NULL, payload VARCHAR(64) NOT NULL);
    " > "$OUT_DIR/w5-setup-${DS}.log" 2>&1
    UPSTREAM_TABLE="bench_writes"
    ;;
  oracle)
    # APP_USER's schema is BENCH. Oracle uppercases unquoted identifiers — we expose them in
    # lowercase to the bridge by quoting the column names at table creation.
    cmp exec -T oracle bash -lc 'sqlplus -S bench/bench_password@//localhost:1521/FREEPDB1 <<SQL > /tmp/w5-setup.log 2>&1
WHENEVER SQLERROR CONTINUE
BEGIN EXECUTE IMMEDIATE '"'"'DROP TABLE bench_writes'"'"'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE bench_writes ("id" NUMBER(19) NOT NULL, "payload" VARCHAR2(64) NOT NULL);
EXIT;
SQL
cat /tmp/w5-setup.log' > "$OUT_DIR/w5-setup-${DS}.log" 2>&1
    UPSTREAM_TABLE="bench_writes"
    ;;
  *) echo "unknown datasource: $DS" >&2; exit 1 ;;
esac

# 2. Recreate the CH-side JDBC engine table. ENGINE=JDBC(<datasource>, <schema>, <table>) —
#    empty schema falls back to the JDBC driver's default for that datasource.
CH_TABLE="bench_writes_${DS}"
cmp exec -T clickhouse clickhouse-client --multiquery -q "
  DROP TABLE IF EXISTS default.${CH_TABLE};
  CREATE TABLE default.${CH_TABLE} (id UInt64, payload String)
    ENGINE = JDBC('${DS}?batch_size=${BATCH}', '', '${UPSTREAM_TABLE}');
" > "$OUT_DIR/w5-ch-create.log" 2>&1 || true

# 3. Drive the workload. Each "query" inserts $BATCH rows via the engine table.
CH_QUERY="INSERT INTO default.${CH_TABLE} (id, payload) SELECT number AS id, concat('p_', toString(number)) AS payload FROM numbers(${BATCH})"
echo "  query: $CH_QUERY"

echo "$CH_QUERY" | cmp exec -T clickhouse \
  clickhouse benchmark \
    --concurrency "$CONC" \
    --timelimit "$DURATION" \
    --delay 10 \
    --max-consecutive-errors 1000 \
    --ignore-error \
  > "$OUT_DIR/w5-mutation-${DS}-b${BATCH}.txt" 2>&1 || true

# 4. Correctness check — count rows persisted upstream.
case "$DS" in
  mssql)
    INSERTED="$(cmp exec -T sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "Bench_passw0rd!" -No -d bench -h-1 -Q "SET NOCOUNT ON; SELECT COUNT_BIG(*) FROM dbo.bench_writes;" 2>/dev/null | tr -d '[:space:]')"
    ;;
  oracle)
    INSERTED="$(cmp exec -T oracle bash -lc 'sqlplus -S bench/bench_password@//localhost:1521/FREEPDB1 <<SQL 2>/dev/null
SET HEADING OFF FEEDBACK OFF PAGES 0
SELECT COUNT(*) FROM bench_writes;
EXIT;
SQL
' | tr -d '[:space:]')"
    ;;
esac
echo "rows_inserted: ${INSERTED:-unknown}" > "$OUT_DIR/w5-mutation-${DS}-b${BATCH}-rows.txt"

echo "  -> $OUT_DIR/w5-mutation-${DS}-b${BATCH}.txt"
echo "  -> rows inserted: ${INSERTED:-unknown}"
