# Deployment recommendations

How to size and tune the bridge for production, derived from the grid search at
`misc/bench/scripts/grid-search.sh` (full-scan workload, c=10 concurrent clients,
SQL Server upstream, on a Java 25 JVM with the perf patches in commits `a2fa4ae`
and `04d56c9`).

The script is reproducible — re-run it on your target host to confirm the
recommendation against your actual upstream and CPU profile. The numbers below
were measured on an arm64 dev laptop with sqlserver under amd64 emulation, so
they're directional rather than absolute. **The shape of the result generalises;
the absolute QPS does not.**

## TL;DR

```yaml
env:
  - name: JDBC_BRIDGE_JVM_OPTS
    value: >-
      -Xms2g -Xmx2g
      -XX:+UseG1GC
      -XX:MaxGCPauseMillis=100
      -XX:+ParallelRefProcEnabled
      -XX:+AlwaysPreTouch
      -XX:+ExitOnOutOfMemoryError
      -Xlog:gc*:file=/app/logs/gc.log:tags,time,uptime,level
resources:
  limits:
    memory: 2.5Gi
  requests:
    memory: 2.5Gi
```

Container memory limit = `Xmx + ~25%` non-heap headroom (metaspace, direct
buffers, JIT code cache, JNI). Don't go lower; the JVM will be killed by the
cgroup OOM rather than the JVM's own OOM handler.

## What the grid actually showed

Full-scan of a 1M-row table at c=10, 30 s per cell:

| GC profile                               | Xmx=1g | Xmx=2g | Xmx=4g | Xmx=8g | Xmx=10g |
|------------------------------------------|--------|--------|--------|--------|---------|
| current (G1 + StringDedup + Reserve=20)  | 12.77  | 12.67  | 12.10  | **9.32** | **9.89** |
| g1-tuned (this doc)                      | 12.52  | **12.84** | 12.16  | 12.30  | 12.21   |
| zgc                                      | 3.50   | 5.29   | 6.37   | 5.62   | 3.22    |

QPS units. Higher is better. p99 latency tracked the same shape (g1-tuned p99
held at 1.8–2.5 s across all Xmx; the `current` profile drifted upward past
Xmx=8g).

Three things to take away:

1. **G1 beats ZGC by 2–3× on this workload.** ZGC's sub-millisecond pauses come
   with real concurrent-reclamation CPU cost; at high allocation rates (the
   bridge constantly churns short-lived `ByteBuffer` and JDBC result objects)
   G1's throughput wins. ZGC is the right choice for *request-path latency
   floors*, not for batch-streaming throughput.

2. **`-XX:+UseStringDeduplication` hurts at large heap.** With Xmx ≥ 8g, more
   short-lived strings survive young-gen long enough to be candidates for
   dedup, but they were going to die anyway — so the dedup CPU is wasted.
   Throughput drops 25–27% at Xmx=10g vs Xmx=2g. Drop the flag.

3. **Xmx > 2g doesn't buy anything.** The workload is upstream-bound (and the
   bridge itself, with the OOM/backpressure fix in commit `67ff93a`, holds
   in-flight Netty queues bounded regardless of heap). 2 GB is enough for c=10.
   Scale Xmx up only if you're running at much higher concurrency *and* you
   see GC pause rate climbing past ~5% of wall time.

## When to re-run the grid

The recommendation above was tuned for:
- Concurrency around c=10
- Full-scan reads of 1M-row tables
- SQL Server as the upstream
- 4 CPU cores allocated to the bridge container

Re-run `misc/bench/scripts/grid-search.sh` if any of those differ materially
on your prod host. The script outputs `results/<label>/summary.md` with a
ranked table and the winning combo.

Per-request batch/fetch tuning lives in `QueryParameters.java`. The current
defaults (`batch_size=4096`, `fetch_size=16384`, set in commit `a2fa4ae`) sit
on a flat throughput plateau across the entire `{1024…65535}^2` grid —
operators can leave them alone unless their workload is materially different.

## Sizing heuristic at higher concurrency

If you're running materially higher than c=10:

| concurrency | suggested Xmx | suggested container limit |
|-------------|---------------|---------------------------|
| ≤ 10        | 2 g           | 2.5 Gi                    |
| 10–50       | 4 g           | 5 Gi                      |
| 50–200      | 8 g           | 10 Gi                     |
| > 200       | re-run grid   | —                         |

This is conservative — backpressure (commit `67ff93a`) caps in-flight bytes at
`concurrency × max_block_size`, so actual heap pressure scales sub-linearly.
The table errs on the side of headroom.

## HikariCP / datasource pool

Out of scope for this doc. The W5 (mutation) workload in `misc/bench/` surfaced
that small batches at low concurrency hit a p99 cliff with default Hikari
settings (`connectionTestQuery=SELECT 1` per borrow + no Oracle implicit
statement cache); that's a separate tuning effort that depends on your
specific upstream and write pattern. Start with the Hikari panels added to the
Grafana dashboard (commit `132ef4b`) and tune from there if you have a
write-heavy workload.

## What we deliberately did NOT recommend

- **`-XX:+UseStringDeduplication`** — net negative for this workload.
- **`-XX:G1ReservePercent=20`** — only helps if you've actually hit
  evacuation failures. Default (10) is fine until proven otherwise.
- **`-XX:+UnlockExperimentalVMOptions`** — no longer required by any
  non-experimental flag on JDK 25.
- **Mismatched `-Xms`/`-Xmx`** — set them equal to avoid resize pauses during
  ramp-up.
- **ZGC** — see point 1 above. Worth revisiting if you're running with much
  larger heaps (≥ 32g) and care about p99 over throughput.

## References

- Bench harness: `misc/bench/`
- Grid script: `misc/bench/scripts/grid-search.sh`
- Perf patches: `a2fa4ae`, `04d56c9`
- OOM/backpressure fix: `67ff93a`
- ClickHouse-vs-bridge perf analysis: discussed in the bench-suite PR
