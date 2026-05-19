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

import static com.clickhouse.jdbcbridge.core.DataType.DEFAULT_LENGTH;
import static com.clickhouse.jdbcbridge.core.DataType.DEFAULT_PRECISION;
import static com.clickhouse.jdbcbridge.core.DataType.DEFAULT_SCALE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link TableDefinition} JSON/string factory methods, constructor overloads,
 * fromObject dispatcher, equals/hashCode contract, defensive-copy semantics.
 */
public class TableDefinitionJsonTest {

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE);
    }

    @Test(groups = { "unit" })
    public void varargsConstructorRejectsEmptyAndNull() {
        assertThrows(IllegalArgumentException.class, () -> new TableDefinition(new ColumnDefinition[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new TableDefinition(1, (ColumnDefinition[]) null));
    }

    @Test(groups = { "unit" })
    public void listConstructorRejectsNullAndEmpty() {
        assertThrows(NullPointerException.class, () -> new TableDefinition((java.util.List<ColumnDefinition>) null));
        assertThrows(IllegalArgumentException.class,
                () -> new TableDefinition(Collections.<ColumnDefinition>emptyList()));
    }

    @Test(groups = { "unit" })
    public void copyConstructor_columnsAreDeepCopied() {
        // Each ColumnDefinition wrapped via copy ctor — callers can mutate orig without leaking.
        ColumnDefinition orig = col("a", DataType.Int32);
        TableDefinition def = new TableDefinition(orig);

        assertNotSame(def.getColumn(0), orig);
        assertEquals(def.getColumn(0).getName(), orig.getName());
        assertEquals(def.getColumn(0).getType(), orig.getType());
    }

    @Test(groups = { "unit" })
    public void templateConstructor_appendsBehindExistingColumns() {
        TableDefinition base = new TableDefinition(col("a", DataType.Int32), col("b", DataType.Str));

        TableDefinition appended = new TableDefinition(base, /* insert = */ false,
                col("c", DataType.Bool));

        assertEquals(appended.size(), 3);
        assertEquals(appended.getColumn(0).getName(), "a");
        assertEquals(appended.getColumn(1).getName(), "b");
        assertEquals(appended.getColumn(2).getName(), "c");
    }

    @Test(groups = { "unit" })
    public void templateConstructor_prependsWhenInsertIsTrue() {
        TableDefinition base = new TableDefinition(col("a", DataType.Int32), col("b", DataType.Str));

        TableDefinition prepended = new TableDefinition(base, /* insert = */ true,
                col("z", DataType.Bool));

        assertEquals(prepended.size(), 3);
        assertEquals(prepended.getColumn(0).getName(), "z");
        assertEquals(prepended.getColumn(1).getName(), "a");
        assertEquals(prepended.getColumn(2).getName(), "b");
    }

    @Test(groups = { "unit" })
    public void fromJson_nullArrayProducesEmptyColumnsAndThusRejected() {
        // Regression guard: fromJson(null) -> empty cols[] -> ctor rejects.
        assertThrows(IllegalArgumentException.class,
                () -> TableDefinition.fromJson((JsonArray) null));
    }

    @Test(groups = { "unit" })
    public void fromJson_arrayBuildsColumnsInOrder() {
        JsonArray array = new JsonArray()
                .add(new JsonObject().put("name", "a").put("type", "Int32"))
                .add(new JsonObject().put("name", "b").put("type", "Str"));

        TableDefinition def = TableDefinition.fromJson(array);

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "a");
        assertEquals(def.getColumn(0).getType(), DataType.Int32);
        assertEquals(def.getColumn(1).getName(), "b");
        assertEquals(def.getColumn(1).getType(), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void fromJson_stringHandlesObjectForm() {
        String json = "{ \"version\": 1, \"columns\": ["
                + "{\"name\": \"x\", \"type\": \"Int64\"}"
                + "] }";

        TableDefinition def = TableDefinition.fromJson(json);

        assertEquals(def.size(), 1);
        assertEquals(def.getColumn(0).getName(), "x");
        assertEquals(def.getColumn(0).getType(), DataType.Int64);
    }

    @Test(groups = { "unit" })
    public void fromJson_stringHandlesBareArrayForm() {
        String json = "[{\"name\": \"y\", \"type\": \"Float64\"}]";

        TableDefinition def = TableDefinition.fromJson(json);

        assertEquals(def.size(), 1);
        assertEquals(def.getColumn(0).getName(), "y");
        assertEquals(def.getColumn(0).getType(), DataType.Float64);
    }

    @Test(groups = { "unit" })
    public void fromJson_stringSkipsLeadingWhitespace() {
        String json = "   \n\t [{\"name\": \"z\", \"type\": \"Bool\"}]";

        TableDefinition def = TableDefinition.fromJson(json);

        assertEquals(def.getColumn(0).getName(), "z");
    }

    @Test(groups = { "unit" })
    public void fromJson_stringNotJsonFallsBackToColumnDefinitionFromObject() {
        // Non-JSON falls through to ColumnDefinition.fromObject -> single-column table.
        TableDefinition def = TableDefinition.fromJson("just-a-bare-string");

        assertEquals(def.size(), 1);
    }

    @Test(groups = { "unit" })
    public void fromObject_nullReturnsDefaultSingletonColumns() {
        assertSame(TableDefinition.fromObject(null), TableDefinition.DEFAULT_RESULT_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void fromObject_passesTableDefinitionThroughUntouched() {
        TableDefinition orig = new TableDefinition(col("a", DataType.Int8));
        assertSame(TableDefinition.fromObject(orig), orig);
    }

    @Test(groups = { "unit" })
    public void fromObject_wrapsColumnDefinitionArray() {
        ColumnDefinition[] cols = new ColumnDefinition[] { col("a", DataType.Int8) };
        TableDefinition def = TableDefinition.fromObject(cols);

        assertEquals(def.size(), 1);
        assertEquals(def.getColumn(0).getName(), "a");
    }

    @Test(groups = { "unit" })
    public void fromObject_dispatchesOnPrimitiveArrayTypes() {
        // Dispatcher has branch per primitive array type; column count must equal array length.
        assertEquals(TableDefinition.fromObject(new boolean[] { true, false }).size(), 2);
        assertEquals(TableDefinition.fromObject(new byte[] { 1, 2, 3 }).size(), 3);
        assertEquals(TableDefinition.fromObject(new short[] { 1 }).size(), 1);
        assertEquals(TableDefinition.fromObject(new int[] { 1, 2 }).size(), 2);
        assertEquals(TableDefinition.fromObject(new long[] { 1L }).size(), 1);
        assertEquals(TableDefinition.fromObject(new float[] { 1f, 2f, 3f, 4f }).size(), 4);
        assertEquals(TableDefinition.fromObject(new double[] { 1d }).size(), 1);
    }

    @Test(groups = { "unit" })
    public void fromObject_dispatchesOnIterableAndArray() {
        TableDefinition fromIterable = TableDefinition.fromObject(Arrays.asList(1, 2, 3));
        assertEquals(fromIterable.size(), 3);

        TableDefinition fromObjArr = TableDefinition.fromObject(new Object[] { "a", 1, 1.5 });
        assertEquals(fromObjArr.size(), 3);
    }

    // ---------- accessors ----------

    @Test(groups = { "unit" })
    public void containsColumnDetectsMembership() {
        TableDefinition def = new TableDefinition(col("alpha", DataType.Int32), col("beta", DataType.Str));

        assertTrue(def.containsColumn("alpha"));
        assertTrue(def.containsColumn("beta"));
        assertFalse(def.containsColumn("missing"));
        assertFalse(def.containsColumn(""));
    }

    @Test(groups = { "unit" })
    public void getColumnsReturnsDefensiveCopy() {
        TableDefinition def = new TableDefinition(col("a", DataType.Int32));

        ColumnDefinition[] snapshot = def.getColumns();
        snapshot[0] = col("hacked", DataType.Str);

        assertEquals(def.getColumn(0).getName(), "a");
    }

    @Test(groups = { "unit" })
    public void getVersionAndHasColumn() {
        TableDefinition def = new TableDefinition(2, col("a", DataType.Int32));

        assertEquals(def.getVersion(), 2);
        assertTrue(def.hasColumn());
    }

    @Test(groups = { "unit" })
    public void toJsonStringIncludesQueryAndColumns() {
        TableDefinition def = new TableDefinition(col("a", DataType.Int32));

        String json = def.toJsonString("SELECT 1");
        JsonObject parsed = new JsonObject(json);

        assertEquals(parsed.getInteger("version"), Integer.valueOf(1));
        assertEquals(parsed.getString("query"), "SELECT 1");
        assertEquals(parsed.getJsonArray("columns").size(), 1);
        assertEquals(parsed.getJsonArray("columns").getJsonObject(0).getString("name"), "a");
    }

    @Test(groups = { "unit" })
    public void toJsonStringOmitsQueryWhenNull() {
        TableDefinition def = new TableDefinition(col("a", DataType.Int32));

        JsonObject parsed = new JsonObject(def.toJsonString(null));

        assertFalse(parsed.containsKey("query"),
                "null query argument must not emit the `query` key");
    }

    @Test(groups = { "unit" })
    public void toStringRoundTripsThroughFromString() {
        TableDefinition def = new TableDefinition(2,
                col("a", DataType.Int32),
                col("b", DataType.Str));

        TableDefinition parsed = TableDefinition.fromString(def.toString());

        assertEquals(parsed.getVersion(), 2);
        assertEquals(parsed.size(), 2);
        assertEquals(parsed.getColumn(0).getName(), "a");
        assertEquals(parsed.getColumn(0).getType(), DataType.Int32);
        assertEquals(parsed.getColumn(1).getName(), "b");
        assertEquals(parsed.getColumn(1).getType(), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void fromString_nullReturnsDefaultResultColumns() {
        assertSame(TableDefinition.fromString(null), TableDefinition.DEFAULT_RESULT_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void equalsAndHashCode_consistentWithVersionAndColumns() {
        TableDefinition a = new TableDefinition(1, col("a", DataType.Int32));
        TableDefinition b = new TableDefinition(1, col("a", DataType.Int32));
        TableDefinition differentVersion = new TableDefinition(2, col("a", DataType.Int32));
        TableDefinition differentColumns = new TableDefinition(1, col("b", DataType.Int32));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentVersion);
        assertNotEquals(a, differentColumns);

        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-table-definition"));
    }
}
