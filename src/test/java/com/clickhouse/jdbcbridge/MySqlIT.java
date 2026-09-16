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
            // A MySQL driver reports DATETIME and TIME with a scale of 0, which is
            // the combination that used to be written as a millisecond count into
            // a second-ticking column. `born` is before 1970 and `midnight` sits
            // exactly on the epoch, the two instants that used to be crushed.
            s.execute("CREATE TABLE IF NOT EXISTS test_table "
                    + "(id INT PRIMARY KEY, name VARCHAR(100), value INT, "
                    + " born DATETIME, midnight DATETIME, clock TIME)");
            s.execute("INSERT INTO test_table VALUES "
                    + "(1, 'test1', 100, '1950-06-15 08:30:00', '1970-01-01 00:00:00', '08:30:00'), "
                    + "(2, 'test2', 200, '2026-01-15 08:30:00', '1970-01-01 00:00:00', '00:00:00'), "
                    + "(3, 'test3', 300, '1900-01-01 00:00:00', '1970-01-01 00:00:00', '23:59:59')");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table";
    }

    /**
     * The scale a MySQL driver reports for DATETIME and TIME is 0, so the bridge
     * must declare the tick unit it actually writes.
     *
     * The value itself is asserted by the unit tests of the encoder: the bridge
     * answers in RowBinary and this harness captures the body as a String, so a
     * tick cannot be decoded from here.
     */
    @org.testng.annotations.Test(groups = { "sit" })
    public void testDatetimeColumnsDeclareTheirScale() throws Exception {
        String columnsInfo = postColumnsInfo(getDatasourceName(),
                "SELECT born, midnight, clock FROM test_table WHERE id = 1");

        org.testng.Assert.assertTrue(columnsInfo.contains("DateTime64(0)"),
                "MySQL datetime columns must declare their tick unit; bridge returned: " + columnsInfo);
    }
}
