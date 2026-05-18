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
import static org.testng.Assert.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * Drives {@link JdbcDataSource#executeQuery} through the per-DataType
 * branches of {@link JdbcDataSource.ResultSetReader#read}. The vanilla
 * {@code JdbcDataSourceH2Test} only exercises a few common types (Int32,
 * Str, Decimal, Bool); this file walks the wider matrix — Int8/16/64,
 * Float32/64, Date, DateTime, DateTime64, FixedStr — so a regression in
 * the read switch isn't invisible.
 *
 * <p>The read path is the bridge's hot read-intensive code: every byte
 * streamed back to ClickHouse routes through one of these branches.</p>
 */
public class JdbcDataSourceReadPathTest {

    private String h2Url;

    @BeforeMethod(groups = { "unit" })
    public void perTestDatabase() throws Exception {
        ensureH2DriverRegistered();
        h2Url = "jdbc:h2:mem:readpath-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE t ("
                    + "i8 SMALLINT, "         // H2 lacks TINYINT; SMALLINT serves
                    + "i16 SMALLINT, "
                    + "i32 INT, "
                    + "i64 BIGINT, "
                    + "f32 REAL, "
                    + "f64 DOUBLE PRECISION, "
                    + "dec32 DECIMAL(9,2), "
                    + "dec64 DECIMAL(18,4), "
                    + "dt DATE, "
                    + "ts TIMESTAMP, "
                    + "fixed VARCHAR(8), "
                    + "txt VARCHAR(255))");
            s.execute("INSERT INTO t VALUES ("
                    + "127, 32000, 2000000, 9000000000, "
                    + "1.5, 2.25, "
                    + "1234.56, 99999999.1234, "
                    + "DATE '2026-05-19', TIMESTAMP '2026-05-19 12:00:00', "
                    + "'abc', 'hello world')");
        }
    }

    @AfterMethod(groups = { "unit" })
    public void tearDownDatabase() throws Exception {
        try {
            ensureH2DriverRegistered();
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement()) {
                s.execute("SHUTDOWN");
            }
        } catch (Exception ignored) {
        }
    }

    private static void ensureH2DriverRegistered() throws Exception {
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

    /** Captures total bytes written so we can assert "the read switch
     *  actually wrote something" without parsing RowBinary. */
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
    public void readPath_integerBranches() {
        JdbcDataSource ds = new JdbcDataSource("h2-ints", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("I8", DataType.Int8),
                    col("I16", DataType.Int16),
                    col("I32", DataType.Int32),
                    col("I64", DataType.Int64),
                    col("F32", DataType.Float32),
                    col("F64", DataType.Float64));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT i8, i16, i32, i64, f32, f64 FROM t",
                    "SELECT i8, i16, i32, i64, f32, f64 FROM t",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0,
                    "integer/float branches must emit non-empty RowBinary bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_dateAndDateTimeBranches() {
        JdbcDataSource ds = new JdbcDataSource("h2-dates", repo(), baseConfig());
        try {
            // Column names must match the SELECT projection's column labels:
            // bridge does name-based binding in DataTableReader.process.
            TableDefinition cols = new TableDefinition(
                    col("DT", DataType.Date),
                    col("TS", DataType.DateTime),
                    col("TS64", DataType.DateTime64, 0, 0, 3));
            Capture w = new Capture();

            ds.executeQuery("",
                    "SELECT dt AS dt, ts AS ts, ts AS ts64 FROM t",
                    "SELECT dt AS dt, ts AS ts, ts AS ts64 FROM t",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "date/datetime branches must emit bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_decimalBranches() {
        JdbcDataSource ds = new JdbcDataSource("h2-decs", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("DEC32", DataType.Decimal32, 0, 9, 2),
                    col("DEC64", DataType.Decimal64, 0, 18, 4),
                    col("DEC", DataType.Decimal, 0, 18, 4));
            Capture w = new Capture();

            ds.executeQuery("",
                    "SELECT dec32 AS dec32, dec64 AS dec64, dec64 AS dec FROM t",
                    "SELECT dec32 AS dec32, dec64 AS dec64, dec64 AS dec FROM t",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "decimal branches must emit bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_stringAndFixedStringBranches() {
        JdbcDataSource ds = new JdbcDataSource("h2-strs", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    // FixedStr length=4 matches 'abc' + 1 NUL pad
                    col("FIXED", DataType.FixedStr, 4, 0, 0),
                    col("TXT", DataType.Str));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT fixed, txt FROM t", "SELECT fixed, txt FROM t",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "string branches must emit bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_unsignedIntegerBranches() {
        // UInt8/16/32/64 branches go through writeUInt* — exercise them by
        // requesting the same column data via unsigned column types.
        JdbcDataSource ds = new JdbcDataSource("h2-uints", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("I8", DataType.UInt8),
                    col("I16", DataType.UInt16),
                    col("I32", DataType.UInt32),
                    col("I64", DataType.UInt64));
            Capture w = new Capture();

            ds.executeQuery("", "SELECT i8, i16, i32, i64 FROM t",
                    "SELECT i8, i16, i32, i64 FROM t",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "unsigned int branches must emit bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void readPath_skipRowsHonorsOffset() {
        // skipRows pulls (offset) rows from the cursor before emitting. With
        // 3 rows seeded and offset=1, only 2 should be emitted.
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("INSERT INTO t VALUES (2, 2, 2, 2, 2.0, 2.0, 1.0, 1.0, "
                    + "DATE '2026-01-01', TIMESTAMP '2026-01-01 00:00:00', 'b', 'two')");
            s.execute("INSERT INTO t VALUES (3, 3, 3, 3, 3.0, 3.0, 1.0, 1.0, "
                    + "DATE '2026-02-01', TIMESTAMP '2026-02-01 00:00:00', 'c', 'three')");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        JdbcDataSource ds = new JdbcDataSource("h2-offset", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(col("I32", DataType.Int32));

            QueryParameters noOffset = new QueryParameters();
            Capture allRows = new Capture();
            ds.executeQuery("", "SELECT i32 FROM t ORDER BY i32",
                    "SELECT i32 FROM t ORDER BY i32", cols, noOffset, allRows);

            QueryParameters withOffset = new QueryParameters();
            withOffset.merge(new JsonObject().put(QueryParameters.PARAM_OFFSET, 1));
            Capture skipped = new Capture();
            ds.executeQuery("", "SELECT i32 FROM t ORDER BY i32",
                    "SELECT i32 FROM t ORDER BY i32", cols, withOffset, skipped);

            assertTrue(allRows.bytes > skipped.bytes,
                    "offset=1 must emit fewer bytes than no-offset (got allRows=" + allRows.bytes
                            + ", skipped=" + skipped.bytes + ")");
            // 3 rows of Int32 = 3 * 4 = 12 bytes (no leb128 prefix per row);
            // 2 rows = 8 bytes.
            assertEquals(skipped.bytes, allRows.bytes - 4,
                    "offset=1 must drop exactly one Int32 row (4 bytes)");
        } finally {
            ds.close();
        }
    }
}
