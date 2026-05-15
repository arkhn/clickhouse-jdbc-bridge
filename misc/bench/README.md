# clickhouse-jdbc-bridge benchmark suite

End-to-end benchmark stack for the bridge against SQL Server (primary) and Oracle (secondary).
PostgreSQL and MySQL are intentionally *not* covered — ClickHouse has native `postgresql()` and
`mysql()` table functions, so the JDBC bridge is rarely the production choice for those. The
realistic deployment is "CH talking to a non-natively-supported RDBMS over JDBC", which is
exactly SQL Server and Oracle.

## What it measures

- **Speed** — p50/p95/p99 latency, queries/sec, requests/sec
- **RAM** — JVM heap, GC pause rate, container RSS (cAdvisor)
- **Stability** — error rate, FD/thread/heap drift over soak runs, bridge crash detection (OOMKill)

## Stack

| service       | image                                        | role                                    |
|---------------|----------------------------------------------|-----------------------------------------|
| clickhouse    | `clickhouse/clickhouse-server:25.8`          | query driver via `clickhouse benchmark` |
| jdbc-bridge   | `arkhn/clickhouse-jdbc-bridge:bench`         | system under test                       |
| sqlserver     | `mcr.microsoft.com/mssql/server:2022-latest` | primary upstream JDBC source            |
| oracle        | `gvenzl/oracle-free:23-slim`                 | secondary upstream JDBC source          |
| prometheus    | `prom/prometheus:v2.55.1`                    | metrics scrape                          |
| grafana       | `grafana/grafana:11.3.0`                     | dashboards (http://localhost:3001)      |
| cadvisor      | `gcr.io/cadvisor/cadvisor:v0.49.1`           | per-container cgroup stats              |

Build the bridge image first (uses the project's full Dockerfile target):

```bash
docker build --target full -t arkhn/clickhouse-jdbc-bridge:bench ../..
```

## Apple Silicon note

`sqlserver` and `clickhouse` and the bridge image are amd64-only and run under emulation on
arm64 hosts. `oracle` has an arm64 native image and runs at full speed. The harness sets
`emulated=true` on the run summary so emulated numbers aren't mistaken for real ones — use x86
hardware (or a remote runner) for publishable figures.

## Usage

### Smoke run (everything default-sized)

```bash
./run.sh --workloads W1,W2,W3,W4,W5,Wsoak \
         --duration 60 --concurrency 4 \
         --w3-limits 100000,500000 --w5-batches 100,1000 \
         --label smoke
```

### Gate (apply thresholds.yaml after the run)

```bash
./run.sh --workloads W1,W2,W3,W4,W5,Wsoak --duration 60 --label my-run --gate
# or separately:
./run.sh gate my-run
```

### Compare two runs

```bash
./run.sh compare baseline rerun [--regress-pct 15]
# exits non-zero if any metric in `rerun` regresses past --regress-pct vs `baseline`
```

### Load data only

```bash
./datagen/load.sh --rows 1000000             # 1M rows into both upstreams
./datagen/load.sh --rows 100000 --only mssql # only one side
```

Results land under `results/<label>/`:
- `summary.md` — human-readable
- `metrics.tsv` — tab-separated, easy to script against
- `W*/snapshots/` — raw `/metrics` snapshots + GC log tail
- `ps.txt` — container state at run time

Dashboards at <http://localhost:3001>. Prometheus at <http://localhost:9090>.

## Workloads

| ID    | What                                               | Stresses                                                 |
|-------|----------------------------------------------------|----------------------------------------------------------|
| W1    | `ab` against `/ping`                               | Vert.x request loop only, no JDBC                        |
| W2    | CH → bridge → `WHERE id = ?` (random)              | HikariCP pool reuse, `/columns_info`, 1-row serialization |
| W3    | CH → bridge → `SELECT * LIMIT N`                   | RowBinary streaming, heap pressure on large result sets  |
| W4    | CH → bridge → `wide_types`                         | TypeUtils conversion path (every JDBC type)              |
| W5    | CH → bridge → `INSERT batches` via JDBC engine     | HikariCP write path, prepared-statement reuse            |
| Wsoak | sustained W2 with FD/thread/heap drift snapshots   | Slow-leak detection (use `--duration 1800` for real)     |

## Findings surfaced so far

- **Bridge streams W3 — does not buffer the full result set.** Heap stays ~300 MB regardless
  of `LIMIT 100k` vs `LIMIT 1M`; throughput is upstream-bound, not bridge-bound.
- **Bridge OOMs at `--concurrency 10 --w3-limits 1000000`** on the default 1.5 GB container cap.
  The harness's `ensure_bridge` detects and restarts; the restart is recorded in `summary.md`.
- **W4 surfaces real bridge gaps**: SQL Server `DATETIMEOFFSET` (type −155) and Oracle
  `BINARY_FLOAT`/`BINARY_DOUBLE` (types 100/101) aren't in `TypeUtils`. The workload skips them
  in its query rather than failing silently — fix in `TypeUtils` is a follow-up.
- **W5 oracle b100 has p99=6.3 s vs b1000 p99=21 ms** — larger batches reduce HikariCP
  pool contention more than they add per-batch latency.

## Files

```
misc/bench/
├── docker-compose.yml          # stack definition
├── run.sh                      # main harness
├── thresholds.yaml             # pass/fail gates
├── README.md                   # this file
├── bridge-config/              # mounted into the bridge container
│   ├── server.json             # JSON config: timeouts, repositories, extensions
│   ├── httpd.json
│   ├── vertx.json              # worker pool / event-loop tuning
│   └── datasources/
│       ├── mssql.json
│       └── oracle.json
├── ch-config/bench.xml         # CH server config (jdbc_bridge + prometheus port)
├── datagen/
│   ├── init-mssql.sql
│   ├── init-oracle.sql
│   └── load.sh
├── workloads/                  # one script per workload, all idempotent
│   ├── w1-ping.sh
│   ├── w2-point-lookup.sh
│   ├── w3-bulk-read.sh
│   ├── w4-wide-types.sh
│   ├── w5-mutation.sh
│   ├── wsoak.sh
│   ├── _parse-bench.sh         # helper: extracts queries/qps/p99 from CH bench text
│   └── snapshot-metrics.sh
├── lib/
│   ├── compare.py              # `./run.sh compare` implementation
│   └── gate.py                 # threshold evaluation
├── prometheus/prometheus.yml
└── grafana/
    ├── provisioning/
    └── dashboards/bridge.json
```
