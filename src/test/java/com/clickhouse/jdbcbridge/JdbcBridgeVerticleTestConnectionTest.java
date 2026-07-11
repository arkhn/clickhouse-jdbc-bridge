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
package com.clickhouse.jdbcbridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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

import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.impl.JsonFileRepository;

import io.vertx.core.json.JsonObject;

/**
 * Tests {@link JdbcBridgeVerticle#testDatasource} (the pure core of POST /test)
 * end-to-end against in-process H2 — no HttpServer needed. The request/response
 * shape is a datasource entity in, {ok, code, message} out.
 */
public class JdbcBridgeVerticleTestConnectionTest {

    private String jdbcUrl;

    @BeforeMethod(groups = { "unit" })
    public void perTestDatabase() throws Exception {
        // JdbcDataSource deregisters the driver during init; re-register each time.
        boolean registered = false;
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if ("org.h2.Driver".equals(drivers.nextElement().getClass().getName())) {
                registered = true;
                break;
            }
        }
        if (!registered) {
            DriverManager.registerDriver((Driver) Class.forName("org.h2.Driver")
                    .getDeclaredConstructor().newInstance());
        }

        jdbcUrl = "jdbc:h2:mem:testconn-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        // Seed connection fixes the sa/"" credentials for the in-memory DB.
        try (Connection seed = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement s = seed.createStatement()) {
            s.execute("SELECT 1");
        }
    }

    @AfterMethod(groups = { "unit" })
    public void tearDown() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, "sa", "");
                Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        } catch (Exception ignored) {
        }
    }

    private static Repository<NamedDataSource> repo() {
        return new JsonFileRepository<>(NamedDataSource.class);
    }

    private JsonObject entity(String username, String password) {
        return new JsonObject()
                .put("driverClassName", "org.h2.Driver")
                .put("jdbcUrl", jdbcUrl)
                .put("username", username)
                .put("password", password);
    }

    @Test(groups = { "unit" })
    public void test_ok_forReachableDatasource() {
        JsonObject result = JdbcBridgeVerticle.testDatasource(repo(), entity("sa", ""));
        assertTrue(result.getBoolean("ok"), result.encode());
        assertEquals(result.getString("code"), "ok");
        assertEquals(result.getString("message"), "Connection successful.");
    }

    @Test(groups = { "unit" })
    public void test_authFailure_isClassified() {
        // H2 rejects a wrong password instantly with "Wrong user name or password".
        JsonObject result = JdbcBridgeVerticle.testDatasource(repo(), entity("sa", "wrong-pass"));
        assertFalse(result.getBoolean("ok"), result.encode());
        assertEquals(result.getString("code"), "auth");
    }

    @Test(groups = { "unit" })
    public void test_doesNotMutateCallerConfig() {
        JsonObject in = entity("sa", "");
        JdbcBridgeVerticle.testDatasource(repo(), in);
        // the test-friendly pool overrides are applied to a copy, not the input
        assertFalse(in.containsKey("maximumPoolSize"));
        assertFalse(in.containsKey("initializationFailTimeout"));
    }

    @Test(groups = { "unit" })
    public void test_neverReturnsRawExceptionOrSecret() {
        JsonObject result = JdbcBridgeVerticle.testDatasource(repo(), entity("sa", "wrong-pass"));
        String msg = result.getString("message");
        assertFalse(msg.toLowerCase().contains("wrong-pass"));
        assertFalse(msg.toLowerCase().contains("h2"));
        assertFalse(msg.contains("Exception"));
    }
}
