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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class MySqlIT extends AbstractBridgeIT {

    @Override
    protected JdbcDatabaseContainer<?> createDatabaseContainer() {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
    }

    @Override
    protected String getDatasourceName() {
        return "mysql";
    }

    @Override
    protected void setupTestData(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS test_table "
                    + "(id INT PRIMARY KEY, name VARCHAR(100), value INT)");
            s.execute("INSERT INTO test_table VALUES "
                    + "(1, 'test1', 100), (2, 'test2', 200), (3, 'test3', 300)");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table";
    }
}
