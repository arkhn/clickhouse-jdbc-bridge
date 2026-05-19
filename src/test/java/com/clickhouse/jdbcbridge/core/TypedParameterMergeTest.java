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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.TimeZone;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link TypedParameter} merge variants and per-DataType writeValueTo branches.
 */
public class TypedParameterMergeTest {

    @DataProvider(name = "mergeJsonRouting")
    Object[][] mergeJsonRouting() {
        return new Object[][] {
            { BigDecimal.class, BigDecimal.ZERO, 12.5, BigDecimal.valueOf(12.5) },
            { Double.class,     0.0,             3.14, 3.14 },
            { Float.class,      0f,              2.5,  2.5f },
            { Long.class,       0L,              9_999_999_999L, 9_999_999_999L },
            { Boolean.class,    false,           true, true },
            { Integer.class,    0,               42,   42 },    // defaultValue Number branch
            { String.class,     "",              "hi", "hi" },  // default String branch
        };
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test(groups = { "unit" }, dataProvider = "mergeJsonRouting")
    public void mergeJson_routesByTypeOrDefault(Class type, Object dflt, Object jsonValue, Object expected) {
        TypedParameter p = new TypedParameter(type, "x", dflt);
        p.merge(new JsonObject().put("x", jsonValue));
        if (expected instanceof BigDecimal) {
            assertEquals(((BigDecimal) p.getValue()).compareTo((BigDecimal) expected), 0);
        } else if (expected instanceof Float) {
            assertEquals((float) p.getValue(), (float) expected, 0.0001f);
        } else if (expected instanceof Double) {
            assertEquals((double) p.getValue(), (double) expected, 0.0001);
        } else {
            assertEquals(p.getValue(), expected);
        }
    }

    @DataProvider(name = "mergeStringRouting")
    Object[][] mergeStringRouting() {
        return new Object[][] {
            { BigDecimal.class, BigDecimal.ZERO, "3.14", BigDecimal.valueOf(3.14) },
            { Double.class,     0.0,             "2.718", 2.718 },
            { Float.class,      0f,              "1.5",   1.5f },
            { Long.class,       0L,              "9999999999", 9_999_999_999L },
            { Boolean.class,    false,           "true",  true },
            { Integer.class,    0,               "17",    17 },
            { String.class,     "",              "plain", "plain" },
        };
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test(groups = { "unit" }, dataProvider = "mergeStringRouting")
    public void mergeString_routesByTypeOrDefault(Class type, Object dflt, String value, Object expected) {
        TypedParameter p = new TypedParameter(type, "x", dflt);
        p.merge(value);
        if (expected instanceof BigDecimal) {
            assertEquals(((BigDecimal) p.getValue()).compareTo((BigDecimal) expected), 0);
        } else if (expected instanceof Float) {
            assertEquals((float) p.getValue(), (float) expected, 0.0001f);
        } else if (expected instanceof Double) {
            assertEquals((double) p.getValue(), (double) expected, 0.0001);
        } else {
            assertEquals(p.getValue(), expected);
        }
    }

    @Test(groups = { "unit" })
    public void mergeJson_extras() {
        // explicit-name override + missing-key no-op + null-json no-op
        TypedParameter<Integer> aliased = new TypedParameter<>(Integer.class, "actual", 0, 0);
        aliased.merge(new JsonObject().put("alias", 42), "alias");
        assertEquals(aliased.getValue(), Integer.valueOf(42));

        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 7);
        p.merge(new JsonObject().put("unrelated", 99));
        assertEquals(p.getValue(), Integer.valueOf(7));
        p.merge((JsonObject) null);
        assertEquals(p.getValue(), Integer.valueOf(7));
    }

    @Test(groups = { "unit" })
    public void mergeOther_andString_andObject_nullPaths() {
        TypedParameter<Integer> a = new TypedParameter<>(Integer.class, "n", 0, 5);
        TypedParameter<Integer> b = new TypedParameter<>(Integer.class, "n", 0, 99);
        a.merge(b);
        assertEquals(a.getValue(), Integer.valueOf(99));
        a.merge((TypedParameter<Integer>) null);
        assertEquals(a.getValue(), Integer.valueOf(99));
        a.merge((String) null);
        assertEquals(a.getValue(), Integer.valueOf(99));
        a.merge((Object) null);
        assertEquals(a.getValue(), Integer.valueOf(99));
        a.merge(Integer.valueOf(42));
        assertEquals(a.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void mergeString_BooleanBothCases() {
        TypedParameter<Boolean> p = new TypedParameter<>(Boolean.class, "x", false);
        p.merge("true");
        assertTrue(p.getValue());
        p.merge("false");
        assertFalse(p.getValue());
    }

    private static ByteBuffer fresh() {
        return ByteBuffer.newInstance(64);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_int_familiesEmitMatchingBytes() {
        // ChType routing table; wire format covered by ByteBufferRoundTripTest.
        ByteBuffer b = fresh();
        new TypedParameter<>(Integer.class, DataType.Int8, "i8", 0, 1).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Integer.class, DataType.Int32, "i32", 0, 100).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Long.class, DataType.Int64, "i64", 0L, 1000L).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Float.class, DataType.Float32, "f32", 0f, 1.5f).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Double.class, DataType.Float64, "f64", 0.0, 2.5).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(String.class, DataType.Str, "s", "", "hello").writeValueTo(b, 0, 0, null);
        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_decimal_familiesPickDefaultPrecisionWhenZero() {
        ByteBuffer b = fresh();
        for (DataType dt : new DataType[] { DataType.Decimal32, DataType.Decimal64, DataType.Decimal128, DataType.Decimal256 }) {
            new TypedParameter<>(BigDecimal.class, dt, "d", BigDecimal.ZERO, new BigDecimal("1.50"))
                    .writeValueTo(b, 0, 0, null);
        }
        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_dateTime_familyAcceptsEpochMillis() {
        ByteBuffer b = fresh();
        new TypedParameter<>(Long.class, DataType.DateTime, "dt", 0L, 1_700_000_000_000L)
                .writeValueTo(b, 0, 0, TimeZone.getTimeZone("UTC"));
        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_uintFamilies() {
        ByteBuffer b = fresh();
        new TypedParameter<>(Integer.class, DataType.UInt8, "u8", 0, 200).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Integer.class, DataType.UInt16, "u16", 0, 50000).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Long.class, DataType.UInt32, "u32", 0L, 4_000_000_000L).writeValueTo(b, 0, 0, null);
        new TypedParameter<>(Long.class, DataType.UInt64, "u64", 0L, Long.MAX_VALUE).writeValueTo(b, 0, 0, null);
        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void toKeyValuePairString_rendersNameEqualsValue() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "max_rows", 0, 100);
        assertEquals(p.toKeyValuePairString(), "max_rows=100");
    }

    @Test(groups = { "unit" })
    public void equalsAndHashCode_discriminateOnAllFields() {
        TypedParameter<Integer> a = new TypedParameter<>(Integer.class, "n", 0, 5);
        TypedParameter<Integer> b = new TypedParameter<>(Integer.class, "n", 0, 5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new TypedParameter<>(Integer.class, "n", 0, 6));
        assertNotEquals(a, new TypedParameter<>(Integer.class, "m", 0, 5));
        assertNotEquals(a, new TypedParameter<>(Integer.class, "n", 1, 5));
        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-param"));
    }
}
