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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testng.annotations.Test;

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
            // Keep the smoke schema to the basic types the bridge handles
            // unambiguously. NUMERIC/TIMESTAMPTZ coverage moves to a dedicated
            // @Test method once the smoke is green — they're the most
            // divergent types between Postgres and ClickHouse and need their
            // own assertions, not a "bytes > 0" check.
            s.execute("CREATE TABLE IF NOT EXISTS test_table ("
                    + "  id INT PRIMARY KEY, "
                    + "  name VARCHAR(100), "
                    + "  value INT)");
            s.execute("INSERT INTO test_table VALUES "
                    + "(1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
            s.execute("CREATE TABLE IF NOT EXISTS dt_table (id INT PRIMARY KEY, ts TIMESTAMP, day DATE)");
            s.execute("INSERT INTO dt_table VALUES "
                    + "(1, '1950-06-15 08:30:00.123456', '1950-06-15'), "
                    + "(2, '1970-01-01 00:00:00', '1970-01-01'), "
                    + "(3, '2026-01-15 08:30:00.456789', '2026-01-15'), "
                    + "(4, '-infinity', '-infinity'), "
                    + "(5, 'infinity', 'infinity')");
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table ORDER BY id";
    }

    // PostgreSQL reports a scale of 6 for TIMESTAMP, so a tick is a microsecond.
    @Test(groups = { "sit" })
    public void testTimestampTicksAreTheSourceWallClock() throws Exception {
        long[] ticks = postQueryTicks(getDatasourceName(), "SELECT ts FROM dt_table ORDER BY id", 5);

        assertEquals(ticks, new long[] {
                wallClockTick("1950-06-15T08:30:00.123456", 6),
                wallClockTick("1970-01-01T00:00:00", 6),
                wallClockTick("2026-01-15T08:30:00.456789", 6),
                // infinities saturate to the bounds of ClickHouse
                wallClockTick("1900-01-01T00:00:00", 6),
                wallClockTick("2299-12-31T23:59:59.999999", 6) });
    }

    @Test(groups = { "sit" })
    public void testDateDaysAreTheSourceDate() throws Exception {
        long[] days = postQueryDays(getDatasourceName(), "SELECT day FROM dt_table ORDER BY id", 5);

        assertEquals(days, new long[] {
                LocalDate.parse("1950-06-15").toEpochDay(),
                LocalDate.parse("1970-01-01").toEpochDay(),
                LocalDate.parse("2026-01-15").toEpochDay(),
                // infinities saturate to the bounds of ClickHouse
                LocalDate.parse("1900-01-01").toEpochDay(),
                LocalDate.parse("2299-12-31").toEpochDay() });
    }
}
