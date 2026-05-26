#!/usr/bin/env bash
# Harness for the clickhouse-jdbc-bridge benchmark stack.
#
# Subcommands:
#   ./run.sh [options]                   # run benchmarks (default)
#   ./run.sh compare <label-a> <label-b> # diff two prior runs and exit non-zero on regression
#   ./run.sh gate <label>                # apply thresholds.yaml to <label> and exit non-zero on failure
#
# Phases:
#   1. ensure stack is up + healthy
#   2. ensure dataset is loaded
#   3. warm-up
#   4. run requested workloads x each concurrency setting
#   5. snapshot metrics after each workload
#   6. write summary.md + metrics.tsv
#   7. optionally apply gates
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export BENCH_COMPOSE="$SCRIPT_DIR/docker-compose.yml"

# ---------------------------------------------------------------------------- subcommands

if [[ "${1:-}" == "compare" ]]; then
  shift
  [[ $# -ge 2 ]] || { echo "Usage: $0 compare <label-a> <label-b>" >&2; exit 1; }
  exec python3 "$SCRIPT_DIR/lib/compare.py" \
    "$SCRIPT_DIR/results/$1" "$SCRIPT_DIR/results/$2" "${@:3}"
fi

if [[ "${1:-}" == "gate" ]]; then
  shift
  LBL="${1:?label}"
  exec "$SCRIPT_DIR/run.sh" --gate-only --label "$LBL"
fi

# ---------------------------------------------------------------------------- defaults

WORKLOADS="W1"
DURATION=60
CONCURRENCY="10"
LABEL=""
ROWS=1000000
SKIP_LOAD="false"
DATASOURCES="mssql,oracle"
W3_LIMITS="100000,1000000"
W5_BATCHES="100,1000"
GATE="false"
GATE_ONLY="false"
VIRTUAL_THREADS_MODE="off"

usage() {
  cat <<EOF
Usage: $0 [options]
       $0 compare <label-a> <label-b> [--regress-pct N]
       $0 gate <label>

Options:
  --workloads LIST    comma-separated list (W1,W2,W3,W4,W5,Wsoak)        [default: W1]
  --duration N        seconds per workload run                            [default: 60]
  --concurrency LIST  comma-separated concurrency settings                [default: 10]
  --datasources LIST  comma-separated datasources (mssql,oracle)          [default: mssql,oracle]
  --rows N            dataset row count for datagen                       [default: 1000000]
  --w3-limits LIST    comma-separated row limits for W3                   [default: 100000,1000000]
  --w5-batches LIST   comma-separated batch sizes for W5                  [default: 100,1000]
  --skip-load         skip the datagen step
  --gate              apply thresholds.yaml after run, exit non-zero on fail
  --virtual-threads MODE  on|off — dispatch /query and /write to virtual    [default: off]
                          threads instead of the Vert.x worker pool. Run
                          once with off, once with on, then compare labels.
  --label NAME        results dir name                                    [default: ISO timestamp]
  -h|--help

Workloads:
  W1     ping (HTTP-only)
  W2     point lookup CH -> bridge -> upstream
  W3     bulk read CH -> bridge -> upstream  (uses --w3-limits)
  W4     wide-types CH -> bridge -> upstream
  W5     mutation CH -> bridge -> upstream   (uses --w5-batches)
  Wsoak  long-running W2 with FD/thread leak detection
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workloads)    WORKLOADS="$2"; shift 2 ;;
    --duration)     DURATION="$2"; shift 2 ;;
    --concurrency)  CONCURRENCY="$2"; shift 2 ;;
    --datasources)  DATASOURCES="$2"; shift 2 ;;
    --rows)         ROWS="$2"; shift 2 ;;
    --w3-limits)    W3_LIMITS="$2"; shift 2 ;;
    --w5-batches)   W5_BATCHES="$2"; shift 2 ;;
    --skip-load)    SKIP_LOAD="true"; shift ;;
    --gate)         GATE="true"; shift ;;
    --gate-only)    GATE_ONLY="true"; GATE="true"; shift ;;
    --virtual-threads) VIRTUAL_THREADS_MODE="$2"; shift 2 ;;
    --label)        LABEL="$2"; shift 2 ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

[[ -z "$LABEL" ]] && LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$SCRIPT_DIR/results/$LABEL"
mkdir -p "$OUT_DIR"

HOST_ARCH="$(uname -m)"
EMULATED="false"
[[ "$HOST_ARCH" == "arm64" || "$HOST_ARCH" == "aarch64" ]] && EMULATED="true"

SUMMARY="$OUT_DIR/summary.md"
METRICS_TSV="$OUT_DIR/metrics.tsv"

if [[ "$GATE_ONLY" == "true" ]]; then
  [[ -f "$SUMMARY" ]] || { echo "no summary.md in $OUT_DIR" >&2; exit 1; }
  exec python3 "$SCRIPT_DIR/lib/gate.py" "$OUT_DIR" "$SCRIPT_DIR/thresholds.yaml"
fi

echo ">>> results dir: $OUT_DIR"

case "$VIRTUAL_THREADS_MODE" in
  on)  VIRTUAL_THREADS_ENV="true"  ;;
  off) VIRTUAL_THREADS_ENV="false" ;;
  *)   echo "--virtual-threads must be on|off (got: $VIRTUAL_THREADS_MODE)" >&2; exit 1 ;;
esac
export VIRTUAL_THREADS="$VIRTUAL_THREADS_ENV"
echo ">>> virtual threads: $VIRTUAL_THREADS_MODE (VIRTUAL_THREADS=$VIRTUAL_THREADS)"

# ---------------------------------------------------------------------------- stack

echo ">>> bringing up stack"
docker compose -f "$BENCH_COMPOSE" up -d --no-build --remove-orphans 2>&1 | tail -10
# Force-recreate the bridge if its env changed since last run (compose otherwise
# reuses an existing container with stale env, which silently invalidates the
# virtual-threads toggle).
docker compose -f "$BENCH_COMPOSE" up -d --no-build --force-recreate jdbc-bridge 2>&1 | tail -5

echo ">>> waiting for healthchecks (up to 5min)"
for i in $(seq 1 60); do
  not_ok="$(docker compose -f "$BENCH_COMPOSE" ps --format json 2>/dev/null \
    | python3 -c 'import sys, json
for line in sys.stdin:
    try: o = json.loads(line)
    except: continue
    h = o.get("Health", "")
    if h not in ("", "healthy"):
        print(o.get("Name", "?"), h)' || true)"
  if [[ -z "$not_ok" ]]; then
    echo "    all healthy"
    break
  fi
  echo "    waiting... ($i/60) not_ok=[$not_ok]"
  sleep 5
done

docker compose -f "$BENCH_COMPOSE" ps > "$OUT_DIR/ps.txt"

# ---------------------------------------------------------------------------- datagen

if [[ "$SKIP_LOAD" == "false" ]]; then
  echo ">>> loading dataset ($ROWS rows)"
  "$SCRIPT_DIR/datagen/load.sh" --rows "$ROWS" 2>&1 | tee "$OUT_DIR/datagen.log" >/dev/null
fi

# ---------------------------------------------------------------------------- warmup

echo ">>> warmup"
curl -fsS http://localhost:9019/ping > /dev/null 2>&1 || true
for ds in ${DATASOURCES//,/ }; do
  case "$ds" in
    mssql)  warm_sql="SELECT 1" ;;
    oracle) warm_sql="SELECT 1 FROM dual" ;;
  esac
  docker compose -f "$BENCH_COMPOSE" exec -T clickhouse \
    clickhouse-client -q "SELECT count() FROM jdbc('$ds', '$warm_sql')" > /dev/null 2>&1 \
      || echo "    warmup $ds failed (continuing)"
done

# ---------------------------------------------------------------------------- summary header

{
  echo "# Bench run: $LABEL"
  echo ""
  echo "- date: $(date -u +%FT%TZ)"
  echo "- host: $(uname -srm)"
  echo "- emulated: $EMULATED"
  echo "- workloads: $WORKLOADS"
  echo "- concurrency: $CONCURRENCY"
  echo "- duration: ${DURATION}s"
  echo "- datasources: $DATASOURCES"
  echo "- rows: $ROWS"
  echo "- virtual_threads: $VIRTUAL_THREADS_MODE"
  echo ""
  if [[ "$EMULATED" == "true" ]]; then
    echo "> **emulated=true** — SQL Server and ClickHouse and the bridge run under amd64 emulation on this host."
    echo "> Oracle is native arm64. Throughput/latency from emulated services is directional only."
    echo ""
  fi
} > "$SUMMARY"

echo -e "workload\tconcurrency\tdatasource\textra\tqueries\tqps\tp99_s" > "$METRICS_TSV"

# helper: parse a clickhouse-benchmark stdout file into TSV line
parse_bench() { "$SCRIPT_DIR/workloads/_parse-bench.sh" "$1"; }

# Between every workload: if the bridge died (OOM is the usual cause), restart it and record
# the event so the summary reflects reality. The benchmark itself is allowed to OOM the bridge —
# that's a finding, not a harness failure.
declare -a BRIDGE_RESTARTS=()
ensure_bridge() {
  if ! curl -fsS --max-time 2 http://localhost:9019/ping > /dev/null 2>&1; then
    OOMKILLED="$(docker inspect bench-jdbc-bridge --format '{{.State.OOMKilled}}' 2>/dev/null || echo unknown)"
    EXIT="$(docker inspect bench-jdbc-bridge --format '{{.State.ExitCode}}' 2>/dev/null || echo unknown)"
    echo "    !! bridge unresponsive (oom=$OOMKILLED exit=$EXIT) — restarting"
    BRIDGE_RESTARTS+=("$(date -u +%FT%TZ) oom=$OOMKILLED exit=$EXIT before=$1")
    docker compose -f "$BENCH_COMPOSE" up -d --no-build jdbc-bridge > /dev/null 2>&1 || true
    for j in $(seq 1 30); do
      curl -fsS --max-time 2 http://localhost:9019/ping > /dev/null 2>&1 && break
      sleep 2
    done
  fi
}

# ---------------------------------------------------------------------------- workloads

for wl in ${WORKLOADS//,/ }; do
  for c in ${CONCURRENCY//,/ }; do
    case "$wl" in

      W1)
        D="$OUT_DIR/W1-c$c"
        "$SCRIPT_DIR/workloads/w1-ping.sh"     "$c" "$DURATION" "$D"
        "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
        RPS="$(grep -oE 'Requests per second:\s+[0-9.]+' "$D/w1-ping.txt" | head -1 | awk '{print $NF}')"
        FAILED="$(grep -oE 'Failed requests:\s+[0-9]+' "$D/w1-ping.txt" | awk '{print $NF}')"
        {
          echo "## W1 ping c=$c"
          echo ""
          echo "- rps: ${RPS:-?}"
          echo "- failed: ${FAILED:-?}"
          echo ""
        } >> "$SUMMARY"
        echo -e "W1\t$c\t-\t-\t${RPS:-0}\t${RPS:-0}\t0" >> "$METRICS_TSV"
        ;;

      W2)
        for ds in ${DATASOURCES//,/ }; do
          ensure_bridge "W2-c$c-$ds"
          D="$OUT_DIR/W2-c$c-$ds"
          "$SCRIPT_DIR/workloads/w2-point-lookup.sh" "$c" "$DURATION" "$D" "$ds"
          "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
          read -r Q QPS P99 < <(parse_bench "$D/w2-point-$ds.txt")
          {
            echo "## W2 point-lookup ds=$ds c=$c"
            echo ""
            echo "- queries: $Q"
            echo "- qps: $QPS"
            echo "- p99: ${P99}s"
            echo ""
          } >> "$SUMMARY"
          echo -e "W2\t$c\t$ds\t-\t$Q\t$QPS\t$P99" >> "$METRICS_TSV"
        done
        ;;

      W3)
        for ds in ${DATASOURCES//,/ }; do
          for lim in ${W3_LIMITS//,/ }; do
            ensure_bridge "W3-c$c-$ds-$lim"
            D="$OUT_DIR/W3-c$c-$ds-$lim"
            "$SCRIPT_DIR/workloads/w3-bulk-read.sh" "$c" "$DURATION" "$D" "$ds" "$lim"
            "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
            read -r Q QPS P99 < <(parse_bench "$D/w3-bulk-$ds-$lim.txt")
            {
              echo "## W3 bulk-read ds=$ds limit=$lim c=$c"
              echo ""
              echo "- queries: $Q"
              echo "- qps: $QPS"
              echo "- p99: ${P99}s"
              echo '```'
              cat "$D/w3-bulk-$ds-$lim-jvm.txt" 2>/dev/null || true
              echo '```'
              echo ""
            } >> "$SUMMARY"
            echo -e "W3\t$c\t$ds\t$lim\t$Q\t$QPS\t$P99" >> "$METRICS_TSV"
          done
        done
        ;;

      W4)
        for ds in ${DATASOURCES//,/ }; do
          ensure_bridge "W4-c$c-$ds"
          D="$OUT_DIR/W4-c$c-$ds"
          "$SCRIPT_DIR/workloads/w4-wide-types.sh" "$c" "$DURATION" "$D" "$ds"
          "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
          read -r Q QPS P99 < <(parse_bench "$D/w4-wide-$ds.txt")
          {
            echo "## W4 wide-types ds=$ds c=$c"
            echo ""
            echo "- queries: $Q"
            echo "- qps: $QPS"
            echo "- p99: ${P99}s"
            echo ""
          } >> "$SUMMARY"
          echo -e "W4\t$c\t$ds\t-\t$Q\t$QPS\t$P99" >> "$METRICS_TSV"
        done
        ;;

      W5)
        for ds in ${DATASOURCES//,/ }; do
          for b in ${W5_BATCHES//,/ }; do
            ensure_bridge "W5-c$c-$ds-b$b"
            D="$OUT_DIR/W5-c$c-$ds-b$b"
            "$SCRIPT_DIR/workloads/w5-mutation.sh" "$c" "$DURATION" "$D" "$ds" "$b"
            "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
            read -r Q QPS P99 < <(parse_bench "$D/w5-mutation-$ds-b$b.txt")
            ROWS_INS="$(cat "$D/w5-mutation-$ds-b$b-rows.txt" 2>/dev/null | tr -d '[:space:]')"
            {
              echo "## W5 mutation ds=$ds batch=$b c=$c"
              echo ""
              echo "- queries (inserts issued): $Q"
              echo "- qps: $QPS"
              echo "- p99: ${P99}s"
              echo "- $ROWS_INS"
              echo ""
            } >> "$SUMMARY"
            echo -e "W5\t$c\t$ds\tb$b\t$Q\t$QPS\t$P99" >> "$METRICS_TSV"
          done
        done
        ;;

      Wsoak)
        for ds in ${DATASOURCES//,/ }; do
          ensure_bridge "Wsoak-c$c-$ds"
          D="$OUT_DIR/Wsoak-c$c-$ds"
          "$SCRIPT_DIR/workloads/wsoak.sh" "$c" "$DURATION" "$D" "$ds"
          "$SCRIPT_DIR/workloads/snapshot-metrics.sh" "$D"
          read -r Q QPS P99 < <(parse_bench "$D/soak-$ds.txt")
          {
            echo "## Wsoak ds=$ds c=$c duration=${DURATION}s"
            echo ""
            echo "- queries: $Q"
            echo "- qps: $QPS"
            echo "- p99: ${P99}s"
            echo '```'
            cat "$D/soak-$ds-stability.txt" 2>/dev/null || true
            echo '```'
            echo ""
          } >> "$SUMMARY"
          echo -e "Wsoak\t$c\t$ds\t-\t$Q\t$QPS\t$P99" >> "$METRICS_TSV"
        done
        ;;

      *) echo "unknown workload $wl" >&2 ;;
    esac
  done
done

# Record any bridge restarts that happened during the run (a real signal — usually OOM).
if [[ ${#BRIDGE_RESTARTS[@]} -gt 0 ]]; then
  {
    echo ""
    echo "## Bridge restarts during run"
    echo ""
    echo "The harness restarted the bridge ${#BRIDGE_RESTARTS[@]} time(s). Most common cause is OOM"
    echo "(\`State.OOMKilled=true\`). Cross-check with cAdvisor RSS and JVM heap panels in Grafana."
    echo ""
    for evt in "${BRIDGE_RESTARTS[@]}"; do echo "- $evt"; done
    echo ""
  } >> "$SUMMARY"
fi

echo ">>> done. summary at $SUMMARY"
echo "       metrics at $METRICS_TSV"
[[ ${#BRIDGE_RESTARTS[@]} -gt 0 ]] && echo "       NOTE: bridge restarted ${#BRIDGE_RESTARTS[@]} time(s) — see summary"

# ---------------------------------------------------------------------------- optional gating

if [[ "$GATE" == "true" ]]; then
  echo ">>> applying gates"
  python3 "$SCRIPT_DIR/lib/gate.py" "$OUT_DIR" "$SCRIPT_DIR/thresholds.yaml"
fi
