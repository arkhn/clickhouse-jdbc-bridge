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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Method;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link BaseRepository#update(String, JsonObject)} — the
 * config-reload entry point used by JsonFileRepository's filesystem
 * scanner. This is the path that gets called every time a datasource
 * JSON file is added, changed, or touched on disk.
 *
 * <p>Also covers the {@code createFromType} / {@code createFromConfig}
 * factory helpers and the type-registry resolution.</p>
 */
public class BaseRepositoryUpdateTest {

    static class CountingRepo extends BaseRepository<NamedDataSource> {
        int adds = 0;
        int removes = 0;

        CountingRepo() {
            super(NamedDataSource.class);
        }

        @Override
        protected void atomicAdd(NamedDataSource entity) {
            adds++;
        }

        @Override
        protected void atomicRemove(NamedDataSource entity) {
            removes++;
        }
    }

    /** Reflection bridge — update is protected. We're in the same package
     *  (core) so we could call it directly, but I want the test pattern to
     *  be obvious in the source. */
    private static void callUpdate(BaseRepository<NamedDataSource> repo, String id, JsonObject cfg)
            throws Exception {
        Method m = BaseRepository.class.getDeclaredMethod("update", String.class, JsonObject.class);
        m.setAccessible(true);
        m.invoke(repo, id, cfg);
    }

    // ---------- update() ----------

    @Test(groups = { "unit" })
    public void update_newEntityIsAdded() throws Exception {
        CountingRepo repo = new CountingRepo();
        JsonObject cfg = new JsonObject().put("type", "default");

        callUpdate(repo, "ds1", cfg);

        assertNotNull(repo.get("ds1"));
        assertEquals(repo.adds, 1, "atomicAdd must fire on first registration");
    }

    @Test(groups = { "unit" })
    public void update_sameConfigIsNoOp() throws Exception {
        CountingRepo repo = new CountingRepo();
        // Same config encoded both times -> digest matches -> no replace.
        JsonObject cfg = new JsonObject().put("type", "default").put("setting", "value");

        callUpdate(repo, "ds1", cfg);
        int addsAfterFirst = repo.adds;
        int removesAfterFirst = repo.removes;

        callUpdate(repo, "ds1", cfg.copy());

        assertEquals(repo.adds, addsAfterFirst,
                "identical config must not re-register the entity");
        assertEquals(repo.removes, removesAfterFirst);
    }

    @Test(groups = { "unit" })
    public void update_changedConfigTriggersRemoveThenAdd() throws Exception {
        CountingRepo repo = new CountingRepo();
        callUpdate(repo, "ds1", new JsonObject().put("v", 1));

        callUpdate(repo, "ds1", new JsonObject().put("v", 2));

        assertEquals(repo.adds, 2, "different config must re-add");
        assertEquals(repo.removes, 1);
    }

    @Test(groups = { "unit" })
    public void update_nullConfigOnUnknownIdIsNoOp() throws Exception {
        // No existing entity + null config -> nothing to do.
        CountingRepo repo = new CountingRepo();

        callUpdate(repo, "never-existed", null);

        assertEquals(repo.adds, 0);
        assertEquals(repo.removes, 0);
    }

    @Test(groups = { "unit" })
    public void update_addAliasFromConfig() throws Exception {
        CountingRepo repo = new CountingRepo();
        JsonObject cfg = new JsonObject().put("aliases", new JsonArray().add("alias-a"));

        callUpdate(repo, "ds1", cfg);

        assertNotNull(repo.get("ds1"));
        assertSame(repo.get("alias-a"), repo.get("ds1"),
                "alias must resolve to the same entity");
    }

    @Test(groups = { "unit" })
    public void update_aliasCollisionIsLoggedAndSkipped() throws Exception {
        // Two entities, the second claims an alias that already exists.
        // The mapping is preserved for the first entity; second entity's
        // primary id still registers but the colliding alias is dropped.
        CountingRepo repo = new CountingRepo();
        callUpdate(repo, "ds1", new JsonObject().put("aliases", new JsonArray().add("shared")));
        callUpdate(repo, "ds2", new JsonObject().put("aliases", new JsonArray().add("shared")));

        assertNotNull(repo.get("ds1"));
        assertNotNull(repo.get("ds2"));
        // The "shared" alias must still point at ds1 (first registrant).
        assertSame(repo.get("shared"), repo.get("ds1"));
    }

    @Test(groups = { "unit" })
    public void update_constructorFailureIsSwallowedNotPropagated() throws Exception {
        // BaseRepository.update catches RuntimeException + Exception around
        // the entity construction. A bad config that makes
        // createFromConfig throw must NOT crash the whole reload — the
        // scanner needs to keep running for other datasources.
        //
        // We trigger this by passing a config that the default extension's
        // constructor will reject. NamedDataSource has no "always rejects"
        // path for arbitrary config, so we use the type registry to point
        // at a deliberately-broken extension.
        CountingRepo repo = new CountingRepo() {
            @Override
            protected NamedDataSource createFromConfig(String id, JsonObject config) {
                throw new IllegalArgumentException("simulated broken config");
            }
        };

        // Must NOT throw out of update — caught by the catch (Exception e).
        callUpdate(repo, "broken", new JsonObject().put("anything", true));

        assertNull(repo.get("broken"),
                "failed registration must NOT leave a partial entity in the map");
        assertEquals(repo.adds, 0, "atomicAdd must NOT be called on failure");
    }

    // ---------- getExtensionByType / createFromType ----------

    @Test(groups = { "unit" })
    public void getExtensionByType_withRegisteredType_returnsIt() {
        CountingRepo repo = new CountingRepo();
        Extension<NamedDataSource> ext = new Extension<>(NamedDataSource.class);
        repo.registerType("jdbc", ext);

        // get() with a "jdbc:..." id triggers createFromType which calls
        // getExtensionByType(type, false). The matching type is returned.
        // No public API directly returns the extension; we test the flow:
        // a get() against a registered type returns a new entity.
        NamedDataSource ds = repo.get("jdbc:my-test-uri");
        assertNotNull(ds, "registered type 'jdbc' must yield an adhoc entity");
        assertEquals(ds.getId(), "jdbc:my-test-uri");
    }

    @Test(groups = { "unit" })
    public void getExtensionByType_unknownTypeReturnsNull_when_not_auto_create() {
        // createFromType passes autoCreate=false. Unknown type + no
        // autoCreate -> IAE thrown from getExtensionByType, but
        // createFromType catches via its own logic — actually it returns
        // extension==null and yields null. Let me re-check.
        //
        // Actually getExtensionByType throws IAE when autoCreate=false and
        // the type isn't registered. The exception propagates out of
        // createFromType, then out of get(). Pin that contract.
        CountingRepo repo = new CountingRepo();
        repo.registerType("jdbc", new Extension<>(NamedDataSource.class));

        // The type-prefix "other" isn't registered. get("other:xxx") tries
        // createFromType("other:xxx", "other") which throws IAE.
        assertThrows(IllegalArgumentException.class, () -> repo.get("other:xxx"));
    }

    // ---------- accept(Class<?>) with null and subclass ----------

    @Test(groups = { "unit" })
    public void accept_acceptsSupertypeOfDeclaredEntity() {
        // accept(c) returns c != null && c.isAssignableFrom(this.clazz)
        // — i.e. accepts c IF c is a supertype of the repo's declared
        // entity class. So a NamedDataSourceRepo accepts ManagedEntity.
        CountingRepo repo = new CountingRepo();
        assertTrue(repo.accept(ManagedEntity.class),
                "repo declared for NamedDataSource must accept ManagedEntity (supertype)");
    }

    // ---------- update() with null entity and null config ----------

    @Test(groups = { "unit" })
    public void update_nullId_nullConfigPath() throws Exception {
        CountingRepo repo = new CountingRepo();
        // mappings.get(null) is null -> addEntity = true.
        // Then `if (addEntity && config != null)` is false -> short-circuit.
        callUpdate(repo, null, null);

        assertEquals(repo.adds, 0);
    }
}
