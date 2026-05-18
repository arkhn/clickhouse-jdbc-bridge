# ClickHouse JDBC Bridge — Docker image

## What is ClickHouse JDBC Bridge?

A stateless proxy that lets ClickHouse® query any JDBC-accessible datasource
in real time, via the [`jdbc()` table function](https://clickhouse.com/docs/en/sql-reference/table-functions/jdbc/)
and the [`JDBC` table engine](https://clickhouse.com/docs/en/engines/table-engines/integrations/jdbc/).

Maintained by [Arkhn](https://arkhn.com) under the Apache License 2.0. See
the project [README](https://github.com/arkhn/clickhouse-jdbc-bridge#readme)
for the full feature list and the [LICENSE](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/LICENSE)
for legal terms.

## Image variants

The build produces two variants from the same multi-stage `Dockerfile`:

| tag | contents | when to use |
|---|---|---|
| `arkhn/clickhouse-jdbc-bridge:base` | runtime + bridge jar, no JDBC drivers | when you mount your own pinned driver jars |
| `arkhn/clickhouse-jdbc-bridge:latest` (a.k.a. `:full`) | base + curated set of JDBC drivers (ClickHouse, PostgreSQL, MySQL, MariaDB, MSSQL, Oracle, SQLite, Trino, Neo4j, OpenDistro SQL) | quick start, demos, most production cases |

Branch tags like `1.1` point to the latest release on that minor; full version
tags like `1.1.0` pin a specific release.

## How to use this image

### Start the bridge

```bash
docker run -d --name ch-jdbc-bridge -p 9019:9019 arkhn/clickhouse-jdbc-bridge:latest
```

To use the slim image and supply your own drivers and datasource config from
the host, fetch the jars yourself (the image no longer downloads them at
startup) and mount them in:

```bash
wget -P drivers \
    https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.4/mariadb-java-client-3.5.4.jar \
    https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar

wget -P datasources \
    https://raw.githubusercontent.com/arkhn/clickhouse-jdbc-bridge/master/misc/quick-start/jdbc-bridge/config/datasources/mariadb10.json \
    https://raw.githubusercontent.com/arkhn/clickhouse-jdbc-bridge/master/misc/quick-start/jdbc-bridge/config/datasources/postgres13.json
# edit datasources/*.json with your actual hosts/credentials before starting

docker run -d --name ch-jdbc-bridge -p 9019:9019 \
    -v $(pwd)/drivers:/app/drivers \
    -v $(pwd)/datasources:/app/config/datasources \
    arkhn/clickhouse-jdbc-bridge:base
```

For a reproducible deployment, build a derived image instead — extend
`:base` (or `:full`) and `COPY` the driver jars into `/app/drivers` so
versions are pinned in your image. Remote `driverUrls` and the upstream
`JDBC_DRIVERS` / `MAVEN_REPO_URL` runtime-download env vars are not
supported in this fork (see
[SECURITY.md](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/SECURITY.md#jdbc-drivers)).

### Configure ClickHouse

ClickHouse defaults to `localhost:9019` for the bridge. Override in
`/etc/clickhouse-server/config.xml`:

```xml
<clickhouse>
  <jdbc_bridge>
    <host>jdbc-bridge</host>
    <port>9019</port>
  </jdbc_bridge>
</clickhouse>
```

### Issue queries on ClickHouse

```sql
-- list configured datasources
select * from jdbc('', 'show datasources');

-- named datasource + inline schema + adhoc query
select * from jdbc('mariadb10', 'num UInt8', 'select 1 as num');

-- adhoc JDBC URL — discouraged, see SECURITY.md
select * from jdbc('jdbc:mariadb://...', 'select 1');
```

## Filesystem layout

```text
/app
├── drivers/         JDBC drivers loaded into the shared classloader at startup
├── extra/           per-datasource driver jars, referenced via `driverUrls` (see README)
├── config/
│   ├── datasources/ named datasources
│   ├── schemas/     named schemas
│   └── queries/     named queries
├── extensions/      pluggable extensions
└── logs/            application logs
```

`drivers/` holds the default driver set — every jar here is visible to
every datasource. `extra/` is opt-in: jars live there until a datasource
explicitly references them via `driverUrls: ["extra/<subdir>"]`. Use the
second slot when you want a separate driver instance for a specific
datasource (e.g. a legacy connector version) without polluting the shared
classloader.

Port `9019` is exposed for both ClickHouse integration and Prometheus
scraping (`/metrics`) and probes (`/ping`).

## Environment variables

| Environment Variable | Java System Property | Default | Description |
|---|---|---|---|
| `CONFIG_DIR` | `jdbc-bridge.config.dir` | `config` | Configuration directory |
| `SERIAL_MODE` | `jdbc-bridge.serial.mode` | `false` | Run queries in serial mode |
| `CUSTOM_DRIVER_LOADER` | `jdbc-bridge.driver.loader` | `false` (image default) | Per-datasource driver classloader. The Arkhn image defaults to `false` so jars in `/app/drivers` are shared |
| `DATASOURCE_CONFIG_DIR` | `jdbc-bridge.datasource.config.dir` | `datasources` | Directory for named datasources |
| `DEFAULT_VALUE` | `jdbc-bridge.type.default` | `false` | Support `DEFAULT` expressions in column definitions |
| `DRIVER_DIR` | `jdbc-bridge.driver.dir` | `drivers` | Driver directory |
| `HTTPD_CONFIG_FILE` | `jdbc-bridge.httpd.config.file` | `httpd.json` | HTTP server configuration |
| `JDBC_BRIDGE_JVM_OPTS` | — | — | Extra JVM args (heap, GC, system properties) |
| `QUERY_CONFIG_DIR` | `jdbc-bridge.query.config.dir` | `queries` | Directory for named queries |
| `SCHEMA_CONFIG_DIR` | `jdbc-bridge.schema.config.dir` | `schemas` | Directory for named schemas |
| `SERVER_CONFIG_FILE` | `jdbc-bridge.server.config.file` | `server.json` | Bridge server configuration |
| `VERTX_CONFIG_FILE` | `jdbc-bridge.vertx.config.file` | `vertx.json` | Vert.x configuration |

## Further reading

- [README](https://github.com/arkhn/clickhouse-jdbc-bridge#readme) — full
  feature set and usage examples.
- [DEPLOYMENT.md](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/DEPLOYMENT.md) —
  JVM, GC and Kubernetes manifest recommendations.
- [PERFORMANCE.md](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/PERFORMANCE.md) —
  benchmark suite and reference numbers.
- [SECURITY.md](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/SECURITY.md) —
  threat model and hardening guidance.

## License

Apache License 2.0. See
[LICENSE](https://github.com/arkhn/clickhouse-jdbc-bridge/blob/master/LICENSE)
for the software contained in this image.
