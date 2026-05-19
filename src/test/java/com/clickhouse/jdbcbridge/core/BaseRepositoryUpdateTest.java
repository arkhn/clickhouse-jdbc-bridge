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
 * Tests for {@link BaseRepository#update(String, JsonObject)} — config-reload entry point
 * used by JsonFileRepository's filesystem scanner.
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

    // Reflection bridge — update is protected.
    private static void callUpdate(BaseRepository<NamedDataSource> repo, String id, JsonObject cfg)
            throws Exception {
        Method m = BaseRepository.class.getDeclaredMethod("update", String.class, JsonObject.class);
        m.setAccessible(true);
        m.invoke(repo, id, cfg);
    }

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
        // Same config both times -> digest matches -> no replace.
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
        // Second entity's primary id still registers but its colliding alias is dropped.
        CountingRepo repo = new CountingRepo();
        callUpdate(repo, "ds1", new JsonObject().put("aliases", new JsonArray().add("shared")));
        callUpdate(repo, "ds2", new JsonObject().put("aliases", new JsonArray().add("shared")));

        assertNotNull(repo.get("ds1"));
        assertNotNull(repo.get("ds2"));
        assertSame(repo.get("shared"), repo.get("ds1"));
    }

    @Test(groups = { "unit" })
    public void update_constructorFailureIsSwallowedNotPropagated() throws Exception {
        // update() catches around construction — bad config must NOT crash the whole reload;
        // scanner needs to keep running for other datasources.
        CountingRepo repo = new CountingRepo() {
            @Override
            protected NamedDataSource createFromConfig(String id, JsonObject config) {
                throw new IllegalArgumentException("simulated broken config");
            }
        };

        callUpdate(repo, "broken", new JsonObject().put("anything", true));

        assertNull(repo.get("broken"),
                "failed registration must NOT leave a partial entity in the map");
        assertEquals(repo.adds, 0, "atomicAdd must NOT be called on failure");
    }

    @Test(groups = { "unit" })
    public void getExtensionByType_withRegisteredType_returnsIt() {
        CountingRepo repo = new CountingRepo();
        Extension<NamedDataSource> ext = new Extension<>(NamedDataSource.class);
        repo.registerType("jdbc", ext);

        // get() with a "jdbc:..." id triggers createFromType which calls getExtensionByType.
        NamedDataSource ds = repo.get("jdbc:my-test-uri");
        assertNotNull(ds, "registered type 'jdbc' must yield an adhoc entity");
        assertEquals(ds.getId(), "jdbc:my-test-uri");
    }

    @Test(groups = { "unit" })
    public void getExtensionByType_unknownTypeReturnsNull_when_not_auto_create() {
        // getExtensionByType throws IAE when autoCreate=false and type isn't registered;
        // exception propagates out of createFromType -> get().
        CountingRepo repo = new CountingRepo();
        repo.registerType("jdbc", new Extension<>(NamedDataSource.class));

        assertThrows(IllegalArgumentException.class, () -> repo.get("other:xxx"));
    }

    @Test(groups = { "unit" })
    public void accept_acceptsSupertypeOfDeclaredEntity() {
        // accept(c) returns c.isAssignableFrom(this.clazz) — c must be supertype.
        CountingRepo repo = new CountingRepo();
        assertTrue(repo.accept(ManagedEntity.class),
                "repo declared for NamedDataSource must accept ManagedEntity (supertype)");
    }

    @Test(groups = { "unit" })
    public void update_nullId_nullConfigPath() throws Exception {
        CountingRepo repo = new CountingRepo();
        // mappings.get(null) is null -> addEntity = true. config == null short-circuits.
        callUpdate(repo, null, null);

        assertEquals(repo.adds, 0);
    }
}
