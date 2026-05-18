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

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class PostgresIT extends AbstractBridgeIT {

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
        return "postgres";
    }

    @Override
    protected void setupTestData(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            // Exercises NUMERIC + TIMESTAMPTZ + NULL on the streaming path,
            // since type mapping for those columns is the most divergent between
            // Postgres and ClickHouse.
            s.execute("CREATE TABLE IF NOT EXISTS test_table ("
                    + "  id INT PRIMARY KEY, "
                    + "  name VARCHAR(100), "
                    + "  big NUMERIC(20, 4), "
                    + "  ts TIMESTAMPTZ, "
                    + "  maybe_null TEXT)");
            s.execute("INSERT INTO test_table VALUES "
                    + "(1, 'a', 12345.6789, NOW(), 'present'), "
                    + "(2, 'b', -1.0,         NOW(), NULL), "
                    + "(3, 'c', 1e10,         NOW(), 'present')");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table ORDER BY id";
    }
}
