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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.vertx.core.json.JsonObject;

/**
 * Exercises the bridge against SQL Server. Covers the curated
 * {@code EngineDefaults} per-driver tweaks (cursor select-method, adaptive
 * response buffering, bulk-copy batch insert) and the DATETIMEOFFSET type
 * mapping fix from commit cb73d94.
 */
public class MsSqlIT extends AbstractBridgeIT {

    @Override
    protected JdbcDatabaseContainer<?> createDatabaseContainer() {
        return new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                .acceptLicense()
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Override
    protected String getDatasourceName() {
        return "mssql";
    }

    @Override
    protected JsonObject extraDatasourceProps() {
        // Encryption is on by default in newer mssql-jdbc; testcontainers'
        // self-signed cert won't validate. Disable for the integration test.
        return new JsonObject().put("dataSource", new JsonObject()
                .put("encrypt", "false")
                .put("trustServerCertificate", "true"));
    }

    @Override
    protected void setupTestData(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("IF OBJECT_ID('test_table', 'U') IS NOT NULL DROP TABLE test_table");
            // Includes DATETIMEOFFSET to exercise the bridge's type mapping fix.
            s.execute("CREATE TABLE test_table ("
                    + "  id INT PRIMARY KEY, "
                    + "  name NVARCHAR(100), "
                    + "  value INT, "
                    + "  ts DATETIMEOFFSET)");
            s.execute("INSERT INTO test_table VALUES "
                    + "(1, 'a', 10, SYSDATETIMEOFFSET()), "
                    + "(2, 'b', 20, SYSDATETIMEOFFSET()), "
                    + "(3, 'c', 30, SYSDATETIMEOFFSET())");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT id, name, value FROM test_table";
    }
}
