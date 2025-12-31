/*
 * Copyright 2019-2025, Zhichun Wu
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

import static org.testng.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;

public class JdbcBridgeIntegrationIT {
    private static final Network sharedNetwork = Network.newNetwork();
    
    private static MySQLContainer<?> mysqlContainer;
    private static GenericContainer<?> clickHouseContainer;
    
    private static Vertx vertx;
    private static String bridgeDeploymentId;
    private static Path configDir;
    private static int bridgePort = 9019;
    
    @BeforeClass(alwaysRun = true, groups = { "sit" })
    public static void setupContainers() throws Exception {
        try {
            System.out.println("Starting MySQL container...");
            // Start MySQL container
            mysqlContainer = new MySQLContainer<>("mysql:8.0")
                    .withNetwork(sharedNetwork)
                    .withNetworkAliases("mysql_server")
                    .withDatabaseName("testdb")
                    .withUsername("testuser")
                    .withPassword("testpass")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
            mysqlContainer.start();
            System.out.println("MySQL container started");

            // Setup test data in MySQL
            System.out.println("Setting up MySQL test data...");
            setupMySQLData();

            // Create config directory in target/test-classes/sit/jdbc-bridge (like existing test)
            System.out.println("Creating config files...");
            Path sitConfigDir = Paths.get("target/test-classes/sit/jdbc-bridge");
            Files.createDirectories(sitConfigDir);
            configDir = sitConfigDir;
            createConfigFiles();

            // Create ClickHouse config for JDBC bridge
            createClickHouseJdbcBridgeConfig();

            // Verify config files were created
            System.out.println("Config directory: " + configDir.toAbsolutePath());
            System.out.println("Config files created:");
            Files.walk(configDir).forEach(path -> {
                if (Files.isRegularFile(path)) {
                    System.out.println("  " + configDir.relativize(path));
                }
            });

            // Start JDBC Bridge directly using Java code
            System.out.println("Starting JDBC Bridge...");
            startJdbcBridge();

            // Expose the bridge port to containers so ClickHouse can access it
            System.out.println("Exposing bridge port " + bridgePort + " to containers...");
            Testcontainers.exposeHostPorts(bridgePort);

            // Now start ClickHouse with the JDBC bridge config
            System.out.println("Starting ClickHouse container...");
            Path clickHouseConfigPath = configDir.resolve("clickhouse-jdbc-bridge.xml").toAbsolutePath();
            clickHouseContainer = new GenericContainer<>("clickhouse/clickhouse-server:22.3")
                    .withNetwork(sharedNetwork)
                    .withNetworkAliases("clickhouse_server")
                    .withExposedPorts(8123, 9000)
                    .withFileSystemBind(
                            clickHouseConfigPath.toString(),
                            "/etc/clickhouse-server/config.d/jdbc_bridge.xml",
                            BindMode.READ_ONLY)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));
            clickHouseContainer.start();
            System.out.println("ClickHouse container started");

            System.out.println("Setup complete!");
        } catch (Exception e) {
            System.err.println("Setup failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private static void createClickHouseJdbcBridgeConfig() throws IOException {
        // Create ClickHouse config file to configure JDBC bridge connection
        // The bridge runs on the host, accessible via host.testcontainers.internal
        String configXml = "<?xml version=\"1.0\"?>\n" +
                "<clickhouse>\n" +
                "    <jdbc_bridge>\n" +
                "        <host>host.testcontainers.internal</host>\n" +
                "        <port>" + bridgePort + "</port>\n" +
                "    </jdbc_bridge>\n" +
                "</clickhouse>\n";

        Path configPath = configDir.resolve("clickhouse-jdbc-bridge.xml");
        Files.write(configPath, configXml.getBytes());
        System.out.println("Created ClickHouse JDBC bridge config at: " + configPath);
    }
    
    private static void setupMySQLData() throws Exception {
        String jdbcUrl = mysqlContainer.getJdbcUrl();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, mysqlContainer.getUsername(), mysqlContainer.getPassword());
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, name VARCHAR(100), value INT)");
            stmt.execute("INSERT INTO test_table VALUES (1, 'test1', 100), (2, 'test2', 200), (3, 'test3', 300)");
            // check data is present
            ResultSet resultSet = stmt.executeQuery("SELECT * FROM test_table");
            while (resultSet.next()) {
                System.out.println(resultSet.getInt("id") + " " + resultSet.getString("name") + " " + resultSet.getInt("value"));
            }
        }
    }
    
    private static void createConfigFiles() throws IOException {
        // Create config subdirectory
        Path configSubDir = configDir.resolve("config");
        Files.createDirectories(configSubDir);
        
        // Create server.json
        JsonObject serverConfig = new JsonObject()
                .put("requestTimeout", 5000)
                .put("queryTimeout", 60000)
                .put("configScanPeriod", 5000)
                .put("serverPort", 9019)
                .put("repositories", new io.vertx.core.json.JsonArray()
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedDataSource")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository"))
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedSchema")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository"))
                        .add(new JsonObject()
                                .put("entity", "com.clickhouse.jdbcbridge.core.NamedQuery")
                                .put("repository", "com.clickhouse.jdbcbridge.impl.JsonFileRepository")))
                .put("extensions", new io.vertx.core.json.JsonArray()
                        .add(new JsonObject().put("class", "com.clickhouse.jdbcbridge.impl.JdbcDataSource"))
                        .add(new JsonObject().put("class", "com.clickhouse.jdbcbridge.impl.ConfigDataSource"))
                        .add(new JsonObject().put("class", "com.clickhouse.jdbcbridge.impl.ScriptDataSource")));
        
        Files.write(configSubDir.resolve("server.json"), serverConfig.encodePrettily().getBytes());
        
        // Create datasources directory
        Path datasourcesDir = configSubDir.resolve("datasources");
        Files.createDirectories(datasourcesDir);
        
        // Create MySQL datasource config
        // The MySQL driver is already in the test classpath (mysql-connector-j 8.2.0)
        // so we don't need driverUrls - just use the driver from classpath
        String mysqlJdbcUrl = String.format("jdbc:mysql://localhost:%d/testdb?useSSL=false&allowPublicKeyRetrieval=true",
                mysqlContainer.getMappedPort(3306));
        JsonObject mysqlConfig = new JsonObject()
                .put("mysql", new JsonObject()
                        .put("driverClassName", "com.mysql.cj.jdbc.Driver")
                        .put("jdbcUrl", mysqlJdbcUrl)
                        .put("username", mysqlContainer.getUsername())
                        .put("password", mysqlContainer.getPassword())
                        .put("initializationFailTimeout", 30000)
                        .put("minimumIdle", 1)
                        .put("maximumPoolSize", 5));
        
        Files.write(datasourcesDir.resolve("mysql.json"), mysqlConfig.encodePrettily().getBytes());
        
        // Create httpd.json with minimal config (just maxInitialLineLength like the default)
        JsonObject httpdConfig = new JsonObject()
                .put("maxInitialLineLength", 2147483647L);
        Files.write(configSubDir.resolve("httpd.json"), httpdConfig.encodePrettily().getBytes());
    }
    
    private static void startJdbcBridge() throws Exception {
        // Set the config directory system property so the verticle can find the config
        Path configSubDir = configDir.resolve("config");
        System.setProperty("jdbc-bridge.config.dir", configSubDir.toAbsolutePath().toString());
        
        // Create Vertx instance
        vertx = Vertx.vertx();
        
        // Deploy the JdbcBridgeVerticle
        CompletableFuture<String> deployFuture = new CompletableFuture<>();
        vertx.deployVerticle(new JdbcBridgeVerticle(), new DeploymentOptions(), result -> {
            if (result.succeeded()) {
                bridgeDeploymentId = result.result();
                deployFuture.complete(bridgeDeploymentId);
            } else {
                deployFuture.completeExceptionally(result.cause());
            }
        });
        
        try {
            deployFuture.get(30, TimeUnit.SECONDS);
            System.out.println("JDBC Bridge verticle deployed: " + bridgeDeploymentId);
        } catch (Exception e) {
            System.err.println("Failed to deploy JDBC Bridge verticle: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        
        // Wait for the server to be ready by pinging it
        HttpClient client = vertx.createHttpClient();
        int maxAttempts = 30;
        boolean ready = false;
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                CompletableFuture<Integer> pingFuture = new CompletableFuture<>();
                client.request(io.vertx.core.http.HttpMethod.GET, bridgePort, "localhost", "/ping")
                        .onSuccess(request -> {
                            request.send().onSuccess(response -> {
                                pingFuture.complete(response.statusCode());
                            }).onFailure(pingFuture::completeExceptionally);
                        })
                        .onFailure(pingFuture::completeExceptionally);
                
                Integer statusCode = pingFuture.get(2, TimeUnit.SECONDS);
                if (statusCode == 200) {
                    ready = true;
                    break;
                }
            } catch (Exception e) {
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(1000); // Wait 1 second before retrying
                } else {
                    System.err.println("JDBC Bridge server did not become ready after " + maxAttempts + " attempts");
                    throw new RuntimeException("JDBC Bridge server did not become ready", e);
                }
            }
        }
        
        if (ready) {
            System.out.println("JDBC Bridge server is ready on port " + bridgePort);
        } else {
            throw new RuntimeException("JDBC Bridge server did not become ready after " + maxAttempts + " attempts");
        }

        // Wait for the datasource configuration to be loaded and verify MySQL connectivity
        // The config scan period is 5 seconds, so wait a bit for configs to load
        Thread.sleep(2000);

        // Warmup query to ensure MySQL datasource is fully initialized
        System.out.println("Warming up MySQL datasource...");
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                CompletableFuture<io.vertx.core.http.HttpClientResponse> warmupFuture = new CompletableFuture<>();
                String warmupQuery = "connection_string=mysql&table=" + java.net.URLEncoder.encode("SELECT 1", "UTF-8");

                client.request(io.vertx.core.http.HttpMethod.POST, bridgePort, "localhost", "/")
                        .onSuccess(request -> {
                            request.putHeader("Content-Type", "application/x-www-form-urlencoded")
                                    .send(warmupQuery)
                                    .onSuccess(warmupFuture::complete)
                                    .onFailure(warmupFuture::completeExceptionally);
                        })
                        .onFailure(warmupFuture::completeExceptionally);

                io.vertx.core.http.HttpClientResponse warmupResponse = warmupFuture.get(10, TimeUnit.SECONDS);
                if (warmupResponse.statusCode() == 200) {
                    // Read the body to ensure connection is fully established
                    CompletableFuture<String> bodyFuture = new CompletableFuture<>();
                    warmupResponse.body().onSuccess(body -> {
                        bodyFuture.complete(body.toString());
                    }).onFailure(bodyFuture::completeExceptionally);
                    String body = bodyFuture.get(5, TimeUnit.SECONDS);
                    if (body.length() > 0) {
                        System.out.println("MySQL datasource warmup successful (response length: " + body.length() + ")");
                        break;
                    }
                }
                System.out.println("Warmup attempt " + (attempt + 1) + " - waiting for datasource...");
                Thread.sleep(1000);
            } catch (Exception e) {
                if (attempt < 9) {
                    System.out.println("Warmup attempt " + (attempt + 1) + " failed: " + e.getMessage() + ", retrying...");
                    Thread.sleep(1000);
                } else {
                    System.err.println("MySQL datasource warmup failed after 10 attempts: " + e.getMessage());
                    // Continue anyway - the actual test will fail with a clearer error if there's a real problem
                }
            }
        }
    }
    
    @AfterClass(groups = { "sit" })
    public static void tearDown() {
        if (vertx != null && bridgeDeploymentId != null) {
            CompletableFuture<Void> undeployFuture = new CompletableFuture<>();
            vertx.undeploy(bridgeDeploymentId, result -> {
                if (result.succeeded()) {
                    undeployFuture.complete(null);
                } else {
                    undeployFuture.completeExceptionally(result.cause());
                }
            });
            try {
                undeployFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore
            }
        }
        
        if (vertx != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.close(result -> {
                if (result.succeeded()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(result.cause());
                }
            });
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Clear system property
        System.clearProperty("jdbc-bridge.config.dir");
        
        if (clickHouseContainer != null) {
            clickHouseContainer.stop();
        }
        if (mysqlContainer != null) {
            mysqlContainer.stop();
        }
    }
    
    @Test(groups = { "sit" })
    public void testBridgePing() throws Exception {
        HttpClient client = vertx.createHttpClient();
        CompletableFuture<String> future = new CompletableFuture<>();
        
        client.request(io.vertx.core.http.HttpMethod.GET, bridgePort, "localhost", "/ping")
                .onSuccess(request -> {
                    request.send().onSuccess(response -> {
                        response.body().onSuccess(body -> {
                            future.complete(body.toString());
                        }).onFailure(future::completeExceptionally);
                    }).onFailure(future::completeExceptionally);
                })
                .onFailure(future::completeExceptionally);
        
        String response = future.get(10, TimeUnit.SECONDS);
        assertNotNull(response);
        assertTrue(response.contains("Ok."));
    }
    
    @Test(groups = { "sit" })
    public void testMySQLQuery() throws Exception {
        // Query MySQL through JDBC bridge
        String query = "SELECT * FROM test_table";
        
        HttpClient client = vertx.createHttpClient();
        CompletableFuture<io.vertx.core.http.HttpClientResponse> future = new CompletableFuture<>();
        
        // Send query using the bridge's query format: connection_string and table (query)
        String queryBody = "connection_string=mysql&table=" + java.net.URLEncoder.encode(query, "UTF-8");
        
        client.request(io.vertx.core.http.HttpMethod.POST, bridgePort, "localhost", "/")
                .onSuccess(request -> {
                    request.putHeader("Content-Type", "application/x-www-form-urlencoded")
                            .send(queryBody)
                            .onSuccess(future::complete)
                            .onFailure(future::completeExceptionally);
                })
                .onFailure(future::completeExceptionally);
        
        io.vertx.core.http.HttpClientResponse response = future.get(30, TimeUnit.SECONDS);
        assertEquals(response.statusCode(), 200, "Query should succeed");
        
        CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        response.body().onSuccess(body -> {
            bodyFuture.complete(body.toString());
        }).onFailure(bodyFuture::completeExceptionally);
        
        String body = bodyFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(body);
        // Response should contain data from MySQL (binary format, but should not be empty)
        assertTrue(body.length() > 0, "Response should not be empty");
    }
    
    @Test(groups = { "sit" })
    public void testClickHouseJdbcBridgeConnection() throws Exception {
        // Test that ClickHouse is accessible
        HttpClient client = vertx.createHttpClient();

        // Test that we can at least ping ClickHouse
        CompletableFuture<Integer> pingFuture = new CompletableFuture<>();
        client.request(io.vertx.core.http.HttpMethod.GET,
                        clickHouseContainer.getMappedPort(8123),
                        clickHouseContainer.getHost(),
                        "/ping")
                .onSuccess(request -> {
                    request.send().onSuccess(response -> {
                        pingFuture.complete(response.statusCode());
                    }).onFailure(pingFuture::completeExceptionally);
                })
                .onFailure(pingFuture::completeExceptionally);

        Integer statusCode = pingFuture.get(10, TimeUnit.SECONDS);
        assertEquals(statusCode.intValue(), 200, "ClickHouse should be accessible");
    }

    @Test(groups = { "sit" })
    public void testEndToEndClickHouseToMySQLViaJdbcBridge() throws Exception {
        // E2E test: ClickHouse -> JDBC Bridge -> MySQL
        // This tests the full flow using ClickHouse's jdbc() table function

        // Disable compression to avoid LZ4 library dependency
        String clickHouseUrl = String.format("jdbc:clickhouse://%s:%d/default?compress=0",
                clickHouseContainer.getHost(),
                clickHouseContainer.getMappedPort(8123));

        System.out.println("Connecting to ClickHouse at: " + clickHouseUrl);

        try (Connection conn = DriverManager.getConnection(clickHouseUrl);
             Statement stmt = conn.createStatement()) {

            // Test 1: Query using jdbc() with SQL query syntax
            // Syntax: jdbc('named_datasource', 'sql query')
            System.out.println("Testing jdbc() table function with SQL query...");
            String query1 = "SELECT * FROM jdbc('mysql', 'SELECT * FROM test_table ORDER BY id')";

            ResultSet rs1 = stmt.executeQuery(query1);
            List<String> results1 = new ArrayList<>();
            while (rs1.next()) {
                int id = rs1.getInt("id");
                String name = rs1.getString("name");
                int value = rs1.getInt("value");
                String row = String.format("id=%d, name=%s, value=%d", id, name, value);
                results1.add(row);
                System.out.println("  Row: " + row);
            }
            rs1.close();

            assertEquals(results1.size(), 3, "Should return 3 rows from MySQL");
            assertTrue(results1.get(0).contains("id=1"), "First row should have id=1");
            assertTrue(results1.get(0).contains("name=test1"), "First row should have name=test1");
            assertTrue(results1.get(0).contains("value=100"), "First row should have value=100");
            assertTrue(results1.get(1).contains("id=2"), "Second row should have id=2");
            assertTrue(results1.get(2).contains("id=3"), "Third row should have id=3");

            // Test 2: Query using jdbc() with schema and table syntax
            // Syntax: jdbc('named_datasource', 'schema', 'table')
            System.out.println("Testing jdbc() table function with schema/table syntax...");
            String query2 = "SELECT * FROM jdbc('mysql', 'testdb', 'test_table') ORDER BY id";

            ResultSet rs2 = stmt.executeQuery(query2);
            List<String> results2 = new ArrayList<>();
            while (rs2.next()) {
                int id = rs2.getInt("id");
                String name = rs2.getString("name");
                int value = rs2.getInt("value");
                String row = String.format("id=%d, name=%s, value=%d", id, name, value);
                results2.add(row);
                System.out.println("  Row: " + row);
            }
            rs2.close();

            assertEquals(results2.size(), 3, "Should return 3 rows from MySQL via schema/table syntax");

            // Test 3: Query with WHERE clause through jdbc()
            System.out.println("Testing jdbc() table function with WHERE clause...");
            String query3 = "SELECT * FROM jdbc('mysql', 'SELECT * FROM test_table WHERE value > 150')";

            ResultSet rs3 = stmt.executeQuery(query3);
            List<String> results3 = new ArrayList<>();
            while (rs3.next()) {
                int id = rs3.getInt("id");
                String name = rs3.getString("name");
                int value = rs3.getInt("value");
                String row = String.format("id=%d, name=%s, value=%d", id, name, value);
                results3.add(row);
                System.out.println("  Row: " + row);
            }
            rs3.close();

            assertEquals(results3.size(), 2, "Should return 2 rows where value > 150");

            // Test 4: Aggregation through jdbc()
            System.out.println("Testing jdbc() table function with aggregation...");
            String query4 = "SELECT sum(value) as total FROM jdbc('mysql', 'SELECT * FROM test_table')";

            ResultSet rs4 = stmt.executeQuery(query4);
            assertTrue(rs4.next(), "Should have aggregation result");
            long total = rs4.getLong("total");
            System.out.println("  Total value: " + total);
            assertEquals(total, 600L, "Sum of values should be 600 (100+200+300)");
            rs4.close();

            System.out.println("E2E test completed successfully!");
        }
    }
}

