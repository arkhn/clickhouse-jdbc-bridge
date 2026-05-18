#!/usr/bin/env python3
"""Apply thresholds.yaml to a results dir and exit non-zero on any failure.

Reads:
    <results_dir>/metrics.tsv  (TSV: workload, concurrency, datasource, extra, queries, qps, p99_s)
    <results_dir>/W*/snapshots/bridge-gc.log     (optional)
    <results_dir>/Wsoak-*/soak-*-stability.txt   (optional)

thresholds.yaml is a flat key:value file (no PyYAML dependency).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


def parse_thresholds(p: Path) -> dict[str, float]:
    out: dict[str, float] = {}
    for line in p.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        k, v = line.split(":", 1)
        try:
            out[k.strip()] = float(v.strip())
        except ValueError:
            pass
    return out


def parse_metrics_tsv(p: Path) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    lines = p.read_text().splitlines()
    if not lines:
        return rows
    hdr = lines[0].split("\t")
    for line in lines[1:]:
        if not line.strip():
            continue
        vals = line.split("\t")
        rows.append(dict(zip(hdr, vals)))
    return rows


def parse_stability(p: Path) -> dict[str, float]:
    out: dict[str, float] = {}
    for line in p.read_text().splitlines():
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        try:
            out[k.strip()] = float(v.strip())
        except ValueError:
            pass
    return out


def pct_drift(start: float, end: float) -> float:
    if start == 0:
        return 0.0
    return (end - start) / start * 100.0


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: gate.py <results_dir> <thresholds.yaml>", file=sys.stderr)
        return 2

    results_dir = Path(sys.argv[1])
    th = parse_thresholds(Path(sys.argv[2]))

    failures: list[str] = []
    metrics = parse_metrics_tsv(results_dir / "metrics.tsv")

    for row in metrics:
        wl = row["workload"]
        try:
            qps = float(row["qps"] or 0)
            p99 = float(row["p99_s"] or 0)
        except (KeyError, ValueError):
            continue

        if wl == "W2":
            mx = th.get("p99_w2_max")
            mn = th.get("qps_w2_min")
            if mx is not None and p99 > mx:
                failures.append(f"W2 p99 {p99}s > max {mx}s ({row['datasource']} c={row['concurrency']})")
            if mn is not None and qps < mn:
                failures.append(f"W2 qps {qps} < min {mn} ({row['datasource']} c={row['concurrency']})")
        elif wl == "W3":
            mx = th.get("p99_w3_max")
            mn = th.get("qps_w3_min")
            if mx is not None and p99 > mx:
                failures.append(f"W3 p99 {p99}s > max {mx}s ({row['datasource']} {row['extra']} c={row['concurrency']})")
            if mn is not None and qps < mn:
                failures.append(f"W3 qps {qps} < min {mn} ({row['datasource']} {row['extra']} c={row['concurrency']})")
        elif wl == "W4":
            mx = th.get("p99_w4_max")
            if mx is not None and p99 > mx:
                failures.append(f"W4 p99 {p99}s > max {mx}s ({row['datasource']} c={row['concurrency']})")

    # Soak stability checks.
    for stab_file in results_dir.glob("Wsoak-*/soak-*-stability.txt"):
        s = parse_stability(stab_file)
        fd_drift = pct_drift(s.get("fd_start", 0), s.get("fd_end", 0))
        th_drift = pct_drift(s.get("threads_start", 0), s.get("threads_end", 0))
        heap_drift = pct_drift(s.get("heap_start", 0), s.get("heap_end", 0))
        err_rate = s.get("error_rate", 0)
        if th.get("fd_drift_pct_max") is not None and abs(fd_drift) > th["fd_drift_pct_max"]:
            failures.append(f"soak fd drift {fd_drift:+.1f}% > {th['fd_drift_pct_max']}% ({stab_file.name})")
        if th.get("thread_drift_pct_max") is not None and abs(th_drift) > th["thread_drift_pct_max"]:
            failures.append(f"soak thread drift {th_drift:+.1f}% > {th['thread_drift_pct_max']}% ({stab_file.name})")
        if th.get("heap_drift_pct_max") is not None and abs(heap_drift) > th["heap_drift_pct_max"]:
            failures.append(f"soak heap drift {heap_drift:+.1f}% > {th['heap_drift_pct_max']}% ({stab_file.name})")
        if th.get("error_rate_max") is not None and err_rate > th["error_rate_max"]:
            failures.append(f"soak error rate {err_rate} > {th['error_rate_max']} ({stab_file.name})")

    if failures:
        print(f"\n## Gate FAILED: {len(failures)} check(s)\n")
        for f in failures:
            print(f"  - {f}")
        return 1

    print("\n## Gate PASSED — all checks within thresholds\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
