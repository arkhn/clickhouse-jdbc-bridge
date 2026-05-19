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

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.Extension;
import com.clickhouse.jdbcbridge.core.ManagedEntity;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.NamedQuery;
import com.clickhouse.jdbcbridge.core.NamedSchema;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.core.UsageStats;

/**
 * Unit tests for {@link DefaultRepositoryManager} — the in-memory
 * orchestrator that maps an entity class to a registered repository.
 * The lookup path is hot (every request hits getRepository to resolve
 * the datasource by name), so pin both the happy paths and the failure
 * modes (NPE on null class, IAE when no repo accepts).
 */
public class DefaultRepositoryManagerTest {

    /**
     * Minimal Repository test double — only {@code accept} and
     * {@code getEntityClass} matter for the manager's lookup path. The
     * other methods are unused in this test.
     */
    private static final class FakeRepo<T extends ManagedEntity> implements Repository<T> {
        private final Class<T> clazz;

        FakeRepo(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override public Class<T> getEntityClass() { return clazz; }
        @Override public boolean accept(Class<?> c) { return clazz.equals(c); }
        @Override public String resolve(String name) { return name; }
        @Override public List<UsageStats> getUsageStats() { return Collections.emptyList(); }
        @Override public void registerType(String type, Extension<T> ext) { }
        @Override public void put(String id, T entity) { }
        @Override public T get(String id) { return null; }
    }

    // ---------- getRepository: null + empty + match ----------

    @Test(groups = { "unit" })
    public void getRepository_nullClass_throwsNPE() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        // Objects.requireNonNull guards the class param — pin so an
        // accidental dereference can't silently NPE deeper in lookup.
        assertThrows(NullPointerException.class, () -> m.getRepository(null));
    }

    @Test(groups = { "unit" })
    public void getRepository_noReposRegistered_throwsIAE() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        // Empty manager — must surface as IAE so the caller can give the
        // operator a clear "no repository for X" message rather than NPE.
        try {
            m.getRepository(NamedDataSource.class);
            org.testng.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertNotNull(ex.getMessage());
            org.testng.Assert.assertTrue(ex.getMessage().contains(NamedDataSource.class.getName()),
                    "IAE message must name the missing class: " + ex.getMessage());
        }
    }

    @Test(groups = { "unit" })
    public void getRepository_returnsAcceptingRepo() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> ds = new FakeRepo<>(NamedDataSource.class);
        m.update(Collections.singletonList(ds));

        // Repository registered for NamedDataSource — lookup must return
        // the exact same instance (no defensive copy, no proxy).
        Repository<NamedDataSource> got = m.getRepository(NamedDataSource.class);
        assertSame(got, ds);
    }

    @Test(groups = { "unit" })
    public void getRepository_unknownClass_throwsIAE() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        m.update(Collections.singletonList(new FakeRepo<>(NamedDataSource.class)));

        // Schema is not registered — the accept() check fails for all
        // entries, so the manager throws IAE listing the missing class.
        try {
            m.getRepository(NamedSchema.class);
            org.testng.Assert.fail("expected IllegalArgumentException for unregistered type");
        } catch (IllegalArgumentException ex) {
            assertNotNull(ex.getMessage());
            org.testng.Assert.assertTrue(ex.getMessage().contains(NamedSchema.class.getName()),
                    "IAE message must name the missing class: " + ex.getMessage());
        }
    }

    @Test(groups = { "unit" })
    public void getRepository_returnsFirstAccepting() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> first = new FakeRepo<>(NamedDataSource.class);
        FakeRepo<NamedDataSource> second = new FakeRepo<>(NamedDataSource.class);
        m.update(Arrays.asList(first, second));

        // When two repos both accept the same class (a real config error
        // but possible), the iteration order is first-wins. Pin so a
        // future map-based switch with HashMap ordering doesn't silently
        // pick the other one.
        Repository<NamedDataSource> got = m.getRepository(NamedDataSource.class);
        assertSame(got, first);
    }

    // ---------- update: null + add + multi-type ----------

    @Test(groups = { "unit" })
    public void update_null_isNoop() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        // Null input must not throw — the caller may legitimately pass
        // null when config parsing returned nothing. Following lookup
        // still surfaces as IAE (empty manager).
        m.update(null);
        assertThrows(IllegalArgumentException.class,
                () -> m.getRepository(NamedDataSource.class));
    }

    @Test(groups = { "unit" })
    public void update_addsMultipleDistinctRepos() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> ds = new FakeRepo<>(NamedDataSource.class);
        FakeRepo<NamedSchema> sch = new FakeRepo<>(NamedSchema.class);
        FakeRepo<NamedQuery> q = new FakeRepo<>(NamedQuery.class);

        m.update(new ArrayList<>(Arrays.asList(ds, sch, q)));

        // All three should be reachable via their respective entity types.
        assertSame(m.getRepository(NamedDataSource.class), ds);
        assertSame(m.getRepository(NamedSchema.class), sch);
        assertSame(m.getRepository(NamedQuery.class), q);
    }

    @Test(groups = { "unit" })
    public void update_secondCallStillResolvesEachType() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> ds = new FakeRepo<>(NamedDataSource.class);
        FakeRepo<NamedSchema> sch = new FakeRepo<>(NamedSchema.class);

        m.update(Collections.singletonList(ds));
        m.update(Collections.singletonList(sch));

        // Each entity type still resolves to its repo after a second
        // update call (the bridge calls update once per repository type
        // during JsonFileRepository plug-in loading).
        assertSame(m.getRepository(NamedDataSource.class), ds);
        assertSame(m.getRepository(NamedSchema.class), sch);
    }
}
