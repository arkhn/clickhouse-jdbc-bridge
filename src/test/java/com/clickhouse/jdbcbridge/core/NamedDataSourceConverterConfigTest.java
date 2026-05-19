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
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link NamedDataSource} config branches not covered by the
 * existing test files: custom DataTypeConverter loading, custom type
 * mappings, cache config parsing, and the parametersAsJsonString
 * round-trip used by the SHOW DATASOURCES admin endpoint.
 */
public class NamedDataSourceConverterConfigTest {

    private static NamedDataSource build(JsonObject cfg) {
        return new NamedDataSource("nds-conv",
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class), cfg);
    }

    // ---------- custom converter block ----------

    @Test(groups = { "unit" })
    public void converterBlock_emptyClassFallsBackToDefault() {
        // CONF_CONVERTER with no `class` field falls back to the default
        // converter — exercised at line 288-291 of NamedDataSource.
        JsonObject cfg = new JsonObject().put("converter",
                new JsonObject()); // empty {} -> empty mappings, no explicit class

        NamedDataSource ds = build(cfg);

        // Construction must succeed; the converter is private but we
        // confirm a downstream operation that depends on it works.
        assertNotNull(ds.getDefaultValues());
    }

    @Test(groups = { "unit" })
    public void converterBlock_customMappingsAreParsed() {
        // CONF_MAPPINGS array carries DataTypeMapping entries — each is a
        // {jdbcType, nativeType, to} triple. We pass one valid mapping and
        // confirm the ds still builds (the mapping is stored in a private
        // field, but failure to parse would throw out of Utils.loadExtension).
        JsonObject cfg = new JsonObject().put("converter", new JsonObject()
                .put("mappings", new JsonArray()
                        .add(new JsonObject()
                                .put("jdbcType", "VARCHAR")
                                .put("nativeType", "*")
                                .put("to", "Str"))));

        NamedDataSource ds = build(cfg);
        assertEquals(ds.getId(), "nds-conv");
    }

    @Test(groups = { "unit" })
    public void converterBlock_nonJsonObjectEntriesInMappingsAreSkipped() {
        // The mappings loop has `if (m instanceof JsonObject)` — a raw
        // string in the array is silently ignored rather than crashing
        // the whole datasource registration. Pin that defensive contract.
        JsonObject cfg = new JsonObject().put("converter", new JsonObject()
                .put("mappings", new JsonArray()
                        .add("this is not a json object")
                        .add(new JsonObject()
                                .put("jdbcType", "INTEGER")
                                .put("nativeType", "*")
                                .put("to", "Int32"))));

        NamedDataSource ds = build(cfg); // must not throw
        assertEquals(ds.getId(), "nds-conv");
    }

    @Test(groups = { "unit" })
    public void converterBlock_unknownConverterClassNameFallsBackSilently() {
        // Loading a converter class that doesn't exist must not crash the
        // datasource — the constructor catches the exception, logs a warn,
        // and proceeds with the default converter. Worst regression
        // possible: a typo in the converter class kills the whole bridge.
        JsonObject cfg = new JsonObject().put("converter", new JsonObject()
                .put("class", "com.example.DefinitelyDoesNotExist"));

        NamedDataSource ds = build(cfg); // must not throw
        assertEquals(ds.getId(), "nds-conv");
    }

    // ---------- cache config ----------

    @Test(groups = { "unit" })
    public void cacheConfig_columnsCacheSizeAndExpirationParsed() {
        // CONF_CACHE -> CONF_COLUMNS -> {size, expiration} maps to the
        // Caffeine columnsCache builder. We can't introspect the cache
        // config directly, but we confirm the construction path completes
        // and the cache is functional.
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("columns", new JsonObject()
                        .put("size", 500)
                        .put("expiration", 60)));

        NamedDataSource ds = build(cfg);

        String cacheUsage = ds.getCacheUsage();
        JsonObject parsed = new JsonObject(cacheUsage);
        // Fresh cache, all counters at 0.
        assertEquals(parsed.getInteger("hitCount"), Integer.valueOf(0));
        assertEquals(parsed.getInteger("missCount"), Integer.valueOf(0));
    }

    @Test(groups = { "unit" })
    public void cacheConfig_nonColumnsCacheNameIsIgnored() {
        // The loop has `if (CONF_COLUMNS.equals(cacheName))`; entries
        // under any other name are skipped. Pin so a future feature that
        // adds a second cache must explicitly add a branch.
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("unrecognized-cache-name", new JsonObject().put("size", 999)));

        NamedDataSource ds = build(cfg); // must not throw
        assertNotNull(ds.getCacheUsage());
    }

    @Test(groups = { "unit" })
    public void cacheConfig_columnsValueMustBeJsonObject() {
        // `entry.getValue() instanceof JsonObject` guards the cast.
        // Passing a non-JsonObject for `columns` must be silently skipped.
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("columns", "not-a-json-object"));

        NamedDataSource ds = build(cfg);
        assertNotNull(ds.getCacheUsage());
    }

    // ---------- driverUrls + driverClassLoader ----------

    @Test(groups = { "unit" })
    public void driverUrls_nullEntriesAreFiltered() {
        // The driverUrls loop only adds entries that are String AND non-empty.
        // A JsonArray with a null entry must be silently skipped.
        JsonObject cfg = new JsonObject().put("driverUrls", new JsonArray()
                .add("file:///lib/a.jar")
                .addNull()
                .add(42)            // wrong type
                .add(""));          // empty string

        NamedDataSource ds = build(cfg);

        assertEquals(ds.getDriverUrls().size(), 1,
                "only the valid String entry must be retained");
        assertTrue(ds.getDriverUrls().contains("file:///lib/a.jar"));
    }

    // ---------- parametersAsJsonString ----------

    @Test(groups = { "unit" })
    public void parametersAsJsonString_emitsValidJsonWithIdAndSealed() {
        NamedDataSource ds = build(new JsonObject());

        String json = ds.getParametersAsJsonString();
        JsonObject parsed = new JsonObject(json);

        // The id is always emitted; sealed is always emitted (defaulting
        // to false). These drive the SHOW DATASOURCES admin response.
        assertEquals(parsed.getString("id"), "nds-conv");
        assertEquals(parsed.getBoolean("sealed"), Boolean.FALSE);
        // parameters is also always emitted (queryParameters.toJson()).
        assertNotNull(parsed.getJsonObject("parameters"));
    }

    @Test(groups = { "unit" })
    public void parametersAsJsonString_omitsAliasesWhenEmpty() {
        // Aliases collection of size <= 1 (just the id itself) omits the
        // aliases field from the JSON. Pin so a future refactor that
        // always-emits empty aliases doesn't sneak in.
        NamedDataSource ds = build(new JsonObject());

        JsonObject parsed = new JsonObject(ds.getParametersAsJsonString());

        assertFalse(parsed.containsKey("aliases"),
                "no aliases configured -> aliases key must be absent");
    }

    @Test(groups = { "unit" })
    public void parametersAsJsonString_includesAliasesWhenPresent() {
        NamedDataSource ds = build(new JsonObject()
                .put("aliases", new JsonArray().add("a1").add("a2")));

        JsonObject parsed = new JsonObject(ds.getParametersAsJsonString());

        assertTrue(parsed.containsKey("aliases"));
        // Aliases > 1 -> field emitted.
        assertEquals(parsed.getJsonArray("aliases").size(), 2);
    }
}
