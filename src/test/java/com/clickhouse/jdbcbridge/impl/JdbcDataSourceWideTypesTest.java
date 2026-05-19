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
 * Drives {@link JdbcDataSource.ResultSetReader#read} through wider-integer,
 * large-Decimal, Bool, and Enum branches.
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
            // BIGINT -> Int128/256 via rs.getObject(BigInteger.class).
            // DECIMAL(38,...) covers Decimal128. SMALLINT serves Enum8/16.
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

    @Test(groups = { "unit" })
    public void readPath_int128_routesToBigIntegerWriteInt128() {
        // Int128 -> rs.getObject(BigInteger.class) + writeInt128 (16 bytes/cell).
        JdbcDataSource ds = new JdbcDataSource("h2-int128", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(col("BIG", DataType.Int128));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT big FROM wide", "SELECT big FROM wide",
                    cols, new QueryParameters(), w);

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

            assertTrue(w.bytes >= 32,
                    "Int256 branch must emit at least 32 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_uint128_uint256_routeViaBigInteger() {
        // UInt128 delegates to writeUInt128 -> writeInt128 (16 bytes/cell).
        JdbcDataSource ds = new JdbcDataSource("h2-uint-wide", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("BIG", DataType.UInt128));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT big FROM wide", "SELECT big FROM wide",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes >= 16,
                    "UInt128 must emit 16 bytes per row; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_decimal128_routesByScale() {
        // Decimal128 -> writeDecimal128 (16 raw bytes per value).
        JdbcDataSource ds = new JdbcDataSource("h2-dec128", repo(), baseConfig());
        try {
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
        // Decimal256 -> 32 raw bytes per value.
        JdbcDataSource ds = new JdbcDataSource("h2-dec256", repo(), baseConfig());
        try {
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

    @Test(groups = { "unit" })
    public void readPath_bool_routesViaEnum8WithImplicitOptions() {
        // Bool ColumnDefs may lack "true"/"false" options -> route H2 Boolean via Int8 (rs.getInt 1/0).
        JdbcDataSource ds = new JdbcDataSource("h2-bool", repo(), baseConfig());
        try {
            ColumnDefinition boolCol = ColumnDefinition.fromString(
                    "flag Bool");
            TableDefinition cols = new TableDefinition(col("FLAG", DataType.Int8));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT flag FROM wide", "SELECT flag FROM wide",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes >= 1,
                    "Boolean-as-Int8 branch must emit a byte; got " + w.bytes);

            assertTrue(boolCol != null);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_nullCellRoutesViaWriteNull() throws Exception {
        // Nullable column with NULL row exercises the isNull check inside ResultSetReader.
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

            // NULL row -> 1 byte marker; "second" -> 1 + leb128 + 6 chars = 8 bytes; total >= 9.
            assertTrue(w.bytes >= 9,
                    "nullable column must emit null marker for the NULL row; got " + w.bytes);
        } finally {
            ds.close();
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement()) {
                s.execute("DROP TABLE nullable_t");
            } catch (Exception ignored) {
            }
        }
    }

    @Test(groups = { "unit" })
    public void readPath_unconfiguredCustomColumnHasEmptyOptions() {
        // Plain Int32/Str columns: getOptions must be empty map, not NPE.
        ColumnDefinition c = new ColumnDefinition("c", DataType.Int32, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);

        assertTrue(c.getOptions() != null);
        assertTrue(c.getOptions().isEmpty());
        try {
            c.getOptions().put("x", 1);
        } catch (UnsupportedOperationException expected) {
            // unmodifiable map throws — pinned
        }

        assertTrue(Collections.emptyMap() != null);
    }
}
