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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

public class DataSourceStatsTest {

    private static NamedDataSource buildDataSource(String id) {
        return new NamedDataSource(id,
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class),
                new JsonObject());
    }

    @Test(groups = { "unit" })
    public void carriesIdAndIdentityFromDataSource() {
        NamedDataSource ds = buildDataSource("primary");
        DataSourceStats stats = new DataSourceStats("primary", ds);

        assertEquals(stats.getName(), "primary");
        assertEquals(stats.getInstance(), ds.hashCode());
        assertFalse(stats.isAlias(), "stats.idOrAlias matches ds.id -> not an alias");
        assertNotNull(stats.getCreateDateTime());
        assertEquals(stats.getType(), ds.getType());
    }

    @Test(groups = { "unit" })
    public void detectsAliasWhenLookupIdDiffersFromDataSourceId() {
        NamedDataSource ds = buildDataSource("primary");
        DataSourceStats stats = new DataSourceStats("primary-alias", ds);

        assertEquals(stats.getName(), "primary-alias");
        assertTrue(stats.isAlias(),
                "looking up the ds under a name different from its real id must mark stats as alias");
    }

    @Test(groups = { "unit" })
    public void exposesDataSourceConfigSnapshotsAsJson() {
        NamedDataSource ds = buildDataSource("snap");
        DataSourceStats stats = new DataSourceStats("snap", ds);

        // NamedDataSource's *AsJsonString() methods emit JSON objects/arrays.
        // Asserting the shape (not just non-null) catches a regression where
        // a getter starts returning a raw stringification or "null".
        assertTrue(stats.getDefaults().startsWith("{"),
                "defaults snapshot must be a JSON object, got: " + stats.getDefaults());
        assertTrue(stats.getParameters().startsWith("{"),
                "parameters snapshot must be a JSON object, got: " + stats.getParameters());
        assertTrue(stats.getCustomColumns().startsWith("["),
                "customColumns snapshot must be a JSON array, got: " + stats.getCustomColumns());
        // cacheUsage / poolUsage are free-form strings from upstream — pin
        // only that the wiring delivers a non-null value (the NamedDataSource
        // contract returns "" for an unconfigured pool, never null).
        assertNotNull(stats.getCacheUsage());
        assertNotNull(stats.getPoolUsage());
    }

    @Test(groups = { "unit" })
    public void rejectsNullIdAndNullDataSource() {
        NamedDataSource ds = buildDataSource("x");

        assertThrows(NullPointerException.class, () -> new DataSourceStats(null, ds));
        assertThrows(NullPointerException.class, () -> new DataSourceStats("x", null));
    }
}
