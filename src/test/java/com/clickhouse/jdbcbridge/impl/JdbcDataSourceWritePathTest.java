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
import static org.testng.Assert.fail;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.UUID;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.ByteBuffer;
import com.clickhouse.jdbcbridge.core.ColumnDefinition;
import com.clickhouse.jdbcbridge.core.DataAccessException;
import com.clickhouse.jdbcbridge.core.DataType;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.QueryParameters;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.core.ResponseWriter;
import com.clickhouse.jdbcbridge.core.TableDefinition;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link JdbcDataSource} mutation path (writeMutationResult) and
 * bare-table-name short-circuit in writeQueryResult.
 */
public class JdbcDataSourceWritePathTest {

    private String h2Url;

    @BeforeMethod(groups = { "unit" })
    public void setUp() throws Exception {
        ensureH2Driver();
        h2Url = "jdbc:h2:mem:writepath-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE items (id INT PRIMARY KEY, label VARCHAR(32))");
            s.execute("INSERT INTO items VALUES (1, 'alpha')");
            s.execute("INSERT INTO items VALUES (2, 'beta')");
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

    private static QueryParameters mutationParams() {
        QueryParameters p = new QueryParameters();
        p.merge(new JsonObject().put(QueryParameters.PARAM_MUTATION, true));
        return p;
    }

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    @Test(groups = { "unit" })
    public void mutation_insertReportsAffectedRowsAndPersistsRow() throws Exception {
        JdbcDataSource ds = new JdbcDataSource("h2-mut-insert", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("type", DataType.Str),
                    col("rows", DataType.UInt64));
            Capture w = new Capture();

            ds.executeQuery("",
                    "INSERT INTO items VALUES (3, 'gamma')",
                    "INSERT INTO items VALUES (3, 'gamma')",
                    cols, mutationParams(), w);

            assertTrue(w.bytes > 0, "mutation must emit a result row with affected-rows count");

            // Constructor deregistered the H2 driver during Hikari init; restore before verify.
            ensureH2Driver();
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM items WHERE id = 3")) {
                rs.next();
                assertEquals(rs.getInt(1), 1, "INSERT did not persist a new row");
            }
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void mutation_updatePersistsAndReportsRows() throws Exception {
        JdbcDataSource ds = new JdbcDataSource("h2-mut-update", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("type", DataType.Str),
                    col("rows", DataType.UInt64));
            Capture w = new Capture();

            ds.executeQuery("",
                    "UPDATE items SET label = 'updated' WHERE id = 1",
                    "UPDATE items SET label = 'updated' WHERE id = 1",
                    cols, mutationParams(), w);

            assertTrue(w.bytes > 0);

            ensureH2Driver();
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                    Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("SELECT label FROM items WHERE id = 1")) {
                rs.next();
                assertEquals(rs.getString(1), "updated");
            }
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void mutation_badSqlSurfacesAsDataAccessExceptionFromMutationPath() {
        JdbcDataSource ds = new JdbcDataSource("h2-mut-error", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("type", DataType.Str),
                    col("rows", DataType.UInt64));
            Capture w = new Capture();

            try {
                ds.executeQuery("",
                        "DELETE FROM nonexistent_table",
                        "DELETE FROM nonexistent_table",
                        cols, mutationParams(), w);
                fail("expected mutation against a missing table to throw");
            } catch (DataAccessException e) {
                // writeMutationResult wraps SQLException as DAE with ds id in message.
                assertTrue(e.getMessage().contains("h2-mut-error"),
                        "thrown message must include ds id: " + e.getMessage());
            }
            assertEquals(w.writes, 0,
                    "failed mutation must not have flushed bytes to the writer");
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void queryByBareTableName_buildsSelectFromAndStreams() {
        // loadedQuery with no whitespace -> treated as table name, "SELECT cols FROM `<table>`".
        // H2 PG mode uppercases unquoted identifiers -> use ITEMS.
        JdbcDataSource ds = new JdbcDataSource("h2-bare-table", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(
                    col("ID", DataType.Int32),
                    col("LABEL", DataType.Str));
            Capture w = new Capture();

            ds.executeQuery("", "ITEMS", "ITEMS", cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "bare-table-name path must stream rows; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void queryByBareTable_schemaPrefixIsHonored() {
        // schema non-empty + whitespace-free -> bridge wraps as `<schema>`.`<table>`.
        JdbcDataSource ds = new JdbcDataSource("h2-schema-prefix", repo(), baseConfig());
        try {
            TableDefinition cols = new TableDefinition(col("ID", DataType.Int32));
            Capture w = new Capture();

            ds.executeQuery("PUBLIC", "ITEMS", "ITEMS", cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0,
                    "schema-prefixed bare-table-name path must succeed; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }
}
