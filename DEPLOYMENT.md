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

## Engine defaults (applied automatically per driver)

The bridge sets a curated set of per-engine JDBC connection properties at
datasource-load time. These are properties that are not part of the JDBC
standard and that operators almost never know to set, but that materially
affect performance. **Operator config always wins**: a default is only
applied if the same key is absent from both the JDBC URL and the datasource's
`dataSourceProperties` block. To override, set the key in either place.

| Driver | Property | Value | Why |
|---|---|---|---|
| `SQLServerDriver` (mssql-jdbc ≥ 6.0) | `selectMethod` | `cursor` | server-side cursor → true streaming on large results |
| `SQLServerDriver` | `responseBuffering` | `adaptive` | bounded client buffering on the driver side |
| `SQLServerDriver` | `useBulkCopyForBatchInsert` | `true` | collapses INSERT batches into TDS bulk-load protocol |
| `OracleDriver` | `oracle.jdbc.defaultRowPrefetch` | `2000` | driver default is 10 (!) — terrible for bulk reads |
| `OracleDriver` | `oracle.jdbc.implicitStatementCacheSize` | `50` | server-side cursor cache; fixes the small-batch p99 cliff |
| `OracleDriver` | `oracle.jdbc.useThreadLocalBufferCache` | `true` | per-thread buffer reuse |
| `OracleDriver` | `useFetchSizeWithLongColumn` | `true` | fetch_size honoured even for CLOB/BLOB |
| `OracleDriver` | `oracle.net.disableOob` | `true` | works around misbehaving firewalls on OOB breaks |
| `OracleDriver` | `oracle.jdbc.timezoneAsRegion` | `false` | pass JVM TZ literally, fewer Oracle session surprises |
| `mysql.cj.jdbc.Driver` | `useServerPrepStmts`, `cachePrepStmts`, `rewriteBatchedStatements`, `useUnicode=true`, `characterEncoding=UTF-8`, `useLocalSessionState` | — | prep-stmt reuse, bulk INSERT rewrite |
| `mariadb.jdbc.Driver` | same as MySQL minus `useLocalSessionState` | — | — |
| `postgresql.Driver` | `prepareThreshold=3`, `defaultRowFetchSize=10000`, `binaryTransfer=true` | — | server-side prep + bounded fetch + binary protocol |

The mssql `selectMethod=cursor` default is conditional on `Driver.getMajorVersion() >= 6` —
older mssql-jdbc parses the property differently. If we can't determine the version
(driver not on classpath), `selectMethod` is omitted; the other mssql defaults still apply.

In addition to the connection properties above, Oracle datasources get a
**statement-level `fetch_size` default of 2000** (see [Per-request knobs](#per-request-knobs)).
This is distinct from `oracle.jdbc.defaultRowPrefetch`: the bridge calls
`Statement.setFetchSize(...)` on every read, and that call *overrides* the
`defaultRowPrefetch` connection property — so without a matching statement-level
default the prefetch tuning would be silently lost and Oracle would fall back to
the compiled `fetch_size=16384`. Because the Oracle thin driver pre-allocates
client-side fetch buffers as `fetch_size × max-column-width`, a high fetch size on
wide rows (e.g. `VARCHAR2(4000)`/CLOB) is a real OOM hazard; 2000 keeps the buffer
bounded while still avoiding the round-trip-per-row default. Set `fetch_size`
explicitly (datasource `parameters` or request URI) to override.

Real-world impact measured by `misc/bench`:

- **Oracle W5 batch=100 c=4**: p99 dropped from **6.35 s → 9 ms** (700× faster)
  thanks to `implicitStatementCacheSize=50`. Same throughput cap, but every
  request now completes in a tight latency band instead of a long tail.
- Engine defaults log line at INFO when each property is applied — easy to
  audit at startup.

## Per-request knobs

These can be set in three places, in increasing precedence:

1. **Compiled default** — in `QueryParameters.java`.
2. **Engine default** — per-driver, applied only when the operator left the knob
   unset (currently: Oracle `fetch_size = 2000`).
3. **Datasource config** — a `parameters` block in the datasource JSON, e.g.
   `"parameters": { "fetch_size": 2000 }`. Applies to every query against that
   datasource.
4. **Per-request URL** — `'mssql?batch_size=X&fetch_size=Y&max_block_size=Z'`.
   Wins over everything.

A value set at a higher-precedence layer is *not* clobbered by a lower layer's
default: a datasource-level `fetch_size` survives requests that don't mention it,
and the engine default only applies when neither datasource config nor the request
set the knob.

| param | default | grid sweet spot | when to override |
|---|---|---|---|
| `batch_size` | 4096 | 16384 | flat plateau between 4096–65535; default is fine |
| `fetch_size` | 16384 (Oracle: 2000) | 4096 | **memory-tight pods / wide rows**: lower saves heap at same QPS |
| `max_block_size` | 65535 | 65535 | leave alone |

The compiled default `fetch_size=16384` is kept higher than the grid's
memory-optimal pick because lower fetch_size means more JDBC round-trips —
which matters on real x86 hardware (this grid was upstream-bound on emulated
SQL Server, so fetch_size didn't visibly cost throughput). Oracle is the
exception: its driver pre-allocates fetch buffers as `fetch_size × column-width`,
so it defaults to the lower, prefetch-aligned 2000.

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
