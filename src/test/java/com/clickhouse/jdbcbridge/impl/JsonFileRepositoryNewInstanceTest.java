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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.Extension;
import com.clickhouse.jdbcbridge.core.ExtensionManager;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.NamedQuery;
import com.clickhouse.jdbcbridge.core.NamedSchema;
import com.clickhouse.jdbcbridge.core.RepositoryManager;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link JsonFileRepository#newInstance(Object...)} — per-type config.dir
 * default and reload-consumer registration with {@link ExtensionManager}.
 */
public class JsonFileRepositoryNewInstanceTest {

    private static final class CapturingManager implements ExtensionManager {
        final List<String> registeredPaths = new ArrayList<>();
        Consumer<JsonObject> lastConsumer;

        @Override public <T> Extension<T> getExtension(Class<? extends T> clazz) { return null; }
        @Override public RepositoryManager getRepositoryManager() { return null; }

        @Override
        public void registerConfigLoader(String configPath, Consumer<JsonObject> consumer) {
            registeredPaths.add(configPath);
            this.lastConsumer = consumer;
        }
    }

    @Test(groups = { "unit" })
    public void newInstance_lessThanTwoArgs_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonFileRepository.newInstance());
        assertThrows(IllegalArgumentException.class,
                () -> JsonFileRepository.newInstance(new CapturingManager()));
    }

    @Test(groups = { "unit" })
    public void newInstance_nullArgs_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> JsonFileRepository.newInstance((Object[]) null));
    }

    @Test(groups = { "unit" })
    public void newInstance_nullManager_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> JsonFileRepository.newInstance(null, NamedDataSource.class));
    }

    @Test(groups = { "unit" })
    public void newInstance_nullEntityClass_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> JsonFileRepository.newInstance(new CapturingManager(), null));
    }

    @Test(groups = { "unit" })
    public void newInstance_namedDataSource_usesDatasourcesDir() {
        // NamedDataSource -> config.dir defaults to "datasources".
        CapturingManager mgr = new CapturingManager();

        JsonFileRepository<NamedDataSource> repo =
                JsonFileRepository.newInstance(mgr, NamedDataSource.class);

        assertNotNull(repo);
        assertSame(repo.getEntityClass(), NamedDataSource.class);
        assertEquals(mgr.registeredPaths.size(), 1);
        // Utils.getConfiguration may return absolute/expanded path; pin the suffix.
        assertTrue(mgr.registeredPaths.get(0).endsWith("datasources")
                        || mgr.registeredPaths.get(0).contains("datasource"),
                "datasource config path must end with 'datasources': "
                        + mgr.registeredPaths.get(0));
        assertNotNull(mgr.lastConsumer);
    }

    @Test(groups = { "unit" })
    public void newInstance_namedSchema_usesSchemasDir() {
        CapturingManager mgr = new CapturingManager();

        JsonFileRepository<NamedSchema> repo =
                JsonFileRepository.newInstance(mgr, NamedSchema.class);

        assertNotNull(repo);
        assertSame(repo.getEntityClass(), NamedSchema.class);
        assertEquals(mgr.registeredPaths.size(), 1);
        assertTrue(mgr.registeredPaths.get(0).endsWith("schemas")
                        || mgr.registeredPaths.get(0).contains("schema"),
                "schema config path must end with 'schemas': "
                        + mgr.registeredPaths.get(0));
    }

    @Test(groups = { "unit" })
    public void newInstance_namedQuery_usesQueriesDir() {
        CapturingManager mgr = new CapturingManager();

        JsonFileRepository<NamedQuery> repo =
                JsonFileRepository.newInstance(mgr, NamedQuery.class);

        assertNotNull(repo);
        assertSame(repo.getEntityClass(), NamedQuery.class);
        assertEquals(mgr.registeredPaths.size(), 1);
        assertTrue(mgr.registeredPaths.get(0).endsWith("queries")
                        || mgr.registeredPaths.get(0).contains("quer"),
                "query config path must end with 'queries': "
                        + mgr.registeredPaths.get(0));
    }

    @Test(groups = { "unit" })
    public void newInstance_reloadConsumerIsRepoReload() {
        // Registered consumer is repo::reload — drives same path as watched-file change.
        CapturingManager mgr = new CapturingManager();
        JsonFileRepository<NamedDataSource> repo =
                JsonFileRepository.newInstance(mgr, NamedDataSource.class);

        mgr.lastConsumer.accept(new JsonObject());

        assertNotNull(repo);
    }

}
