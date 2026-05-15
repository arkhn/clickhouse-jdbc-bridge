#!/usr/bin/env bash
# Load the bench dataset into SQL Server and Oracle.
# Idempotent: re-running is cheap (SQL scripts check row counts and skip if already loaded).
#
# Usage:
#   ./load.sh [--rows N] [--only mssql|oracle]
set -euo pipefail

ROWS="${ROWS:-1000000}"
ONLY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rows)  ROWS="$2"; shift 2 ;;
    --only)  ONLY="$2"; shift 2 ;;
    -h|--help) echo "Usage: $0 [--rows N] [--only mssql|oracle]"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

load_mssql() {
  echo ">>> [mssql] loading $ROWS rows"
  # mssql-tools18 ships in the official mssql image. Copy the script in, run with sqlcmd.
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" cp \
    "$SCRIPT_DIR/init-mssql.sql" sqlserver:/tmp/init-mssql.sql
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T sqlserver \
    /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "Bench_passw0rd!" -No \
      -v ROWS=$ROWS -i /tmp/init-mssql.sql
}

load_oracle() {
  echo ">>> [oracle] loading $ROWS rows"
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" cp \
    "$SCRIPT_DIR/init-oracle.sql" oracle:/tmp/init-oracle.sql
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T oracle \
    bash -lc "sqlplus -S bench/bench_password@//localhost:1521/FREEPDB1 @/tmp/init-oracle.sql $ROWS"
}

case "$ONLY" in
  mssql)  load_mssql ;;
  oracle) load_oracle ;;
  "")     load_mssql; load_oracle ;;
  *)      echo "unknown --only target: $ONLY" >&2; exit 1 ;;
esac

echo "== datagen done =="
