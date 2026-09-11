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
import java.sql.Timestamp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

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
    // Widest offset any timezone uses, so the instant assertion does not depend
    // on the one of the machine running the test.
    private static final long MAX_TIMEZONE_OFFSET_SECONDS = 14 * 3600L;


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
            // datewithtime is an Oracle DATE column with a time component and a pre-1970
            // value — used by the smoke query to catch serialisation regressions end-to-end.
            // The type-mapping correctness (DATE → DateTime64) is unit-tested in
            // DefaultDataTypeConverterTest#oracleDateTypeMapsToDateTime64.
            s.execute("CREATE TABLE test_table ("
                    + "  id NUMBER(10) PRIMARY KEY, "
                    + "  name VARCHAR2(100), "
                    + "  value NUMBER(10), "
                    + "  bf BINARY_FLOAT, "
                    + "  bd BINARY_DOUBLE, "
                    + "  datewithtime DATE)");
            s.execute("INSERT INTO test_table VALUES (1, 'a', 10, 1.5, 1.5, TO_DATE('2024-01-15 14:30:00', 'YYYY-MM-DD HH24:MI:SS'))");
            s.execute("INSERT INTO test_table VALUES (2, 'b', 20, -2.25, 3.14159265358979, TO_DATE('2024-06-01 09:00:00', 'YYYY-MM-DD HH24:MI:SS'))");
            s.execute("INSERT INTO test_table VALUES (3, 'c', 30, 0.0, 0.0, TO_DATE('1960-03-15 08:00:00', 'YYYY-MM-DD HH24:MI:SS'))");
            // No explicit commit: DriverManager.getConnection defaults to
            // autoCommit=true, and Oracle throws ORA-17273 if you call
            // commit() with autoCommit on.
        }
    }

    @Override
    protected String smokeQuery() {
        return "SELECT * FROM test_table";
    }

    /**
     * An Oracle DATE is reported with a scale of 0, so its tick is a second. The
     * declared type says so, this checks the bytes agree: they used to carry a
     * millisecond count, which ClickHouse read as a date in year 58000.
     *
     * The instant is asserted within the widest timezone offset there is, because
     * the writer shifts it by the offset of its own timezone and that belongs to
     * the deployment, not to this test. A millisecond count would still be off by
     * three orders of magnitude, so the check keeps all its teeth.
     */
    @org.testng.annotations.Test(groups = { "sit" })
    public void testOracleDateIsSerialisedInSeconds() throws Exception {
        byte[] row = postQueryBytes(getDatasourceName(),
                "SELECT datewithtime FROM test_table WHERE id = 1");

        assertEquals(row.length, 9, "expected a null flag and an Int64 tick, got " + row.length + " bytes");
        assertEquals(row[0], 0, "the value of the row is not null");

        long tick = 0L;
        for (int i = 8; i >= 1; i--) {
            tick = (tick << 8) | (row[i] & 0xFFL);
        }

        long expected = Timestamp.valueOf("2024-01-15 14:30:00").getTime() / 1000L;
        assertTrue(Math.abs(tick - expected) <= MAX_TIMEZONE_OFFSET_SECONDS,
                "expected a second tick near " + expected + " for 2024-01-15 14:30:00, got " + tick);
    }

    @org.testng.annotations.Test(groups = { "sit" })
    public void testOracleDateColumnMapsToDateTime64() throws Exception {
        // /columns_info must declare the column as DateTime64, not DateTime.
        // DateTime (UInt32) cannot represent the pre-1970 value in the test data (1960-03-15).
        String columnsInfo = postColumnsInfo(getDatasourceName(),
                "SELECT datewithtime FROM test_table WHERE id = 3");
        assertTrue(columnsInfo.contains("DateTime64"),
                "Oracle DATE column must be mapped to DateTime64; bridge returned: " + columnsInfo);
    }

}
