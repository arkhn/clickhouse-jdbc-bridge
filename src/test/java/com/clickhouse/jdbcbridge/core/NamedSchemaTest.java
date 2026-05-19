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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class NamedSchemaTest {

    private static Repository<NamedSchema> stubRepo() {
        return new NamedDataSourceTest.TestRepository<>(NamedSchema.class);
    }

    private static JsonArray oneColumn() {
        return new JsonArray().add(new JsonObject().put("name", "a").put("type", "Int32"));
    }

    @Test(groups = { "unit" })
    public void emptyColumnsArrayIsRejected() {
        // Schema contract: at least one column required.
        assertThrows(IllegalArgumentException.class,
                () -> new NamedSchema("s1", stubRepo(), new JsonObject()));
        assertThrows(IllegalArgumentException.class,
                () -> new NamedSchema("s1", stubRepo(),
                        new JsonObject().put("columns", new JsonArray())));
    }

    @Test(groups = { "unit" })
    public void constructorParsesColumnsArray() {
        JsonObject config = new JsonObject().put("columns",
                new JsonArray()
                        .add(new JsonObject().put("name", "a").put("type", "Int32"))
                        .add(new JsonObject().put("name", "b").put("type", "Str")));

        NamedSchema schema = new NamedSchema("s2", stubRepo(), config);

        assertTrue(schema.hasColumn());
        assertEquals(schema.getColumns().getColumns().length, 2);
        assertEquals(schema.getColumns().getColumns()[0].getName(), "a");
        assertEquals(schema.getColumns().getColumns()[1].getName(), "b");
    }

    @Test(groups = { "unit" })
    public void getUsageAlwaysReturnsNull() {
        NamedSchema schema = new NamedSchema("s3", stubRepo(),
                new JsonObject().put("columns", oneColumn()));
        assertNull(schema.getUsage("anything"));
    }

    @Test(groups = { "unit" })
    public void aliasesArePulledFromConfig() {
        JsonObject config = new JsonObject()
                .put("columns", oneColumn())
                .put("aliases", new JsonArray().add("alias1").add("alias2"));

        NamedSchema schema = new NamedSchema("s4", stubRepo(), config);

        assertTrue(schema.getAliases().contains("alias1"));
        assertTrue(schema.getAliases().contains("alias2"));
    }

    @Test(groups = { "unit" })
    public void newInstanceRequiresIdAndRepo() {
        assertThrows(IllegalArgumentException.class, () -> NamedSchema.newInstance("only-id"));
        assertThrows(NullPointerException.class, () -> NamedSchema.newInstance((Object[]) null));
    }

    @Test(groups = { "unit" })
    public void newInstanceBuildsViaFactory() {
        NamedSchema schema = NamedSchema.newInstance("s5", stubRepo(),
                new JsonObject().put("columns",
                        new JsonArray().add(new JsonObject().put("name", "x").put("type", "Int8"))));

        assertEquals(schema.getId(), "s5");
        assertTrue(schema.hasColumn());
    }

    @Test(groups = { "unit" })
    public void nullConfigIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new NamedSchema("s6", stubRepo(), null));
    }
}
