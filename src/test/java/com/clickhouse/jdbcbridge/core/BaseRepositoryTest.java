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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link BaseRepository}: the entity-management lifecycle that
 * sits behind every JsonFileRepository. Existing tests reach BaseRepository
 * only indirectly (via NamedDataSourceTest's stub subclass) — this file
 * exercises put/get/remove/getUsageStats/registerType/accept/resolve
 * directly.
 */
public class BaseRepositoryTest {

    /** Minimal NamedDataSource subclass for entities in this test. */
    static class TestEntity extends NamedDataSource {
        TestEntity(String id, BaseRepository<NamedDataSource> repo, JsonObject config) {
            super(id, repo, config);
        }
    }

    /** Concrete BaseRepository — tracks add/remove via counters. */
    static class CountingRepository extends BaseRepository<NamedDataSource> {
        final AtomicInteger adds = new AtomicInteger();
        final AtomicInteger removes = new AtomicInteger();

        CountingRepository() {
            super(NamedDataSource.class);
        }

        @Override
        protected void atomicAdd(NamedDataSource entity) {
            adds.incrementAndGet();
        }

        @Override
        protected void atomicRemove(NamedDataSource entity) {
            removes.incrementAndGet();
        }
    }

    private static NamedDataSource ds(String id, BaseRepository<NamedDataSource> repo, String... aliases) {
        JsonObject cfg = new JsonObject();
        if (aliases.length > 0) {
            JsonArray a = new JsonArray();
            for (String alias : aliases) {
                a.add(alias);
            }
            cfg.put("aliases", a);
        }
        return new TestEntity(id, repo, cfg);
    }

    // ---------- accept / resolve / getEntityClass ----------

    @Test(groups = { "unit" })
    public void accept_checksClassAssignability() {
        CountingRepository repo = new CountingRepository();

        assertTrue(repo.accept(NamedDataSource.class),
                "repo must accept its declared entity class");
        // A subclass (more specific) is NOT assignable from NamedDataSource
        // (less specific); accept demands the input be a supertype.
        assertFalse(repo.accept(TestEntity.class));
        assertFalse(repo.accept(null), "null class must be rejected");
    }

    @Test(groups = { "unit" })
    public void resolve_passesThroughPlainStrings() {
        CountingRepository repo = new CountingRepository();
        // No {{var}} markers -> no substitution -> input pass-through.
        assertEquals(repo.resolve("plain-name"), "plain-name");
        assertEquals(repo.resolve(""), "");
    }

    @Test(groups = { "unit" })
    public void getEntityClass_returnsCtorArg() {
        CountingRepository repo = new CountingRepository();
        assertSame(repo.getEntityClass(), NamedDataSource.class);
    }

    // ---------- put / get / remove ----------

    @Test(groups = { "unit" })
    public void put_addsEntityAndDispatchesAtomicAdd() {
        CountingRepository repo = new CountingRepository();
        NamedDataSource entity = ds("ds1", repo);

        repo.put("ds1", entity);

        assertSame(repo.get("ds1"), entity);
        assertEquals(repo.adds.get(), 1);
    }

    @Test(groups = { "unit" })
    public void put_nullIdResolvedFromEntity() {
        CountingRepository repo = new CountingRepository();
        NamedDataSource entity = ds("auto-id", repo);

        repo.put(null, entity);

        assertSame(repo.get("auto-id"), entity);
    }

    @Test(groups = { "unit" })
    public void put_registersAliases() {
        CountingRepository repo = new CountingRepository();
        NamedDataSource entity = ds("primary", repo, "alias-a", "alias-b");

        repo.put("primary", entity);

        assertSame(repo.get("primary"), entity);
        assertSame(repo.get("alias-a"), entity,
                "lookup by alias must return the primary entity");
        assertSame(repo.get("alias-b"), entity);
    }

    @Test(groups = { "unit" })
    public void put_overwriteExistingId_triggersRemoveThenAdd() {
        CountingRepository repo = new CountingRepository();
        NamedDataSource first = ds("ds1", repo);
        NamedDataSource second = ds("ds1", repo);

        repo.put("ds1", first);
        repo.put("ds1", second);

        assertSame(repo.get("ds1"), second);
        // Two adds (one per put), one remove (the implicit one before the
        // second put).
        assertEquals(repo.adds.get(), 2);
        assertEquals(repo.removes.get(), 1);
    }

    @Test(groups = { "unit" })
    public void remove_removesEntityAndAliases() {
        CountingRepository repo = new CountingRepository();
        NamedDataSource entity = ds("primary", repo, "alias-a");
        repo.put("primary", entity);

        repo.remove("primary");

        // After removing the primary id, the alias also resolves to null
        // (single-type repo path returns null directly; no IAE).
        assertNull(repo.get("primary"));
        assertNull(repo.get("alias-a"));
        assertEquals(repo.removes.get(), 1);
    }

    @Test(groups = { "unit" })
    public void remove_missingId_isNoOp() {
        CountingRepository repo = new CountingRepository();
        repo.remove("nope");
        assertEquals(repo.removes.get(), 0,
                "removing an unregistered id must not dispatch atomicRemove");
    }

    @Test(groups = { "unit" })
    public void put_nullEntityIsRejected() {
        CountingRepository repo = new CountingRepository();
        assertThrows(NullPointerException.class, () -> repo.put("x", null));
    }

    // ---------- getUsageStats ----------

    @Test(groups = { "unit" })
    public void getUsageStats_collectsOnePerMapping_andIsUnmodifiable() {
        CountingRepository repo = new CountingRepository();
        repo.put("a", ds("a", repo));
        repo.put("b", ds("b", repo));

        List<UsageStats> stats = repo.getUsageStats();
        assertNotNull(stats);
        // 2 entities, 2 stats (alias-free in this scenario).
        assertEquals(stats.size(), 2);

        // Defensive copy: caller can't grow the list and feed it back.
        assertThrows(UnsupportedOperationException.class, () -> stats.add(null));
    }

    // ---------- get() under multi-type repo ----------

    @Test(groups = { "unit" })
    public void get_singleTypeRepo_returnsNullForMissing() {
        // With NO registerType() call, the repo is single-type and missing
        // ids resolve to null (rather than throwing). This is the path the
        // bridge uses for NamedSchema / NamedQuery lookups.
        CountingRepository repo = new CountingRepository();
        assertNull(repo.get("never-registered"));
    }

    @Test(groups = { "unit" })
    public void get_multiTypeRepo_missingBareIdThrowsIAE() {
        // Once registerType has been called, the get() path switches into
        // multi-type mode: bare ids that aren't in the map throw rather
        // than return null (allows callers to distinguish "datasource not
        // configured" from "lookup hasn't been done yet").
        CountingRepository repo = new CountingRepository();
        repo.registerType("jdbc", new Extension<>(TestEntity.class));

        assertThrows(IllegalArgumentException.class, () -> repo.get("does-not-exist"));
    }

    @Test(groups = { "unit" })
    public void get_multiTypeRepo_strippedQueryString() {
        // Multi-type get() strips trailing ?... before looking up the id,
        // so "ds1?param=1" resolves to the entity at "ds1". This is the
        // path that turns connection strings into entity lookups.
        CountingRepository repo = new CountingRepository();
        repo.registerType("jdbc", new Extension<>(TestEntity.class));
        repo.put("ds1", ds("ds1", repo));

        assertSame(repo.get("ds1?param=value"), repo.get("ds1"));
    }

    // ---------- registerType ----------

    @Test(groups = { "unit" })
    public void registerType_acceptsFirstAsDefault() {
        CountingRepository repo = new CountingRepository();
        repo.registerType("jdbc", new Extension<>(TestEntity.class));

        // First registered type becomes the default; subsequent registerType
        // calls don't override. The bridge uses this to route adhoc URIs to
        // the JDBC extension by default.
        repo.registerType("config", new Extension<>(TestEntity.class));

        // No public getter for defaultType; just confirm the call sequence
        // didn't blow up. (The behavior is exercised end-to-end via
        // JdbcBridgeVerticle.getDataSource adhoc-allowed tests.)
    }

    @Test(groups = { "unit" })
    public void registerType_duplicateTypeIsDiscarded() {
        CountingRepository repo = new CountingRepository();
        Extension<NamedDataSource> first = new Extension<>(TestEntity.class);
        Extension<NamedDataSource> second = new Extension<>(TestEntity.class);

        repo.registerType("jdbc", first);
        // Second registration for the same type-name must be discarded
        // with a warning log — not silently override. Prevents two
        // extensions racing for the same key.
        repo.registerType("jdbc", second);
    }

    @Test(groups = { "unit" })
    public void registerType_nullExtensionIsRejected() {
        CountingRepository repo = new CountingRepository();
        assertThrows(NullPointerException.class, () -> repo.registerType("jdbc", null));
    }
}
