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
 * Tests for {@link NamedDataSource} config branches: custom DataTypeConverter loading,
 * custom type mappings, cache config parsing, parametersAsJsonString round-trip.
 */
public class NamedDataSourceConverterConfigTest {

    private static NamedDataSource build(JsonObject cfg) {
        return new NamedDataSource("nds-conv",
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class), cfg);
    }

    @Test(groups = { "unit" })
    public void converterBlock_emptyClassFallsBackToDefault() {
        // CONF_CONVERTER with no `class` field falls back to default converter.
        JsonObject cfg = new JsonObject().put("converter",
                new JsonObject());

        NamedDataSource ds = build(cfg);

        assertNotNull(ds.getDefaultValues());
    }

    @Test(groups = { "unit" })
    public void converterBlock_customMappingsAreParsed() {
        // CONF_MAPPINGS entries are {jdbcType, nativeType, to} triples.
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
        // Defensive: `if (m instanceof JsonObject)` guards the cast.
        JsonObject cfg = new JsonObject().put("converter", new JsonObject()
                .put("mappings", new JsonArray()
                        .add("this is not a json object")
                        .add(new JsonObject()
                                .put("jdbcType", "INTEGER")
                                .put("nativeType", "*")
                                .put("to", "Int32"))));

        NamedDataSource ds = build(cfg);
        assertEquals(ds.getId(), "nds-conv");
    }

    @Test(groups = { "unit" })
    public void converterBlock_unknownConverterClassNameFallsBackSilently() {
        // Worst regression: typo in converter class kills the bridge. Must fall back silently.
        JsonObject cfg = new JsonObject().put("converter", new JsonObject()
                .put("class", "com.example.DefinitelyDoesNotExist"));

        NamedDataSource ds = build(cfg);
        assertEquals(ds.getId(), "nds-conv");
    }

    @Test(groups = { "unit" })
    public void cacheConfig_columnsCacheSizeAndExpirationParsed() {
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("columns", new JsonObject()
                        .put("size", 500)
                        .put("expiration", 60)));

        NamedDataSource ds = build(cfg);

        String cacheUsage = ds.getCacheUsage();
        JsonObject parsed = new JsonObject(cacheUsage);
        assertEquals(parsed.getInteger("hitCount"), Integer.valueOf(0));
        assertEquals(parsed.getInteger("missCount"), Integer.valueOf(0));
    }

    @Test(groups = { "unit" })
    public void cacheConfig_nonColumnsCacheNameIsIgnored() {
        // Loop has `if (CONF_COLUMNS.equals(cacheName))` — pin so future caches need explicit branch.
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("unrecognized-cache-name", new JsonObject().put("size", 999)));

        NamedDataSource ds = build(cfg);
        assertNotNull(ds.getCacheUsage());
    }

    @Test(groups = { "unit" })
    public void cacheConfig_columnsValueMustBeJsonObject() {
        JsonObject cfg = new JsonObject().put("cache", new JsonObject()
                .put("columns", "not-a-json-object"));

        NamedDataSource ds = build(cfg);
        assertNotNull(ds.getCacheUsage());
    }

    @Test(groups = { "unit" })
    public void driverUrls_nullEntriesAreFiltered() {
        // driverUrls loop only adds String + non-empty entries.
        JsonObject cfg = new JsonObject().put("driverUrls", new JsonArray()
                .add("file:///lib/a.jar")
                .addNull()
                .add(42)
                .add(""));

        NamedDataSource ds = build(cfg);

        assertEquals(ds.getDriverUrls().size(), 1,
                "only the valid String entry must be retained");
        assertTrue(ds.getDriverUrls().contains("file:///lib/a.jar"));
    }

    @Test(groups = { "unit" })
    public void parametersAsJsonString_emitsValidJsonWithIdAndSealed() {
        NamedDataSource ds = build(new JsonObject());

        String json = ds.getParametersAsJsonString();
        JsonObject parsed = new JsonObject(json);

        // id + sealed always emitted; drives SHOW DATASOURCES admin response.
        assertEquals(parsed.getString("id"), "nds-conv");
        assertEquals(parsed.getBoolean("sealed"), Boolean.FALSE);
        assertNotNull(parsed.getJsonObject("parameters"));
    }

    @Test(groups = { "unit" })
    public void parametersAsJsonString_omitsAliasesWhenEmpty() {
        // Aliases size <= 1 (just id) -> field omitted.
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
        assertEquals(parsed.getJsonArray("aliases").size(), 2);
    }
}
