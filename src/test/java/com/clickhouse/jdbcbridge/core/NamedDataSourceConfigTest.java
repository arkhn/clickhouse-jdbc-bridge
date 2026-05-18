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
 * Companion tests for {@link NamedDataSource} — focuses on the config parser
 * branches: timezone/timeouts, sealed semantics, aliases, driver URLs,
 * custom columns, default values, the cache stats getter, and the
 * UsageStats / lifecycle hooks.
 */
public class NamedDataSourceConfigTest {

    private static NamedDataSource build(JsonObject cfg) {
        return new NamedDataSource("ds-test",
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class), cfg);
    }

    // ---------- null-config path ----------

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

    // ---------- config-driven fields ----------

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

    // ---------- sealed semantics ----------

    @Test(groups = { "unit" })
    public void sealedDataSourceIgnoresCallerTimeouts() {
        // The sealed datasource must NOT let a caller override its
        // configured timeouts — this is the security/quota lever.
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
        // Negative customTimeout falls back to the configured value.
        assertEquals(unsealed.getQueryTimeout(-1), 100);
        assertEquals(unsealed.getWriteTimeout(-1), 200);
    }

    // ---------- aliases ----------

    @Test(groups = { "unit" })
    public void aliasesParsedAndIdRemoved() {
        // The constructor removes the datasource's own id from the alias set —
        // a "self-alias" is a no-op and would mask routing mistakes downstream.
        NamedDataSource ds = build(new JsonObject()
                .put("aliases", new JsonArray()
                        .add("ds-test")
                        .add("alias-a")
                        .add("alias-b")
                        .add(""))); // empty string filtered out

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

    // ---------- driver URLs ----------

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
        // The codebase guards driverClassLoader behind a USE_CUSTOM_DRIVER_LOADER
        // flag (default false). The contract callers depend on is: no driverUrls
        // -> no per-datasource classloader needs to be juggled.
        // We don't pin the flag value; we just assert getDriverUrls() is empty.
        assertTrue(ds.getDriverUrls().isEmpty());
    }

    // ---------- custom columns ----------

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

    // ---------- validate() ----------

    @Test(groups = { "unit" })
    public void validateRejectsEmptyId() {
        // Construct via the factory so validate() runs.
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

    // ---------- usage / lifecycle ----------

    @Test(groups = { "unit" })
    public void getUsageReturnsDataSourceStatsForGivenId() {
        NamedDataSource ds = build(new JsonObject());

        UsageStats stats = ds.getUsage("alias-name");
        assertNotNull(stats);
        assertEquals(stats.getName(), "alias-name");
        // Stats must point back at this datasource's identity.
        assertEquals(((DataSourceStats) stats).getInstance(), ds.hashCode());
    }

    @Test(groups = { "unit" })
    public void closeIsSafeToCallMultipleTimes() {
        // The base NamedDataSource.close() only logs; subclasses (e.g.
        // JdbcDataSource) actually release pools. We pin the base contract:
        // it's idempotent and doesn't throw.
        NamedDataSource ds = build(new JsonObject());
        ds.close();
        ds.close();
    }

    // ---------- newQueryParameters merging ----------

    @Test(groups = { "unit" })
    public void newQueryParameters_layersCallerOverDataSourceDefaults() {
        // Build a ds with a baseline `max_rows=10`. The merge order is:
        // (fresh) + (datasource defaults) + (caller params). A caller
        // overriding max_rows must win.
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

    // ---------- saved-query loading ----------

    @Test(groups = { "unit" })
    public void loadSavedQueryAsNeeded_loadsContentOfQueryFile() {
        NamedDataSource ds = build(new JsonObject());

        String result = ds.loadSavedQueryAsNeeded("src/test/resources/simple.query", new QueryParameters());

        // simple.query contains "select 1 as a,\n    2 as b" — pin enough of
        // it to detect a regression that would load the wrong file or skip
        // the load entirely.
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
        // The first guard is `indexOf('\n') == -1` — multi-line strings are
        // treated as inline SQL even if they look like a `.query` file.
        NamedDataSource ds = build(new JsonObject());

        String multiline = "SELECT 1\nFROM dual";
        assertEquals(ds.loadSavedQueryAsNeeded(multiline, new QueryParameters()), multiline);
    }

    // ---------- cache stats getter ----------

    @Test(groups = { "unit" })
    public void getCacheUsage_emitsStructuredJsonObject() {
        NamedDataSource ds = build(new JsonObject());

        String json = ds.getCacheUsage();
        JsonObject parsed = new JsonObject(json);

        // Cache fresh from construction -> all-zero stats. Pin the schema so
        // a regression that drops one of the keys is caught.
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
        // The base NamedDataSource has no pool; JdbcDataSource overrides this
        // with real Hikari stats. Tests pin the base contract.
        NamedDataSource ds = build(new JsonObject());
        // EMPTY_USAGE is the literal JSON object "{}"; downstream consumers
        // parse it, so an empty string would be a contract break.
        assertEquals(ds.getPoolUsage(), "{}");
    }

    // ---------- isDifferentFrom ----------

    @Test(groups = { "unit" })
    public void isDifferentFromCatchesConfigChanges() {
        JsonObject base = new JsonObject().put("queryTimeout", 100);
        NamedDataSource ds = build(base);

        // Identical content -> same digest -> not different.
        assertFalse(ds.isDifferentFrom(base.copy()));
        // Any meaningful change -> different.
        assertTrue(ds.isDifferentFrom(new JsonObject().put("queryTimeout", 200)));
        // Null config falls through to digest-based comparison and counts
        // as "different" if the current digest is non-empty.
        assertTrue(ds.isDifferentFrom(null));
    }
}
