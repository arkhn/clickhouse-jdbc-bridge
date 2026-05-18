# Performance

This document collects benchmark methodology and reference numbers for
`clickhouse-jdbc-bridge`. Two test harnesses live in the repo:

- **`misc/bench/`** — the current benchmark suite. End-to-end, runs against
  SQL Server and Oracle (the realistic upstreams where the bridge actually
  matters in production; PostgreSQL and MySQL have native ClickHouse
  integrations). Drives the grid search whose results back the recommendations
  in [DEPLOYMENT.md](DEPLOYMENT.md).
- **`misc/perf-test/`** — the legacy ApacheBench-based harness from the
  original ClickHouse fork. Useful as a historical baseline against MariaDB.
  Kept for reproducibility, not actively iterated on.

> **Numbers below are directional.** Absolute throughput is host-specific
> (CPU generation, kernel, network, upstream DB sizing). Re-run the
> appropriate suite on your target hardware before quoting a number in a
> capacity-planning document.

---

## Current suite — `misc/bench`

End-to-end benchmark with a containerised Prometheus + Grafana + cAdvisor
stack. The bench harness is documented in [`misc/bench/README.md`](misc/bench/README.md);
the JVM/sizing recommendations that come out of it live in
[`DEPLOYMENT.md`](DEPLOYMENT.md).

### What it measures

- **Speed** — p50/p95/p99 latency, queries/sec
- **RAM** — JVM heap, GC pause rate, container RSS
- **Stability** — error rate, FD/thread/heap drift, OOMKill detection

### Stack

| service | image | role |
|---|---|---|
| clickhouse | `clickhouse/clickhouse-server:25.8` | query driver via `clickhouse benchmark` |
| jdbc-bridge | `arkhn/clickhouse-jdbc-bridge:bench` | system under test |
| sqlserver | `mcr.microsoft.com/mssql/server:2022-latest` | primary upstream |
| oracle | `gvenzl/oracle-free:23-slim` | secondary upstream |
| prometheus / grafana / cadvisor | — | metrics + dashboards |

> On Apple Silicon, the SQL Server image is amd64-emulated and dominates
> wall-clock time for SQL Server workloads. Numbers from those runs are
> directional only — re-run on x86 for publishable figures. The harness
> flags `emulated=true` in the summary automatically.

### Workloads

| id | path | stresses |
|---|---|---|
| W1 | `ab` against `/ping` | Vert.x request loop only |
| W2 | `WHERE id = ?` through CH | pool reuse, `/columns_info`, 1-row serialization |
| W3 | `SELECT * LIMIT N` through CH | RowBinary streaming, heap pressure |
| W4 | `wide_types` through CH | every JDBC type-mapping path |
| W5 | `INSERT batches` via JDBC engine | HikariCP write path, prepared-stmt reuse |
| Wsoak | long-running W2 with drift checks | slow-leak detection |

### Usage

```bash
cd misc/bench

# bring up the stack and load 1M rows once
docker compose up -d
./datagen/load.sh --rows 1000000

# functional smoke across all workloads (~5 min)
./run.sh --workloads W1,W2,W3,W4,W5,Wsoak \
         --duration 60 --concurrency 4 \
         --w3-limits 100000,500000 --w5-batches 100,1000 \
         --label smoke

# full JVM/param tuning grid (~25 min, drives all 5 phases)
./scripts/grid-search.sh prod-tune

# regression diff between two runs (exits non-zero on regress)
./run.sh compare baseline candidate

# pass/fail gates from thresholds.yaml (exits non-zero on fail)
./run.sh gate my-run
```

Results land under `misc/bench/results/<label>/`: `summary.md` (human),
`metrics.tsv` (machine), `cells/*.txt` (raw). Grafana dashboards:
<http://localhost:3001>.

### Headline grid results

From `scripts/grid-search.sh` on the reference hardware — full-scan of a
1M-row table at `concurrency=10`, 30 s per cell:

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

### Key wins worth highlighting

These are the changes whose effect is visible in the grid:

- **Backpressure on the streaming path** (commit `67ff93a`) caps in-flight
  bytes at `concurrency × max_block_size`, so heap pressure scales
  sub-linearly with result size.
- **Per-driver engine defaults** applied automatically at datasource load
  (commit `14b4336`). Most dramatic effect: Oracle W5 at `batch=100 c=4`
  saw p99 drop **6.35 s → 9 ms** (≈700× faster) thanks to
  `oracle.jdbc.implicitStatementCacheSize=50`. See the full per-driver
  table in [DEPLOYMENT.md](DEPLOYMENT.md#engine-defaults-applied-automatically-per-driver).
- **Read-path microbenchmarks** (`a2fa4ae`, `04d56c9`) — raised
  `batch_size` default to 4096 and `fetch_size` to 16384, hoisted
  nullability checks, and added an adaptive `ByteBuffer` size hint.

Full data: `misc/bench/results/<your-label>/results.csv` after running the
grid.

---

## Legacy suite — `misc/perf-test`

Historical baseline from the original ClickHouse fork. Numbers are kept for
context — the topology (3 KVMs running CentOS 7, MariaDB upstream,
ApacheBench driving 20 concurrent users issuing 100,000 identical queries
after warm-up) is documented in [`misc/perf-test/docker-compose.yml`](misc/perf-test/docker-compose.yml).

### Reference results

Test Case | Time Spent (s) | Throughput (#/s) | Failed Requests | Min (ms) | Mean (ms) | Median (ms) | Max (ms)
-- | -- | -- | -- | -- | -- | -- | --
[clickhouse_ping](misc/perf-test/results/clickhouse_ping.txt) | 801.367 | 124.79 | 0 | 1 | 160 | 4 | 1,075
[jdbc-bridge_ping](misc/perf-test/results/jdbc-bridge_ping.txt) | 804.017 | 124.38 | 0 | 1 | 161 | 10 | 3,066
[clickhouse_url(clickhouse)](misc/perf-test/results/clickhouse_url(clickhouse).txt) | 801.448 | 124.77 | 3 | 3 | 160 | 8 | 1,077
[clickhouse_url(jdbc-bridge)](misc/perf-test/results/clickhouse_url(jdbc-bridge).txt) | 811.299 | 123.26 | 446 | 3 | 162 | 10 | 3,066
[clickhouse_constant-query](misc/perf-test/results/clickhouse_constant-query.txt) | 797.775 | 125.35 | 0 | 1 | 159 | 4 | 1,077
[clickhouse_constant-query(mysql)](misc/perf-test/results/clickhouse_constant-query(mysql).txt) | 1,598.426 | 62.56 | 0 | 7 | 320 | 18 | 2,049
[clickhouse_constant-query(remote)](misc/perf-test/results/clickhouse_constant-query(remote).txt) | 802.212 | 124.66 | 0 | 2 | 160 | 8 | 3,073
[clickhouse_constant-query(url)](misc/perf-test/results/clickhouse_constant-query(url).txt) | 801.686 | 124.74 | 0 | 3 | 160 | 11 | 1,123
[clickhouse_constant-query(jdbc)](misc/perf-test/results/clickhouse_constant-query(jdbc).txt) | 925.087 | 108.10 | 5,813 | 14 | 185 | 75 | 4,091
[clickhouse(patched)_constant-query(jdbc)](misc/perf-test/results/clickhouse(patched)_constant-query(jdbc).txt) | 833.892 | 119.92 | 1,577 | 10 | 167 | 51 | 3,109
[clickhouse(patched)_constant-query(jdbc-dual)](misc/perf-test/results/clickhouse(patched)_constant-query(jdbc-dual).txt) | 846.403 | 118.15 | 3,021 | 8 | 169 | 50 | 3,054
[clickhouse_10k-rows-query](misc/perf-test/results/clickhouse_10k-rows-query.txt) | 854.886 | 116.97 | 0 | 12 | 171 | 99 | 1,208
[clickhouse_10k-rows-query(mysql)](misc/perf-test/results/clickhouse_10k-rows-query(mysql).txt) | 1,657.425 | 60.33 | 0 | 28 | 331 | 123 | 2,228
[clickhouse_10k-rows-query(remote)](misc/perf-test/results/clickhouse_10k-rows-query(remote).txt) | 854.610 | 117.01 | 0 | 12 | 171 | 99 | 1,201
[clickhouse_10k-rows-query(url)](misc/perf-test/results/clickhouse_10k-rows-query(url).txt) | 853.292 | 117.19 | 5 | 23 | 171 | 105 | 2,026
[clickhouse_10k-rows-query(jdbc)](misc/perf-test/results/clickhouse_10k-rows-query(jdbc).txt) | 1,483.565 | 67.41 | 11,588 | 66 | 297 | 206 | 2,051
[clickhouse(patched)_10k-rows-query(jdbc)](misc/perf-test/results/clickhouse(patched)_10k-rows-query(jdbc).txt) | 1,186.422 | 84.29 | 6,632 | 61 | 237 | 184 | 2,021
[clickhouse(patched)_10k-rows-query(jdbc-dual)](misc/perf-test/results/clickhouse(patched)_10k-rows-query(jdbc-dual).txt) | 1,080.676 | 92.53 | 4,195 | 65 | 216 | 180 | 2,013

`clickhouse(patched)` is a build with the bridge health-check disabled.
`jdbc-dual` runs two bridge replicas behind docker swarm on the same KVM
(limited resources at the time).

### Test-case URLs

Test Case | (Decoded) URL
-- | --
clickhouse_ping | `http://ch-server:8123/ping`
jdbc-bridge_ping | `http://jdbc-bridge:9019/ping`
clickhouse_url(clickhouse) | `http://ch-server:8123/?query=select * from url('http://ch-server:8123/ping', CSV, 'results String')`
clickhouse_url(jdbc-bridge) | `http://ch-server:8123/?query=select * from url('http://jdbc-bridge:9019/ping', CSV, 'results String')`
clickhouse_constant-query | `http://ch-server:8123/?query=select 1`
clickhouse_constant-query(mysql) | `http://ch-server:8123/?query=select * from mysql('mariadb:3306', 'test', 'constant', 'root', 'root')`
clickhouse_constant-query(remote) | `http://ch-server:8123/?query=select * from remote('ch-server:9000', system.constant, 'default', '')`
clickhouse_constant-query(url) | `http://ch-server:8123/?query=select * from url('http://ch-server:8123/?query=select 1', CSV, 'results String')`
clickhouse*_constant-query(jdbc*) | `http://ch-server:8123/?query=select * from jdbc('mariadb', 'constant')`
clickhouse_10k-rows-query | `http://ch-server:8123/?query=select 1`
clickhouse_10k-rows-query(mysql) | `http://ch-server:8123/?query=select * from mysql('mariadb:3306', 'test', '10k_rows', 'root', 'root')`
clickhouse_10k-rows-query(remote) | `http://ch-server:8123/?query=select * from remote('ch-server:9000', system.10k_rows, 'default', '')`
clickhouse_10k-rows-query(url) | `http://ch-server:8123/?query=select * from url('http://ch-server:8123/?query=select * from 10k_rows', CSV, 'results String')`
clickhouse*_10k-rows-query(jdbc*) | `http://ch-server:8123/?query=select * from jdbc('mariadb', 'small-table')`

### Reading the two side-by-side

The legacy table compares the bridge against ClickHouse's native integrations
(`remote`, `url`, `mysql`) and shows where it sat relative to them at the
time. The current grid focuses on what changes inside the bridge — JVM
tuning, connection-pool behaviour, per-driver knobs — against the upstreams
that don't have a native CH path. They answer different questions; don't try
to directly compare a row from one to a row from the other.

## References

- Bench harness: [`misc/bench/README.md`](misc/bench/README.md)
- Grid script: [`misc/bench/scripts/grid-search.sh`](misc/bench/scripts/grid-search.sh)
- Deployment / tuning recommendations: [DEPLOYMENT.md](DEPLOYMENT.md)
- Key commits: `67ff93a` (backpressure), `14b4336` (engine defaults),
  `a2fa4ae` / `04d56c9` (read-path perf)
