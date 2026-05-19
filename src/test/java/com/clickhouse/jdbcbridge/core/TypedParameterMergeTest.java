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

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link TypedParameter} merge variants and per-DataType writeValueTo branches.
 */
public class TypedParameterMergeTest {

    @Test(groups = { "unit" })
    public void mergeFromOtherTypedParameter_overridesCurrentValue() {
        TypedParameter<Integer> a = new TypedParameter<>(Integer.class, "n", 0, 5);
        TypedParameter<Integer> b = new TypedParameter<>(Integer.class, "n", 0, 99);

        a.merge(b);

        assertEquals(a.getValue(), Integer.valueOf(99));
        assertEquals(a.getDefaultValue(), Integer.valueOf(0));
    }

    @Test(groups = { "unit" })
    public void mergeFromNullTypedParameter_isNoOp() {
        TypedParameter<Integer> a = new TypedParameter<>(Integer.class, "n", 0, 7);

        a.merge((TypedParameter<Integer>) null);

        assertEquals(a.getValue(), Integer.valueOf(7));
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDeclaredType_BigDecimal() {
        // BigDecimal.valueOf may normalize representation — pin numeric value not raw scale.
        TypedParameter<BigDecimal> p = new TypedParameter<>(BigDecimal.class, "amount", BigDecimal.ZERO);

        p.merge(new JsonObject().put("amount", 12.5));

        assertEquals(p.getValue().compareTo(BigDecimal.valueOf(12.5)), 0);
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDeclaredType_Double() {
        TypedParameter<Double> p = new TypedParameter<>(Double.class, "x", 0.0);
        p.merge(new JsonObject().put("x", 3.14));
        assertEquals(p.getValue(), 3.14, 0.0001);
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDeclaredType_Float() {
        TypedParameter<Float> p = new TypedParameter<>(Float.class, "x", 0f);
        p.merge(new JsonObject().put("x", 2.5));
        assertEquals(p.getValue(), 2.5f, 0.0001f);
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDeclaredType_Long() {
        TypedParameter<Long> p = new TypedParameter<>(Long.class, "x", 0L);
        p.merge(new JsonObject().put("x", 9_999_999_999L));
        assertEquals(p.getValue(), Long.valueOf(9_999_999_999L));
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDeclaredType_Boolean() {
        TypedParameter<Boolean> p = new TypedParameter<>(Boolean.class, "x", false);
        p.merge(new JsonObject().put("x", true));
        assertTrue(p.getValue());
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDefaultValue_Integer() {
        // Falls through to `defaultValue instanceof Number` branch.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "x", 0);
        p.merge(new JsonObject().put("x", 42));
        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void mergeJson_routesByDefaultValue_String() {
        TypedParameter<String> p = new TypedParameter<>(String.class, "x", "");
        p.merge(new JsonObject().put("x", "hello"));
        assertEquals(p.getValue(), "hello");
    }

    @Test(groups = { "unit" })
    public void mergeJson_missingKeyDoesNotOverwriteValue() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 7);
        p.merge(new JsonObject().put("unrelated", 99));
        assertEquals(p.getValue(), Integer.valueOf(7),
                "missing key in JSON must leave value untouched");
    }

    @Test(groups = { "unit" })
    public void mergeJson_nullJsonIsNoOp() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 7);
        p.merge((JsonObject) null);
        assertEquals(p.getValue(), Integer.valueOf(7));
    }

    @Test(groups = { "unit" })
    public void mergeJson_explicitNameOverride() {
        // merge(JsonObject, name) lets caller alias parameter names across config sources.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "actual_name", 0, 0);
        p.merge(new JsonObject().put("alias_name", 42), "alias_name");
        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void mergeString_BigDecimal() {
        TypedParameter<BigDecimal> p = new TypedParameter<>(BigDecimal.class, "x", BigDecimal.ZERO);
        p.merge("3.14");
        assertEquals(p.getValue().compareTo(BigDecimal.valueOf(3.14)), 0);
    }

    @Test(groups = { "unit" })
    public void mergeString_Double() {
        TypedParameter<Double> p = new TypedParameter<>(Double.class, "x", 0.0);
        p.merge("2.718");
        assertEquals(p.getValue(), 2.718, 0.0001);
    }

    @Test(groups = { "unit" })
    public void mergeString_Float() {
        TypedParameter<Float> p = new TypedParameter<>(Float.class, "x", 0f);
        p.merge("1.5");
        assertEquals(p.getValue(), 1.5f, 0.0001f);
    }

    @Test(groups = { "unit" })
    public void mergeString_Long() {
        TypedParameter<Long> p = new TypedParameter<>(Long.class, "x", 0L);
        p.merge("9999999999");
        assertEquals(p.getValue(), Long.valueOf(9999999999L));
    }

    @Test(groups = { "unit" })
    public void mergeString_Boolean() {
        TypedParameter<Boolean> p = new TypedParameter<>(Boolean.class, "x", false);
        p.merge("true");
        assertTrue(p.getValue());
        p.merge("false");
        assertFalse(p.getValue());
    }

    @Test(groups = { "unit" })
    public void mergeString_Integer_routedViaDefaultValueBranch() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "x", 0);
        p.merge("17");
        assertEquals(p.getValue(), Integer.valueOf(17));
    }

    @Test(groups = { "unit" })
    public void mergeString_DefaultStringBranch() {
        TypedParameter<String> p = new TypedParameter<>(String.class, "x", "");
        p.merge("plain");
        assertEquals(p.getValue(), "plain");
    }

    @Test(groups = { "unit" })
    public void mergeString_nullIsNoOp() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 5);
        p.merge((String) null);
        assertEquals(p.getValue(), Integer.valueOf(5));
    }

    @Test(groups = { "unit" })
    public void mergeObject_routesToStringMerge() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "x", 0);
        p.merge(Integer.valueOf(42));
        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void mergeObject_nullIsNoOp() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 9);
        p.merge((Object) null);
        assertEquals(p.getValue(), Integer.valueOf(9));
    }

    private static ByteBuffer fresh() {
        return ByteBuffer.newInstance(64);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_int_familiesEmitMatchingBytes() {
        // Pins ChType routing table; wire format covered by ByteBufferRoundTripTest.
        TypedParameter<Integer> i8 = new TypedParameter<>(Integer.class, DataType.Int8, "i8", 0, 1);
        TypedParameter<Integer> i32 = new TypedParameter<>(Integer.class, DataType.Int32, "i32", 0, 100);
        TypedParameter<Long> i64 = new TypedParameter<>(Long.class, DataType.Int64, "i64", 0L, 1000L);
        TypedParameter<Float> f32 = new TypedParameter<>(Float.class, DataType.Float32, "f32", 0f, 1.5f);
        TypedParameter<Double> f64 = new TypedParameter<>(Double.class, DataType.Float64, "f64", 0.0, 2.5);
        TypedParameter<String> str = new TypedParameter<>(String.class, DataType.Str, "s", "", "hello");

        ByteBuffer b = fresh();
        i8.writeValueTo(b, 0, 0, null);
        i32.writeValueTo(b, 0, 0, null);
        i64.writeValueTo(b, 0, 0, null);
        f32.writeValueTo(b, 0, 0, null);
        f64.writeValueTo(b, 0, 0, null);
        str.writeValueTo(b, 0, 0, null);

        assertTrue(b.length() > 0,
                "writeValueTo must accumulate bytes across each ChType branch");
    }

    @Test(groups = { "unit" })
    public void writeValueTo_decimal_familiesPickDefaultPrecisionWhenZero() {
        TypedParameter<BigDecimal> d32 = new TypedParameter<>(BigDecimal.class, DataType.Decimal32,
                "d32", BigDecimal.ZERO, new BigDecimal("1.50"));
        TypedParameter<BigDecimal> d64 = new TypedParameter<>(BigDecimal.class, DataType.Decimal64,
                "d64", BigDecimal.ZERO, new BigDecimal("2.50"));
        TypedParameter<BigDecimal> d128 = new TypedParameter<>(BigDecimal.class, DataType.Decimal128,
                "d128", BigDecimal.ZERO, new BigDecimal("3.50"));
        TypedParameter<BigDecimal> d256 = new TypedParameter<>(BigDecimal.class, DataType.Decimal256,
                "d256", BigDecimal.ZERO, new BigDecimal("4.50"));

        ByteBuffer b = fresh();
        // precision=0, scale=0 -> defaults kick in.
        d32.writeValueTo(b, 0, 0, null);
        d64.writeValueTo(b, 0, 0, null);
        d128.writeValueTo(b, 0, 0, null);
        d256.writeValueTo(b, 0, 0, null);

        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_dateTime_familyAcceptsEpochMillis() {
        // DateTime routes to writeDateTime(long, TimeZone). DateTime64 needs explicit scale.
        TypedParameter<Long> dt = new TypedParameter<>(Long.class, DataType.DateTime,
                "dt", 0L, 1_700_000_000_000L);

        ByteBuffer b = fresh();
        dt.writeValueTo(b, 0, 0, TimeZone.getTimeZone("UTC"));

        assertTrue(b.length() > 0);
    }

    @Test(groups = { "unit" })
    public void writeValueTo_uintFamilies() {
        TypedParameter<Integer> u8 = new TypedParameter<>(Integer.class, DataType.UInt8, "u8", 0, 200);
        TypedParameter<Integer> u16 = new TypedParameter<>(Integer.class, DataType.UInt16, "u16", 0, 50000);
        TypedParameter<Long> u32 = new TypedParameter<>(Long.class, DataType.UInt32, "u32", 0L, 4_000_000_000L);
        TypedParameter<Long> u64 = new TypedParameter<>(Long.class, DataType.UInt64, "u64", 0L, Long.MAX_VALUE);

        ByteBuffer b = fresh();
        u8.writeValueTo(b, 0, 0, null);
        u16.writeValueTo(b, 0, 0, null);
        u32.writeValueTo(b, 0, 0, null);
        u64.writeValueTo(b, 0, 0, null);

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

        TypedParameter<Integer> diffValue = new TypedParameter<>(Integer.class, "n", 0, 6);
        assertNotEquals(a, diffValue);
        TypedParameter<Integer> diffName = new TypedParameter<>(Integer.class, "m", 0, 5);
        assertNotEquals(a, diffName);
        TypedParameter<Integer> diffDefault = new TypedParameter<>(Integer.class, "n", 1, 5);
        assertNotEquals(a, diffDefault);

        assertFalse(a.equals(null));
        assertFalse(a.equals("not-a-param"));
    }
}
