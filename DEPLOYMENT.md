# Deployment recommendations

How to size and tune the bridge for production. Derived from a comprehensive
five-phase grid search at `misc/bench/scripts/grid-search-v2.sh` (sweeps 7 GC
profiles × 4 Xmx values × 36 batch/fetch/max_block combos × 5 concurrencies ×
3 workload types) against a 1M-row SQL Server upstream, on Java 25 with the
perf patches in commits `a2fa4ae`, `04d56c9`, `67ff93a`.

The script is reproducible — **re-run it on your target host to confirm the
recommendation against your actual upstream and CPU profile.** Numbers below
were measured on an arm64 dev laptop with sqlserver under amd64 emulation, so
they're directional rather than absolute. The shape of the result generalises;
the absolute QPS does not.

## TL;DR (bare-metal / VM)

```bash
JDBC_BRIDGE_JVM_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/app/logs/gc.log:tags,time,uptime,level"
```

That's it. The grid-v2 sweep across 7 G1 variants found **no statistically
significant gain** from `MaxGCPauseMillis`, `ParallelRefProcEnabled`,
`StringDeduplication`, `G1ReservePercent`, or `AlwaysPreTouch` — they all
sit within ±5% of each other on QPS, and most of them *use more heap* than
the default profile. **The simplest config wins.**

Container memory budget: `Xmx + ~25%` non-heap headroom (metaspace, direct
buffers, JIT code cache, JNI from the JDBC driver). For Xmx=2g, allocate
2.5 GiB. Don't go lower; the JVM will be killed by the cgroup OOM rather
than its own OOM handler.

## TL;DR (Kubernetes)

```yaml
env:
  - name: JDBC_BRIDGE_JVM_OPTS
    value: >-
      -XX:InitialRAMPercentage=80 -XX:MaxRAMPercentage=80
      -XX:+UseG1GC
      -XX:ActiveProcessorCount=4
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/oom
      -Xlog:gc*:stdout:tags,time,uptime,level
resources:
  requests: { memory: 2.5Gi, cpu: "4" }
  limits:   { memory: 2.5Gi, cpu: "4" }   # request == limit => Guaranteed QoS
livenessProbe:
  httpGet: { path: /ping, port: 9019 }
  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 3
readinessProbe:
  httpGet: { path: /ping, port: 9019 }
  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 2
terminationGracePeriodSeconds: 60      # let in-flight queries drain
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }
volumeMounts:                          # writable mounts needed for read-only fs
  - { name: app-logs,  mountPath: /app/logs }
  - { name: oom-dumps, mountPath: /var/log/oom }
volumes:
  - { name: app-logs,  emptyDir: { sizeLimit: 500Mi } }
  - { name: oom-dumps, emptyDir: { sizeLimit: 3Gi } }   # >= Xmx for full dump
```

What changes vs the bare-metal recipe:

- **`MaxRAMPercentage` instead of `-Xms/-Xmx`** — JVM sizes itself from the
  cgroup limit. Tune memory in one place (the Deployment YAML) instead of
  drifting between the env var and the resource block.
- **`-XX:ActiveProcessorCount=N` matching the CPU limit** — the JVM otherwise
  sees all node cores and computes GC/ForkJoinPool sizes for them, leading to
  thread-thrash inside a 4-core slice.
- **`-Xlog:gc*:stdout`** — logs go through kubelet → your log shipper. No
  bind-mount, no rotation, no readOnlyRootFilesystem fight.
- **HeapDumpPath in an emptyDir** — survives container restart (within the pod)
  for post-mortem. If the pod restarts, dumps are wiped; use a preStop hook +
  S3 upload if you need durable forensics.
- **`+AlwaysPreTouch` deliberately dropped** — it adds 1–3 s to pod startup at
  Xmx=2g, costing readiness latency on every rolling deploy. The throughput
  difference at steady state is in the noise.

### Is the heap dump necessary?

For most production deployments, **no** — Prometheus heap-usage metrics + the
GC log will tell you what happened during an OOM. The heap dump is only
useful for `kubectl cp`-ing the `.hprof` out and analysing it offline with
Eclipse MAT or VisualVM. If you don't actually do that, skip the dump:

```yaml
# Trim from the JVM_OPTS above:
- -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/oom
# And remove the oom-dumps volume + mount.
```

The bridge will still exit fast on OOM thanks to `+ExitOnOutOfMemoryError`,
and Kubernetes will replace the pod.

## What the grid actually showed

Grid v2: full-scan of a 1M-row table at c=10 unless varied, 30 s per cell.
Real precision (the `qps=12.00` in the auto-summary is locale-truncated; the
CSV has the real values):

### GC profiles at Xmx=4g

```
current          qps=11.88   p99=2.48s   heap=1730 MB   gc-time=1.6%
g1-tuned         qps=12.16   p99=2.95s   heap=1750 MB   gc-time=1.4%
g1-pause50       qps=11.97   p99=2.27s   heap=2358 MB   gc-time=1.2%
g1-pause200      qps=12.25   p99=2.68s   heap=2358 MB   gc-time=1.6%
g1-no-pretouch   qps=12.02   p99=2.07s   heap=1700 MB   gc-time=1.1%
g1-default       qps=12.59   p99=1.95s   heap= 544 MB   gc-time=1.9%  ← winner
zgc              qps= 6.73   p99=2.75s   heap=2922 MB   gc-time=0.01%
```

`g1-default` (just `-Xms2g -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`)
wins both on QPS AND on heap usage. The tuned variants are within noise on
throughput but pay 3× the memory.

### Xmx sweep (g1-tuned)

```
Xmx=1g   qps=12.89   p99=1.82s
Xmx=2g   qps=12.89   p99=2.09s
Xmx=4g   qps=12.37   p99=2.74s
Xmx=8g   qps=12.22   p99=2.00s
```

QPS spread = noise. **No throughput gain past Xmx=2g.** Larger heaps just
hold more transient garbage.

### batch × fetch × max_block top picks (36-cell sweep)

```
─── peak QPS ──────────────────────────────────────────────────────────
batch=65535 fetch=4096   mb=16384    qps=13.94   heap=2071 MB
batch=16384 fetch=4096   mb=65535    qps=13.91   heap= 262 MB   ★ memory-efficient
batch=65535 fetch=65535  mb=65535    qps=13.87   heap=4698 MB
batch=65535 fetch=65535  mb=262144   qps=13.86   heap=4698 MB
```

All cells across the full 36-combo grid land between **13.6–14.0 QPS** — flat
plateau. The interesting axis is **heap**: the same QPS can be achieved
with anywhere from 262 MB to 4900 MB of heap depending on the combo.
**The single best memory-efficient combo is `batch=16384 fetch=4096
max_block_size=65535`** — peak throughput, **~18× less heap** than the worst
cell at the same QPS.

### Concurrency sweep (full scan, best params)

```
c=1    qps=  5.26   p99=0.29s   ← single client under-utilises
c=4    qps= 14.05   p99=0.36s   ★ peak throughput, low p99
c=10   qps= 13.83   p99=0.96s   ← saturated, p99 climbing
c=25   qps= 12.80   p99=2.94s   ← over-saturated
c=50   qps= 13.34   p99=6.34s   ← queueing dominates
```

Throughput saturates at **c=4** on this host. Past c=4 you pay linearly
worse p99 for no QPS gain — the upstream (mssql-emulated-on-arm64) caps total
system throughput regardless of how many clients pile on. **For full-scan
workloads, scale horizontally past c=4 per pod rather than vertically.**

### Workload-type sensitivity (c=10, best params)

```
point lookups (1 row)        qps=1376   p99=34 ms
small scan (100k rows)       qps= 137   p99=123 ms
full scan (1M rows)          qps=  14   p99=1.02 s
```

The same JVM + bridge params handle **1376 QPS point lookups and 14 QPS
1M-row scans without retuning**. The earlier regression on point lookups
(seen in `perf-fix-validate` at 1156 QPS) was caused by an unfortunate
batch/fetch ratio; the v2 winner combo recovers it.

## Per-request tuning (batch / fetch / max_block_size)

These are the per-query parameters operators can override via the JDBC URL
(`'mssql?batch_size=X&fetch_size=Y&max_block_size=Z'`). Compiled-in defaults
live in `QueryParameters.java`.

| param | current default | grid-v2 sweet spot | notes |
|---|---|---|---|
| `batch_size` | 4096 | 16384 | flat plateau between 4096..65535 |
| `fetch_size` | 16384 | 4096 | lower fetch → dramatically less heap, same QPS |
| `max_block_size` | 65535 | 65535 | unchanged |

**Why we didn't drop the compiled-in `fetch_size` default to 4096**: on this
host the workload is *upstream-bound* (sqlserver under emulation caps
throughput), so JDBC fetch round-trips don't matter. On real x86 prod hardware
where the network/protocol cost is proportionally larger, the higher
fetch_size will pay off in fewer round-trips. Re-tune on your prod host
before changing compiled defaults.

For **memory-constrained pods**, override `fetch_size=4096` via the JDBC URL
in your datasource config to get the heap savings without losing throughput.

## When to re-run the grid

The recommendation above was tuned for:
- Concurrency 1–10 (full scan saturates earlier; point lookups scale further)
- SQL Server as the upstream
- 4 CPU cores allocated to the bridge container
- arm64 host with amd64-emulated SQL Server (artificially low ceiling)

Re-run `misc/bench/scripts/grid-search-v2.sh` if any of those differ on your
prod host. Output: `results/<label>/results.csv` (real precision) and
`results/<label>/summary.md` (operator-friendly tables).

## Sizing heuristic at higher concurrency

For full-scan workloads:

| target concurrency | Xmx | container memory | notes |
|---|---|---|---|
| ≤ 10 | 2 g | 2.5 Gi | sweet spot for most read-heavy use cases |
| 10–50 | 4 g | 5 Gi | watch GC pause rate ≥ 5 % wall time |
| 50–200 | re-run grid | — | likely bottlenecked elsewhere (driver, DB, network) |

Backpressure (commit `67ff93a`) bounds in-flight bytes at
`concurrency × max_block_size`, so actual heap pressure scales sub-linearly
with concurrency. The table errs on the side of headroom.

For point-lookup-heavy workloads, the same Xmx=2g handles 1000+ QPS without
issue — the bottleneck moves to the JDBC driver's connection pool and the
event loop, not heap.

## HikariCP / datasource pool

Out of scope for this doc. The W5 (mutation) workload in `misc/bench/`
surfaced that small batches at low concurrency hit a p99 cliff with default
Hikari settings (per-borrow `SELECT 1`, no Oracle implicit statement cache).
That's a separate tuning effort. Start with the Hikari panels in the Grafana
dashboard (commit `132ef4b`) and tune from there if you have a write-heavy
workload.

## What we deliberately did NOT recommend

Each of these came out as either neutral or net-negative in the v2 grid:

- **`-XX:MaxGCPauseMillis=*`** — neutral on QPS, mildly higher heap.
- **`-XX:+ParallelRefProcEnabled`** — no measurable effect on this workload.
- **`-XX:+AlwaysPreTouch`** — costs 1–3 s pod startup, no steady-state win.
- **`-XX:+UseStringDeduplication`** — net negative at Xmx ≥ 8g (−27% QPS).
- **`-XX:G1ReservePercent=20`** — only helps if you've actually hit
  evacuation failures. Default (10) is fine until proven otherwise.
- **`-XX:+UnlockExperimentalVMOptions`** — no longer required by any
  non-experimental flag on JDK 25.
- **Mismatched `-Xms`/`-Xmx`** — set them equal to avoid resize pauses.
- **ZGC** — 2× slower on this throughput-oriented workload. Worth revisiting
  for very large heaps (≥ 32 g) where you care about p99 over QPS.

## References

- Bench harness: `misc/bench/`
- Grid script v1 (initial sweep): `misc/bench/scripts/grid-search.sh`
- Grid script v2 (comprehensive): `misc/bench/scripts/grid-search-v2.sh`
- Perf patches: `a2fa4ae` (raise default batch/fetch), `04d56c9` (hoist
  nullability + adaptive buffer hint)
- OOM/backpressure fix: `67ff93a`
- HikariCP observability: `132ef4b`
