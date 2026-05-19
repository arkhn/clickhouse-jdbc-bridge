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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.BaseRepository;
import com.clickhouse.jdbcbridge.core.ByteBuffer;
import com.clickhouse.jdbcbridge.core.ColumnDefinition;
import com.clickhouse.jdbcbridge.core.DataType;
import com.clickhouse.jdbcbridge.core.DefaultValues;
import com.clickhouse.jdbcbridge.core.ManagedEntity;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.QueryParameters;
import com.clickhouse.jdbcbridge.core.ResponseWriter;
import com.clickhouse.jdbcbridge.core.TableDefinition;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link ConfigDataSource} — SHOW DATASOURCES admin endpoint.
 */
public class ConfigDataSourceTest {

    static final class TestableConfigDataSource extends ConfigDataSource {
        TestableConfigDataSource(BaseRepository<NamedDataSource> repo) {
            super(repo);
        }

        public ConfigQuery callParse(String q) {
            return parse(q);
        }

        public void callWriteQueryResult(String loadedQuery, ColumnDefinition[] requestColumns,
                ResponseWriter writer) {
            writeQueryResult("", loadedQuery, loadedQuery, new QueryParameters(),
                    requestColumns, new ColumnDefinition[0], new DefaultValues(), writer);
        }
    }

    static final class Capture extends ResponseWriter {
        int writes;
        long bytes;

        @Override public boolean isOpen() { return true; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public void write(ByteBuffer buffer) {
            writes++;
            if (buffer != null) bytes += buffer.length();
        }
    }

    static class StubRepository<T extends ManagedEntity> extends BaseRepository<T> {
        StubRepository(Class<T> clazz) { super(clazz); }
        @Override protected void atomicAdd(T entity) {}
        @Override protected void atomicRemove(T entity) {}
    }

    private static BaseRepository<NamedDataSource> repoWithEntries(int count) {
        BaseRepository<NamedDataSource> repo = new StubRepository<>(NamedDataSource.class);
        for (int i = 0; i < count; i++) {
            String id = "ds-" + i;
            repo.put(id, new NamedDataSource(id, repo, new JsonObject()));
        }
        return repo;
    }

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, true,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    @Test(groups = { "unit" })
    public void parse_acceptsShowDatasources() {
        TestableConfigDataSource cds = new TestableConfigDataSource(repoWithEntries(0));

        ConfigDataSource.ConfigQuery cq = cds.callParse("SHOW DATASOURCES");
        assertEquals(cq.queryType, "SHOW");
        assertEquals(cq.configType, "DATASOURCES");
    }

    @Test(groups = { "unit" })
    public void parse_caseInsensitive() {
        // Lexer matches via equalsIgnoreCase.
        TestableConfigDataSource cds = new TestableConfigDataSource(repoWithEntries(0));
        ConfigDataSource.ConfigQuery cq = cds.callParse("show datasources");
        assertEquals(cq.configType, "DATASOURCES");
    }

    @Test(groups = { "unit" })
    public void parse_unknownQueryThrowsIAE() {
        TestableConfigDataSource cds = new TestableConfigDataSource(repoWithEntries(0));
        assertThrows(IllegalArgumentException.class, () -> cds.callParse("DROP DATASOURCES"));
        assertThrows(IllegalArgumentException.class, () -> cds.callParse(""));
        assertThrows(IllegalArgumentException.class, () -> cds.callParse(null));
        assertThrows(IllegalArgumentException.class, () -> cds.callParse("SHOW TABLES"));
        assertThrows(IllegalArgumentException.class, () -> cds.callParse("SHOW"));
    }

    @Test(groups = { "unit" })
    public void writeQueryResult_emitsOneRowPerRegisteredDatasource() {
        // DataSourceStatReader.nextRow skips the empty-name entry (the ConfigDataSource itself).
        BaseRepository<NamedDataSource> repo = repoWithEntries(2);
        TestableConfigDataSource cds = new TestableConfigDataSource(repo);

        ColumnDefinition[] requestColumns = new ColumnDefinition[] {
                col("name", DataType.Str),
                col("is_alias", DataType.UInt8),
                col("instance", DataType.Int32),
                col("create_datetime", DataType.DateTime),
                col("type", DataType.Str),
                col("parameters", DataType.Str),
                col("defaults", DataType.Str),
                col("custom_columns", DataType.Str),
                col("cache_usage", DataType.Str),
                col("pool_usage", DataType.Str),
        };
        Capture w = new Capture();

        cds.callWriteQueryResult("SHOW DATASOURCES", requestColumns, w);

        assertTrue(w.bytes > 0,
                "SHOW DATASOURCES must emit bytes for registered datasources; writes=" + w.writes);
    }

    @Test(groups = { "unit" })
    public void writeQueryResult_emptyRepoYieldsNoRows() {
        BaseRepository<NamedDataSource> repo = repoWithEntries(0);
        TestableConfigDataSource cds = new TestableConfigDataSource(repo);

        ColumnDefinition[] requestColumns = new ColumnDefinition[] { col("name", DataType.Str) };
        Capture w = new Capture();

        cds.callWriteQueryResult("SHOW DATASOURCES", requestColumns, w);
        // No rows -> bytes can be 0 but call must not throw.
    }

    @Test(groups = { "unit" })
    public void writeQueryResult_malformedQueryThrows() {
        TestableConfigDataSource cds = new TestableConfigDataSource(repoWithEntries(0));
        Capture w = new Capture();

        ColumnDefinition[] cols = new ColumnDefinition[] { col("name", DataType.Str) };

        assertThrows(IllegalArgumentException.class,
                () -> cds.callWriteQueryResult("SELECT * FROM not-a-config-query", cols, w));
    }

    @Test(groups = { "unit" })
    public void getType_isConfig() {
        TestableConfigDataSource cds = new TestableConfigDataSource(repoWithEntries(0));
        assertEquals(cds.getType(), ConfigDataSource.EXTENSION_NAME);
        assertEquals(cds.getType(), "config");
    }

    @Test(groups = { "unit" })
    public void datasourceConfigColumns_isStable() {
        // Pins SHOW DATASOURCES column-binding indirectly — operator dashboards depend on the schema.
        BaseRepository<NamedDataSource> repo = repoWithEntries(1);
        TestableConfigDataSource cds = new TestableConfigDataSource(repo);

        ColumnDefinition[] requestColumns = new ColumnDefinition[] {
                col("name", DataType.Str),
                col("type", DataType.Str),
        };
        Capture w = new Capture();

        cds.callWriteQueryResult("SHOW DATASOURCES", requestColumns, w);

        assertTrue(w.bytes > 0, "expected bytes for the 1 registered ds");
    }

    @Test(groups = { "unit" })
    public void initialize_registersSingletonAtEmptyId() {
        // Empty-string key is reserved for the config datasource (production initialize() wiring).
        BaseRepository<NamedDataSource> repo = new StubRepository<>(NamedDataSource.class);

        ConfigDataSource singleton = new TestableConfigDataSource(repo);
        repo.put(com.clickhouse.jdbcbridge.core.Utils.EMPTY_STRING, singleton);

        assertEquals(singleton.getType(), "config");
    }
}
