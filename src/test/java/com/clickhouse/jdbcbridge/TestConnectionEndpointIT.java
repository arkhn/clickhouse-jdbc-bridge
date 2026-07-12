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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.annotations.Test;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;

/**
 * Integration coverage for {@code POST /test} over real HTTP against a
 * testcontainers Postgres. Reuses {@link AbstractBridgeIT}'s harness (container
 * + bridge on a free port); the inherited smoke suite still runs, and these
 * methods add the /test endpoint contract on top.
 *
 * <p>The request body is a datasource entity — the exact JSON stored in Vault —
 * and the response is {@code {ok, code, message}}.</p>
 */
public class TestConnectionEndpointIT extends AbstractBridgeIT {

    @Override
    protected JdbcDatabaseContainer<?> createDatabaseContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
    }

    @Override
    protected String getDatasourceName() {
        return "testconn";
    }

    @Override
    protected void setupTestData(Connection conn) {
        // /test is stateless — the default smoke query (SELECT 1) needs no table.
    }

    @Test(groups = { "sit" })
    public void testEndpoint_okForReachableDatasource() throws Exception {
        JsonObject r = postTest(entity(dbContainer.getPassword()));
        assertTrue(r.getBoolean("ok"), r.encode());
        assertEquals(r.getString("code"), "ok");
        assertEquals(r.getString("message"), "Connection successful.");
    }

    @Test(groups = { "sit" })
    public void testEndpoint_authFailure() throws Exception {
        JsonObject r = postTest(entity("wrong-password"));
        assertFalse(r.getBoolean("ok"), r.encode());
        assertEquals(r.getString("code"), "auth");
        // no secret / raw exception leaks into the message
        assertFalse(r.getString("message").toLowerCase().contains("wrong-password"));
    }

    @Test(groups = { "sit" })
    public void testEndpoint_hostUnreachable() throws Exception {
        JsonObject e = new JsonObject()
                .put("driverClassName", dbContainer.getDriverClassName())
                .put("jdbcUrl", "jdbc:postgresql://localhost:1/nope")
                .put("username", "x")
                .put("password", "y");
        JsonObject r = postTest(e);
        assertFalse(r.getBoolean("ok"), r.encode());
        assertEquals(r.getString("code"), "host");
    }

    @Test(groups = { "sit" })
    public void testEndpoint_dnsResolutionFailure() throws Exception {
        // A host name that cannot resolve (.invalid is reserved by RFC 2606, so
        // it never resolves) exercises the DNS-failure path end-to-end.
        JsonObject e = new JsonObject()
                .put("driverClassName", dbContainer.getDriverClassName())
                .put("jdbcUrl", "jdbc:postgresql://no-such-host.invalid:5432/nope")
                .put("username", "x")
                .put("password", "y");
        JsonObject r = postTest(e);
        assertFalse(r.getBoolean("ok"), r.encode());
        assertEquals(r.getString("code"), "host");
    }

    @Test(groups = { "sit" })
    public void testEndpoint_connectTimeout() throws Exception {
        // A routable-but-unresponsive address (TEST-NET-1, RFC 5737) never
        // answers the TCP SYN, so the driver hits the connect timeout. A short
        // connectionTimeout keeps the test fast; the outcome is still "host".
        JsonObject e = new JsonObject()
                .put("driverClassName", dbContainer.getDriverClassName())
                .put("jdbcUrl", "jdbc:postgresql://192.0.2.1:5432/nope")
                .put("username", "x")
                .put("password", "y")
                .put("connectionTimeout", 3000);
        JsonObject r = postTest(e);
        assertFalse(r.getBoolean("ok"), r.encode());
        assertEquals(r.getString("code"), "host");
    }

    /** The datasource entity as the admin builds it (and stores in Vault). */
    private JsonObject entity(String password) {
        return new JsonObject()
                .put("driverClassName", dbContainer.getDriverClassName())
                .put("jdbcUrl", dbContainer.getJdbcUrl())
                .put("username", dbContainer.getUsername())
                .put("password", password);
    }

    /** POST a JSON entity to /test and return the parsed {ok, code, message}. */
    private JsonObject postTest(JsonObject entity) throws Exception {
        HttpClient client = vertx.createHttpClient();
        CompletableFuture<HttpClientResponse> respFuture = new CompletableFuture<>();
        client.request(HttpMethod.POST, bridgePort, "localhost", "/test")
                .onSuccess(req -> req.putHeader("Content-Type", "application/json")
                        .send(entity.encode())
                        .onSuccess(respFuture::complete)
                        .onFailure(respFuture::completeExceptionally))
                .onFailure(respFuture::completeExceptionally);
        HttpClientResponse resp = respFuture.get(60, TimeUnit.SECONDS);
        assertEquals(resp.statusCode(), 200, "/test must always answer 200");

        CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        resp.body()
                .onSuccess(b -> bodyFuture.complete(b.toString()))
                .onFailure(bodyFuture::completeExceptionally);
        return new JsonObject(bodyFuture.get(60, TimeUnit.SECONDS));
    }
}
