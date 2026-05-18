# Security Policy

This document describes how to report a vulnerability in
`clickhouse-jdbc-bridge`, which versions receive security fixes, and the
threat model operators should keep in mind when deploying it.

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.**

Report vulnerabilities privately to **security@arkhn.com**. Please include:

- A description of the issue and its impact.
- The version (or commit SHA) of the bridge you reproduced it on.
- Steps to reproduce, ideally with a minimal configuration / payload.
- Whether you intend to disclose publicly, and on what timeline.

You can also use [GitHub's private vulnerability reporting](https://github.com/arkhn/clickhouse-jdbc-bridge/security/advisories/new)
on this repository if you prefer.

### What to expect

- **Acknowledgement** within 3 business days.
- **Triage and severity assessment** within 7 business days, communicated
  back to the reporter.
- **Fix or mitigation** on a timeline proportional to severity:
  - Critical / High — patched release ASAP, target ≤ 14 days.
  - Medium — patched release in the next scheduled cycle, target ≤ 30 days.
  - Low — bundled with the next minor release.
- **Coordinated disclosure** — we'll agree on a public disclosure date with
  the reporter, advisory drafted via GitHub Security Advisories, CVE
  requested where applicable.

We do not currently run a bug-bounty programme, but we're happy to credit
reporters in the advisory unless they ask otherwise.

## Supported versions

| Version | Supported |
|---|---|
| `1.x` (current `master`) | ✅ — security fixes and bug fixes |
| `2.x` (upstream ClickHouse fork) | ❌ — see [upstream](https://github.com/ClickHouse/clickhouse-jdbc-bridge) |
| `< 2.0` (upstream) | ❌ |

Arkhn's fork starts at `1.0.0`; older `2.x` tags refer to the upstream
ClickHouse-maintained line and are not covered by this policy.

## Threat model

The bridge is a **stateless HTTP service that holds credentials for one or
more external databases and executes SQL on the caller's behalf**. The
attack surface follows from that:

1. **The HTTP endpoint** is unauthenticated by default — anyone who can
   reach `:9019` can ask it to run a query as any configured datasource.
2. **The JDBC drivers** are third-party native-ish code with a long history
   of CVEs (deserialisation, log4shell, JNDI injection). They are loaded
   from disk at startup and trusted.
3. **The configuration files** under `config/` (datasources, queries,
   schemas) include credentials in plaintext and are read on every change
   (hot reload).
4. **The target databases** are reached over JDBC; misconfigured
   `dataSourceProperties` can disable TLS, enable arbitrary file reads
   (e.g. MySQL `allowLoadLocalInfile`), or pull driver code over HTTP.

The bridge is intended to run on a **trusted internal network alongside
ClickHouse**. It is **not** designed to be exposed to the public internet.

## Hardening guidance

### Network

- Bind the bridge to a private interface, or front it with `iptables` /
  Kubernetes `NetworkPolicy` so that only ClickHouse pods/nodes can reach
  `:9019`.
- Use a service mesh (mTLS) or a TLS-terminating sidecar if traffic between
  ClickHouse and the bridge crosses untrusted networks.

### Adhoc datasources

The `jdbc()` table function accepts a full JDBC URL in place of a named
datasource:

```sql
-- DON'T do this in production
select * from jdbc('jdbc:mysql://internal-host:3306/?user=...&password=...', 'select 1');
```

This bypasses your reviewed datasource configuration entirely and lets the
caller pick driver properties freely (TLS off, `allowLoadLocalInfile=true`,
etc.). **Prefer named datasources** and treat adhoc JDBC URLs as a
development-only convenience.

### Credentials

- Credentials live in `config/datasources/*.json` as plaintext. The
  directory should be readable only by the bridge user
  (`chmod 0600` on the JSON files, `chmod 0700` on the directory).
- In Kubernetes, mount datasource JSON from a `Secret`, not a `ConfigMap`.
- Rotate credentials when an operator with config access leaves.

### JDBC drivers

- Pin driver versions. The published `arkhn/clickhouse-jdbc-bridge:full`
  image vendors specific versions; if you build your own, pin them too.
- Subscribe to the CVE feeds for the drivers you ship — most recent
  bridge-relevant incidents (`log4shell`, MySQL deserialisation, Postgres
  driver path traversal) live in the driver layer, not the bridge.
- Don't load drivers from untrusted Maven coordinates at runtime via the
  `JDBC_DRIVERS` env var on a host you don't control.

### Driver `dataSourceProperties` to watch

| Driver | Property | Why it matters |
|---|---|---|
| MySQL / MariaDB | `allowLoadLocalInfile` | enables `LOAD DATA LOCAL INFILE`, server can read files on the bridge host |
| MySQL / MariaDB | `autoDeserialize` | RCE-grade deserialisation of server-sent payloads |
| PostgreSQL | `socketFactory`, `socketFactoryArg` | arbitrary class loading on connect |
| All | `sslMode=disable` / `useSSL=false` | plaintext credentials on the wire |

The bridge applies a curated set of **performance** defaults per driver (see
[DEPLOYMENT.md](DEPLOYMENT.md#engine-defaults-applied-automatically-per-driver));
it does **not** override the security-sensitive properties above. Operators
are responsible for setting them safely.

### Container

- Run as non-root with a read-only root filesystem
  (`securityContext.runAsNonRoot: true`, `readOnlyRootFilesystem: true`).
- Drop all Linux capabilities
  (`securityContext.capabilities.drop: ["ALL"]`).
- A reference K8s manifest is in [DEPLOYMENT.md](DEPLOYMENT.md#kubernetes).

### Scripting

The legacy `script` / `ScriptDataSource` extension is in the process of
being **removed**. It allowed evaluating Nashorn / JavaScript from incoming
requests, which is effectively remote code execution against the bridge
host. Until it is removed:

- Do **not** enable it on production deployments.
- Remove `com.clickhouse.jdbcbridge.impl.ScriptDataSource` from
  `extensions` in `config/server.json`.
- Delete `config/datasources/script.json`.

### Logs

- The bridge logs query bodies at `INFO`. Ensure your log shipper redacts
  obvious secrets or downgrade log level if your queries embed credentials.

## Out of scope

- **Denial-of-service** against the bridge from a fully-trusted ClickHouse
  caller (e.g. unbounded `SELECT *` from a large table). Use
  `max_rows`, `fetch_size`, `max_block_size` and HikariCP `maximumPoolSize`
  to bound resource usage; backpressure is enforced on the streaming path
  (commit `67ff93a`), but a hostile caller on the internal network can
  still exhaust upstream-DB resources.
- **Compromise of the underlying JVM, OS, or container runtime**.
- **Vulnerabilities in third-party JDBC drivers** — please report those to
  the respective driver maintainers; we'll happily bump the vendored
  versions once a fix is published.

## References

- [DEPLOYMENT.md](DEPLOYMENT.md) — JVM, container, K8s manifest with
  hardened `securityContext`.
- [PERFORMANCE.md](PERFORMANCE.md) — workload-bound resource limits.
- Upstream advisories: [ClickHouse/clickhouse-jdbc-bridge](https://github.com/ClickHouse/clickhouse-jdbc-bridge/security).
