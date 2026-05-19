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
package com.clickhouse.jdbcbridge.impl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.NamedDataSource;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link JsonFileRepository#reload(JsonObject)} — filesystem-scanner
 * entry point covering add/update/remove flow.
 */
public class JsonFileRepositoryReloadTest {

    private static JsonFileRepository<NamedDataSource> repo() {
        return new JsonFileRepository<>(NamedDataSource.class);
    }

    private static JsonObject dsCfg() {
        return new JsonObject().put("type", "test");
    }

    @Test(groups = { "unit" })
    public void reload_addsNewEntries() {
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject config = new JsonObject()
                .put("ds1", dsCfg())
                .put("ds2", dsCfg());

        r.reload(config);

        assertNotNull(r.get("ds1"));
        assertNotNull(r.get("ds2"));
    }

    @Test(groups = { "unit" })
    public void reload_idempotentForUnchangedConfig() {
        // Same content -> digest match -> no re-register -> same instance.
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject config = new JsonObject().put("ds1", dsCfg());

        r.reload(config);
        NamedDataSource first = r.get("ds1");

        r.reload(config.copy());
        NamedDataSource second = r.get("ds1");

        assertEquals(second, first,
                "identical reload must reuse the existing entity instance");
    }

    @Test(groups = { "unit" })
    public void reload_removesEntriesNoLongerInConfig() {
        // Powers "delete JSON file -> bridge forgets datasource" UX.
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()).put("ds2", dsCfg()));
        assertNotNull(r.get("ds1"));
        assertNotNull(r.get("ds2"));

        r.reload(new JsonObject().put("ds1", dsCfg()));

        assertNotNull(r.get("ds1"));
        assertNull(r.get("ds2"));
    }

    @Test(groups = { "unit" })
    public void reload_updateExistingEntry_swapsInstance() {
        // Same id, different config -> isDifferentFrom true -> entity replaced.
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()));
        NamedDataSource v1 = r.get("ds1");

        r.reload(new JsonObject().put("ds1", new JsonObject()
                .put("type", "test")
                .put("queryTimeout", 9999)));

        NamedDataSource v2 = r.get("ds1");
        assertNotNull(v2);
        org.testng.Assert.assertNotSame(v2, v1,
                "changed config must yield a new entity instance");
    }

    @Test(groups = { "unit" })
    public void reload_skipsNonJsonObjectValues() {
        // Mis-typed entries must be silently skipped — don't crash the reload.
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject config = new JsonObject()
                .put("good", dsCfg())
                .put("bad-shape", "this is not a json object");

        r.reload(config);

        assertNotNull(r.get("good"));
        assertNull(r.get("bad-shape"));
    }

    @Test(groups = { "unit" })
    public void reload_emptyConfigClearsExistingEntries() {
        // Pin "wipe on empty" — refactor to "preserve when config goes empty" would be silent surprise.
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()).put("ds2", dsCfg()));
        assertNotNull(r.get("ds1"));

        r.reload(new JsonObject());

        assertNull(r.get("ds1"));
        assertNull(r.get("ds2"));
    }

    @Test(groups = { "unit" })
    public void reload_nullConfigClearsExistingEntries() {
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()));
        assertNotNull(r.get("ds1"));

        r.reload(null);

        assertNull(r.get("ds1"));
    }

    @Test(groups = { "unit" })
    public void reload_aliasedEntryIsResolvedViaAliasAfterReload() {
        // Aliases must still resolve after a content-stable reload.
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject cfg = new JsonObject()
                .put("primary", new JsonObject()
                        .put("aliases", new JsonArray().add("alias-a")));

        r.reload(cfg);

        assertNotNull(r.get("primary"));
        assertNotNull(r.get("alias-a"));
        assertEquals(r.get("alias-a"), r.get("primary"));
    }

    @Test(groups = { "unit" })
    public void newInstance_rejectsTooFewArgs() {
        org.testng.Assert.assertThrows(IllegalArgumentException.class,
                () -> JsonFileRepository.newInstance("only-one"));
        org.testng.Assert.assertThrows(NullPointerException.class,
                () -> JsonFileRepository.newInstance((Object[]) null));
    }
}
