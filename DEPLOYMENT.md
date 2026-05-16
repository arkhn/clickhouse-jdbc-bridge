# Deployment

How to size and tune the bridge in production. Numbers are derived from
`misc/bench/scripts/grid-search.sh` (7 GC profiles × 4 Xmx × 36 batch/fetch/
max_block combos × 5 concurrencies × 3 workload shapes, against a SQL Server
upstream). **Re-run the grid on your prod host** — the absolute QPS is
host-specific, only the shape generalises.

## Bare-metal / VM

```bash
JDBC_BRIDGE_JVM_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/app/logs/gc.log:tags,time,uptime,level"
```

That is the entire recommendation. The grid showed every G1 sub-flag we
tested (`MaxGCPauseMillis`, `ParallelRefProcEnabled`, `AlwaysPreTouch`,
`StringDeduplication`, `G1ReservePercent=20`) is either within noise on
throughput or net-negative on heap. Simpler wins.

Container memory: `Xmx × 1.25` — i.e. 2.5 GiB for Xmx=2g. The 25% is for
metaspace, direct buffers, JIT code cache, JDBC driver JNI.

## Kubernetes

```yaml
env:
  - name: JDBC_BRIDGE_JVM_OPTS
    value: >-
      -XX:InitialRAMPercentage=80 -XX:MaxRAMPercentage=80
      -XX:+UseG1GC
      -XX:ActiveProcessorCount=4
      -XX:+ExitOnOutOfMemoryError
      -Xlog:gc*:stdout:tags,time,uptime,level
resources:
  requests: { memory: 2.5Gi, cpu: "4" }
  limits:   { memory: 2.5Gi, cpu: "4" }   # request == limit ⇒ Guaranteed QoS
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
terminationGracePeriodSeconds: 60
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }
volumeMounts:
  - { name: app-logs, mountPath: /app/logs }
volumes:
  - { name: app-logs, emptyDir: { sizeLimit: 500Mi } }
```

The K8s deltas vs bare-metal:

- **`MaxRAMPercentage` instead of `-Xms/-Xmx`** — JVM sizes itself from the
  cgroup limit. Tune memory once in `resources.limits.memory`, not twice.
- **`-XX:ActiveProcessorCount=N` matching the CPU limit** — without this the
  JVM sees all node cores and over-provisions GC/ForkJoin threads.
- **GC logs to stdout** — flows through kubelet → your log shipper. No file,
  no rotation, no readOnlyRootFilesystem fight.
- **`+AlwaysPreTouch` deliberately dropped** — it adds 1–3 s startup time at
  Xmx=2g, slowing rolling deploys. Steady-state throughput is the same.

### Heap dump on OOM — optional

Add `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/oom` and an
`emptyDir` at `/var/log/oom` only if you actually `kubectl cp` the `.hprof`
and analyse it in MAT/VisualVM. Otherwise skip it — Prometheus heap metrics
plus `kubectl describe pod` give you enough post-mortem signal.

## Per-request knobs

Operators override these via the JDBC URL,
`'mssql?batch_size=X&fetch_size=Y&max_block_size=Z'`. Compiled defaults are
in `QueryParameters.java`.

| param | default | grid sweet spot | when to override |
|---|---|---|---|
| `batch_size` | 4096 | 16384 | flat plateau between 4096–65535; default is fine |
| `fetch_size` | 16384 | 4096 | **memory-tight pods**: 4096 saves ~10× heap at same QPS |
| `max_block_size` | 65535 | 65535 | leave alone |

The compiled default `fetch_size=16384` is kept higher than the grid's
memory-optimal pick because lower fetch_size means more JDBC round-trips —
which matters on real x86 hardware (this grid was upstream-bound on emulated
SQL Server, so fetch_size didn't visibly cost throughput).

## Sizing heuristic

For full-scan workloads:

| target concurrency | Xmx | container memory |
|---|---|---|
| ≤ 10 | 2 g | 2.5 Gi |
| 10–50 | 4 g | 5 Gi |
| 50–200 | re-run grid | — |

Backpressure (commit `67ff93a`) caps in-flight bytes at
`concurrency × max_block_size`, so heap pressure scales sub-linearly.

For point-lookup-heavy workloads, Xmx=2g handles 1000+ QPS — the bottleneck
moves to the HikariCP pool and the event loop, not heap.

## What we deliberately did NOT recommend

Each came out neutral or net-negative in the grid:

- `-XX:MaxGCPauseMillis=*` — neutral on QPS, slightly higher heap.
- `-XX:+ParallelRefProcEnabled` — no measurable effect.
- `-XX:+AlwaysPreTouch` — costs pod startup, no steady-state win.
- `-XX:+UseStringDeduplication` — net negative at Xmx ≥ 8g (−27 % QPS).
- `-XX:G1ReservePercent=20` — only helps if you've actually hit evacuation
  failures; default (10) is fine until proven otherwise.
- `-XX:+UnlockExperimentalVMOptions` — none of the other flags need it on
  JDK 25.
- Mismatched `-Xms`/`-Xmx` — set them equal to avoid resize pauses.
- **ZGC** — 2× slower on this throughput-oriented workload. Reconsider for
  very large heaps (≥ 32 g) where p99 matters more than QPS.

## Headline grid results

Full-scan of a 1M-row table at c=10, 30 s per cell (real precision):

```
GC profiles at Xmx=4g:
  g1-default     qps=12.59   heap= 544 MB   ← simplest config wins
  g1-tuned       qps=12.16   heap=1750 MB
  current        qps=11.88   heap=1730 MB
  zgc            qps= 6.73   heap=2922 MB

Concurrency saturates at c=4 (~14 qps); past that, p99 climbs linearly
for no gain.

Same JVM + params handle 1376 qps point lookups AND 14 qps 1M-row scans
without retuning.
```

Full data: `misc/bench/results/<your-label>/results.csv` after running the
grid script.

## References

- Bench harness: `misc/bench/README.md`
- Grid script: `misc/bench/scripts/grid-search.sh`
- Key commits: `67ff93a` (backpressure), `a2fa4ae`/`04d56c9` (read-path perf)
