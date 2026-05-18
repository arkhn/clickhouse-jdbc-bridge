#!/usr/bin/env bash
# Helper: extract clean stats from a clickhouse-benchmark text output.
# Emits 3 fields on stdout: queries_total qps_final p99_seconds
#
# clickhouse-benchmark prints intermediate reports every --delay seconds AND a final cumulative
# report on EOF. We want the final cumulative line and its preceding percentile block.
set -euo pipefail

FILE="${1:?text file}"

# Last 'localhost:...QPS: X.Y...' line is the final cumulative summary.
LAST_SUMMARY="$(grep -nE '^localhost:[0-9]+, queries:' "$FILE" | tail -n1 || true)"
[[ -z "$LAST_SUMMARY" ]] && { echo "0 0 0"; exit 0; }

LAST_LINE_NO="${LAST_SUMMARY%%:*}"
SUMMARY_BODY="${LAST_SUMMARY#*:}"

QUERIES="$(echo "$SUMMARY_BODY" | sed -nE 's/.*queries: ([0-9.]+).*/\1/p')"
QPS="$(echo "$SUMMARY_BODY"     | sed -nE 's/.*QPS: ([0-9.]+).*/\1/p')"

# Percentile block ending at LAST_LINE_NO - 1. Find the most recent "0%\t..." before it.
BLOCK_START="$(awk -v end="$LAST_LINE_NO" 'NR<end && /^0%/ {n=NR} END{print n+0}' "$FILE")"
[[ "$BLOCK_START" -eq 0 ]] && { echo "${QUERIES:-0} ${QPS:-0} 0"; exit 0; }

P99="$(sed -n "${BLOCK_START},${LAST_LINE_NO}p" "$FILE" | awk '/^99%/{print $2; exit}')"

echo "${QUERIES:-0} ${QPS:-0} ${P99:-0}"
