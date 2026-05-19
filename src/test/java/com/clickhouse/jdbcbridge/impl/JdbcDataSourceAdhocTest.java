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
 * Tests for {@link JdbcDataSource} adhoc path (jdbc-prefixed id + null config)
 * and error-message formatting via buildErrorMessage.
 */
public class JdbcDataSourceAdhocTest {

    private String h2Url;

    @BeforeMethod(groups = { "unit" })
    public void setUp() throws Exception {
        ensureH2Driver();
        // Adhoc path passes DriverManager an empty Properties — URL must carry credentials.
        h2Url = "jdbc:h2:mem:adhoc-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;USER=sa;PASSWORD=";
        try (Connection conn = DriverManager.getConnection(h2Url);
                Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE t (id INT, label VARCHAR(32))");
            s.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
        }
    }

    @AfterMethod(groups = { "unit" })
    public void tearDown() throws Exception {
        try {
            ensureH2Driver();
            try (Connection conn = DriverManager.getConnection(h2Url);
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

    @Test(groups = { "unit" })
    public void adhocCtor_idStartingWithJdbcAndNullConfig_skipsHikariInit() {
        // Adhoc: DataSource field is null, getConnection goes through findDriver+connect directly.
        JdbcDataSource ds = new JdbcDataSource(h2Url, repo(), null);

        assertEquals(ds.getId(), h2Url);
        assertEquals(ds.getPoolUsage(), "{}");
        ds.close();
    }

    @Test(groups = { "unit" })
    public void adhocCtor_actuallyServicesQueries() throws Exception {
        JdbcDataSource ds = new JdbcDataSource(h2Url, repo(), null);
        try {
            TableDefinition cols = new TableDefinition(
                    new ColumnDefinition("ID", DataType.Int32, false,
                            DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE));
            CapturingResponseWriter w = new CapturingResponseWriter();

            ds.executeQuery("",
                    "SELECT id FROM t ORDER BY id",
                    "SELECT id FROM t ORDER BY id",
                    cols, new QueryParameters(), w);

            assertTrue(w.bytes > 0, "adhoc ds must stream bytes; writes=" + w.writes);
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void mutationError_propagatesSqlStateAndVendorCodeIntoMessage() {
        // buildErrorMessage formats as "SQLState(...) VendorCode(...) <message>".
        // Exercised indirectly: writeMutationResult wraps SQLException in DataAccessException.
        JdbcDataSource ds = new JdbcDataSource("adhoc-err", repo(),
                new JsonObject()
                        .put("driverClassName", "org.h2.Driver")
                        .put("jdbcUrl", h2Url)
                        .put("username", "sa").put("password", ""));
        try {
            CapturingResponseWriter w = new CapturingResponseWriter();
            QueryParameters mutation = new QueryParameters();
            mutation.merge(new JsonObject().put(QueryParameters.PARAM_MUTATION, true));

            try {
                ds.executeQuery("",
                        "DELETE FROM nonexistent",
                        "DELETE FROM nonexistent",
                        new TableDefinition(new ColumnDefinition("type", DataType.Str, false,
                                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE)),
                        mutation, w);
                fail("expected DataAccessException for missing table");
            } catch (DataAccessException e) {
                String msg = e.getMessage();
                // Don't pin exact H2 codes (may change between versions); just verify formatting.
                assertTrue(msg.contains("SQLState"),
                        "DAE message must include SQLState prefix from buildErrorMessage: " + msg);
                assertTrue(msg.contains("VendorCode"),
                        "DAE message must include VendorCode prefix: " + msg);
            }
        } finally {
            ds.close();
        }
    }

    @Test(groups = { "unit" })
    public void adhocCtor_handlesDriverThatAcceptsAutoCommitTrue() throws Exception {
        // Adhoc path calls conn.setAutoCommit(true) in try/catch — pin it never throws.
        JdbcDataSource ds = new JdbcDataSource(h2Url, repo(), null);
        try {
            TableDefinition cols = new TableDefinition(
                    new ColumnDefinition("ID", DataType.Int32, false,
                            DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE));
            CapturingResponseWriter w = new CapturingResponseWriter();
            ds.executeQuery("", "SELECT id FROM t", "SELECT id FROM t",
                    cols, new QueryParameters(), w);
            assertTrue(w.bytes > 0);
        } finally {
            ds.close();
        }
    }

    static final class CapturingResponseWriter extends ResponseWriter {
        int writes;
        long bytes;

        @Override public boolean isOpen() { return true; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public void write(ByteBuffer buffer) {
            writes++;
            if (buffer != null) bytes += buffer.length();
        }
    }
}
