# `clickhouse-jdbc-bridge`

`clickhouse-jdbc-bridge` is a stateless Java/Vert.x proxy that lets ClickHouse query any JDBC-accessible datasource in real time via the `jdbc()` table function or the `JDBC` table engine.

# Behavioral guidelines

These guidelines extend the general behavioral guidelines in the root [`data-ai/CLAUDE.md`](../CLAUDE.md) (Think Before Coding, Simplicity First, Surgical Changes) — read those first, they apply here too. The rules below are specific to this repo.

## 1. This Is a Security-Hardened Fork — Don't Reintroduce What Was Removed

**Two upstream features were deliberately removed for security reasons. Never re-add them, even partially.**

- **Scripting datasource** (`ScriptDataSource`, Rhino / `javax.script`) — evaluating request-supplied JavaScript against the bridge host is remote code execution. It does not exist in this codebase; don't bring it back to "restore parity" with upstream.
- **Remote driver/extension URLs** — `driverUrls` / `libUrls` only accept local filesystem paths; `http://`/`https://` entries are rejected at startup (see `AdhocPolicy` and `SECURITY.md`). Don't add code that fetches a driver jar over the network at runtime (that's what the removed `JDBC_DRIVERS` env var did).
- Adhoc JDBC URLs as a datasource are rejected unless `ALLOW_ADHOC_CONNECTIONS=true` is explicitly set. Don't change that default.

If a task seems to require one of these, stop and ask — the answer is almost always "stay on upstream for that," not "add it back here."

The test: before adding or restoring any feature here, check it isn't one of the two removed above — if it resembles them, stop and ask instead of implementing it.

## 2. Don't Silently Regress Measured Tuning or Security Defaults

**`EngineDefaults`/query-parameter precedence, JVM flags, and the adhoc-connection policy encode measured tradeoffs (benchmarks in `DEPLOYMENT.md`, threat model in `SECURITY.md`) that a "reasonable-looking" change can break without failing any test.**

- Operator-supplied config (the JDBC URL, `dataSourceProperties`, or a more specific config layer) must always win over a compiled default — never let a new tunable invert that precedence.
- Read `DEPLOYMENT.md`/`SECURITY.md` before touching JVM flags, `EngineDefaults`, or `AdhocPolicy` — they're the record of why the current defaults are what they are, not just background reading.

The test: a change to tuning defaults, JVM flags, or the adhoc-connection policy points to the `DEPLOYMENT.md`/`SECURITY.md` section it affects, and doesn't invert the "operator config wins" precedence.

## 3. Keep This Document in Sync

**`Architecture` and `Key Conventions` below mirror `DEPLOYMENT.md`, `SECURITY.md`, and `pom.xml`.**

If you notice those have changed in a way that isn't reflected here, **ask the user before editing this file** to bring it back in sync — don't update it silently.

The test: before relying on a claim here about tuning, security policy, or build config, check it still matches `DEPLOYMENT.md`/`SECURITY.md`/`pom.xml` — if not, flag the drift instead of assuming this file is current.

------------

# Project Overview

ClickHouse calls the bridge over HTTP; the bridge translates the request into a JDBC call against the target datasource (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, ClickHouse, SQLite, Trino, Neo4j, OpenDistro/Elasticsearch, or any JDBC-4 driver dropped into `drivers/`) and streams results back in ClickHouse's `RowBinary` format.

**This is a maintained fork**, not an original project: `groupId` is still `com.clickhouse`, license headers carry both `Copyright 2019-2021, Zhichun Wu` and `Copyright 2024-2026, Arkhn`, and origin is `github.com:arkhn/clickhouse-jdbc-bridge`. It tracks the upstream [`ClickHouse/clickhouse-jdbc-bridge`](https://github.com/ClickHouse/clickhouse-jdbc-bridge) concept but has diverged on purpose:
- Modernized: JDK 25, current JDBC drivers, current ClickHouse versions.
- Hardened: scripting datasource and remote driver URLs removed (see the Behavioral guidelines above); adhoc connections opt-in only.
- Extended: `/test` HTTP endpoint to validate a datasource definition (the same JSON an operator is about to persist to Vault) without saving it — see `TestConnectionEndpointIT.java` and `JdbcBridgeVerticle.handleTestConnection`.
- Operated independently: own CI (`build.yml`, `docker-publish.yml`, `bench-pr.yml`), own semantic-release (`.releaserc.json`) publishing `arkhn/clickhouse-jdbc-bridge` images to Docker Hub, own `DEPLOYMENT.md`/`PERFORMANCE.md`/`SECURITY.md`. There is no automated sync job pulling upstream commits — divergence is tracked manually; check the [upstream repo](https://github.com/ClickHouse/clickhouse-jdbc-bridge) if you need to know whether a fix there also applies here.

In the Arkhn platform, this repo is what lets Codex raw dbt models emit `jdbc()` when a source's loader is `jdbc` (see the root [`data-ai/CLAUDE.md`](../CLAUDE.md)). Named datasource configuration (`config/datasources/*.json`) is **Vault-backed**: the bridge itself only reads that JSON off the local filesystem with hot-reload (`configScanPeriod`) — it does not talk to Vault directly. Whatever writes `config/datasources/datasources.json` from Vault secrets (an init container, Vault agent, or the `admin` repo via the `/test` endpoint) lives outside this repo.

## Setup & Environment

```bash
git clone git@github.com:arkhn/clickhouse-jdbc-bridge.git
cd clickhouse-jdbc-bridge

# compile + run unit tests (skips integration tests)
mvn -Prelease verify
```

Requires JDK 25 (`java.version` / `maven.compiler.source|target` in `pom.xml`) and Maven. Integration tests use Testcontainers against Docker, so a running Docker daemon is required for the full `verify` (`mvn verify` also runs failsafe ITs; set `TESTCONTAINERS_RYUK_DISABLED=true` as CI does if Ryuk causes issues in a sandboxed environment).

Local end-to-end stack (bridge + ClickHouse + a few JDBC backends) via Docker Compose:

```bash
cd misc/quick-start
docker compose up -d
docker compose run --rm ch-server \
  clickhouse-client --query="select * from jdbc('self?datasource_column', 'select 1')"
```

## Common Commands

```bash
# Unit tests only (fast, no Docker required)
mvn test -DskipITs=true

# Full verify: unit + integration tests (Testcontainers, needs Docker)
mvn -Prelease verify

# Skip all tests
mvn verify -DskipTests=true

# Build the shaded jar
mvn -Prelease package

# JMH micro-benchmarks (src/jmh)
mvn -Pjmh test -DskipTests=false
```

Docker images:

```bash
# full image (JDBC drivers vendored) — default target
docker build -t arkhn/clickhouse-jdbc-bridge:dev .

# base image (no drivers, mount your own into /app/drivers)
docker build --target base -t arkhn/clickhouse-jdbc-bridge:base .

# all-in-one (ClickHouse + bridge in one container, for demos)
docker build -t arkhn/clickhouse-all-in-one -f all-in-one.Dockerfile .
```

Local micro-benchmark grid used for `DEPLOYMENT.md`'s sizing numbers: `misc/bench/run.sh` (see `misc/bench/README.md`). CI runs a small PR-time bench when a PR has the `bench-pr` label (`.github/workflows/bench-pr.yml`).

## Architecture

Entry point: `com.clickhouse.jdbcbridge.JdbcBridgeVerticle` (Vert.x verticle), routes HTTP requests from ClickHouse:
- `/` (`jdbc()` table function / `JDBC` engine calls) — parses datasource/schema/query, dispatches to the right `NamedDataSource`, streams `RowBinary` back.
- `/ping` — liveness/readiness probe.
- `/metrics` — Prometheus (JVM, GC, HikariCP, request-path).
- `/test` (fork addition) — builds a transient datasource from a posted JSON body (the exact shape stored in Vault), opens one connection, returns `{ok, code, message}`; never leaks the raw driver exception.

Core packages, both under `src/main/java/com/clickhouse/jdbcbridge/`:
- **`core/`** — engine-agnostic machinery: `NamedDataSource`/`Repository`/`BaseRepository`/`JsonFileRepository` (config-file-backed, hot-reloadable datasource/query/schema registries), `QueryParser`, `QueryParameters`, `DataType`/`DataTypeConverter`, `ColumnDefinition`/`TableDefinition`, `ExpandedUrlClassLoader` (per-datasource driver classloading), `ExtensionManager` (loads classes listed in `config/server.json`'s `extensions`), `AdhocPolicy` (adhoc-connection and driver-URL allow/deny rules), `EngineDefaults` (per-driver JDBC property tuning), `CaCertificateSupport`.
- **`impl/`** — concrete implementations: `JdbcDataSource` (the actual JDBC bridge to a target database — connection pooling via HikariCP, streaming reads/writes) and `ConfigDataSource` (the reference `NamedDataSource` extension backing `config/`-driven datasources).

Configuration surfaces (see `docker/config/` for JSON Schemas the repo ships for editor autocomplete):

| Concern | Where |
|---|---|
| JDBC drivers | `drivers/` (shared classloader) or per-datasource `driverUrls` pointing at `extra/<name>/` |
| Named datasources (Vault-backed) | `config/datasources/*.json` |
| Named queries | `config/queries/*.json` |
| Named schemas | `config/schemas/*.json` |
| Server / extensions | `config/server.json` |
| Vert.x / HTTPD | `config/vertx.json`, `config/httpd.json` |

Extending the bridge: any class listed under `extensions` in `config/server.json` is loaded via classpath scanning (optional `EXTENSION_NAME`, `initialize(ExtensionManager)`, `newInstance(Object...)`). `ConfigDataSource` and `JdbcDataSource` are the reference implementations to model a custom `Repository`/`DataSource`/type-converter on.

## Key Conventions

- **License header required on every `src/**/*.java` file.** Enforced by `license-maven-plugin` (`check-license` goal bound to `initialize`) against `misc/license-header.template`, which carries both the original Zhichun Wu copyright and the Arkhn one. A missing/wrong header fails the build.
- **Unit vs integration tests are separated by naming and TestNG group, not by directory**: everything lives under `src/test/java/`, but classes ending in `IT.java` (e.g. `PostgresIT`, `TestConnectionEndpointIT`) are excluded from `maven-surefire-plugin` (unit run) and picked up by `maven-failsafe-plugin` instead (`groups=sit`, Testcontainers-backed, one JVM per IT class — `JdbcBridgeVerticle` resolves its config dir from a system property in a static initializer, so reusing a JVM across IT classes would leak stale config). Regular unit tests are tagged `@Test(groups = { "unit" })`.
- **Extracted pure-logic seams for testability**: HTTP handlers in `JdbcBridgeVerticle` factor their decision logic into small static/package-private methods (e.g. `resolveErrorResponse`, `testDatasource`) so behavior can be unit-tested without standing up an `HttpServer` or a real driver connection.
- **Conventional commits + semantic-release**: commit messages follow `<type>(<scope>): <description>` (see recent `git log`: `fix:`, `feat:`, `test(it):`, `chore(release):`). `.releaserc.json` drives `semantic-release` off `conventionalcommits` — commit type determines whether a release ships and its version bump (patch/minor/major), and updates `CHANGELOG.md` + the `<version>` in `pom.xml` automatically. Don't hand-edit the version in `pom.xml` or entries in `CHANGELOG.md`.
- **Tuning/security precedence** — see the Behavioral guideline above (`EngineDefaults`, `AdhocPolicy`, `DEPLOYMENT.md`/`SECURITY.md`).
