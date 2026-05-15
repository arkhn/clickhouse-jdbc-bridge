#!/usr/bin/env python3
"""Compare two bench runs' summary.md outputs and emit a regression table.

Usage:
    compare.py <results_dir_a> <results_dir_b> [--regress-pct N]

Exits non-zero if any metric in B regresses relative to A by more than --regress-pct.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Section header line: "## W2 point-lookup ds=mssql c=4".
# Body now uses bullet lists ("- queries: N", "- qps: X", "- p99: Ys") emitted by run.sh.
# Also fall back to the legacy embedded clickhouse-benchmark / ab text for older runs.
BULLET_RE = re.compile(r"^- (?P<k>[a-zA-Z0-9_ ]+?):\s*(?P<v>.+)$", re.MULTILINE)
LOCALHOST_RE = re.compile(r"localhost:\d+, queries: (?P<q>[0-9.]+).*QPS: (?P<qps>[0-9.]+)")
P99_RE = re.compile(r"^99%\s+([0-9.]+) sec\.", re.MULTILINE)
AB_RPS_RE = re.compile(r"Requests per second:\s+([0-9.]+)")
AB_MEAN_RE = re.compile(r"Time per request:\s+([0-9.]+).*\(mean\)")
HEAP_MAX_RE = re.compile(r"bridge heap \(B\) max:\s+([0-9.]+)")


def parse_summary(path: Path) -> dict[str, dict[str, float]]:
    text = path.read_text()
    # Split on section headers, keep header in each chunk.
    parts = re.split(r"(?m)^## ", text)
    out: dict[str, dict[str, float]] = {}
    for part in parts[1:]:
        head, _, body = part.partition("\n")
        section = head.strip()
        metrics: dict[str, float] = {}

        # Primary: parse the new "- key: value" bullet lines.
        for bm in BULLET_RE.finditer(body):
            key = bm.group("k").strip().lower()
            val = bm.group("v").strip().rstrip("s")  # strip the trailing "s" on "p99: 0.21s"
            try:
                # extract leading numeric token (e.g. "0.021" from "0.021" or "1.234s")
                num = float(re.match(r"[0-9.]+", val).group(0))
            except (AttributeError, ValueError):
                continue
            # Normalize a few key names.
            if key == "qps":
                metrics["qps"] = num
            elif key == "p99":
                metrics["p99_s"] = num
            elif key == "rps":
                metrics["rps"] = num
            elif key == "queries":
                metrics["queries"] = num
            elif key == "failed":
                metrics["failed"] = num

        # Legacy fallbacks for older summaries.
        m = LOCALHOST_RE.search(body)
        if m:
            metrics.setdefault("queries", float(m.group("q")))
            metrics.setdefault("qps", float(m.group("qps")))
        p99_matches = P99_RE.findall(body)
        if p99_matches:
            metrics.setdefault("p99_s", float(p99_matches[-1]))
        m = AB_RPS_RE.search(body)
        if m:
            metrics.setdefault("rps", float(m.group(1)))
        m = AB_MEAN_RE.search(body)
        if m:
            metrics.setdefault("mean_ms", float(m.group(1)))
        m = HEAP_MAX_RE.search(body)
        if m:
            metrics["heap_max_b"] = float(m.group(1))

        if metrics:
            out[section] = metrics
    return out


def fmt(v: float | None) -> str:
    if v is None:
        return "n/a"
    if v >= 1_000_000:
        return f"{v/1_000_000:.2f}M"
    if v >= 1_000:
        return f"{v/1_000:.2f}k"
    return f"{v:.3f}"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("dir_a", type=Path)
    p.add_argument("dir_b", type=Path)
    p.add_argument("--regress-pct", type=float, default=15.0,
                   help="error if a metric in B regresses by more than this percent vs A")
    args = p.parse_args()

    a = parse_summary(args.dir_a / "summary.md")
    b = parse_summary(args.dir_b / "summary.md")

    # higher_is_better for these metrics; everything else lower-is-better.
    HIGHER_BETTER = {"queries", "qps", "rps"}

    print(f"# Compare: {args.dir_a.name} -> {args.dir_b.name}\n")
    print(f"| section | metric | a | b | delta % | verdict |")
    print(f"|---|---|---|---|---|---|")

    regressed = False
    for section in sorted(set(a) | set(b)):
        ma = a.get(section, {})
        mb = b.get(section, {})
        for key in sorted(set(ma) | set(mb)):
            va = ma.get(key)
            vb = mb.get(key)
            if va is None or vb is None or va == 0:
                print(f"| {section} | {key} | {fmt(va)} | {fmt(vb)} | n/a | - |")
                continue
            delta = (vb - va) / va * 100
            if key in HIGHER_BETTER:
                bad = delta < -args.regress_pct
            else:
                bad = delta > args.regress_pct
            verdict = "REGRESS" if bad else "ok"
            if bad:
                regressed = True
            print(f"| {section} | {key} | {fmt(va)} | {fmt(vb)} | {delta:+.1f}% | {verdict} |")

    return 1 if regressed else 0


if __name__ == "__main__":
    sys.exit(main())
