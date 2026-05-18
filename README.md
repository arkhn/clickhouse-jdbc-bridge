# ClickHouse JDBC Bridge

[![Build](https://github.com/arkhn/clickhouse-jdbc-bridge/actions/workflows/build.yml/badge.svg)](https://github.com/arkhn/clickhouse-jdbc-bridge/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/arkhn/clickhouse-jdbc-bridge/branch/master/graph/badge.svg)](https://codecov.io/gh/arkhn/clickhouse-jdbc-bridge)
[![Release](https://img.shields.io/github/v/release/arkhn/clickhouse-jdbc-bridge?include_prereleases)](https://github.com/arkhn/clickhouse-jdbc-bridge/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A stateless proxy that lets [ClickHouse®](https://clickhouse.com/) query any JDBC-accessible
datasource in real time. Use it to run distributed queries across heterogeneous
backends — data warehousing, monitoring, integrity checks, federated analytics.

> **Maintained by [Arkhn](https://arkhn.com)** under the Apache License 2.0.
> This fork is actively maintained: it tracks current ClickHouse versions,
> modern JDBC drivers, and recent JDKs, and ships performance, observability
> and security improvements on top of the original Zhichun Wu / ClickHouse Inc.
> codebase. See [NOTICE](NOTICE) for attribution.

> [!WARNING]
> **Removed from upstream.** This fork drops two features that exist in the
> original [ClickHouse/clickhouse-jdbc-bridge](https://github.com/ClickHouse/clickhouse-jdbc-bridge):
>
> - **Scripting datasource** (`ScriptDataSource`, Rhino / `javax.script`).
>   Upstream allowed evaluating JavaScript supplied in incoming requests,
>   which is effectively remote code execution against the bridge host.
> - **Remote driver / extension URLs.** `driverUrls` and `libUrls` only
>   accept local filesystem paths now; `http://` / `https://` entries are
>   rejected at startup. The `JDBC_DRIVERS` env var (which `wget`-ed jars
>   from Maven Central at container start) has been removed.
>
> To add a JDBC driver that isn't vendored in the `:full` image, extend the
> base image and copy your jar into `/app/drivers` (or mount a volume
> there). If you rely on either removed feature, stay on upstream — they
> will not work here.

## Overview

![Overview](https://user-images.githubusercontent.com/4270380/103492828-a06d1200-4e68-11eb-9287-ef830f575d3e.png)

ClickHouse calls the bridge over HTTP via the [`jdbc()` table function](https://clickhouse.com/docs/en/sql-reference/table-functions/jdbc/)
or the [`JDBC` table engine](https://clickhouse.com/docs/en/engines/table-engines/integrations/jdbc).
The bridge translates that into a JDBC call against the target datasource and
streams the result back in ClickHouse's `RowBinary` format.

## Features

- **Multi-engine JDBC connectivity** — first-class support and tuned defaults
  for PostgreSQL, MySQL, MariaDB, Microsoft SQL Server, Oracle, ClickHouse,
  SQLite, Trino, Neo4j, and OpenDistro for Elasticsearch. Any JDBC-4-compliant
  driver can be dropped into `drivers/` and used.
- **Named datasources, queries and schemas** — declarative JSON configuration
  with hot-reload (`configScanPeriod`), JSON Schema validation, and aliases.
- **Per-driver engine defaults** — curated non-standard JDBC properties applied
  automatically at datasource load (e.g. SQL Server `selectMethod=cursor`,
  Oracle `defaultRowPrefetch=2000`, PostgreSQL `defaultRowFetchSize=10000`).
  Operator config always wins. See [DEPLOYMENT.md](DEPLOYMENT.md#engine-defaults-applied-automatically-per-driver).
- **Streaming with backpressure** — bounded in-flight bytes
  (`concurrency × max_block_size`), so heap pressure scales sub-linearly with
  result size. Tunable `batch_size`, `fetch_size`, `max_block_size` per
  request.
- **Connection pooling** — HikariCP per datasource, with observability via
  Prometheus.
- **Read & write** — `SELECT` plus simple `INSERT` / `CREATE` / `DROP` /
  `TRUNCATE` mutation through `?mutation` or the `JDBC` table engine.
- **Inline and named schemas** — skip the type-inference round-trip by
  declaring a schema once.
- **Prometheus metrics** — `/metrics` endpoint with JVM, GC, HikariCP and
  request-path metrics; ready-made Grafana dashboards live in
  [`misc/bench/grafana`](misc/bench/grafana).
- **Liveness / readiness probes** — `/ping` endpoint, K8s-friendly.
- **Extensible** — custom `DataSource`, `Repository` and type-converter
  implementations can be loaded via the `extensions/` directory; see
  [Extending the bridge](#extending-the-bridge).
- **Modern runtime** — built on JDK 25 and Vert.x.

## Known limitations

- Complex ClickHouse types like `Array` and `Tuple` are currently treated as
  `String`.
- Predicate pushdown is not supported, so an adhoc query may execute twice when
  the schema has to be inferred (use named or inline schemas to avoid it).
- Mutation support is limited to simple `INSERT` / DDL — no transactions, no
  upserts, no `UPDATE`/`DELETE`.

## Quick start

The fastest way to try the bridge is the published image:

```bash
docker run -d --name jdbc-bridge -p 9019:9019 arkhn/clickhouse-jdbc-bridge:latest
```

The `:latest` tag is the `full` variant, which ships the JDBC drivers listed
above. For a minimal image, use `arkhn/clickhouse-jdbc-bridge:base` and mount
your own jars into `/app/drivers`.

### Local stack with Docker Compose

```bash
git clone https://github.com/arkhn/clickhouse-jdbc-bridge.git
cd clickhouse-jdbc-bridge/misc/quick-start
docker compose up -d

# verify ClickHouse can reach the bridge through the named "self" datasource
docker compose run --rm ch-server \
  clickhouse-client --query="select * from jdbc('self?datasource_column', 'select 1')"
```

### Wiring ClickHouse to the bridge

ClickHouse assumes the bridge runs at `localhost:9019`. Override via
`/etc/clickhouse-server/config.xml`:

```xml
<clickhouse>
  <jdbc_bridge>
    <host>jdbc-bridge</host>
    <port>9019</port>
  </jdbc_bridge>
</clickhouse>
```

## Usage

The primary entry point is the
[`jdbc()` table function](https://clickhouse.com/docs/en/sql-reference/table-functions/jdbc/):

```sql
select * from jdbc('<datasource>', '<schema>', '<query>')
```

Only `datasource` and `query` are mandatory; `schema` is optional but
recommended (see below). The query is in the **native SQL dialect of the
target datasource** — `limit` works in MariaDB but not PostgreSQL, and so on.

### Datasources

```sql
-- list configured datasources
select * from jdbc('', 'show datasources');

-- query a named datasource
select * from jdbc('ch-server', 'select 1');
```

Adhoc JDBC URLs as the datasource (e.g. `jdbc:clickhouse://...`) are
**rejected by default** in this fork — see [SECURITY.md](SECURITY.md). To
opt in, set `ALLOW_ADHOC_CONNECTIONS=true` (env) or
`-Djdbc-bridge.adhoc.allow=true` (sysprop). Optionally restrict to a list
of `jdbc:<vendor>:` prefixes via
`ADHOC_ALLOWED_JDBC_PREFIXES=jdbc:clickhouse:,jdbc:postgresql:`.

### Schemas

By default, an adhoc query is executed twice: once for type inference, once
for results. Metadata is cached (5 min by default), but you can skip the
inference round-trip entirely by passing a schema:

```sql
-- inline schema
select * from jdbc('ch-server', 'num UInt8, str String', 'select 1 as num, ''2'' as str');

-- named schema (defined under config/schemas/)
select * from jdbc('ch-server', 'query-log', 'show-query-logs');
```

### Queries

```sql
-- adhoc
select * from jdbc('ch-server', 'select * from system.query_log where user != ''default''');

-- table query (datasource + table name)
select * from jdbc('ch-server', 'system', 'query_log');

-- named query (defined under config/queries/)
select * from jdbc('ch-server', 'show-query-logs');
```

### Query parameters

```sql
select *
from jdbc('ch-server?datasource_column&max_rows=1&fetch_size=1&one=1&two=2',
          'select {{one}} union all select {{ two }}');
```

Supported parameters are listed in
[`QueryParameters.java`](src/main/java/com/clickhouse/jdbcbridge/core/QueryParameters.java).
The shorthand `key` is equivalent to `key=true`.

### JDBC table & dictionary

```sql
create table system.test (a String, b UInt8)
  engine=JDBC('ch-server', '', 'select user as a, is_initial_query as b from system.processes');

create dictionary system.dict_test (b UInt64 default 0, a String)
  primary key b
  source(clickhouse(host 'localhost' port 9000 user 'default' table 'test' db 'system'))
  lifetime(min 82800 max 86400)
  layout(flat());
```

### Mutation

```sql
-- via query parameter
select * from jdbc('ch-server?mutation', 'create table system.test_table(a String, b UInt8) engine=Memory()');
select * from jdbc('ch-server?mutation', 'insert into system.test_table values(''a'', 1)');

-- via JDBC engine
create table system.jdbc_table (a String, b UInt8)
  engine=JDBC('ch-server?batch_size=1000', 'system', 'test_table');
insert into system.jdbc_table(a, b) values('a', 1);
```

### Monitoring

```bash
curl http://jdbc-bridge:9019/metrics    # Prometheus
curl http://jdbc-bridge:9019/ping       # liveness/readiness
```

## Configuration

| Concern | Where |
|---|---|
| JDBC drivers | `drivers/` (or per-datasource `driverUrls`) |
| Named datasources | `config/datasources/*.json` |
| Named queries | `config/queries/*.json` |
| Named schemas | `config/schemas/*.json` |
| Server / extensions | `config/server.json` |
| Vert.x / HTTPD | `config/vertx.json`, `config/httpd.json` |
| Logging | `logging.properties` |

JSON schemas for the configuration files live under
[`docker/config/`](docker/config/) — modern editors (VSCode, IntelliJ) pick
them up automatically for autocomplete and validation.

### Driver URLs

The bridge ships two driver directories with distinct roles:

| Path | Loaded by | Use it for |
|---|---|---|
| `/app/drivers` (override: `DRIVER_DIR`) | Shared classloader, scanned at startup. Every `*.jar` becomes available to every datasource. | The default driver per backend. The `:full` image vendors the curated set here. |
| `/app/extra` | Nothing by default — only loaded when a datasource's `driverUrls` points at it. | Per-datasource jars: alternative driver versions, vendor-specific drivers, anything you don't want in the shared classloader. |

To add a driver, extend `arkhn/clickhouse-jdbc-bridge:base` (or `:full`) and
`COPY` the jar into the appropriate directory. Only local filesystem paths
are accepted in `driverUrls`; remote URLs (`http://`, `https://`) are
rejected at startup.

```json
{
  "legacy-mariadb": {
    "driverUrls": ["extra/mariadb-2.7"],
    "driverClassName": "org.mariadb.jdbc.Driver",
    "jdbcUrl": "jdbc:mariadb://host:3306/db",
    "username": "...",
    "password": "..."
  }
}
```

#### Running two versions of the same driver

A common case is wanting one datasource on the default MariaDB 3.x driver
(vendored in `drivers/`) and another datasource pinned to a legacy MariaDB
2.x driver. The pattern is:

1. Keep the default jar in `drivers/` (or let the `:full` image vendor it).
2. Drop the second version into `extra/<name>/`, e.g.
   `extra/mariadb-2.7/mariadb-java-client-2.7.4.jar`.
3. On the datasource that needs the second version, set
   `driverUrls: ["extra/mariadb-2.7"]` so it gets its own classloader.

> [!NOTE]
> The per-datasource classloader delegates to the shared `drivers/`
> classloader (parent-first). If the same `Driver` class exists in **both**
> `drivers/` and `extra/<name>/`, the parent (i.e. `drivers/`) wins and
> the `extra/` copy is shadowed. For a truly distinct driver instance,
> place that driver **only** in `extra/<name>/` and not in `drivers/`.

Examples under [`misc/quick-start/jdbc-bridge/config`](misc/quick-start/jdbc-bridge/config).

### Timeouts

There are several layers, applied bottom-up:

1. Datasource (e.g. MariaDB `max_execution_time`).
2. JDBC driver (`connectTimeout`, `socketTimeout`).
3. Bridge (`queryTimeout` in `config/server.json`,
   `maxWorkerExecuteTime` in `config/vertx.json`).
4. ClickHouse (`max_execution_time`, `keep_alive_timeout`, `http_receive_timeout`).
5. Client (e.g. `socketTimeout` in the ClickHouse JDBC driver).

## Deployment & performance

- **[DEPLOYMENT.md](DEPLOYMENT.md)** — JVM/GC tuning, container sizing, K8s
  manifest, per-driver engine defaults, sizing heuristics.
- **[PERFORMANCE.md](PERFORMANCE.md)** — benchmark suite, methodology, and
  reference results (legacy ApacheBench numbers + current `misc/bench` grid).
- **[SECURITY.md](SECURITY.md)** — threat model, hardening guidance, and how
  to report vulnerabilities.

## Build

```bash
git clone https://github.com/arkhn/clickhouse-jdbc-bridge.git
cd clickhouse-jdbc-bridge

# compile + run unit tests
mvn -Prelease verify

# build the shaded jar
mvn -Prelease package
```

Docker:

```bash
# full image (drivers vendored) — default target
docker build -t arkhn/clickhouse-jdbc-bridge:dev .

# base image (no drivers, mount your own)
docker build --target base -t arkhn/clickhouse-jdbc-bridge:base .

# all-in-one (ClickHouse + bridge in one container, for demos)
docker build -t arkhn/clickhouse-all-in-one -f all-in-one.Dockerfile .
```

## Extending the bridge

The bridge is pluggable. Any class on the classpath can be loaded as an
extension by listing it under `extensions` in `config/server.json`:

```json
"extensions": [
  { "class": "com.mycompany.MyExtension" }
]
```

An extension typically declares:

1. An extension name (optional):
   ```java
   public static final String EXTENSION_NAME = "myExtension";
   ```
2. An initialization hook (optional, called once at load time):
   ```java
   public static void initialize(ExtensionManager manager) { ... }
   ```
3. A static factory (optional, used in preference to constructor scanning):
   ```java
   public static MyExtension newInstance(Object... args) { ... }
   ```

[`ConfigDataSource`](src/main/java/com/clickhouse/jdbcbridge/impl/ConfigDataSource.java)
and [`JdbcDataSource`](src/main/java/com/clickhouse/jdbcbridge/impl/JdbcDataSource.java)
are the reference implementations. The first `NamedDataSource` extension in
`server.json` becomes the default for all named datasources.

Put extension jars (and any extra dependencies) under `extensions/`.

## License & attribution

Apache License 2.0. See [LICENSE](LICENSE).

The original ClickHouse JDBC Bridge was written by Zhichun Wu and previously
maintained under the [ClickHouse organisation](https://github.com/ClickHouse/clickhouse-jdbc-bridge).
This fork carries that work forward under Arkhn's stewardship; full
attribution is recorded in [NOTICE](NOTICE).

ClickHouse® is a registered trademark of ClickHouse, Inc.
