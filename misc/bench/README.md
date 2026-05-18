# Benchmark suite

End-to-end bench for `clickhouse-jdbc-bridge` against SQL Server and Oracle —
the realistic upstreams where the bridge actually matters in production
(PostgreSQL and MySQL have native CH integrations).

## What it measures

- **Speed** — p50/p95/p99 latency, queries/sec
- **RAM** — JVM heap, GC pause rate, container RSS
- **Stability** — error rate, FD/thread/heap drift, bridge OOMKill detection

## Stack

| service | image | role |
|---|---|---|
| clickhouse | `clickhouse/clickhouse-server:25.8` | query driver via `clickhouse benchmark` |
| jdbc-bridge | `arkhn/clickhouse-jdbc-bridge:bench` | system under test |
| sqlserver | `mcr.microsoft.com/mssql/server:2022-latest` | primary upstream |
| oracle | `gvenzl/oracle-free:23-slim` | secondary upstream |
| prometheus / grafana / cadvisor | — | metrics + dashboards |

Build the bridge image once: `docker build --target full -t arkhn/clickhouse-jdbc-bridge:bench ../..`.

On Apple Silicon: `clickhouse`, `oracle`, and the bridge run natively;
**`sqlserver` is amd64-emulated** and dominates the wall-clock for SQL Server
workloads. Numbers from runs touching sqlserver are directional only —
re-run on x86 for publishable figures. The harness flags `emulated=true` in
the summary automatically.

## Usage

```bash
# bring up stack + load 1M rows once
docker compose up -d
./datagen/load.sh --rows 1000000

# functional smoke across all workloads (~5 min)
./run.sh --workloads W1,W2,W3,W4,W5,Wsoak \
         --duration 60 --concurrency 4 \
         --w3-limits 100000,500000 --w5-batches 100,1000 \
         --label smoke

# JVM/param tuning grid (~25 min, full-scan only, drives all 5 phases)
./scripts/grid-search.sh prod-tune

# regression diff between two runs (exits non-zero on regress)
./run.sh compare baseline candidate

# pass/fail gates from thresholds.yaml (exits non-zero on fail)
./run.sh gate my-run
```

Results land under `results/<label>/`: `summary.md` (human), `metrics.tsv`
(machine), `cells/*.txt` (raw). Dashboards: <http://localhost:3001>.

## Workloads

| id | path | stresses |
|---|---|---|
| W1 | `ab` against `/ping` | Vert.x request loop only |
| W2 | `WHERE id = ?` through CH | pool reuse, /columns_info, 1-row serialization |
| W3 | `SELECT * LIMIT N` through CH | RowBinary streaming, heap pressure |
| W4 | `wide_types` through CH | every JDBC type-mapping path |
| W5 | `INSERT batches` via JDBC engine | HikariCP write path, prepared-stmt reuse |
| Wsoak | long-running W2 with drift checks | slow-leak detection (`--duration 1800` for real) |

## Layout

```
misc/bench/
├── docker-compose.yml          stack definition
├── run.sh                      main harness (workloads, compare, gate)
├── thresholds.yaml             pass/fail gates
├── bridge-config/              mounted into the bridge container
├── ch-config/bench.xml         CH server config (jdbc_bridge + prometheus port)
├── datagen/                    init-mssql.sql, init-oracle.sql, load.sh
├── workloads/                  one script per workload (idempotent)
├── lib/                        compare.py, gate.py
├── scripts/grid-search.sh      5-phase JVM/param tuning sweep
├── prometheus/prometheus.yml
└── grafana/                    provisioning + dashboards/bridge.json
```

## Tuning recommendations

Live in `DEPLOYMENT.md` at the repo root. They're derived from running
`scripts/grid-search.sh` and re-derived whenever that grid is rerun on real
hardware.
