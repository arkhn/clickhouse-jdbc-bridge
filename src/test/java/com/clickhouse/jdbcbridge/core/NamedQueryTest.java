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
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class NamedQueryTest {

    private static Repository<NamedQuery> stubRepo() {
        return new NamedDataSourceTest.TestRepository<>(NamedQuery.class);
    }

    private static JsonArray oneColumn() {
        return new JsonArray().add(new JsonObject().put("name", "a").put("type", "Int32"));
    }

    @Test(groups = { "unit" })
    public void constructorReadsQueryAndSchema() {
        JsonObject config = new JsonObject()
                .put("columns", oneColumn())
                .put("query", "SELECT 1")
                .put("schema", "ch-cluster");
        NamedQuery q = new NamedQuery("q1", stubRepo(), config);

        assertEquals(q.getId(), "q1");
        assertEquals(q.getQuery(), "SELECT 1");
        assertEquals(q.getSchema(), "ch-cluster");
        assertNotNull(q.getParameters(),
                "parameters must be initialized even when 'parameters' key is absent");
    }

    @Test(groups = { "unit" })
    public void schemaDefaultsToEmptyStringWhenAbsent() {
        JsonObject config = new JsonObject()
                .put("columns", oneColumn())
                .put("query", "SELECT 1");
        NamedQuery q = new NamedQuery("q2", stubRepo(), config);

        assertEquals(q.getSchema(), Utils.EMPTY_STRING);
    }

    @Test(groups = { "unit" })
    public void parametersAreParsedFromConfig() {
        JsonObject params = new JsonObject().put(QueryParameters.PARAM_MAX_ROWS, 100);
        JsonObject config = new JsonObject()
                .put("columns", oneColumn())
                .put("query", "SELECT 1")
                .put("parameters", params);

        NamedQuery q = new NamedQuery("q3", stubRepo(), config);

        assertEquals(q.getParameters().getMaxRows(), 100);
    }

    @Test(groups = { "unit" })
    public void missingQueryFieldIsRejected() {
        // NamedQuery requires non-null `query` (Objects.requireNonNull).
        JsonObject config = new JsonObject().put("columns", oneColumn());
        assertThrows(NullPointerException.class,
                () -> new NamedQuery("q4", stubRepo(), config));
    }

    @Test(groups = { "unit" })
    public void newInstanceRequiresIdAndRepo() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedQuery.newInstance("only-id"));
        assertThrows(NullPointerException.class,
                () -> NamedQuery.newInstance((Object[]) null));
    }

    @Test(groups = { "unit" })
    public void newInstanceBuildsViaFactory() {
        NamedQuery q = NamedQuery.newInstance("q5", stubRepo(),
                new JsonObject().put("columns", oneColumn()).put("query", "SELECT 42"));
        assertEquals(q.getId(), "q5");
        assertEquals(q.getQuery(), "SELECT 42");
    }
}
