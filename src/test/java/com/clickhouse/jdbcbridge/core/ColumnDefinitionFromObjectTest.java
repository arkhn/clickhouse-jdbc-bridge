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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Extra tests for {@link ColumnDefinition} targeting branches not exercised by
 * {@code ColumnDefinitionTest}: fromObject dispatcher, options, index lifecycle,
 * defensive-copy ctor, toJson quirks, equals/hashCode contract.
 */
public class ColumnDefinitionFromObjectTest {

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE);
    }

    @Test(groups = { "unit" })
    public void fromObject_nullProducesDefaultStrColumn() {
        ColumnDefinition c = ColumnDefinition.fromObject(null);
        assertEquals(c.getName(), ColumnDefinition.DEFAULT_NAME);
        assertEquals(c.getType(), DataType.Str);
        assertTrue(c.isNullable());
    }

    @Test(groups = { "unit" })
    public void fromObject_passesThroughExistingColumnDefinition() {
        ColumnDefinition orig = col("preexisting", DataType.Int64);
        assertSame(ColumnDefinition.fromObject(orig), orig,
                "ColumnDefinition input must be returned unwrapped (no new copy)");
    }

    @Test(groups = { "unit" })
    public void fromObject_delegatesJsonObjectToFromJson() {
        JsonObject json = new JsonObject().put("name", "a").put("type", "Int32");

        ColumnDefinition c = ColumnDefinition.fromObject(json);

        assertEquals(c.getName(), "a");
        assertEquals(c.getType(), DataType.Int32);
    }

    @Test(groups = { "unit" })
    public void fromObject_parsesMapWithAllFields() {
        // FixedStr honors `length` (Decimal derives it from precision and clobbers user value).
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "tag");
        m.put("type", "FixedStr");
        m.put("nullable", false);
        m.put("length", "16");

        ColumnDefinition c = ColumnDefinition.fromObject(m);

        assertEquals(c.getName(), "tag");
        assertEquals(c.getType(), DataType.FixedStr);
        assertFalse(c.isNullable());
        assertEquals(c.getLength(), 16);
    }

    @Test(groups = { "unit" })
    public void fromObject_parsesMapWithDecimalPrecisionAndScale() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "amount");
        m.put("type", "Decimal");
        m.put("precision", "18");
        m.put("scale", "2");

        ColumnDefinition c = ColumnDefinition.fromObject(m);

        assertEquals(c.getType(), DataType.Decimal);
        assertEquals(c.getPrecision(), 18);
        assertEquals(c.getScale(), 2);
    }

    @Test(groups = { "unit" })
    public void fromObject_emptyMapFallsBackToStrDefault() {
        ColumnDefinition c = ColumnDefinition.fromObject(new HashMap<>());

        assertEquals(c.getName(), ColumnDefinition.DEFAULT_NAME);
        assertEquals(c.getType(), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void fromObject_arbitraryObjectStringifiedAsColumnName() {
        // Default branch: stringifies the object as column name, type=Str.
        ColumnDefinition c = ColumnDefinition.fromObject(Integer.valueOf(42));

        assertEquals(c.getName(), "42");
        assertEquals(c.getType(), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void copyConstructor_producesEqualButDistinctInstance() {
        ColumnDefinition orig = col("a", DataType.Int32);

        ColumnDefinition copy = new ColumnDefinition(orig);

        assertNotSame(copy, orig);
        assertEquals(copy, orig,
                "copy ctor must yield an equal-but-not-same instance");
    }

    @Test(groups = { "unit" })
    public void index_startsUnindexedAndAcceptsExactlyOneAssignment() {
        ColumnDefinition c = col("a", DataType.Int32);

        assertFalse(c.isIndexed());
        assertEquals(c.getIndex(), -1);

        c.setIndex(7);
        assertTrue(c.isIndexed());
        assertEquals(c.getIndex(), 7);

        // setIndex is one-shot: a second call would silently rewire an in-flight request.
        assertThrows(IllegalStateException.class, () -> c.setIndex(3));
    }

    @Test(groups = { "unit" })
    public void setIndex_rejectsNegativeIndex() {
        ColumnDefinition c = col("a", DataType.Int32);
        assertThrows(IllegalArgumentException.class, () -> c.setIndex(-1));
    }

    @Test(groups = { "unit" })
    public void enumOptions_lookupRequiresExactNameAndExactValue() {
        ColumnDefinition c = ColumnDefinition.fromString("status Enum8('N/A'=1, 'OK'=2, 'ERR'=3)");

        assertEquals(c.getOptionValue("N/A"), 1);
        assertEquals(c.getOptionValue("OK"), 2);
        assertEquals(c.getOptionValue("ERR"), 3);
        assertThrows(IllegalArgumentException.class, () -> c.getOptionValue("missing"));

        assertEquals(c.requireValidOptionValue(1), 1);
        assertEquals(c.requireValidOptionValue(3), 3);
        assertThrows(IllegalArgumentException.class, () -> c.requireValidOptionValue(99));

        assertThrows(UnsupportedOperationException.class, () -> c.getOptions().put("X", 9));
    }

    @Test(groups = { "unit" })
    public void fromJson_thenToJson_preservesFixedStrLength() {
        JsonObject src = new JsonObject().put("name", "k").put("type", "FixedStr").put("length", 32);
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        JsonObject roundtripped = c.toJson();

        assertEquals(roundtripped.getString("name"), "k");
        assertEquals(roundtripped.getString("type"), "FixedStr");
        assertEquals(roundtripped.getInteger("length"), Integer.valueOf(32));
    }

    @Test(groups = { "unit" })
    public void fromJson_thenToJson_preservesDecimalPrecisionAndScale() {
        JsonObject src = new JsonObject()
                .put("name", "amount")
                .put("type", "Decimal")
                .put("precision", 18)
                .put("scale", 2);
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        JsonObject roundtripped = c.toJson();

        assertEquals(roundtripped.getInteger("precision"), Integer.valueOf(18));
        assertEquals(roundtripped.getInteger("scale"), Integer.valueOf(2));
    }

    @Test(groups = { "unit" })
    public void fromJson_thenToJson_preservesDateTimeTimezone() {
        JsonObject src = new JsonObject()
                .put("name", "ts")
                .put("type", "DateTime")
                .put("timezone", "UTC");
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        JsonObject roundtripped = c.toJson();

        assertEquals(roundtripped.getString("timezone"), "UTC");
    }

    @Test(groups = { "unit" })
    public void toJson_decimal32ScaleEmittedWithoutPrecision() {
        // toJson switch ladder: smaller decimal families emit scale; only unsized Decimal emits precision.
        JsonObject src = new JsonObject().put("name", "d").put("type", "Decimal32").put("scale", 4);
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        JsonObject out = c.toJson();
        assertEquals(out.getInteger("scale"), Integer.valueOf(4));
        assertFalse(out.containsKey("precision"),
                "Decimal32 must emit scale but not precision");
    }

    @Test(groups = { "unit" })
    public void equalsAndHashCode_consistentAcrossAllFields() {
        ColumnDefinition a = col("a", DataType.Int32);
        ColumnDefinition b = new ColumnDefinition(a);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, col("a-renamed", DataType.Int32));
        assertNotEquals(a, col("a", DataType.Int64));

        ColumnDefinition notNullable = new ColumnDefinition(
                "a", DataType.Int32, /* nullable */ false, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE);
        assertNotEquals(a, notNullable);

        ColumnDefinition indexed = col("a", DataType.Int32);
        indexed.setIndex(0);
        assertNotEquals(a, indexed,
                "index participates in equals");

        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-column-definition"));
        assertTrue(a.equals(a));
    }
}
