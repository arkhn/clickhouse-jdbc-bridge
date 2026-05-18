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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Reusable harness for per-backend integration tests. Spins up a JDBC database
 * container, wires it into a fresh {@code jdbc-bridge} instance running on a
 * free local port, and runs the standard smoke suite (ping + query). Subclasses
 * supply backend-specific bits via three small hooks.
 *
 * <p>This class deliberately does NOT spin up a ClickHouse container — the
 * goal here is to exercise the bridge's HTTP API against each backend. The
 * end-to-end CH→bridge→backend flow lives in {@link EndToEndIT}.</p>
 */
public abstract class AbstractBridgeIT {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AbstractBridgeIT.class);

    protected JdbcDatabaseContainer<?> dbContainer;
    protected Vertx vertx;
    protected String bridgeDeploymentId;
    protected int bridgePort;
    protected Path configRoot;

    // -- Subclass contract --

    /** Construct (but do not start) the backend database container. */
    protected abstract JdbcDatabaseContainer<?> createDatabaseContainer();

    /**
     * The identifier the bridge will use for this datasource (passed as
     * {@code connection_string=<name>} in inbound queries).
     */
    protected abstract String getDatasourceName();

    /** Seed the backend with whatever rows the test suite needs. */
    protected abstract void setupTestData(Connection conn) throws Exception;

    /** SQL the smoke test will run via the bridge. Must select &gt;0 bytes. */
    protected String smokeQuery() {
        return "SELECT 1";
    }

    /** Extra Hikari-style props baked into the datasource JSON. */
    protected JsonObject extraDatasourceProps() {
        return new JsonObject();
    }

    // -- Lifecycle --

    @BeforeClass(alwaysRun = true, groups = { "sit" })
    public void setupHarness() throws Exception {
        log.info("[{}] Starting backend container", getDatasourceName());
        dbContainer = createDatabaseContainer();
        dbContainer.start();

        log.info("[{}] Seeding test data", getDatasourceName());
        try (Connection conn = DriverManager.getConnection(
                dbContainer.getJdbcUrl(),
                dbContainer.getUsername(),
                dbContainer.getPassword())) {
            setupTestData(conn);
        }

        log.info("[{}] Writing bridge config", getDatasourceName());
        configRoot = Files.createTempDirectory("bridge-it-" + getDatasourceName() + "-");
        writeConfigFiles();

        log.info("[{}] Starting bridge on port {}", getDatasourceName(), bridgePort);
        startBridge();
        log.info("[{}] Bridge ready, warming up datasource", getDatasourceName());
        warmupDatasource();
        log.info("[{}] Datasource warmed up", getDatasourceName());
    }

    /**
     * Poll the datasource via the same code path the tests use until it both
     * returns HTTP 200 and produces a non-empty body. The bridge serves /ping
     * as soon as the HTTP server binds, but datasource JSON files are loaded
     * asynchronously by JsonFileRepository — so /ping == 200 does NOT imply
     * that {@code jdbc('mydb', ...)} can resolve the name yet. Without this
     * loop the first @Test method races the file scanner.
     *
     * The warmup uses the test's actual {@link #smokeQuery()} (rather than a
     * trivial SELECT 1) so the Hikari pool, the column inference path, and
     * the schema-cache are all primed by the time the first @Test runs.
     *
     * Requires {@code REQUIRED_OK} consecutive 200+non-empty responses before
     * declaring the datasource ready: in CI we've observed a single
     * post-cold-start success followed by an empty body on the very next
     * call (Hikari evicts a half-initialised connection? type-cache miss?),
     * and a single-shot warmup races right through it. Two successes in a
     * row is cheap and catches that window.
     */
    private void warmupDatasource() throws Exception {
        final int REQUIRED_OK = 2;
        int consecutiveOk = 0;
        int last4xx5xx = 0;
        Exception lastEx = null;
        String query = smokeQuery();
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                ResponseAndBody r = rawPostQueryWithStatus(getDatasourceName(), query);
                if (r.status == 200 && r.body != null && r.body.length() > 0) {
                    if (++consecutiveOk >= REQUIRED_OK) {
                        log.info("[{}] Datasource ready after {} warmup attempt(s)",
                                getDatasourceName(), attempt + 1);
                        return;
                    }
                } else {
                    consecutiveOk = 0;
                    last4xx5xx = r.status;
                }
            } catch (Exception e) {
                consecutiveOk = 0;
                lastEx = e;
            }
            Thread.sleep(500);
        }
        String msg = "Datasource [" + getDatasourceName() + "] not ready after warmup; "
                + "last status=" + last4xx5xx + ", consecutive successes=" + consecutiveOk;
        if (lastEx != null) {
            throw new IllegalStateException(msg + ", last error: " + lastEx, lastEx);
        }
        throw new IllegalStateException(msg);
    }

    private static final class ResponseAndBody {
        final int status;
        final String body;
        ResponseAndBody(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    @AfterClass(alwaysRun = true, groups = { "sit" })
    public void tearDownHarness() {
        if (vertx != null && bridgeDeploymentId != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.undeploy(bridgeDeploymentId, ar -> {
                if (ar.succeeded()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        if (vertx != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.close(ar -> {
                if (ar.succeeded()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        System.clearProperty("jdbc-bridge.config.dir");
        if (dbContainer != null) {
            dbContainer.stop();
        }
    }

    // -- Config & startup helpers --

    private void writeConfigFiles() throws IOException {
        // Allocate a free port HERE so we can bake it into server.json. The
        // actual `listen()` happens later in startBridge().
        bridgePort = pickFreePort();

        Path configDir = configRoot.resolve("config");
        Files.createDirectories(configDir);

        JsonObject server = new JsonObject()
                .put("requestTimeout", 5000)
                .put("queryTimeout", 60000)
                // 1s scan period so the IT's datasource JSON is picked up
                // promptly. The warmupDatasource() loop still bounds the wait.
                .put("configScanPeriod", 1000)
                .put("serverPort", bridgePort)
                .put("repositories", new JsonArray()
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedDataSource")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository"))
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedSchema")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository"))
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedQuery")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository")))
                .put("extensions", new JsonArray()
                        .add(new JsonObject().put("class", "com.clickhouse.jdbcbridge.impl.JdbcDataSource"))
                        .add(new JsonObject().put("class", "com.clickhouse.jdbcbridge.impl.ConfigDataSource")));
        Files.write(configDir.resolve("server.json"), server.encodePrettily().getBytes(StandardCharsets.UTF_8));

        Path datasourcesDir = configDir.resolve("datasources");
        Files.createDirectories(datasourcesDir);

        JsonObject dsProps = new JsonObject()
                .put("driverClassName", dbContainer.getDriverClassName())
                .put("jdbcUrl", dbContainer.getJdbcUrl())
                .put("username", dbContainer.getUsername())
                .put("password", dbContainer.getPassword())
                .put("initializationFailTimeout", 30000)
                .put("minimumIdle", 1)
                .put("maximumPoolSize", 5)
                .mergeIn(extraDatasourceProps());
        JsonObject wrapper = new JsonObject().put(getDatasourceName(), dsProps);
        Files.write(
                datasourcesDir.resolve(getDatasourceName() + ".json"),
                wrapper.encodePrettily().getBytes(StandardCharsets.UTF_8));

        JsonObject httpd = new JsonObject().put("maxInitialLineLength", 2147483647L);
        Files.write(configDir.resolve("httpd.json"), httpd.encodePrettily().getBytes(StandardCharsets.UTF_8));
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void startBridge() throws Exception {
        Path configSubDir = configRoot.resolve("config");
        System.setProperty("jdbc-bridge.config.dir", configSubDir.toAbsolutePath().toString());

        vertx = Vertx.vertx();
        CompletableFuture<String> deploy = new CompletableFuture<>();
        vertx.deployVerticle(new JdbcBridgeVerticle(), new DeploymentOptions(), ar -> {
            if (ar.succeeded()) {
                deploy.complete(ar.result());
            } else {
                deploy.completeExceptionally(ar.cause());
            }
        });
        bridgeDeploymentId = deploy.get(30, TimeUnit.SECONDS);

        // Wait for /ping to return 200.
        HttpClient client = vertx.createHttpClient();
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                CompletableFuture<Integer> ping = new CompletableFuture<>();
                client.request(HttpMethod.GET, bridgePort, "localhost", "/ping")
                        .onSuccess(req -> req.send().onSuccess(resp -> ping.complete(resp.statusCode()))
                                .onFailure(ping::completeExceptionally))
                        .onFailure(ping::completeExceptionally);
                if (ping.get(2, TimeUnit.SECONDS) == 200) {
                    return;
                }
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Bridge did not become ready on port " + bridgePort);
    }

    // -- Query helpers --

    /**
     * POST a form-encoded query to the bridge and return the raw response body
     * as a String. Useful for smoke / "did we get bytes" assertions; the body is
     * in the bridge's RowBinary format and not human-parseable. Asserts the
     * HTTP status is 200; for a retry-loop-friendly variant see
     * {@link #rawPostQuery(String, String)}.
     */
    protected String postQuery(String connectionString, String tableOrSql) throws Exception {
        return doPostQuery(connectionString, tableOrSql, true);
    }

    /**
     * Same as {@link #postQuery(String, String)} but does not assert 200; the
     * caller decides what to do with non-200 responses.
     */
    protected String rawPostQuery(String connectionString, String tableOrSql) throws Exception {
        return rawPostQueryWithStatus(connectionString, tableOrSql).body;
    }

    private ResponseAndBody rawPostQueryWithStatus(String connectionString, String tableOrSql)
            throws Exception {
        HttpClient client = vertx.createHttpClient();
        CompletableFuture<HttpClientResponse> respFuture = new CompletableFuture<>();
        String body = "connection_string=" + URLEncoder.encode(connectionString, "UTF-8")
                + "&table=" + URLEncoder.encode(tableOrSql, "UTF-8");
        client.request(HttpMethod.POST, bridgePort, "localhost", "/")
                .onSuccess(req -> {
                    req.putHeader("Content-Type", "application/x-www-form-urlencoded")
                            .send(body)
                            .onSuccess(respFuture::complete)
                            .onFailure(respFuture::completeExceptionally);
                })
                .onFailure(respFuture::completeExceptionally);

        HttpClientResponse resp = respFuture.get(30, TimeUnit.SECONDS);
        CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        resp.body()
                .onSuccess(b -> bodyFuture.complete(b.toString()))
                .onFailure(bodyFuture::completeExceptionally);
        return new ResponseAndBody(resp.statusCode(), bodyFuture.get(30, TimeUnit.SECONDS));
    }

    private String doPostQuery(String connectionString, String tableOrSql, boolean assert200)
            throws Exception {
        ResponseAndBody r = rawPostQueryWithStatus(connectionString, tableOrSql);
        if (assert200) {
            assertEquals(r.status, 200,
                    "Expected 200 from bridge for [" + tableOrSql + "]; body=" + r.body);
        }
        return r.body;
    }

    // -- Standard smoke tests --

    @Test(groups = { "sit" })
    public void testBridgePing() throws Exception {
        HttpClient client = vertx.createHttpClient();
        CompletableFuture<String> future = new CompletableFuture<>();
        client.request(HttpMethod.GET, bridgePort, "localhost", "/ping")
                .onSuccess(req -> req.send().onSuccess(resp -> resp.body()
                        .onSuccess(b -> future.complete(b.toString()))
                        .onFailure(future::completeExceptionally))
                        .onFailure(future::completeExceptionally))
                .onFailure(future::completeExceptionally);
        String body = future.get(10, TimeUnit.SECONDS);
        assertNotNull(body);
        assertTrue(body.contains("Ok."));
    }

    @Test(groups = { "sit" })
    public void testBridgeQueryReturnsBytes() throws Exception {
        String body = postQuery(getDatasourceName(), smokeQuery());
        assertNotNull(body);
        assertTrue(body.length() > 0, "expected non-empty response from [" + smokeQuery() + "]");
    }
}
