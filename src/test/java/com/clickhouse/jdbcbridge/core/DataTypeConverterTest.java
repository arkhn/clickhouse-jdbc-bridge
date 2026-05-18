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
import static org.testng.Assert.assertSame;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.JDBCType;

import org.testng.annotations.Test;

/**
 * Exercises the default methods on the {@link DataTypeConverter} interface
 * (the {@code as()} value coercer and the {@code toMType()} mapping). The
 * abstract {@link DataTypeConverter#from} methods are exercised separately
 * via {@code com.clickhouse.jdbcbridge.impl.DefaultDataTypeConverterTest}.
 */
public class DataTypeConverterTest {

    /** Minimal stand-in so we can call default methods on the interface. */
    private static final DataTypeConverter CONV = new DataTypeConverter() {
        @Override
        public DataType from(JDBCType jdbcType, String typeName, int precision, int scale, boolean signed) {
            return DataType.Str;
        }

        @Override
        public DataType from(Object javaObject) {
            return DataType.Str;
        }
    };

    // ---------- as(Boolean.class, ...) ----------

    @Test(groups = { "unit" })
    public void asBooleanHandlesBooleanInput() {
        assertEquals(CONV.as(Boolean.class, Boolean.TRUE), Boolean.TRUE);
        assertEquals(CONV.as(Boolean.class, Boolean.FALSE), Boolean.FALSE);
    }

    @Test(groups = { "unit" })
    public void asBooleanTreatsZeroAsFalse() {
        assertEquals(CONV.as(Boolean.class, 0), Boolean.FALSE);
        assertEquals(CONV.as(Boolean.class, 7), Boolean.TRUE);
    }

    @Test(groups = { "unit" })
    public void asBooleanParsesStrings() {
        assertEquals(CONV.as(Boolean.class, "true"), Boolean.TRUE);
        assertEquals(CONV.as(Boolean.class, "false"), Boolean.FALSE);
        // Boolean.parseBoolean treats anything else as false
        assertEquals(CONV.as(Boolean.class, "garbage"), Boolean.FALSE);
    }

    // ---------- numeric coercions ----------

    @Test(groups = { "unit" })
    public void asByteAcceptsNumberBooleanAndString() {
        assertEquals((byte) CONV.as(Byte.class, 5), (byte) 5);
        // Boolean branch: true -> 0, false -> 1 (the codebase chose this convention)
        assertEquals((byte) CONV.as(Byte.class, true), (byte) 0);
        assertEquals((byte) CONV.as(Byte.class, false), (byte) 1);
        assertEquals((byte) CONV.as(Byte.class, "42"), (byte) 42);
    }

    @Test(groups = { "unit" })
    public void asShortAcceptsNumberBooleanAndString() {
        assertEquals((short) CONV.as(Short.class, 7), (short) 7);
        assertEquals((short) CONV.as(Short.class, true), (short) 0);
        assertEquals((short) CONV.as(Short.class, false), (short) 1);
        assertEquals((short) CONV.as(Short.class, "10000"), (short) 10000);
    }

    @Test(groups = { "unit" })
    public void asIntegerAcceptsNumberBooleanAndString() {
        assertEquals((int) CONV.as(Integer.class, 9L), 9);
        assertEquals((int) CONV.as(Integer.class, true), 0);
        assertEquals((int) CONV.as(Integer.class, false), 1);
        assertEquals((int) CONV.as(Integer.class, "12345"), 12345);
    }

    @Test(groups = { "unit" })
    public void asLongAcceptsNumberBooleanAndString() {
        assertEquals((long) CONV.as(Long.class, 9), 9L);
        assertEquals((long) CONV.as(Long.class, true), 0L);
        assertEquals((long) CONV.as(Long.class, false), 1L);
        assertEquals((long) CONV.as(Long.class, "999999999999"), 999999999999L);
    }

    @Test(groups = { "unit" })
    public void asFloatAcceptsNumberBooleanAndString() {
        assertEquals((float) CONV.as(Float.class, 1.5d), 1.5f, 0f);
        assertEquals((float) CONV.as(Float.class, true), 0.0f, 0f);
        assertEquals((float) CONV.as(Float.class, false), 1.0f, 0f);
        assertEquals((float) CONV.as(Float.class, "2.25"), 2.25f, 0f);
    }

    @Test(groups = { "unit" })
    public void asDoubleAcceptsNumberBooleanAndString() {
        assertEquals((double) CONV.as(Double.class, 3.14f), 3.14d, 0.001d);
        assertEquals((double) CONV.as(Double.class, true), 0.0d, 0d);
        assertEquals((double) CONV.as(Double.class, false), 1.0d, 0d);
        assertEquals((double) CONV.as(Double.class, "2.718281828"), 2.718281828d, 0d);
    }

    @Test(groups = { "unit" })
    public void asBigIntegerHandlesAllBranches() {
        assertSame(CONV.as(BigInteger.class, true), BigInteger.ZERO);
        assertSame(CONV.as(BigInteger.class, false), BigInteger.ONE);
        BigInteger passthrough = BigInteger.valueOf(42);
        assertSame(CONV.as(BigInteger.class, passthrough), passthrough);
        assertEquals(CONV.as(BigInteger.class, "17"), BigInteger.valueOf(17));
    }

    @Test(groups = { "unit" })
    public void asBigDecimalHandlesAllBranches() {
        assertSame(CONV.as(BigDecimal.class, true), BigDecimal.ZERO);
        assertSame(CONV.as(BigDecimal.class, false), BigDecimal.ONE);
        BigDecimal passthrough = new BigDecimal("3.14159");
        assertSame(CONV.as(BigDecimal.class, passthrough), passthrough);
        assertEquals(CONV.as(BigDecimal.class, "2.5"), new BigDecimal("2.5"));
    }

    // ---------- as(Date.class, ...) ----------

    @Test(groups = { "unit" })
    public void asDateFromIsoLocalDateString() {
        Object d = CONV.as(java.util.Date.class, "2026-05-18");
        // len == 10 branch -> java.sql.Date
        assertEquals(d.getClass(), java.sql.Date.class);
    }

    @Test(groups = { "unit" })
    public void asDateFromIsoLocalDateTimeString() {
        // len == 19 branch -> java.sql.Timestamp
        Object d = CONV.as(java.util.Date.class, "2026-05-18T22:00:00");
        assertEquals(d.getClass(), java.sql.Timestamp.class);
    }

    @Test(groups = { "unit" })
    public void asDateFromIsoDateTimeWithOffsetString() {
        // len > 19 branch -> java.sql.Timestamp via ISO_DATE_TIME
        Object d = CONV.as(java.util.Date.class, "2026-05-18T22:00:00.123");
        assertEquals(d.getClass(), java.sql.Timestamp.class);
    }

    @Test(groups = { "unit" })
    public void asDateFromBasicIsoFallback() {
        // len < 10 falls through to BASIC_ISO_DATE (yyyyMMdd)
        Object d = CONV.as(java.util.Date.class, "20260518");
        assertEquals(d.getClass(), java.sql.Date.class);
    }

    @Test(groups = { "unit" })
    public void asDateFromNumberUsesEpochMillis() {
        Object d = CONV.as(java.util.Date.class, 0L);
        // Number branch -> plain java.util.Date(long)
        assertEquals(d.getClass(), java.util.Date.class);
        assertEquals(((java.util.Date) d).getTime(), 0L);
    }

    @Test(groups = { "unit" })
    public void asDateFromBooleanProducesEpoch() {
        Object dTrue = CONV.as(java.util.Date.class, true);
        Object dFalse = CONV.as(java.util.Date.class, false);
        assertEquals(((java.util.Date) dTrue).getTime(), 0L);
        assertEquals(((java.util.Date) dFalse).getTime(), 1L);
    }

    @Test(groups = { "unit" })
    public void asStringStringifiesAnything() {
        assertEquals(CONV.as(String.class, 42), "42");
        assertEquals(CONV.as(String.class, true), "true");
        assertEquals(CONV.as(String.class, null), "null");
    }

    @Test(groups = { "unit" })
    public void asUnsupportedTypeReturnsValueUntouched() {
        // No matching `Class<T>` branch -> value passes through as-is.
        Object marker = new Object();
        assertSame(CONV.as(Object.class, marker), marker);
    }

    // ---------- toMType() / toPowerQueryType() ----------

    @Test(groups = { "unit" })
    public void toMTypeMapsIntegerFamilies() {
        assertEquals(CONV.toMType(DataType.Bool), DataTypeConverter.M_FACET_INT8);
        assertEquals(CONV.toMType(DataType.Int8), DataTypeConverter.M_FACET_INT8);
        assertEquals(CONV.toMType(DataType.UInt8), DataTypeConverter.M_FACET_INT16);
        assertEquals(CONV.toMType(DataType.Int16), DataTypeConverter.M_FACET_INT16);
        assertEquals(CONV.toMType(DataType.UInt16), DataTypeConverter.M_FACET_INT32);
        assertEquals(CONV.toMType(DataType.Int32), DataTypeConverter.M_FACET_INT32);
        assertEquals(CONV.toMType(DataType.UInt32), DataTypeConverter.M_FACET_INT64);
        assertEquals(CONV.toMType(DataType.Int64), DataTypeConverter.M_FACET_INT64);
    }

    @Test(groups = { "unit" })
    public void toMTypeMapsFloatFamilies() {
        assertEquals(CONV.toMType(DataType.Float32), DataTypeConverter.M_FACET_SINGLE);
        assertEquals(CONV.toMType(DataType.Float64), DataTypeConverter.M_FACET_DOUBLE);
    }

    @Test(groups = { "unit" })
    public void toMTypeMapsNumberFamily() {
        for (DataType t : new DataType[] {
                DataType.UInt64, DataType.Decimal, DataType.Decimal32, DataType.Decimal64,
                DataType.Decimal128, DataType.Decimal256 }) {
            assertEquals(CONV.toMType(t), DataTypeConverter.M_TYPE_NUMBER, t.name());
        }
    }

    @Test(groups = { "unit" })
    public void toMTypeMapsDateAndDateTime() {
        assertEquals(CONV.toMType(DataType.Date), DataTypeConverter.M_TYPE_DATE);
        assertEquals(CONV.toMType(DataType.DateTime), DataTypeConverter.M_TYPE_DATETIME);
        assertEquals(CONV.toMType(DataType.DateTime64), DataTypeConverter.M_TYPE_DATETIME);
    }

    @Test(groups = { "unit" })
    public void toMTypeFallsBackToText() {
        // Unmapped types (Str, UUID, etc.) hit the default branch.
        assertEquals(CONV.toMType(DataType.Str), DataTypeConverter.M_TYPE_TEXT);
        assertEquals(CONV.toMType(DataType.UUID), DataTypeConverter.M_TYPE_TEXT);
    }

    @Test(groups = { "unit" })
    public void toPowerQueryTypeReturnsConcreteMTypeNames() {
        // Pin concrete outputs so a future change to toPowerQueryType — e.g.
        // returning a different Power Query namespace — trips this test
        // instead of silently mirroring whatever toMType is doing.
        assertEquals(CONV.toPowerQueryType(DataType.Str), DataTypeConverter.M_TYPE_TEXT);
        assertEquals(CONV.toPowerQueryType(DataType.Int32), DataTypeConverter.M_FACET_INT32);
        assertEquals(CONV.toPowerQueryType(DataType.Float64), DataTypeConverter.M_FACET_DOUBLE);
        assertEquals(CONV.toPowerQueryType(DataType.Decimal256), DataTypeConverter.M_TYPE_NUMBER);
        assertEquals(CONV.toPowerQueryType(DataType.Date), DataTypeConverter.M_TYPE_DATE);
        assertEquals(CONV.toPowerQueryType(DataType.DateTime64), DataTypeConverter.M_TYPE_DATETIME);
    }
}
