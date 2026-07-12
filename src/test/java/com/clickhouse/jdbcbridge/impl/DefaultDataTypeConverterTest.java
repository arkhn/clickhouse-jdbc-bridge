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
package com.clickhouse.jdbcbridge.impl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.JDBCType;
import java.util.Arrays;
import java.util.Collections;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.DataType;
import com.clickhouse.jdbcbridge.core.DataTypeMapping;

public class DefaultDataTypeConverterTest {

    private static final DefaultDataTypeConverter CONV = new DefaultDataTypeConverter();

    @Test(groups = { "unit" })
    public void tinyIntMapsByPrecisionAndSign() {
        assertEquals(CONV.from(JDBCType.TINYINT, "tinyint", 3, 0, true), DataType.Int8);
        assertEquals(CONV.from(JDBCType.TINYINT, "tinyint unsigned", 3, 0, false), DataType.UInt8);
    }

    @Test(groups = { "unit" })
    public void smallIntMapsBySign() {
        assertEquals(CONV.from(JDBCType.SMALLINT, "smallint", 5, 0, true), DataType.Int16);
        assertEquals(CONV.from(JDBCType.SMALLINT, "smallint unsigned", 5, 0, false), DataType.UInt16);
    }

    @Test(groups = { "unit" })
    public void integerMapsBySign() {
        assertEquals(CONV.from(JDBCType.INTEGER, "int", 10, 0, true), DataType.Int32);
        assertEquals(CONV.from(JDBCType.INTEGER, "int unsigned", 10, 0, false), DataType.UInt32);
    }

    @Test(groups = { "unit" })
    public void bigIntMapsBySign() {
        assertEquals(CONV.from(JDBCType.BIGINT, "bigint", 19, 0, true), DataType.Int64);
        assertEquals(CONV.from(JDBCType.BIGINT, "bigint unsigned", 20, 0, false), DataType.UInt64);
    }

    @Test(groups = { "unit" })
    public void bitWidensIntoBiggerIntsAsPrecisionGrows() {
        // BIT chooses smallest Int* that fits `precision` bits.
        assertEquals(CONV.from(JDBCType.BIT, "bit", 4, 0, true), DataType.Int8);
        assertEquals(CONV.from(JDBCType.BIT, "bit", 9, 0, true), DataType.Int16);
        assertEquals(CONV.from(JDBCType.BIT, "bit", 17, 0, true), DataType.Int32);
        assertEquals(CONV.from(JDBCType.BIT, "bit", 33, 0, true), DataType.Int64);
        assertEquals(CONV.from(JDBCType.BIT, "bit", 65, 0, true), DataType.Int128);
        assertEquals(CONV.from(JDBCType.BIT, "bit", 129, 0, true), DataType.Int256);
    }

    @Test(groups = { "unit" })
    public void realAndFloatMapToFloat32() {
        assertEquals(CONV.from(JDBCType.REAL, "real", 7, 0, true), DataType.Float32);
        assertEquals(CONV.from(JDBCType.FLOAT, "float", 7, 0, true), DataType.Float32);
    }

    @Test(groups = { "unit" })
    public void doubleMapsToFloat64() {
        assertEquals(CONV.from(JDBCType.DOUBLE, "double", 15, 0, true), DataType.Float64);
    }

    @Test(groups = { "unit" })
    public void numericAndDecimalMapToDecimal() {
        assertEquals(CONV.from(JDBCType.NUMERIC, "numeric", 18, 2, true), DataType.Decimal);
        assertEquals(CONV.from(JDBCType.DECIMAL, "decimal", 18, 2, true), DataType.Decimal);
    }

    @Test(groups = { "unit" })
    public void allStringJdbcTypesMapToStr() {
        for (JDBCType t : new JDBCType[] {
                JDBCType.ARRAY, JDBCType.OTHER, JDBCType.BOOLEAN, JDBCType.CHAR, JDBCType.NCHAR,
                JDBCType.VARCHAR, JDBCType.NVARCHAR, JDBCType.LONGVARCHAR, JDBCType.LONGNVARCHAR,
                JDBCType.NULL }) {
            assertEquals(CONV.from(t, t.name(), 0, 0, true), DataType.Str, t.name());
        }
    }

    @Test(groups = { "unit" })
    public void dateMapsToDate() {
        assertEquals(CONV.from(JDBCType.DATE, "date", 10, 0, true), DataType.Date);
    }


    @Test(groups = { "unit" })
    public void timestampAlwaysMapsToDateTime64() {
        // DateTime (UInt32) cannot represent pre-1970 dates — any JDBC source
        // (Oracle, SQL Server, PostgreSQL...) can produce them. Always use DateTime64.
        assertEquals(CONV.from(JDBCType.TIMESTAMP, "timestamp", 19, 0, true), DataType.DateTime64);
        assertEquals(CONV.from(JDBCType.TIMESTAMP, "timestamp(3)", 23, 3, true), DataType.DateTime64);
        assertEquals(CONV.from(JDBCType.TIMESTAMP, "DATE", 7, 0, true), DataType.DateTime64);
        assertEquals(CONV.from(JDBCType.TIME, "time", 8, 0, true), DataType.DateTime64);
        assertEquals(CONV.from(JDBCType.TIME_WITH_TIMEZONE, "time", 8, 0, true), DataType.DateTime64);
        assertEquals(CONV.from(JDBCType.TIMESTAMP_WITH_TIMEZONE, "timestamptz", 23, 6, true), DataType.DateTime64);
    }

    @Test(groups = { "unit" })
    public void unsupportedJdbcTypeFallsBackToStr() {
        assertEquals(CONV.from(JDBCType.STRUCT, "struct", 0, 0, true), DataType.Str);
        assertEquals(CONV.from(JDBCType.BLOB, "blob", 0, 0, true), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void customMappingShortCircuitsBuiltinResolution() {
        // Custom DECIMAL->Int64 must win over built-in DECIMAL->Decimal.
        DefaultDataTypeConverter custom = new DefaultDataTypeConverter(
                Collections.singletonList(new DataTypeMapping(JDBCType.DECIMAL, "*", DataType.Int64)));

        assertEquals(custom.from(JDBCType.DECIMAL, "decimal", 18, 2, true), DataType.Int64);
    }

    @Test(groups = { "unit" })
    public void customMappingNonMatchFallsThrough() {
        // Non-wildcard mapping bound to specific native must not match different native.
        DefaultDataTypeConverter custom = new DefaultDataTypeConverter(
                Arrays.asList(new DataTypeMapping(JDBCType.BIGINT, "very-specific-type", DataType.Int8)));

        assertEquals(custom.from(JDBCType.BIGINT, "bigint", 19, 0, true), DataType.Int64);
    }

    @Test(groups = { "unit" })
    public void nullMappingsListIsAcceptedAndYieldsBuiltinBehavior() {
        DefaultDataTypeConverter explicitNull = new DefaultDataTypeConverter(null);
        assertEquals(explicitNull.from(JDBCType.INTEGER, "int", 10, 0, true), DataType.Int32);
    }

    @Test(groups = { "unit" })
    public void fromObjectClassifiesPrimitiveBoxes() {
        assertEquals(CONV.from((Object) null), DataType.Str);
        assertEquals(CONV.from((Object) (byte) 1), DataType.Int8);
        assertEquals(CONV.from((Object) (short) 1), DataType.Int16);
        assertEquals(CONV.from((Object) 1), DataType.Int32);
        assertEquals(CONV.from((Object) 1L), DataType.Int64);
        assertEquals(CONV.from(BigInteger.ONE), DataType.Int256);
        assertEquals(CONV.from((Object) 1.0f), DataType.Float32);
        assertEquals(CONV.from((Object) 1.0d), DataType.Float64);
        assertEquals(CONV.from(BigDecimal.ONE), DataType.Decimal256);
        assertEquals(CONV.from("hello"), DataType.Str);
        assertEquals(CONV.from(Boolean.TRUE), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void asPasses_throughForUntypedClasses() {
        String marker = "marker";
        assertSame(CONV.as(String.class, marker), marker);
    }
}
