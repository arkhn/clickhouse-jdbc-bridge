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
 * Extra tests for {@link ColumnDefinition} targeting branches not exercised
 * by the existing {@code ColumnDefinitionTest}: the {@code fromObject(...)}
 * dispatcher, option-table lookup, index lifecycle, defensive-copy ctor,
 * {@code toJson} per-type quirks, and the equals/hashCode contract.
 */
public class ColumnDefinitionFromObjectTest {

    private static ColumnDefinition col(String name, DataType type) {
        return new ColumnDefinition(name, type, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE);
    }

    // ---------- fromObject dispatcher ----------

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
        // Use FixedStr so the `length` field is actually honored (Decimal
        // derives length from its precision and clobbers the user-supplied
        // value).
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
        // The default branch turns the object into a string and uses it as
        // the column name with type=Str. Used by TableDefinition's primitive
        // array dispatch for fallback elements.
        ColumnDefinition c = ColumnDefinition.fromObject(Integer.valueOf(42));

        assertEquals(c.getName(), "42");
        assertEquals(c.getType(), DataType.Str);
    }

    // ---------- defensive-copy constructor ----------

    @Test(groups = { "unit" })
    public void copyConstructor_producesEqualButDistinctInstance() {
        ColumnDefinition orig = col("a", DataType.Int32);

        ColumnDefinition copy = new ColumnDefinition(orig);

        assertNotSame(copy, orig);
        assertEquals(copy, orig,
                "copy ctor must yield an instance that's equal-but-not-same; otherwise TableDefinition's defensive deep-copy in line 93 is broken");
    }

    // ---------- index lifecycle ----------

    @Test(groups = { "unit" })
    public void index_startsUnindexedAndAcceptsExactlyOneAssignment() {
        ColumnDefinition c = col("a", DataType.Int32);

        assertFalse(c.isIndexed());
        assertEquals(c.getIndex(), -1);

        c.setIndex(7);
        assertTrue(c.isIndexed());
        assertEquals(c.getIndex(), 7);

        // setIndex is a one-shot assignment. The bridge sets it during
        // request-column resolution; a second call would silently rewire
        // an in-flight request, so it must be rejected.
        assertThrows(IllegalStateException.class, () -> c.setIndex(3));
    }

    @Test(groups = { "unit" })
    public void setIndex_rejectsNegativeIndex() {
        ColumnDefinition c = col("a", DataType.Int32);
        assertThrows(IllegalArgumentException.class, () -> c.setIndex(-1));
    }

    // ---------- options (Enum8/Enum16) ----------

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

        // Options map is defensively unmodifiable.
        assertThrows(UnsupportedOperationException.class, () -> c.getOptions().put("X", 9));
    }

    // ---------- toJson per-type quirks ----------

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
        // The toJson switch falls through Decimal -> Decimal32..256 in a
        // ladder that emits `scale` for the smaller decimal families but
        // only emits `precision` for the unsized Decimal. This pins the
        // contract so a reordering of the switch is caught.
        JsonObject src = new JsonObject().put("name", "d").put("type", "Decimal32").put("scale", 4);
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        JsonObject out = c.toJson();
        assertEquals(out.getInteger("scale"), Integer.valueOf(4));
        assertFalse(out.containsKey("precision"),
                "Decimal32 must emit scale but not precision");
    }

    // ---------- equals / hashCode ----------

    @Test(groups = { "unit" })
    public void equalsAndHashCode_consistentAcrossAllFields() {
        ColumnDefinition a = col("a", DataType.Int32);
        ColumnDefinition b = new ColumnDefinition(a); // same field values

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        // Pull each discriminating field one at a time to confirm equals
        // includes it.
        assertNotEquals(a, col("a-renamed", DataType.Int32));
        assertNotEquals(a, col("a", DataType.Int64));

        ColumnDefinition notNullable = new ColumnDefinition(
                "a", DataType.Int32, /* nullable */ false, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE);
        assertNotEquals(a, notNullable);

        ColumnDefinition indexed = col("a", DataType.Int32);
        indexed.setIndex(0);
        assertNotEquals(a, indexed,
                "index participates in equals — two columns with same name/type but different indices must differ");

        // Cross-class and null comparisons.
        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-column-definition"));
        assertTrue(a.equals(a));
    }
}
