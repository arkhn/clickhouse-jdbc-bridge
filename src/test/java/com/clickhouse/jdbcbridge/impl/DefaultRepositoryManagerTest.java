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
 * Unit tests for {@link DefaultRepositoryManager} — in-memory orchestrator
 * mapping entity class to registered repository.
 */
public class DefaultRepositoryManagerTest {

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

    @Test(groups = { "unit" })
    public void getRepository_nullClass_throwsNPE() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        assertThrows(NullPointerException.class, () -> m.getRepository(null));
    }

    @Test(groups = { "unit" })
    public void getRepository_noReposRegistered_throwsIAE() {
        // IAE so operator gets clear "no repository for X" rather than NPE.
        DefaultRepositoryManager m = new DefaultRepositoryManager();
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

        // Same instance — no defensive copy, no proxy.
        Repository<NamedDataSource> got = m.getRepository(NamedDataSource.class);
        assertSame(got, ds);
    }

    @Test(groups = { "unit" })
    public void getRepository_unknownClass_throwsIAE() {
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        m.update(Collections.singletonList(new FakeRepo<>(NamedDataSource.class)));

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
        // Two repos accepting same class: iteration order is first-wins (pin so HashMap ordering can't break it).
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> first = new FakeRepo<>(NamedDataSource.class);
        FakeRepo<NamedDataSource> second = new FakeRepo<>(NamedDataSource.class);
        m.update(Arrays.asList(first, second));

        Repository<NamedDataSource> got = m.getRepository(NamedDataSource.class);
        assertSame(got, first);
    }

    @Test(groups = { "unit" })
    public void update_null_isNoop() {
        // Null is allowed (caller may pass when config parsing returned nothing).
        DefaultRepositoryManager m = new DefaultRepositoryManager();
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

        assertSame(m.getRepository(NamedDataSource.class), ds);
        assertSame(m.getRepository(NamedSchema.class), sch);
        assertSame(m.getRepository(NamedQuery.class), q);
    }

    @Test(groups = { "unit" })
    public void update_secondCallStillResolvesEachType() {
        // Bridge calls update once per repository type during plug-in loading.
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> ds = new FakeRepo<>(NamedDataSource.class);
        FakeRepo<NamedSchema> sch = new FakeRepo<>(NamedSchema.class);

        m.update(Collections.singletonList(ds));
        m.update(Collections.singletonList(sch));

        assertSame(m.getRepository(NamedDataSource.class), ds);
        assertSame(m.getRepository(NamedSchema.class), sch);
    }

    // Distinct concrete class needed so the replace-in-place branch fires
    // (requires both current.getClass() and getEntityClass() to differ).
    private static final class OtherFakeRepo<T extends ManagedEntity> implements Repository<T> {
        private final Class<T> clazz;

        OtherFakeRepo(Class<T> clazz) { this.clazz = clazz; }

        @Override public Class<T> getEntityClass() { return clazz; }
        @Override public boolean accept(Class<?> c) { return clazz.equals(c); }
        @Override public String resolve(String name) { return name; }
        @Override public List<UsageStats> getUsageStats() { return Collections.emptyList(); }
        @Override public void registerType(String type, Extension<T> ext) { }
        @Override public void put(String id, T entity) { }
        @Override public T get(String id) { return null; }
    }

    @Test(groups = { "unit" })
    public void update_replaceBranch_swapsInPlaceWhenClassAndEntityDiffer() {
        // Replace-in-place branch: incoming repo's CLASS and entityClass both differ from existing.
        // The schema repo *replaces* the datasource repo at index 0 — pin the odd swap rule.
        DefaultRepositoryManager m = new DefaultRepositoryManager();
        FakeRepo<NamedDataSource> first = new FakeRepo<>(NamedDataSource.class);
        m.update(Collections.singletonList(first));

        OtherFakeRepo<NamedSchema> second = new OtherFakeRepo<>(NamedSchema.class);
        m.update(Collections.singletonList(second));

        assertSame(m.getRepository(NamedSchema.class), second);

        try {
            m.getRepository(NamedDataSource.class);
            org.testng.Assert.fail(
                    "datasource repo should have been replaced by schema repo (swap branch)");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
