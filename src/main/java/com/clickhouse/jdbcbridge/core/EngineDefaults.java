/*
 * Copyright 2019-2021, Zhichun Wu
 * Copyright 2024-2026, Arkhn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clickhouse.jdbcbridge.core;

import java.sql.Driver;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariConfig;

/**
 * Engine-specific JDBC connection-property defaults for performance-critical
 * settings that aren't part of the JDBC standard and that operators almost
 * never know to set.
 *
 * <p>The merge rule is <strong>operator config always wins</strong>: a default
 * is only applied if neither the user-supplied {@code dataSourceProperties}
 * nor the {@code jdbcUrl} already specifies that key. To override, set the
 * key in the JDBC URL (e.g. {@code ;selectMethod=direct} for SQL Server) or
 * in the datasource's {@code dataSourceProperties} block.
 *
 * <p>The list of defaults per engine is documented in {@code DEPLOYMENT.md}.
 * Changes to either should be kept in sync.
 */
public final class EngineDefaults {

    public static final String DRIVER_MSSQL          = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    public static final String DRIVER_ORACLE         = "oracle.jdbc.OracleDriver";
    /**
     * Legacy Oracle driver class. Both {@code oracle.jdbc.OracleDriver}
     * (modern) and {@code oracle.jdbc.driver.OracleDriver} (legacy) ship in
     * every ojdbc release and are used interchangeably by tooling — in
     * particular, testcontainers' OracleContainer reports the legacy form
     * from {@code getDriverClassName()}.
     */
    public static final String DRIVER_ORACLE_LEGACY  = "oracle.jdbc.driver.OracleDriver";
    public static final String DRIVER_MYSQL          = "com.mysql.cj.jdbc.Driver";
    public static final String DRIVER_MARIADB        = "org.mariadb.jdbc.Driver";
    public static final String DRIVER_POSTGRES       = "org.postgresql.Driver";

    /**
     * Default {@code connectionTestQuery} per driver. HikariCP prefers
     * {@code Connection.isValid()} when the driver implements it, but
     * falls back to this query for validation and may run it as a
     * keep-alive even when isValid() works. Oracle in particular rejects
     * bare {@code SELECT 1} (ORA-00923: FROM keyword not found), so we
     * supply the dialect-correct form. Every other driver here accepts
     * the standard form and a defensive default doesn't hurt.
     */
    private static final Map<String, String> CONNECTION_TEST_QUERIES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(DRIVER_ORACLE,        "SELECT 1 FROM dual");
        m.put(DRIVER_ORACLE_LEGACY, "SELECT 1 FROM dual");
        m.put(DRIVER_MSSQL,         "SELECT 1");
        m.put(DRIVER_MYSQL,         "SELECT 1");
        m.put(DRIVER_MARIADB,       "SELECT 1");
        m.put(DRIVER_POSTGRES,      "SELECT 1");
        CONNECTION_TEST_QUERIES = Collections.unmodifiableMap(m);
    }

    /** Visible for testing. */
    static String defaultConnectionTestQuery(String driverClassName) {
        return CONNECTION_TEST_QUERIES.get(driverClassName);
    }

    /**
     * Default statement-level {@code fetch_size} per driver, used only when the
     * operator did not set {@code fetch_size} explicitly (datasource config or
     * request URI).
     *
     * <p>Distinct from the connection-property defaults above: this is the value
     * the bridge passes to {@link java.sql.Statement#setFetchSize(int)} on the data
     * read path. For Oracle, {@code setFetchSize} overrides the
     * {@code oracle.jdbc.defaultRowPrefetch} connection property, so the prefetch
     * default of 2000 would otherwise be silently lost to the compiled bridge
     * default of {@link QueryParameters#DEFAULT_FETCH_SIZE}. The Oracle thin driver
     * pre-allocates client-side fetch buffers as {@code fetchSize × maxColumnWidth},
     * so a large fetch size on wide rows is a real OOM hazard — hence the lower,
     * prefetch-aligned default here.
     */
    private static final Map<String, Integer> DEFAULT_FETCH_SIZES;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put(DRIVER_ORACLE,        2000);
        m.put(DRIVER_ORACLE_LEGACY, 2000);
        DEFAULT_FETCH_SIZES = Collections.unmodifiableMap(m);
    }

    /**
     * The engine-specific default {@code fetch_size} for {@code driverClassName},
     * or {@code null} if the driver has no override (callers should then keep the
     * compiled {@link QueryParameters#DEFAULT_FETCH_SIZE}).
     */
    public static Integer defaultFetchSize(String driverClassName) {
        return driverClassName == null ? null : DEFAULT_FETCH_SIZES.get(driverClassName);
    }

    /**
     * Pluggable driver-major-version provider, swappable in tests so we don't
     * need a real driver on the classpath to validate version-gated defaults.
     */
    public interface DriverVersionProvider {
        /** Return the major version of the loaded driver, or {@code -1} if unavailable. */
        int getMajorVersion(String driverClassName);
    }

    /** Default provider: instantiates the driver via reflection and reads {@link Driver#getMajorVersion()}. */
    public static final DriverVersionProvider DEFAULT_VERSION_PROVIDER = driverClass -> {
        try {
            Class<?> cls = Class.forName(driverClass);
            Driver d = (Driver) cls.getDeclaredConstructor().newInstance();
            return d.getMajorVersion();
        } catch (Throwable t) {
            return -1;
        }
    };

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineDefaults.class);

    private EngineDefaults() {}

    /**
     * Compute the effective engine defaults for {@code driverClassName}.
     * Returns an immutable map (possibly empty for unknown drivers).
     */
    public static Map<String, String> forDriver(String driverClassName) {
        return forDriver(driverClassName, DEFAULT_VERSION_PROVIDER);
    }

    /** Version of {@link #forDriver(String)} that takes an explicit version provider (test hook). */
    public static Map<String, String> forDriver(String driverClassName, DriverVersionProvider versionProvider) {
        if (driverClassName == null) {
            return Collections.emptyMap();
        }
        DriverVersionProvider provider = versionProvider != null ? versionProvider : DEFAULT_VERSION_PROVIDER;
        switch (driverClassName) {
            case DRIVER_MSSQL:          return mssqlDefaults(provider);
            case DRIVER_ORACLE:         return ORACLE_DEFAULTS;
            case DRIVER_ORACLE_LEGACY:  return ORACLE_DEFAULTS;
            case DRIVER_MYSQL:          return MYSQL_DEFAULTS;
            case DRIVER_MARIADB:        return MARIADB_DEFAULTS;
            case DRIVER_POSTGRES:       return POSTGRES_DEFAULTS;
            default:                    return Collections.emptyMap();
        }
    }

    /**
     * Merge engine defaults for {@code config.getDriverClassName()} into {@code config},
     * skipping any key already present in the user's {@code dataSourceProperties} or
     * {@code jdbcUrl}.
     *
     * @return the number of defaults actually applied
     */
    public static int applyTo(HikariConfig config) {
        return applyTo(config, DEFAULT_VERSION_PROVIDER);
    }

    /** Version of {@link #applyTo(HikariConfig)} that takes an explicit version provider (test hook). */
    public static int applyTo(HikariConfig config, DriverVersionProvider versionProvider) {
        String driverClass = config.getDriverClassName();
        int applied = applyConnectionTestQuery(config, driverClass);

        Map<String, String> defaults = forDriver(driverClass, versionProvider);
        if (defaults.isEmpty()) {
            return applied;
        }

        Properties userProps = config.getDataSourceProperties();
        String url = config.getJdbcUrl();
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            String key = e.getKey();
            if (userProps != null && userProps.containsKey(key)) {
                continue;
            }
            if (jdbcUrlContainsKey(url, key)) {
                continue;
            }
            config.addDataSourceProperty(key, e.getValue());
            applied++;
            if (log.isInfoEnabled()) {
                log.info("Engine default applied for {}: {} = {}", driverClass, key, e.getValue());
            }
        }
        return applied;
    }

    /**
     * Apply the {@code connectionTestQuery} default for {@code driverClass} unless
     * the operator has already set one. Returns 1 if applied, 0 otherwise.
     */
    private static int applyConnectionTestQuery(HikariConfig config, String driverClass) {
        if (config.getConnectionTestQuery() != null) {
            return 0;
        }
        String testQuery = CONNECTION_TEST_QUERIES.get(driverClass);
        if (testQuery == null) {
            return 0;
        }
        config.setConnectionTestQuery(testQuery);
        if (log.isInfoEnabled()) {
            log.info("Engine default applied for {}: connectionTestQuery = {}", driverClass, testQuery);
        }
        return 1;
    }

    // ---------------- per-engine builders ----------------

    private static Map<String, String> mssqlDefaults(DriverVersionProvider versionProvider) {
        Map<String, String> m = new HashMap<>();
        m.put("responseBuffering", "adaptive");
        m.put("useBulkCopyForBatchInsert", "true");
        int majorVersion = versionProvider.getMajorVersion(DRIVER_MSSQL);
        if (majorVersion >= 6) {
            // selectMethod=cursor is the property that flips mssql-jdbc into true
            // server-side cursor streaming. Honoured from mssql-jdbc 6.0 onward;
            // older drivers either ignore it silently or, worse, parse it
            // incorrectly. Be defensive: only apply when we can confirm the
            // driver version is >= 6.
            m.put("selectMethod", "cursor");
        }
        return Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> ORACLE_DEFAULTS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("oracle.jdbc.defaultRowPrefetch",        "2000");
        m.put("oracle.jdbc.implicitStatementCacheSize","50");
        m.put("oracle.jdbc.useThreadLocalBufferCache", "true");
        m.put("useFetchSizeWithLongColumn",            "true");
        m.put("oracle.net.disableOob",                 "true");
        m.put("oracle.jdbc.timezoneAsRegion",          "false");
        ORACLE_DEFAULTS = Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> MYSQL_DEFAULTS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("useServerPrepStmts",        "true");
        m.put("cachePrepStmts",            "true");
        m.put("rewriteBatchedStatements",  "true");
        m.put("useUnicode",                "true");
        m.put("characterEncoding",         "UTF-8");
        m.put("useLocalSessionState",      "true");
        MYSQL_DEFAULTS = Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> MARIADB_DEFAULTS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("useServerPrepStmts",        "true");
        m.put("cachePrepStmts",            "true");
        m.put("rewriteBatchedStatements",  "true");
        m.put("useUnicode",                "true");
        m.put("characterEncoding",         "UTF-8");
        MARIADB_DEFAULTS = Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> POSTGRES_DEFAULTS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("prepareThreshold",     "3");
        m.put("defaultRowFetchSize",  "10000");
        m.put("binaryTransfer",       "true");
        POSTGRES_DEFAULTS = Collections.unmodifiableMap(m);
    }

    // ---------------- URL inspection ----------------

    /**
     * Does {@code url} contain a parameter named {@code key} (with any value)?
     * Looks for {@code [;?&]key=} so we don't false-positive on substring matches
     * like {@code selectMethodOther=...}. Visible for testing.
     */
    static boolean jdbcUrlContainsKey(String url, String key) {
        if (url == null || url.isEmpty() || key == null || key.isEmpty()) {
            return false;
        }
        String escapedKey = Pattern.quote(key);
        return Pattern.compile("[;?&]" + escapedKey + "\\s*=").matcher(url).find();
    }
}
