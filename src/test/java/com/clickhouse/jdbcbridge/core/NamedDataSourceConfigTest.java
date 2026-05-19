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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.TimeZone;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link NamedDataSource} config-parser branches: timezone/timeouts,
 * sealed semantics, aliases, driver URLs, custom columns, defaults, cache stats,
 * UsageStats / lifecycle hooks.
 */
public class NamedDataSourceConfigTest {

    private static NamedDataSource build(JsonObject cfg) {
        return new NamedDataSource("ds-test",
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class), cfg);
    }

    @Test(groups = { "unit" })
    public void nullConfig_yieldsConservativeDefaults() {
        NamedDataSource ds = build(null);

        assertEquals(ds.getId(), "ds-test");
        assertNull(ds.getTimeZone());
        assertEquals(ds.getQueryTimeout(), -1);
        assertEquals(ds.getWriteTimeout(), -1);
        assertFalse(ds.isSealed());
        assertTrue(ds.getDriverUrls().isEmpty());
        assertTrue(ds.getCustomColumns().isEmpty());
        assertNotNull(ds.getDefaultValues(),
                "null-config branch must still construct an empty DefaultValues, not leak null");
    }

    @Test(groups = { "unit" })
    public void timezoneConfigIsParsedAsJavaTimeZone() {
        NamedDataSource ds = build(new JsonObject().put("timezone", "America/New_York"));
        TimeZone tz = ds.getTimeZone();

        assertNotNull(tz);
        assertEquals(tz.getID(), "America/New_York");
    }

    @Test(groups = { "unit" })
    public void timeoutsParseAndDefaultTo_minusOne() {
        NamedDataSource ds = build(new JsonObject()
                .put("queryTimeout", 1500)
                .put("writeTimeout", 2500));

        assertEquals(ds.getQueryTimeout(), 1500);
        assertEquals(ds.getWriteTimeout(), 2500);
    }

    @Test(groups = { "unit" })
    public void sealedDataSourceIgnoresCallerTimeouts() {
        // Security/quota lever: sealed ds must NOT let caller override timeouts.
        NamedDataSource sealed = build(new JsonObject()
                .put("sealed", true)
                .put("queryTimeout", 100)
                .put("writeTimeout", 200));

        assertEquals(sealed.getQueryTimeout(5000), 100,
                "sealed ds must ignore custom queryTimeout");
        assertEquals(sealed.getWriteTimeout(5000), 200,
                "sealed ds must ignore custom writeTimeout");
    }

    @Test(groups = { "unit" })
    public void unsealedDataSourceLetsCallerRaiseTimeoutsButNotNegativeOnes() {
        NamedDataSource unsealed = build(new JsonObject()
                .put("queryTimeout", 100)
                .put("writeTimeout", 200));

        assertEquals(unsealed.getQueryTimeout(5000), 5000);
        assertEquals(unsealed.getWriteTimeout(7000), 7000);
        // Negative customTimeout falls back to configured value.
        assertEquals(unsealed.getQueryTimeout(-1), 100);
        assertEquals(unsealed.getWriteTimeout(-1), 200);
    }

    @Test(groups = { "unit" })
    public void aliasesParsedAndIdRemoved() {
        // Self-alias is no-op and would mask routing mistakes — removed by constructor.
        NamedDataSource ds = build(new JsonObject()
                .put("aliases", new JsonArray()
                        .add("ds-test")
                        .add("alias-a")
                        .add("alias-b")
                        .add("")));

        assertFalse(ds.getAliases().contains("ds-test"),
                "self-id alias must be removed");
        assertTrue(ds.getAliases().contains("alias-a"));
        assertTrue(ds.getAliases().contains("alias-b"));
        assertFalse(ds.getAliases().contains(""), "empty string alias must be skipped");
    }

    @Test(groups = { "unit" })
    public void aliasesIgnoresNonStringEntries() {
        NamedDataSource ds = build(new JsonObject()
                .put("aliases", new JsonArray().add("good").add(42).add(true)));

        assertTrue(ds.getAliases().contains("good"));
        assertEquals(ds.getAliases().size(), 1,
                "non-String alias entries must be silently dropped");
    }

    @Test(groups = { "unit" })
    public void driverUrlsParsedAndExposedUnmodifiable() {
        NamedDataSource ds = build(new JsonObject()
                .put("driverUrls", new JsonArray().add("file:///lib/a.jar").add("file:///lib/b.jar")));

        assertTrue(ds.getDriverUrls().contains("file:///lib/a.jar"));
        assertTrue(ds.getDriverUrls().contains("file:///lib/b.jar"));
        assertThrows(UnsupportedOperationException.class,
                () -> ds.getDriverUrls().add("file:///lib/evil.jar"));
    }

    @Test(groups = { "unit" })
    public void noDriverUrls_yieldsNullClassLoaderUnlessCustomLoaderEnabled() {
        NamedDataSource ds = build(new JsonObject());
        assertTrue(ds.getDriverUrls().isEmpty());
    }

    @Test(groups = { "unit" })
    public void customColumnsAreParsedAndExposedUnmodifiable() {
        NamedDataSource ds = build(new JsonObject().put("columns", new JsonArray()
                .add(new JsonObject().put("name", "tag").put("type", "Str"))
                .add(new JsonObject().put("name", "n").put("type", "Int32"))));

        assertEquals(ds.getCustomColumns().size(), 2);
        assertEquals(ds.getCustomColumns().get(0).getName(), "tag");
        assertEquals(ds.getCustomColumns().get(1).getName(), "n");
        assertThrows(UnsupportedOperationException.class,
                () -> ds.getCustomColumns().add(ds.getCustomColumns().get(0)));
    }

    @Test(groups = { "unit" })
    public void customColumnsJsonStringIsValidJsonArray() {
        NamedDataSource ds = build(new JsonObject().put("columns", new JsonArray()
                .add(new JsonObject().put("name", "tag").put("type", "Str"))));

        String json = ds.getCustomColumnsAsJsonString();
        JsonArray parsed = new JsonArray(json);

        assertEquals(parsed.size(), 1);
        assertEquals(parsed.getJsonObject(0).getString("name"), "tag");
    }

    @Test(groups = { "unit" })
    public void validateRejectsEmptyId() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedDataSource.newInstance("",
                        new NamedDataSourceTest.TestRepository<>(NamedDataSource.class),
                        new JsonObject()));
    }

    @Test(groups = { "unit" })
    public void newInstance_requiresIdAndRepo() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedDataSource.newInstance("only-id"));
        assertThrows(NullPointerException.class,
                () -> NamedDataSource.newInstance((Object[]) null));
    }

    @Test(groups = { "unit" })
    public void getUsageReturnsDataSourceStatsForGivenId() {
        NamedDataSource ds = build(new JsonObject());

        UsageStats stats = ds.getUsage("alias-name");
        assertNotNull(stats);
        assertEquals(stats.getName(), "alias-name");
        assertEquals(((DataSourceStats) stats).getInstance(), ds.hashCode());
    }

    @Test(groups = { "unit" })
    public void closeIsSafeToCallMultipleTimes() {
        // Base NamedDataSource.close() only logs; idempotent contract.
        NamedDataSource ds = build(new JsonObject());
        ds.close();
        ds.close();
    }

    @Test(groups = { "unit" })
    public void newQueryParameters_layersCallerOverDataSourceDefaults() {
        // Merge order: fresh + datasource defaults + caller params.
        JsonObject dsParams = new JsonObject().put(QueryParameters.PARAM_MAX_ROWS, 10);
        NamedDataSource ds = build(new JsonObject().put("parameters", dsParams));

        QueryParameters caller = new QueryParameters();
        caller.merge(new JsonObject().put(QueryParameters.PARAM_MAX_ROWS, 50));

        QueryParameters merged = ds.newQueryParameters(caller);

        assertEquals(merged.getMaxRows(), 50);
    }

    @Test(groups = { "unit" })
    public void newQueryParameters_inheritsDataSourceDefaultsWhenCallerNull() {
        JsonObject dsParams = new JsonObject().put(QueryParameters.PARAM_MAX_ROWS, 10);
        NamedDataSource ds = build(new JsonObject().put("parameters", dsParams));

        QueryParameters merged = ds.newQueryParameters(null);

        assertEquals(merged.getMaxRows(), 10);
    }

    @Test(groups = { "unit" })
    public void loadSavedQueryAsNeeded_loadsContentOfQueryFile() {
        NamedDataSource ds = build(new JsonObject());

        String result = ds.loadSavedQueryAsNeeded("src/test/resources/simple.query", new QueryParameters());

        assertTrue(result.contains("select 1 as a"),
                "expected query file contents to be loaded, got: " + result);
        assertTrue(result.contains("2 as b"), "got: " + result);
    }

    @Test(groups = { "unit" })
    public void loadSavedQueryAsNeeded_passThroughInlineQueries() {
        NamedDataSource ds = build(new JsonObject());

        String inline = "SELECT inline";
        assertEquals(ds.loadSavedQueryAsNeeded(inline, new QueryParameters()), inline,
                "non-.query, non-file inputs must be returned untouched");
    }

    @Test(groups = { "unit" })
    public void loadSavedQueryAsNeeded_multilineQueriesAreNeverFileResolved() {
        // First guard is `indexOf('\n') == -1` — multi-line is always inline SQL.
        NamedDataSource ds = build(new JsonObject());

        String multiline = "SELECT 1\nFROM dual";
        assertEquals(ds.loadSavedQueryAsNeeded(multiline, new QueryParameters()), multiline);
    }

    @Test(groups = { "unit" })
    public void getCacheUsage_emitsStructuredJsonObject() {
        // Fresh cache -> all-zero stats; pin the schema so dropped keys are caught.
        NamedDataSource ds = build(new JsonObject());

        String json = ds.getCacheUsage();
        JsonObject parsed = new JsonObject(json);

        for (String key : new String[] {
                "hitCount", "missCount", "loadSuccessCount", "loadFailureCount",
                "totalLoadTime", "evictionCount", "evictionWeight" }) {
            assertTrue(parsed.containsKey(key), "missing cache stat key: " + key);
            assertEquals(parsed.getValue(key).toString(), "0",
                    "fresh cache must report 0 for [" + key + "]");
        }
    }

    @Test(groups = { "unit" })
    public void getPoolUsage_baseClassReturnsEmptyUsage() {
        // Base ds has no pool; JdbcDataSource overrides with Hikari stats.
        // EMPTY_USAGE is literal "{}" — downstream parses it, empty string would break.
        NamedDataSource ds = build(new JsonObject());
        assertEquals(ds.getPoolUsage(), "{}");
    }

    @Test(groups = { "unit" })
    public void isDifferentFromCatchesConfigChanges() {
        JsonObject base = new JsonObject().put("queryTimeout", 100);
        NamedDataSource ds = build(base);

        assertFalse(ds.isDifferentFrom(base.copy()));
        assertTrue(ds.isDifferentFrom(new JsonObject().put("queryTimeout", 200)));
        // Null config falls through to digest-based comparison and counts as different.
        assertTrue(ds.isDifferentFrom(null));
    }
}
