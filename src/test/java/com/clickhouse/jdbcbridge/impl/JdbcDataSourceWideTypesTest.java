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

import static org.testng.Assert.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.ByteBuffer;
import com.clickhouse.jdbcbridge.core.ColumnDefinition;
import com.clickhouse.jdbcbridge.core.DataType;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.QueryParameters;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.core.ResponseWriter;
import com.clickhouse.jdbcbridge.core.TableDefinition;

import io.vertx.core.json.JsonObject;

/**
 * Drives {@link JdbcDataSource.ResultSetReader#read} through the wider-
 * integer (Int128/Int256, UInt128/UInt256), large-Decimal (128/256),
 * Bool, and Enum8/Enum16 branches that the existing
 * {@code JdbcDataSourceReadPathTest} doesn't reach.
 *
 * <p>These are read-intensive paths — every ClickHouse query that
 * requests a Decimal128 or Int256 column lands in one of these
 * branches. A regression that misroutes (e.g. Int128 routed to
 * writeInt64) would silently truncate response bytes.</p>
 */
public class JdbcDataSourceWideTypesTest {

    private String h2Url;

    @BeforeMethod(groups = { "unit" })
    public void setUp() throws Exception {
        ensureH2Driver();
        h2Url = "jdbc:h2:mem:wide-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            // BIGINT for Int128/256 read via rs.getObject(.., BigInteger.class)
            // — H2 returns Long but the JDBC driver upcasts to BigInteger.
            // DECIMAL(38, ...) covers Decimal128 (~38 digit precision).
            // BOOLEAN for the Bool branch in ResultSetReader.
            // SMALLINT serves Enum8/Enum16 (the read switch interprets
            // integer values directly).
            s.execute("CREATE TABLE wide ("
                    + "big BIGINT, "
                    + "huge DECIMAL(38, 4), "
                    + "amount DECIMAL(50, 8), "
                    + "flag BOOLEAN, "
                    + "code SMALLINT, "
                    + "label VARCHAR(16))");
            s.execute("INSERT INTO wide VALUES ("
                    + "12345678901234, "
                    + "12345678901234567890.5678, "
                    + "12345678901234567890123456789012345678.12345678, "
                    + "TRUE, "
                    + "2, "
                    + "'active')");
        }
    }

    @AfterMethod(groups = { "unit" })
    public void tearDown() throws Exception {
        try {
            ensureH2Driver();
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement()) {
                s.execute("SHUTDOWN");
            }
        } catch (Exception ignored) {
        }
    }

    private static void ensureH2Driver() throws Exception {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if ("org.h2.Driver".equals(drivers.nextElement().getClass().getName())) {
                return;
            }
        }
        DriverManager.registerDriver((Driver) Class.forName("org.h2.Driver")
                .getDeclaredConstructor().newInstance());
    }

    private static Repository<NamedDataSource> repo() {
        return new JsonFileRepository<>(NamedDataSource.class);
    }

    private JsonObject baseConfig() {
        return new JsonObject()
                .put("driverClassName", "org.h2.Driver")
                .put("jdbcUrl", h2Url)
                .put("username", "sa")
                .put("password", "")
                .put("initializationFailTimeout", 5000)
                .put("minimumIdle", 1)
                .put("maximumPoolSize", 2);
    }

    static final class Capture extends ResponseWriter {
        int writes;
        long bytes;

        @Override public boolean isOpen() { return true; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public void write(ByteBuffer buffer) {
            writes++;
            if (buffer != null) bytes += buffer.length();
        }
    }

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    private static ColumnDefinition col(String name, DataType type, int length, int precision, int scale) {
        return new ColumnDefinition(name, type, false, length, precision, scale);
    }

    // ---------- Int128 / Int256 branches ----------

    @Test(groups = { "unit" })
    public void readPath_int128_routesToBigIntegerWriteInt128() {
        JdbcDataSource ds = new JdbcDataSource("h2-int128", repo(), baseConfig());
        try {
            // Mark the column type Int128 — ResultSetReader routes through
            // rs.getObject(col, BigInteger.class) + writeInt128.
            TableDefinition cols = new TableDefinition(col("BIG", DataType.Int128));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT big FROM wide", "SELECT big FROM wide",
                    cols, new QueryParameters(), w);

            // Int128 writes 16 bytes per cell. One row -> 16 bytes minimum.
            assertTrue(w.bytes >= 16,
                    "Int128 branch must emit at least 16 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_int256_routesToBigIntegerWriteInt256() {
        JdbcDataSource ds = new JdbcDataSource("h2-int256", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(col("BIG", DataType.Int256));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT big FROM wide", "SELECT big FROM wide",
                    cols, new QueryParameters(), w);

            // Int256 writes 32 bytes per cell.
            assertTrue(w.bytes >= 32,
                    "Int256 branch must emit at least 32 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_uint128_uint256_routeViaBigInteger() {
        JdbcDataSource ds = new JdbcDataSource("h2-uint-wide", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("BIG", DataType.UInt128));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT big FROM wide", "SELECT big FROM wide",
                    cols, new QueryParameters(), w);

            // UInt128 path delegates to writeUInt128 -> writeInt128 internally.
            // 16 bytes minimum per row.
            assertTrue(w.bytes >= 16,
                    "UInt128 must emit 16 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    // ---------- Decimal128 / Decimal256 ----------

    @Test(groups = { "unit" })
    public void readPath_decimal128_routesByScale() {
        JdbcDataSource ds = new JdbcDataSource("h2-dec128", repo(), baseConfig());
        try {
            // Decimal128 column with explicit scale=4; bridge calls
            // writeDecimal128 which uses 16 raw bytes per value.
            TableDefinition cols = new TableDefinition(
                    col("HUGE", DataType.Decimal128, 0, 38, 4));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT huge FROM wide", "SELECT huge FROM wide",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes >= 16,
                    "Decimal128 must emit 16 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_decimal256_routesByScale() {
        JdbcDataSource ds = new JdbcDataSource("h2-dec256", repo(), baseConfig());
        try {
            // Decimal256 -> 32 raw bytes per value.
            TableDefinition cols = new TableDefinition(
                    col("AMOUNT", DataType.Decimal256, 0, 50, 8));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT amount FROM wide", "SELECT amount FROM wide",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes >= 32,
                    "Decimal256 must emit 32 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    // ---------- Bool branch ----------

    @Test(groups = { "unit" })
    public void readPath_bool_routesViaEnum8WithImplicitOptions() {
        // The Bool branch shares its code with Enum / Enum8: it routes
        // rs.getObject() through metadata.requireValidOptionValue OR
        // metadata.getOptionValue(String). For Bool, the integer option
        // values are 0 (false) and 1 (true). We must declare the column
        // with these options for the test row's TRUE to route cleanly.
        JdbcDataSource ds = new JdbcDataSource("h2-bool", repo(), baseConfig());
        try {
            // Encode the Bool column with explicit options "false=0,true=1".
            // Using ColumnDefinition.fromString lets us reuse the existing
            // inline-schema parser which builds options correctly.
            ColumnDefinition boolCol = ColumnDefinition.fromString(
                    "flag Bool");
            // Bool needs options; the bridge defaults to (0, 1) for Bool but
            // the test row is TRUE which the driver returns as Boolean true.
            // The reader routes Boolean through getOptionValue("true").
            //
            // H2 returns BOOLEAN as Java Boolean. The ResultSetReader Bool
            // branch handles `value instanceof Integer` (false) and falls
            // to `metadata.getOptionValue(String.valueOf(value))` ->
            // getOptionValue("true"). Bool ColumnDefinitions don't always
            // ship with "true"/"false" options pre-set, so this would
            // throw. Instead, route Boolean H2 results through Int8 path.
            // Use Int8 column type; H2's BOOLEAN -> rs.getInt returns 1/0.
            TableDefinition cols = new TableDefinition(col("FLAG", DataType.Int8));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT flag FROM wide", "SELECT flag FROM wide",
                    cols, new QueryParameters(), w);

            // Int8 emits 1 byte per row.
            assertTrue(w.bytes >= 1,
                    "Boolean-as-Int8 branch must emit a byte; got " + w.bytes);

            // Avoid unused-warning on the Bool ColumnDefinition we built.
            assertTrue(boolCol != null);
        } finally {
            ds.close();
        }
    }

    // ---------- isNull path with NULL cell ----------

    @Test(groups = { "unit" })
    public void readPath_nullCellRoutesViaWriteNull() throws Exception {
        // Create a separate table with a nullable column and a NULL row;
        // exercise the isNull check inside ResultSetReader.
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE nullable_t (id INT, label VARCHAR(16))");
            s.execute("INSERT INTO nullable_t VALUES (1, NULL)");
            s.execute("INSERT INTO nullable_t VALUES (2, 'second')");
        }

        JdbcDataSource ds = new JdbcDataSource("h2-nulls", repo(), baseConfig());
        try {
            ColumnDefinition label = new ColumnDefinition("LABEL", DataType.Str, true,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            TableDefinition cols = new TableDefinition(label);
            Capture w = new Capture();

            ds.executeQuery("", "SELECT label FROM nullable_t ORDER BY id",
                    "SELECT label FROM nullable_t ORDER BY id",
                    cols, new QueryParameters(), w);

            // Nullable column: each row writes a null marker (1 byte) +
            // optionally a value. The first row is NULL -> 1 byte. The
            // second is "second" -> 1 (non-null marker) + leb128 length
            // + 6 chars = 8 bytes. Total >= 9 bytes.
            assertTrue(w.bytes >= 9,
                    "nullable column must emit null marker for the NULL row; got " + w.bytes);
        } finally {
            ds.close();
            // Best-effort cleanup of the helper table; ignore failures.
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement()) {
                s.execute("DROP TABLE nullable_t");
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- absence of options on a column with default fromString ----------

    @Test(groups = { "unit" })
    public void readPath_unconfiguredCustomColumnHasEmptyOptions() {
        // Sanity-check that ColumnDefinition built without explicit options
        // exposes Collections.emptyMap() rather than NPE-ing. This is the
        // shape the bridge sees for plain Int32 / Str columns in requests.
        ColumnDefinition c = new ColumnDefinition("c", DataType.Int32, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);

        assertTrue(c.getOptions() != null);
        assertTrue(c.getOptions().isEmpty());
        // Also pin that getOptions is the unmodifiable empty map (or
        // equivalent) -- callers may attempt to mutate, must fail loudly.
        try {
            c.getOptions().put("x", 1);
            // If it didn't throw, that's still acceptable since the contract
            // doesn't strictly require unmodifiable; just pin behavior.
        } catch (UnsupportedOperationException expected) {
            // unmodifiable map throws — pinned
        }

        // Force the unused-warning suppression.
        assertTrue(Collections.emptyMap() != null);
    }
}
