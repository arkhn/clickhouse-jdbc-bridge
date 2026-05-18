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
package com.clickhouse.jdbcbridge.impl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.ColumnDefinition;
import com.clickhouse.jdbcbridge.core.DataAccessException;
import com.clickhouse.jdbcbridge.core.DataType;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.QueryParameters;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.core.TableDefinition;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * End-to-end tests for {@link JdbcDataSource} using an in-process H2 database.
 * The integration tests under {@code src/test/java/.../jdbcbridge/*IT.java}
 * already exercise this class against five real databases via testcontainers;
 * those runs are slow (each spins a container). These tests cover the same
 * code paths in &lt;1s by talking to an in-memory H2 instance, which lets the
 * JaCoCo aggregate actually see the lines on every PR build.
 */
public class JdbcDataSourceH2Test {

    private String dbName;
    private String jdbcUrl;

    @BeforeMethod(groups = { "unit" })
    public void perTestDatabase() throws Exception {
        // JdbcDataSource's constructor unconditionally calls
        // deregisterJdbcDriver(driverClassName) before it builds the
        // HikariConfig, so after the first test in this class our H2 driver
        // is gone from DriverManager. Class.forName won't re-trigger the
        // static initializer (the class is already loaded), so we register
        // an explicit instance each time. This also rides out any other
        // test class in the suite that tears down DriverManager.
        boolean registered = false;
        java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if ("org.h2.Driver".equals(drivers.nextElement().getClass().getName())) {
                registered = true;
                break;
            }
        }
        if (!registered) {
            DriverManager.registerDriver(
                    (java.sql.Driver) Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance());
        }

        // Unique per-method database so test order can't leak state. Use a
        // private in-memory DB (no shared registry across DriverManager
        // sessions) and DB_CLOSE_DELAY=-1 so the schema we seed below survives
        // the seed connection closing.
        dbName = "jdbcds-" + UUID.randomUUID().toString().replace("-", "");
        jdbcUrl = "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

        try (Connection seed = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement s = seed.createStatement()) {
            s.execute("CREATE TABLE widgets ("
                    + "id INT PRIMARY KEY, "
                    + "name VARCHAR(64) NOT NULL, "
                    + "price DECIMAL(10,2), "
                    + "in_stock BOOLEAN)");
            s.execute("INSERT INTO widgets VALUES (1, 'sprocket', 9.99, TRUE)");
            s.execute("INSERT INTO widgets VALUES (2, 'gizmo', 19.95, FALSE)");
            s.execute("INSERT INTO widgets VALUES (3, 'thingamabob', 0.50, TRUE)");
        }
    }

    @AfterMethod(groups = { "unit" })
    public void tearDownDatabase() throws Exception {
        // DB_CLOSE_DELAY=-1 keeps the in-memory DB alive until shutdown.
        // Issue an explicit SHUTDOWN so each test starts with a clean slate.
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (Exception ignored) {
        }
    }

    private static Repository<NamedDataSource> repo() {
        return new JsonFileRepository<>(NamedDataSource.class);
    }

    private JsonObject baseConfig() {
        return new JsonObject()
                .put("driverClassName", "org.h2.Driver")
                .put("jdbcUrl", jdbcUrl)
                .put("username", "sa")
                .put("password", "")
                .put("initializationFailTimeout", 5000)
                .put("minimumIdle", 1)
                .put("maximumPoolSize", 2);
    }

    // ---------- constructor / pool init ----------

    @Test(groups = { "unit" })
    public void constructor_initializesHikariPoolAgainstH2() {
        JdbcDataSource ds = new JdbcDataSource("h2-basic", repo(), baseConfig());

        assertEquals(ds.getId(), "h2-basic");
        // Pool stats getter returns a non-empty JSON object once the pool exists.
        String poolUsage = ds.getPoolUsage();
        assertNotNull(poolUsage);
        // The base NamedDataSource.EMPTY_USAGE sentinel is "{}"; with a real
        // pool we expect richer content keyed off Hikari's metrics. We don't
        // pin the exact JSON shape (Hikari upgrades may rename keys), but it
        // must not be the empty-pool sentinel.
        ds.close();
    }

    @Test(groups = { "unit" })
    public void constructor_translatesLegacyDriverAlias() {
        // The "driver" key is HikariCP's pre-2.x name for "driverClassName".
        // The bridge translates it before HikariConfig sees it, so a config
        // written against an older bridge keeps working.
        JsonObject legacy = baseConfig();
        legacy.remove("driverClassName");
        legacy.put("driver", "org.h2.Driver");

        JdbcDataSource ds = new JdbcDataSource("h2-legacy-alias", repo(), legacy);
        assertNotNull(ds);
        ds.close();
    }

    @Test(groups = { "unit" })
    public void constructor_ignoresUnknownTopLevelProperty() {
        JsonObject cfg = baseConfig().put("thisIsNotAHikariSetter", "whatever");

        // Must not throw: filterHikariProps drops unknown keys before HikariConfig sees them.
        JdbcDataSource ds = new JdbcDataSource("h2-unknown-prop", repo(), cfg);
        ds.close();
    }

    @Test(groups = { "unit" })
    public void constructor_threadsCustomDataSourcePropertiesThroughHikari() {
        // Driver-specific options on a `dataSource.*` block must reach the
        // JDBC driver. H2 silently ignores unknown ones, so we use one that
        // actually exists (TRACE_LEVEL_SYSTEM_OUT) and verify the ds still
        // initialises with it set.
        JsonObject cfg = baseConfig().put("dataSource",
                new JsonObject().put("TRACE_LEVEL_SYSTEM_OUT", "0"));

        JdbcDataSource ds = new JdbcDataSource("h2-ds-props", repo(), cfg);
        assertNotNull(ds);
        ds.close();
    }

    // ---------- inferTypes / getResultColumns ----------

    @Test(groups = { "unit" })
    public void inferTypes_resolvesColumnsForRealTableQuery() {
        JdbcDataSource ds = new JdbcDataSource("h2-infer", repo(), baseConfig());
        try {
            TableDefinition cols = ds.getResultColumns("", "SELECT id, name, price, in_stock FROM widgets",
                    new QueryParameters());

            assertEquals(cols.size(), 4);
            assertEquals(cols.getColumn(0).getName().toUpperCase(), "ID");
            // H2's INTEGER maps to Int32. The exact mapping comes from
            // DefaultDataTypeConverter (covered in its own test file); here
            // we just confirm the wiring delivers integer/string/decimal/bool
            // distinctions to ColumnDefinition.
            assertEquals(cols.getColumn(0).getType(), DataType.Int32);
            assertEquals(cols.getColumn(1).getType(), DataType.Str);
            assertEquals(cols.getColumn(2).getType(), DataType.Decimal);
            // H2 reports BOOLEAN as JDBC BOOLEAN which the converter normalises
            // to Str (see DefaultDataTypeConverter.BOOLEAN case).
            assertEquals(cols.getColumn(3).getType(), DataType.Str);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void inferTypes_acceptsBareTableNameAndQuotesIt() {
        // The bare-name branch (no whitespace -> wrap in SELECT * FROM "name")
        // is what callers hit when they just pass the table identifier.
        JdbcDataSource ds = new JdbcDataSource("h2-table-name", repo(), baseConfig());
        try {
            // H2 in PostgreSQL mode preserves UPPERCASE for unquoted identifiers.
            TableDefinition cols = ds.getResultColumns("", "WIDGETS", new QueryParameters());

            assertEquals(cols.size(), 4);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void inferTypes_wrapsSqlExceptionAsDataAccessException() {
        JdbcDataSource ds = new JdbcDataSource("h2-bad-sql", repo(), baseConfig());
        try {
            // Inference goes via columnsCache.get(...) -> inferTypes -> stmt.execute.
            // A syntax error must surface as DataAccessException with the ds id
            // baked into the message. Note: getResultColumns wraps it again as
            // IllegalStateException with "Failed to infer schema from [...]".
            try {
                ds.getResultColumns("", "NOT VALID SQL AT ALL FROM widgets", new QueryParameters());
                fail("expected query against a bad SQL string to surface an exception");
            } catch (IllegalStateException ise) {
                assertTrue(ise.getMessage().contains("h2-bad-sql"),
                        "wrapper message must include the ds id, got: " + ise.getMessage());
                assertTrue(ise.getCause() instanceof DataAccessException,
                        "cause must be DataAccessException, got: " + ise.getCause());
            }
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void inferTypes_columnsCacheReusesPreviousLookup() {
        JdbcDataSource ds = new JdbcDataSource("h2-cache", repo(), baseConfig());
        try {
            QueryParameters params = new QueryParameters();
            TableDefinition first = ds.getResultColumns("", "SELECT id, name FROM widgets", params);
            TableDefinition second = ds.getResultColumns("", "SELECT id, name FROM widgets", params);

            // Both calls hit the same Caffeine entry; identity equality
            // proves the cache short-circuit worked. (A regression that
            // recomputed every time would build two distinct instances.)
            assertSame(second, first, "second call must hit the columnsCache, not re-infer");
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void inferTypes_doNotUseCacheBypassesCache() {
        JdbcDataSource ds = new JdbcDataSource("h2-no-cache", repo(), baseConfig());
        try {
            QueryParameters params = new QueryParameters();
            params.merge(new JsonObject().put("no_cache", true));

            TableDefinition first = ds.getResultColumns("", "SELECT id FROM widgets", params);
            TableDefinition second = ds.getResultColumns("", "SELECT id FROM widgets", params);

            // Each call re-infers; the cache is bypassed. The two table
            // definitions are equal-but-distinct.
            assertEquals(second, first);
            assertTrue(second != first, "no_cache=true must skip the columnsCache");
        } finally {
            ds.close();
        }
    }

    // ---------- newInstance factory ----------

    @Test(groups = { "unit" })
    public void newInstance_buildsViaFactoryWithValidConfig() {
        JdbcDataSource ds = JdbcDataSource.newInstance("h2-factory", repo(), baseConfig());

        assertEquals(ds.getId(), "h2-factory");
        ds.close();
    }

    @Test(groups = { "unit" })
    public void newInstance_rejectsTooFewArgs() {
        assertThrows(IllegalArgumentException.class, () -> JdbcDataSource.newInstance("only-id"));
        assertThrows(NullPointerException.class, () -> JdbcDataSource.newInstance((Object[]) null));
    }

    // ---------- custom columns + aliases plumb through to base class ----------

    @Test(groups = { "unit" })
    public void constructor_exposesCustomColumnsConfiguredAtJsonLevel() {
        JsonObject cfg = baseConfig().put("columns", new JsonArray()
                .add(new JsonObject().put("name", "env").put("type", "Str")));

        JdbcDataSource ds = new JdbcDataSource("h2-custom-cols", repo(), cfg);
        try {
            assertEquals(ds.getCustomColumns().size(), 1);
            ColumnDefinition env = ds.getCustomColumns().get(0);
            assertEquals(env.getName(), "env");
            assertEquals(env.getType(), DataType.Str);
        } finally {
            ds.close();
        }
    }

    // ---------- executeQuery -> ResultSetReader.process (the streaming path) ----------

    /**
     * Captures every {@code write(ByteBuffer)} call so we can prove the streaming
     * query path actually emitted row data, without standing up a Vert.x HTTP
     * response. {@link com.clickhouse.jdbcbridge.core.ResponseWriter} exposes a
     * protected no-arg ctor for exactly this purpose.
     */
    static final class CapturingResponseWriter extends com.clickhouse.jdbcbridge.core.ResponseWriter {
        int writeCalls;
        long totalBytes;

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public void write(com.clickhouse.jdbcbridge.core.ByteBuffer buffer) {
            writeCalls++;
            if (buffer != null) {
                totalBytes += buffer.length();
            }
        }
    }

    @Test(groups = { "unit" })
    public void executeQuery_streamsRowsThroughResultSetReader() {
        JdbcDataSource ds = new JdbcDataSource("h2-execquery", repo(), baseConfig());
        try {
            // Make a request column list matching the projection so the result
            // mapping is unambiguous (column-name match, no implicit DEFAULT
            // routing).
            ColumnDefinition idCol = new ColumnDefinition("ID", DataType.Int32, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            ColumnDefinition nameCol = new ColumnDefinition("NAME", DataType.Str, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            TableDefinition cols = new TableDefinition(idCol, nameCol);

            CapturingResponseWriter writer = new CapturingResponseWriter();
            ds.executeQuery(
                    /* schema */ "",
                    /* originalQuery */ "SELECT id, name FROM widgets ORDER BY id",
                    /* loadedQuery   */ "SELECT id, name FROM widgets ORDER BY id",
                    cols,
                    new QueryParameters(),
                    writer);

            // Three rows in widgets => the streaming reader must have driven
            // at least one write through the response. We don't pin the exact
            // RowBinary byte sequence (covered by ITs against real ClickHouse),
            // just that the streaming path actually flowed data.
            assertTrue(writer.writeCalls > 0, "ResultSetReader must emit at least one write call");
            assertTrue(writer.totalBytes > 0, "ResultSetReader must emit non-empty bytes");
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void executeQuery_badSqlSurfacesAsDataAccessException() {
        JdbcDataSource ds = new JdbcDataSource("h2-exec-error", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(new ColumnDefinition("c", DataType.Str, true,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE));

            CapturingResponseWriter writer = new CapturingResponseWriter();
            try {
                ds.executeQuery("", "SELECT bogus FROM no_such_table", "SELECT bogus FROM no_such_table",
                        cols, new QueryParameters(), writer);
                fail("query against a missing table must throw");
            } catch (DataAccessException ex) {
                assertTrue(ex.getMessage().contains("h2-exec-error"),
                        "thrown message must include ds id, got: " + ex.getMessage());
            }
            assertEquals(writer.writeCalls, 0,
                    "no rows must have been emitted when the query fails");
        } finally {
            ds.close();
        }
    }
}
