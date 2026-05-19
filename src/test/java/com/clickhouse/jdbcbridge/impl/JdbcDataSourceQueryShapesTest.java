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

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
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
import com.clickhouse.jdbcbridge.core.Utils;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link JdbcDataSource}'s remaining writeQueryResult shape
 * branches: the DEFAULT_RESULT_COLUMNS short-circuit (when the caller
 * passes the singleton schema, the bridge swaps in the actual ResultSet
 * metadata), the scrollable-cursor branch (driven by
 * QueryParameters.position != 0), max_rows plumbing, and the adhoc
 * unknown-driver error path.
 */
public class JdbcDataSourceQueryShapesTest {

    private String h2Url;

    @BeforeMethod(groups = { "unit" })
    public void setUp() throws Exception {
        ensureH2Driver();
        h2Url = "jdbc:h2:mem:shapes-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE rows_t (id INT, label VARCHAR(16))");
            s.execute("INSERT INTO rows_t VALUES (1, 'one'), (2, 'two'), (3, 'three'), (4, 'four'), (5, 'five')");
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

    // ---------- DEFAULT_RESULT_COLUMNS short-circuit ----------

    @Test(groups = { "unit" })
    public void writeQueryResult_defaultResultColumns_substitutesResultMetadata() {
        // When the caller passes the DEFAULT_RESULT_COLUMNS sentinel (a
        // single-column TableDefinition named Utils.DEFAULT_COLUMN_NAME),
        // writeQueryResult MUST swap in the actual result-set metadata so
        // all real columns get streamed. Without this branch the bridge
        // would only emit the placeholder column. Triggered when ClickHouse
        // sends a query with no explicit columns header.
        JdbcDataSource ds = new JdbcDataSource("h2-default-cols", repo(), baseConfig());
        try {
            // Sanity-pin the sentinel shape that writeQueryResult checks for.
            assertTrue(TableDefinition.DEFAULT_RESULT_COLUMNS.size() == 1);
            assertTrue(Utils.DEFAULT_COLUMN_NAME.equals(
                    TableDefinition.DEFAULT_RESULT_COLUMNS.getColumn(0).getName()));

            Capture w = new Capture();
            ds.executeQuery("",
                    "SELECT id, label FROM rows_t ORDER BY id",
                    "SELECT id, label FROM rows_t ORDER BY id",
                    TableDefinition.DEFAULT_RESULT_COLUMNS,
                    new QueryParameters(), w);

            // 5 rows × 2 columns -> non-trivial bytes. Pinning > 5*4 (=20)
            // is enough to prove the substitution streamed both columns
            // and not just the placeholder.
            assertTrue(w.bytes > 5 * 4,
                    "DEFAULT_RESULT_COLUMNS substitution must emit both id + label; got " + w.bytes);
        } finally {
            ds.close();
        }
    }

    // ---------- scrollable cursor ----------

    @Test(groups = { "unit" })
    public void writeQueryResult_positionMode_scrollsToAbsoluteRow() {
        // QueryParameters.position > 0 routes createStatement through
        // ResultSet.TYPE_SCROLL_INSENSITIVE so skipRows can do absolute().
        // Without scroll support, JDBC throws. Pin that the path works.
        JdbcDataSource ds = new JdbcDataSource("h2-scroll", repo(), baseConfig());
        try {
            ColumnDefinition idCol = new ColumnDefinition("ID", DataType.Int32, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            TableDefinition cols = new TableDefinition(idCol);

            QueryParameters params = new QueryParameters("?position=3");

            Capture w = new Capture();
            ds.executeQuery("",
                    "SELECT id FROM rows_t ORDER BY id",
                    "SELECT id FROM rows_t ORDER BY id",
                    cols, params, w);

            assertTrue(w.bytes > 0,
                    "scrollable cursor must complete without throwing; bytes=" + w.bytes);
        } finally {
            ds.close();
        }
    }

    // ---------- max_rows ----------

    @Test(groups = { "unit" })
    public void writeQueryResult_maxRowsCapsStream() {
        JdbcDataSource ds = new JdbcDataSource("h2-maxrows", repo(), baseConfig());
        try {
            ColumnDefinition idCol = new ColumnDefinition("ID", DataType.Int32, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            TableDefinition cols = new TableDefinition(idCol);

            Capture all = new Capture();
            ds.executeQuery("", "SELECT id FROM rows_t ORDER BY id",
                    "SELECT id FROM rows_t ORDER BY id",
                    cols, new QueryParameters(), all);

            QueryParameters capped = new QueryParameters();
            capped.merge(new JsonObject().put(QueryParameters.PARAM_MAX_ROWS, 2));
            Capture limited = new Capture();
            ds.executeQuery("", "SELECT id FROM rows_t ORDER BY id",
                    "SELECT id FROM rows_t ORDER BY id",
                    cols, capped, limited);

            assertTrue(limited.bytes < all.bytes,
                    "max_rows=2 must emit fewer bytes (all=" + all.bytes
                            + ", limited=" + limited.bytes + ")");
        } finally {
            ds.close();
        }
    }

    // ---------- adhoc with no driver match ----------

    @Test(groups = { "unit" })
    public void adhoc_unknownJdbcUrlThrowsRuntimeException() {
        // Adhoc construction with a URL that no ServiceLoader-discovered
        // Driver claims. findDriver throws IllegalStateException — bubbles
        // out of getConnection / executeQuery. Pin that the failure mode
        // is a RuntimeException (caller catches generically).
        JdbcDataSource ds = new JdbcDataSource("jdbc:nope-does-not-exist://wat",
                repo(), null);
        try {
            ColumnDefinition c = new ColumnDefinition("c", DataType.Str, true,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
            TableDefinition cols = new TableDefinition(c);
            Capture w = new Capture();

            assertThrows(RuntimeException.class,
                    () -> ds.executeQuery("", "SELECT 1", "SELECT 1",
                            cols, new QueryParameters(), w));
        } finally {
            ds.close();
        }
    }

    // ---------- getQuoteIdentifier ----------

    @Test(groups = { "unit" })
    public void getQuoteIdentifier_returnsNonEmptyString() {
        // The cached identifier-quote getter falls back to the default
        // backtick when uncached; with H2 connected it reflects the JDBC
        // metadata. Just pin non-empty.
        JdbcDataSource ds = new JdbcDataSource("h2-quote", repo(), baseConfig());
        try {
            ds.getResultColumns("", "SELECT 1", new QueryParameters());

            String quote = ds.getQuoteIdentifier();
            assertNotNull(quote);
            assertTrue(quote.length() > 0);
        } finally {
            ds.close();
        }
    }
}
