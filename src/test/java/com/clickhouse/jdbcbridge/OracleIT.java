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

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.OracleContainer;

/**
 * Exercises the bridge against Oracle. Uses the {@code gvenzl/oracle-free}
 * image which boots in ~30s (vs. ~5 min for the legacy oracle-xe). Covers the
 * curated {@code EngineDefaults} per-driver tweaks (defaultRowPrefetch,
 * useFetchSizeWithLongColumn, timezoneAsRegion=false, implicitStatementCacheSize,
 * useThreadLocalBufferCache, net.disableOob) and the BINARY_FLOAT / BINARY_DOUBLE
 * type mapping fix from commit cb73d94.
 */
public class OracleIT extends AbstractBridgeIT {

    @Override
    protected JdbcDatabaseContainer<?> createDatabaseContainer() {
        // No explicit waitingFor() override: testcontainers' OracleContainer
        // defaults to a JDBC-handshake wait which (unlike Wait.forListeningPort)
        // doesn't false-positive while the xepdb1 service is still registering
        // with the listener — that race was the cause of ORA-12514 in CI.
        return new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
                .withUsername("testuser")
                .withPassword("testpass");
    }

    @Override
    protected String getDatasourceName() {
        return "oracle";
    }

    @Override
    protected void setupTestData(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            // Oracle doesn't have IF EXISTS; ignore the drop failure if absent.
            try {
                s.execute("DROP TABLE test_table");
            } catch (Exception ignored) {
            }
            // BINARY_FLOAT and BINARY_DOUBLE exercise the type mapping fix.
            s.execute("CREATE TABLE test_table ("
                    + "  id NUMBER(10) PRIMARY KEY, "
                    + "  name VARCHAR2(100), "
                    + "  value NUMBER(10), "
                    + "  bf BINARY_FLOAT, "
                    + "  bd BINARY_DOUBLE)");
            s.execute("INSERT INTO test_table VALUES (1, 'a', 10, 1.5, 1.5)");
            s.execute("INSERT INTO test_table VALUES (2, 'b', 20, -2.25, 3.14159265358979)");
            s.execute("INSERT INTO test_table VALUES (3, 'c', 30, 0.0, 0.0)");
            // No explicit commit: DriverManager.getConnection defaults to
            // autoCommit=true, and Oracle throws ORA-17273 if you call
            // commit() with autoCommit on.
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table";
    }
}
