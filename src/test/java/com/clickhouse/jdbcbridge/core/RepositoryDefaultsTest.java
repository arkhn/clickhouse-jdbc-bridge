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

import static org.testng.Assert.assertSame;

import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for {@link Repository} interface default methods. The
 * {@code getOrCreate} default sits between {@link #get(String)} and a
 * subclass-provided factory — pin both branches (passthrough when
 * entity exists; throw when subclass hasn't overridden creation).
 */
public class RepositoryDefaultsTest {

    /**
     * Minimal Repository test double that returns whatever was put.
     */
    private static final class MemRepo implements Repository<NamedDataSource> {
        private NamedDataSource stored;

        @Override public Class<NamedDataSource> getEntityClass() { return NamedDataSource.class; }
        @Override public boolean accept(Class<?> c) { return NamedDataSource.class.equals(c); }
        @Override public String resolve(String name) { return name; }
        @Override public List<UsageStats> getUsageStats() { return Collections.emptyList(); }
        @Override public void registerType(String type, Extension<NamedDataSource> ext) { }
        @Override public void put(String id, NamedDataSource entity) { this.stored = entity; }
        @Override public NamedDataSource get(String id) { return stored; }
    }

    @Test(groups = { "unit" })
    public void getOrCreate_returnsStoredEntityWhenPresent() {
        MemRepo repo = new MemRepo();
        NamedDataSource ds = new NamedDataSource("ds", repo, null);
        repo.put("ds", ds);

        // Default getOrCreate must return the existing entity without
        // calling the missing factory branch.
        assertSame(repo.getOrCreate("ds"), ds);
    }

    @Test(groups = { "unit" })
    public void getOrCreate_throwsWhenNotPresentAndNotOverridden() {
        MemRepo repo = new MemRepo();

        // Default behavior: when get() returns null AND the subclass
        // hasn't overridden, the bridge raises an explicit
        // UnsupportedOperationException so the caller knows creation
        // isn't wired up (vs silently returning null).
        try {
            repo.getOrCreate("missing");
            Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException ex) {
            Assert.assertNotNull(ex.getMessage());
        }
    }
}
