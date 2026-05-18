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
package com.clickhouse.jdbcbridge.core;

import static org.testng.Assert.*;

import java.util.Map;
import java.util.Properties;

import org.testng.annotations.Test;

import com.zaxxer.hikari.HikariConfig;

public class EngineDefaultsTest {

    // ---------- shared fixtures ----------

    private static final EngineDefaults.DriverVersionProvider V12 = drv -> 12;  // current mssql-jdbc
    private static final EngineDefaults.DriverVersionProvider V6  = drv -> 6;   // earliest mssql-jdbc supporting selectMethod
    private static final EngineDefaults.DriverVersionProvider V5  = drv -> 5;   // older mssql-jdbc
    private static final EngineDefaults.DriverVersionProvider MISSING = drv -> -1;

    /**
     * Build a HikariConfig that <em>doesn't</em> validate the driver class via
     * {@link Class#forName}. The test classpath has only a subset of the
     * real drivers (mariadb, oracle, mysql via testcontainers); SQL Server,
     * Postgres and synthetic class names will fail HikariConfig.setDriverClassName
     * even though our engine-defaults logic only does string lookups.
     *
     * Set the field directly via reflection. HikariCP validates again at
     * {@code validate()} time (called from HikariDataSource's constructor), which
     * these tests don't exercise.
     */
    private static HikariConfig newConfig(String driverClass, String jdbcUrl) {
        HikariConfig c = new HikariConfig();
        if (driverClass != null) {
            try {
                java.lang.reflect.Field f = HikariConfig.class.getDeclaredField("driverClassName");
                f.setAccessible(true);
                f.set(c, driverClass);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("HikariConfig.driverClassName field not accessible", e);
            }
        }
        if (jdbcUrl != null) {
            c.setJdbcUrl(jdbcUrl);
        }
        c.setUsername("u");
        c.setPassword("p");
        return c;
    }

    // ---------- forDriver ----------

    @Test(groups = { "unit" })
    public void testForDriverUnknownReturnsEmpty() {
        assertTrue(EngineDefaults.forDriver("not.a.real.Driver").isEmpty());
    }

    @Test(groups = { "unit" })
    public void testForDriverNullReturnsEmpty() {
        assertTrue(EngineDefaults.forDriver(null).isEmpty());
    }

    @Test(groups = { "unit" })
    public void testMssqlIncludesSelectMethodOnV12() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MSSQL, V12);
        assertEquals(defs.get("selectMethod"), "cursor");
        assertEquals(defs.get("responseBuffering"), "adaptive");
        assertEquals(defs.get("useBulkCopyForBatchInsert"), "true");
    }

    @Test(groups = { "unit" })
    public void testMssqlIncludesSelectMethodAtExactlyV6() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MSSQL, V6);
        assertEquals(defs.get("selectMethod"), "cursor",
                "selectMethod must be applied at the boundary version 6");
    }

    @Test(groups = { "unit" })
    public void testMssqlOmitsSelectMethodOnV5() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MSSQL, V5);
        assertNull(defs.get("selectMethod"),
                "selectMethod must NOT be applied on pre-6.x mssql-jdbc");
        // Other defaults still present
        assertEquals(defs.get("responseBuffering"), "adaptive");
        assertEquals(defs.get("useBulkCopyForBatchInsert"), "true");
    }

    @Test(groups = { "unit" })
    public void testMssqlOmitsSelectMethodWhenDriverMissing() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MSSQL, MISSING);
        assertNull(defs.get("selectMethod"),
                "selectMethod must NOT be applied when driver version can't be resolved");
        assertEquals(defs.get("responseBuffering"), "adaptive",
                "non-version-gated defaults stay present even if driver lookup fails");
    }

    @Test(groups = { "unit" })
    public void testOracleIncludesAllExpectedDefaults() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_ORACLE);
        assertEquals(defs.get("oracle.jdbc.defaultRowPrefetch"),         "2000");
        assertEquals(defs.get("oracle.jdbc.implicitStatementCacheSize"), "50");
        assertEquals(defs.get("oracle.jdbc.useThreadLocalBufferCache"),  "true");
        assertEquals(defs.get("useFetchSizeWithLongColumn"),             "true");
        assertEquals(defs.get("oracle.net.disableOob"),                  "true");
        assertEquals(defs.get("oracle.jdbc.timezoneAsRegion"),           "false");
    }

    @Test(groups = { "unit" })
    public void testMysqlIncludesAllExpectedDefaults() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MYSQL);
        assertEquals(defs.get("useServerPrepStmts"),       "true");
        assertEquals(defs.get("cachePrepStmts"),           "true");
        assertEquals(defs.get("rewriteBatchedStatements"), "true");
        assertEquals(defs.get("useUnicode"),               "true");
        assertEquals(defs.get("characterEncoding"),        "UTF-8");
        assertEquals(defs.get("useLocalSessionState"),     "true");
    }

    @Test(groups = { "unit" })
    public void testMariadbIncludesAllExpectedDefaults() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_MARIADB);
        assertEquals(defs.get("useServerPrepStmts"),       "true");
        assertEquals(defs.get("cachePrepStmts"),           "true");
        assertEquals(defs.get("rewriteBatchedStatements"), "true");
        assertEquals(defs.get("useUnicode"),               "true");
        assertEquals(defs.get("characterEncoding"),        "UTF-8");
        // MariaDB driver does NOT use the same useLocalSessionState knob — make sure
        // we didn't accidentally copy that one in.
        assertNull(defs.get("useLocalSessionState"));
    }

    @Test(groups = { "unit" })
    public void testPostgresIncludesAllExpectedDefaults() {
        Map<String, String> defs = EngineDefaults.forDriver(EngineDefaults.DRIVER_POSTGRES);
        assertEquals(defs.get("prepareThreshold"),    "3");
        assertEquals(defs.get("defaultRowFetchSize"), "10000");
        assertEquals(defs.get("binaryTransfer"),      "true");
    }

    @Test(groups = { "unit" }, expectedExceptions = UnsupportedOperationException.class)
    public void testForDriverReturnsImmutableMap() {
        EngineDefaults.forDriver(EngineDefaults.DRIVER_ORACLE).put("hack", "no");
    }

    // ---------- applyTo: merge behavior ----------

    @Test(groups = { "unit" })
    public void testApplyToUnknownDriverIsNoop() {
        HikariConfig c = newConfig("not.a.real.Driver", "jdbc:unknown://host/db");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 0);
        assertTrue(c.getDataSourceProperties().isEmpty());
    }

    @Test(groups = { "unit" })
    public void testApplyToAddsAllOracleDefaultsToEmptyConfig() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_ORACLE, "jdbc:oracle:thin:@//host:1521/SVC");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 7, "6 Oracle dataSource defaults + connectionTestQuery = 7");
        Properties p = c.getDataSourceProperties();
        assertEquals(p.getProperty("oracle.jdbc.defaultRowPrefetch"),         "2000");
        assertEquals(p.getProperty("oracle.jdbc.implicitStatementCacheSize"), "50");
        assertEquals(p.getProperty("oracle.jdbc.useThreadLocalBufferCache"),  "true");
        assertEquals(p.getProperty("useFetchSizeWithLongColumn"),             "true");
        assertEquals(p.getProperty("oracle.net.disableOob"),                  "true");
        assertEquals(p.getProperty("oracle.jdbc.timezoneAsRegion"),           "false");
        assertEquals(c.getConnectionTestQuery(), "SELECT 1 FROM dual",
                "Oracle must use SELECT 1 FROM dual (ORA-00923 if it doesn't)");
    }

    @Test(groups = { "unit" })
    public void testApplyToMssqlIncludesSelectMethodOnV12() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MSSQL, "jdbc:sqlserver://host:1433;db=x");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 4, "3 SQL Server defaults + connectionTestQuery = 4");
        assertEquals(c.getDataSourceProperties().getProperty("selectMethod"), "cursor");
        assertEquals(c.getConnectionTestQuery(), "SELECT 1");
    }

    @Test(groups = { "unit" })
    public void testApplyToMssqlSkipsSelectMethodOnV5() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MSSQL, "jdbc:sqlserver://host:1433;db=x");
        int applied = EngineDefaults.applyTo(c, V5);
        assertEquals(applied, 3, "2 dataSource defaults (no selectMethod) + connectionTestQuery = 3");
        assertNull(c.getDataSourceProperties().getProperty("selectMethod"));
        assertEquals(c.getDataSourceProperties().getProperty("responseBuffering"), "adaptive");
    }

    @Test(groups = { "unit" })
    public void testApplyToUserPropertyWinsOverDefault() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_ORACLE, "jdbc:oracle:thin:@//host:1521/SVC");
        c.addDataSourceProperty("oracle.jdbc.defaultRowPrefetch", "500");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 6, "user pre-set 1 of 6 dataSource keys + connectionTestQuery = 6");
        assertEquals(c.getDataSourceProperties().getProperty("oracle.jdbc.defaultRowPrefetch"), "500",
                "user value must NOT be overwritten");
    }

    @Test(groups = { "unit" })
    public void testApplyToJdbcUrlParameterWinsOverDefault_semicolonStyle() {
        // SQL Server style — params separated by ;
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MSSQL,
                "jdbc:sqlserver://host:1433;databaseName=db;selectMethod=direct");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 3, "selectMethod is in URL → 2 dataSource defaults + connectionTestQuery = 3");
        assertNull(c.getDataSourceProperties().getProperty("selectMethod"),
                "engine default must NOT shadow the URL-specified value");
    }

    @Test(groups = { "unit" })
    public void testApplyToJdbcUrlParameterWinsOverDefault_ampersandStyle() {
        // MySQL style — params separated by ?...&...
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MYSQL,
                "jdbc:mysql://host:3306/db?useServerPrepStmts=false&cachePrepStmts=false");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 5, "2 of 6 keys in URL → 4 dataSource defaults + connectionTestQuery = 5");
        assertNull(c.getDataSourceProperties().getProperty("useServerPrepStmts"));
        assertNull(c.getDataSourceProperties().getProperty("cachePrepStmts"));
        // Others are added
        assertEquals(c.getDataSourceProperties().getProperty("rewriteBatchedStatements"), "true");
    }

    @Test(groups = { "unit" })
    public void testApplyToJdbcUrlParameterWinsOverDefault_questionMarkOnly() {
        // First (and only) URL param prefixed with ?
        HikariConfig c = newConfig(EngineDefaults.DRIVER_POSTGRES,
                "jdbc:postgresql://host:5432/db?binaryTransfer=false");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 3, "binaryTransfer in URL → 2 dataSource defaults + connectionTestQuery = 3");
        assertNull(c.getDataSourceProperties().getProperty("binaryTransfer"));
    }

    @Test(groups = { "unit" })
    public void testApplyToMixedOverrides() {
        // Operator has set one key in URL, one in dataSourceProperties, all others
        // should be filled in by engine defaults.
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MSSQL,
                "jdbc:sqlserver://host:1433;databaseName=db;responseBuffering=full");
        c.addDataSourceProperty("useBulkCopyForBatchInsert", "false");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 2, "selectMethod + connectionTestQuery still applied");
        assertNull(c.getDataSourceProperties().getProperty("responseBuffering"));
        assertEquals(c.getDataSourceProperties().getProperty("useBulkCopyForBatchInsert"), "false");
        assertEquals(c.getDataSourceProperties().getProperty("selectMethod"), "cursor");
    }

    @Test(groups = { "unit" })
    public void testApplyToIsIdempotent() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_ORACLE, "jdbc:oracle:thin:@//host:1521/SVC");
        int first = EngineDefaults.applyTo(c, V12);
        int second = EngineDefaults.applyTo(c, V12);
        assertEquals(first, 7);
        assertEquals(second, 0, "second invocation must apply zero — defaults are already in place");
    }

    @Test(groups = { "unit" })
    public void testApplyToHandlesNullJdbcUrl() {
        HikariConfig c = new HikariConfig();
        c.setDriverClassName(EngineDefaults.DRIVER_ORACLE);
        c.setUsername("u");
        c.setPassword("p");
        // jdbcUrl deliberately not set
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 7, "absent URL must not block engine defaults from applying");
    }

    @Test(groups = { "unit" })
    public void testApplyToHandlesEmptyJdbcUrl() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_MSSQL, "");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 4, "empty URL string must be treated as 'no overrides'");
    }

    // ---------- connectionTestQuery defaults ----------

    @Test(groups = { "unit" })
    public void testConnectionTestQuery_oracleUsesFromDual() {
        // Oracle rejects bare `SELECT 1` with ORA-00923 ("FROM keyword not
        // found"), so the dialect-correct form is non-negotiable. Both
        // driver classes (modern and legacy) must use the same query —
        // testcontainers' OracleContainer reports the legacy form.
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_ORACLE),
                "SELECT 1 FROM dual");
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_ORACLE_LEGACY),
                "SELECT 1 FROM dual");
    }

    @Test(groups = { "unit" })
    public void testForDriverOracleLegacyMapsToSameDefaults() {
        // testcontainers' OracleContainer returns the legacy
        // oracle.jdbc.driver.OracleDriver class name. Both must produce
        // the same engine defaults so the legacy classname doesn't sneak
        // past the engine-defaults gate.
        Map<String, String> modern = EngineDefaults.forDriver(EngineDefaults.DRIVER_ORACLE);
        Map<String, String> legacy = EngineDefaults.forDriver(EngineDefaults.DRIVER_ORACLE_LEGACY);
        assertEquals(legacy, modern);
        assertEquals(legacy.size(), 6);
    }

    @Test(groups = { "unit" })
    public void testConnectionTestQuery_otherDriversUseSelectOne() {
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_MYSQL),    "SELECT 1");
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_MARIADB),  "SELECT 1");
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_POSTGRES), "SELECT 1");
        assertEquals(EngineDefaults.defaultConnectionTestQuery(EngineDefaults.DRIVER_MSSQL),    "SELECT 1");
    }

    @Test(groups = { "unit" })
    public void testConnectionTestQuery_unknownDriverReturnsNull() {
        assertNull(EngineDefaults.defaultConnectionTestQuery("not.a.real.Driver"));
        assertNull(EngineDefaults.defaultConnectionTestQuery(null));
    }

    @Test(groups = { "unit" })
    public void testConnectionTestQuery_operatorOverrideWins() {
        HikariConfig c = newConfig(EngineDefaults.DRIVER_ORACLE, "jdbc:oracle:thin:@//host:1521/SVC");
        c.setConnectionTestQuery("SELECT sysdate FROM dual");
        int applied = EngineDefaults.applyTo(c, V12);
        assertEquals(applied, 6, "operator already set connectionTestQuery → only 6 dataSource defaults applied");
        assertEquals(c.getConnectionTestQuery(), "SELECT sysdate FROM dual",
                "operator value must NOT be overwritten");
    }

    // ---------- jdbcUrlContainsKey: substring / edge cases ----------

    @Test(groups = { "unit" })
    public void testUrlContainsKey_semicolonBasic() {
        assertTrue(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:sqlserver://h:1433;databaseName=db;selectMethod=cursor", "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_ampersandBasic() {
        assertTrue(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:mysql://h:3306/db?useServerPrepStmts=true&cachePrepStmts=true", "cachePrepStmts"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_questionMarkOnlyOneParam() {
        assertTrue(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:postgresql://h:5432/db?binaryTransfer=false", "binaryTransfer"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_emptyValueStillCounts() {
        // Driver-side semantics differ here, but if the operator went to the trouble
        // of writing "selectMethod=", they expect to control that key. We must not
        // override.
        assertTrue(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:sqlserver://h:1433;selectMethod=", "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_notPresent() {
        assertFalse(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:sqlserver://h:1433;databaseName=db", "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_substringDoesNotMatch() {
        // selectMethodOther= must NOT be matched when looking for selectMethod.
        assertFalse(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:sqlserver://h:1433;selectMethodOther=foo", "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_nullUrl() {
        assertFalse(EngineDefaults.jdbcUrlContainsKey(null, "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_emptyUrl() {
        assertFalse(EngineDefaults.jdbcUrlContainsKey("", "selectMethod"));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_emptyKey() {
        assertFalse(EngineDefaults.jdbcUrlContainsKey("jdbc:foo://h?a=b", ""));
    }

    @Test(groups = { "unit" })
    public void testUrlContainsKey_keyWithRegexMetaCharacters() {
        // oracle.jdbc.defaultRowPrefetch contains a dot — must be regex-escaped.
        assertTrue(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:oracle:thin:@//h:1521/SVC?oracle.jdbc.defaultRowPrefetch=500",
                "oracle.jdbc.defaultRowPrefetch"));
        // And the dot must not act as a wildcard:
        assertFalse(EngineDefaults.jdbcUrlContainsKey(
                "jdbc:oracle:thin:@//h:1521/SVC?oracleXjdbcXdefaultRowPrefetch=500",
                "oracle.jdbc.defaultRowPrefetch"));
    }
}
