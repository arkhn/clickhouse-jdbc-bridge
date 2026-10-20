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

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.annotations.Test;

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
            s.execute("CREATE TABLE IF NOT EXISTS dt_table (id INT PRIMARY KEY, dt DATETIME, clock TIME, day DATE)");
            s.execute("INSERT INTO dt_table VALUES "
                    + "(1, '1950-06-15 08:30:00', '08:30:00', '1950-06-15'), "
                    + "(2, '2026-01-15 08:30:00', '23:59:59', '2026-01-15'), "
                    + "(3, '1900-01-01 00:00:00', '00:00:00', '1900-01-01'), "
                    + "(4, '1000-01-01 00:00:00', '12:00:00', '1000-01-01')");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table";
    }

    // MySQL reports a scale of 0 for DATETIME and TIME, so a tick is a second.
    @Test(groups = { "sit" })
    public void testDatetimeTicksAreTheSourceWallClock() throws Exception {
        long[] ticks = postQueryTicks(getDatasourceName(), "SELECT dt, clock FROM dt_table ORDER BY id", 8);

        assertEquals(ticks, new long[] {
                wallClockTick("1950-06-15T08:30:00", 0), wallClockTick("1970-01-01T08:30:00", 0),
                wallClockTick("2026-01-15T08:30:00", 0), wallClockTick("1970-01-01T23:59:59", 0),
                wallClockTick("1900-01-01T00:00:00", 0), wallClockTick("1970-01-01T00:00:00", 0),
                // the lowest MySQL DATETIME saturates to the lowest ClickHouse one
                wallClockTick("1900-01-01T00:00:00", 0), wallClockTick("1970-01-01T12:00:00", 0) });
    }

    @Test(groups = { "sit" })
    public void testDateDaysAreTheSourceDate() throws Exception {
        long[] days = postQueryDays(getDatasourceName(), "SELECT day FROM dt_table ORDER BY id", 4);

        assertEquals(days, new long[] {
                LocalDate.parse("1950-06-15").toEpochDay(),
                LocalDate.parse("2026-01-15").toEpochDay(),
                LocalDate.parse("1900-01-01").toEpochDay(),
                // the lowest MySQL DATE saturates to the lowest ClickHouse Date32
                LocalDate.parse("1900-01-01").toEpochDay() });
    }
}
