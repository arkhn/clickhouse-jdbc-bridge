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

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Default methods on {@link DataTypeConverter}: as() value coercer and toMType() mapping.
 */
public class DataTypeConverterTest {

    private static final DataTypeConverter CONV = new DataTypeConverter() {
        @Override public DataType from(JDBCType jdbcType, String typeName, int p, int s, boolean signed) {
            return DataType.Str;
        }
        @Override public DataType from(Object javaObject) { return DataType.Str; }
    };

    @DataProvider(name = "numericCoercions")
    Object[][] numericCoercions() {
        // (targetClass, input, expected) — every numeric branch of as(): number, boolean (true=0/false=1 codebase convention), string parse.
        return new Object[][] {
            { Byte.class,    5,                  (byte) 5 },
            { Byte.class,    true,               (byte) 0 },
            { Byte.class,    false,              (byte) 1 },
            { Byte.class,    "42",               (byte) 42 },
            { Short.class,   7,                  (short) 7 },
            { Short.class,   true,               (short) 0 },
            { Short.class,   false,              (short) 1 },
            { Short.class,   "10000",            (short) 10000 },
            { Integer.class, 9L,                 9 },
            { Integer.class, true,               0 },
            { Integer.class, false,              1 },
            { Integer.class, "12345",            12345 },
            { Long.class,    9,                  9L },
            { Long.class,    true,               0L },
            { Long.class,    false,              1L },
            { Long.class,    "999999999999",     999_999_999_999L },
        };
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test(groups = { "unit" }, dataProvider = "numericCoercions")
    public void as_numericFamilies(Class target, Object input, Object expected) {
        assertEquals(CONV.as(target, input), expected);
    }

    @Test(groups = { "unit" })
    public void as_floatAndDouble_familiesAcceptNumberBooleanAndString() {
        assertEquals((float) CONV.as(Float.class, 1.5d), 1.5f, 0f);
        assertEquals((float) CONV.as(Float.class, true), 0.0f, 0f);
        assertEquals((float) CONV.as(Float.class, false), 1.0f, 0f);
        assertEquals((float) CONV.as(Float.class, "2.25"), 2.25f, 0f);

        assertEquals((double) CONV.as(Double.class, 3.14f), 3.14d, 0.001d);
        assertEquals((double) CONV.as(Double.class, true), 0.0d, 0d);
        assertEquals((double) CONV.as(Double.class, false), 1.0d, 0d);
        assertEquals((double) CONV.as(Double.class, "2.718281828"), 2.718281828d, 0d);
    }

    @Test(groups = { "unit" })
    public void as_booleanCoerces_boolean_int_string() {
        assertEquals(CONV.as(Boolean.class, Boolean.TRUE), Boolean.TRUE);
        assertEquals(CONV.as(Boolean.class, Boolean.FALSE), Boolean.FALSE);
        assertEquals(CONV.as(Boolean.class, 0), Boolean.FALSE);
        assertEquals(CONV.as(Boolean.class, 7), Boolean.TRUE);
        assertEquals(CONV.as(Boolean.class, "true"), Boolean.TRUE);
        assertEquals(CONV.as(Boolean.class, "false"), Boolean.FALSE);
        assertEquals(CONV.as(Boolean.class, "garbage"), Boolean.FALSE);
    }

    @Test(groups = { "unit" })
    public void as_bigInteger_and_bigDecimal() {
        assertSame(CONV.as(BigInteger.class, true), BigInteger.ZERO);
        assertSame(CONV.as(BigInteger.class, false), BigInteger.ONE);
        BigInteger biIn = BigInteger.valueOf(42);
        assertSame(CONV.as(BigInteger.class, biIn), biIn);
        assertEquals(CONV.as(BigInteger.class, "17"), BigInteger.valueOf(17));

        assertSame(CONV.as(BigDecimal.class, true), BigDecimal.ZERO);
        assertSame(CONV.as(BigDecimal.class, false), BigDecimal.ONE);
        BigDecimal bdIn = new BigDecimal("3.14159");
        assertSame(CONV.as(BigDecimal.class, bdIn), bdIn);
        assertEquals(CONV.as(BigDecimal.class, "2.5"), new BigDecimal("2.5"));
    }

    @DataProvider(name = "dateFromString")
    Object[][] dateFromString() {
        // String length picks the formatter: 10 -> Date, 19 -> Timestamp, >19 -> Timestamp(ISO_DATE_TIME), <10 -> BASIC_ISO_DATE -> Date.
        return new Object[][] {
            { "2026-05-18",              java.sql.Date.class },
            { "2026-05-18T22:00:00",     java.sql.Timestamp.class },
            { "2026-05-18T22:00:00.123", java.sql.Timestamp.class },
            { "20260518",                java.sql.Date.class },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "dateFromString")
    public void as_dateFromString_picksFormatterByLength(String input, Class<?> expected) {
        assertEquals(CONV.as(java.util.Date.class, input).getClass(), expected);
    }

    @Test(groups = { "unit" })
    public void as_dateFromNumberAndBoolean() {
        assertEquals(((java.util.Date) CONV.as(java.util.Date.class, 0L)).getTime(), 0L);
        assertEquals(((java.util.Date) CONV.as(java.util.Date.class, true)).getTime(), 0L);
        assertEquals(((java.util.Date) CONV.as(java.util.Date.class, false)).getTime(), 1L);
    }

    @Test(groups = { "unit" })
    public void as_stringStringifiesAnything_andUnsupportedTypeIsPassthrough() {
        assertEquals(CONV.as(String.class, 42), "42");
        assertEquals(CONV.as(String.class, true), "true");
        assertEquals(CONV.as(String.class, null), "null");
        Object marker = new Object();
        assertSame(CONV.as(Object.class, marker), marker);
    }

    @DataProvider(name = "toMTypeMatrix")
    Object[][] toMTypeMatrix() {
        return new Object[][] {
            // Integer families
            { DataType.Bool,       DataTypeConverter.M_FACET_INT8 },
            { DataType.Int8,       DataTypeConverter.M_FACET_INT8 },
            { DataType.UInt8,      DataTypeConverter.M_FACET_INT16 },
            { DataType.Int16,      DataTypeConverter.M_FACET_INT16 },
            { DataType.UInt16,     DataTypeConverter.M_FACET_INT32 },
            { DataType.Int32,      DataTypeConverter.M_FACET_INT32 },
            { DataType.UInt32,     DataTypeConverter.M_FACET_INT64 },
            { DataType.Int64,      DataTypeConverter.M_FACET_INT64 },
            // Float families
            { DataType.Float32,    DataTypeConverter.M_FACET_SINGLE },
            { DataType.Float64,    DataTypeConverter.M_FACET_DOUBLE },
            // Number family (UInt64 + every decimal width)
            { DataType.UInt64,     DataTypeConverter.M_TYPE_NUMBER },
            { DataType.Decimal,    DataTypeConverter.M_TYPE_NUMBER },
            { DataType.Decimal32,  DataTypeConverter.M_TYPE_NUMBER },
            { DataType.Decimal64,  DataTypeConverter.M_TYPE_NUMBER },
            { DataType.Decimal128, DataTypeConverter.M_TYPE_NUMBER },
            { DataType.Decimal256, DataTypeConverter.M_TYPE_NUMBER },
            // Date / DateTime
            { DataType.Date,       DataTypeConverter.M_TYPE_DATE },
            { DataType.DateTime,   DataTypeConverter.M_TYPE_DATETIME },
            { DataType.DateTime64, DataTypeConverter.M_TYPE_DATETIME },
            // Text fallback
            { DataType.Str,        DataTypeConverter.M_TYPE_TEXT },
            { DataType.UUID,       DataTypeConverter.M_TYPE_TEXT },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "toMTypeMatrix")
    public void toMType_mapsAllFamilies(DataType in, String expected) {
        assertEquals(CONV.toMType(in), expected);
    }

    @Test(groups = { "unit" })
    public void toPowerQueryType_returnsConcreteMTypeNames() {
        // Pin so a future change trips this test rather than silently mirroring toMType.
        assertEquals(CONV.toPowerQueryType(DataType.Str), DataTypeConverter.M_TYPE_TEXT);
        assertEquals(CONV.toPowerQueryType(DataType.Int32), DataTypeConverter.M_FACET_INT32);
        assertEquals(CONV.toPowerQueryType(DataType.Float64), DataTypeConverter.M_FACET_DOUBLE);
        assertEquals(CONV.toPowerQueryType(DataType.Decimal256), DataTypeConverter.M_TYPE_NUMBER);
        assertEquals(CONV.toPowerQueryType(DataType.Date), DataTypeConverter.M_TYPE_DATE);
        assertEquals(CONV.toPowerQueryType(DataType.DateTime64), DataTypeConverter.M_TYPE_DATETIME);
    }
}
