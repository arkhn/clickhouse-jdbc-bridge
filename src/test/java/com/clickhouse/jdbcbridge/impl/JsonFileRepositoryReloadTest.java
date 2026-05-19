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
 * Tests for {@link JsonFileRepository#reload(JsonObject)} — the
 * filesystem-scanner entry point. Existing JsonFileRepositoryTest was
 * at ~3% coverage on this class; this file walks reload's add /
 * update / remove flow.
 *
 * <p>Every config-file change on disk triggers reload. A regression
 * here would stop the bridge from picking up new datasources without
 * a restart.</p>
 */
public class JsonFileRepositoryReloadTest {

    private static JsonFileRepository<NamedDataSource> repo() {
        return new JsonFileRepository<>(NamedDataSource.class);
    }

    private static JsonObject dsCfg() {
        // Minimal NamedDataSource config — no jdbcUrl required since
        // NamedDataSource (not JdbcDataSource) doesn't need one.
        return new JsonObject().put("type", "test");
    }

    // ---------- reload with content ----------

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
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject config = new JsonObject().put("ds1", dsCfg());

        r.reload(config);
        NamedDataSource first = r.get("ds1");

        r.reload(config.copy());
        NamedDataSource second = r.get("ds1");

        // Same content -> digest match -> no re-register -> same instance.
        assertEquals(second, first,
                "identical reload must reuse the existing entity instance");
    }

    @Test(groups = { "unit" })
    public void reload_removesEntriesNoLongerInConfig() {
        // Subsequent reload that drops an id must remove the entity.
        // This is what powers the "delete the JSON file -> bridge forgets
        // the datasource" UX.
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()).put("ds2", dsCfg()));
        assertNotNull(r.get("ds1"));
        assertNotNull(r.get("ds2"));

        r.reload(new JsonObject().put("ds1", dsCfg()));

        assertNotNull(r.get("ds1"));
        // ds2 was dropped from the new config -> removed from the repo.
        assertNull(r.get("ds2"));
    }

    @Test(groups = { "unit" })
    public void reload_updateExistingEntry_swapsInstance() {
        // Same id but different config -> isDifferentFrom returns true
        // -> entity is replaced.
        JsonFileRepository<NamedDataSource> r = repo();
        r.reload(new JsonObject().put("ds1", dsCfg()));
        NamedDataSource v1 = r.get("ds1");

        // Change a field to trigger digest mismatch.
        r.reload(new JsonObject().put("ds1", new JsonObject()
                .put("type", "test")
                .put("queryTimeout", 9999)));

        NamedDataSource v2 = r.get("ds1");
        assertNotNull(v2);
        // Different config -> different instance.
        org.testng.Assert.assertNotSame(v2, v1,
                "changed config must yield a new entity instance");
    }

    @Test(groups = { "unit" })
    public void reload_skipsNonJsonObjectValues() {
        // Defensive: a non-JsonObject value under an id (e.g. a stray
        // string) must be silently skipped — the scanner ignores
        // mis-typed entries rather than crashing the whole reload.
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject config = new JsonObject()
                .put("good", dsCfg())
                .put("bad-shape", "this is not a json object");

        r.reload(config);

        assertNotNull(r.get("good"));
        // The bad entry didn't register; in multi-type mode (no types
        // registered here = single-type) get() returns null for missing.
        assertNull(r.get("bad-shape"));
    }

    // ---------- reload with empty/null ----------

    @Test(groups = { "unit" })
    public void reload_emptyConfigClearsExistingEntries() {
        // When the scanner finds an empty config dir (or all files
        // deleted), reload(empty-or-null) wipes the repo. Pin this so a
        // refactor to "preserve entries when config goes empty" gets
        // caught — that would be a silent surprise.
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
        // Aliases declared in config should resolve to the entity even
        // after a reload that doesn't change content.
        JsonFileRepository<NamedDataSource> r = repo();
        JsonObject cfg = new JsonObject()
                .put("primary", new JsonObject()
                        .put("aliases", new JsonArray().add("alias-a")));

        r.reload(cfg);

        assertNotNull(r.get("primary"));
        assertNotNull(r.get("alias-a"));
        // Both must resolve to the same instance.
        assertEquals(r.get("alias-a"), r.get("primary"));
    }

    // ---------- newInstance factory ----------

    @Test(groups = { "unit" })
    public void newInstance_rejectsTooFewArgs() {
        // Factory requires at least (ExtensionManager, Class). One arg or
        // null array -> IAE / NPE.
        org.testng.Assert.assertThrows(IllegalArgumentException.class,
                () -> JsonFileRepository.newInstance("only-one"));
        org.testng.Assert.assertThrows(NullPointerException.class,
                () -> JsonFileRepository.newInstance((Object[]) null));
    }
}
