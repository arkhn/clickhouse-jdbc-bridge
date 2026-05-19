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
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * Walks {@link ByteBuffer#writeDefaultValue} through every DataType branch
 * (NULL-cell fallback when {@code null_as_default=true}).
 */
public class ByteBufferWriteDefaultValueTest {

    private static ColumnDefinition col(DataType type) {
        return col(type, DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    private static ColumnDefinition col(DataType type, int length, int precision, int scale) {
        return new ColumnDefinition("c", type, false, length, precision, scale);
    }

    private static ByteBuffer fresh() {
        return ByteBuffer.newInstance(512);
    }

    private static void assertNonEmpty(ByteBuffer b, DataType t) {
        assertTrue(b.length() > 0,
                "writeDefaultValue must emit bytes for " + t + ", got 0");
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_integerFamilies() {
        DefaultValues dv = new DefaultValues();
        for (DataType t : new DataType[] {
                DataType.Bool, DataType.Int8, DataType.Int16, DataType.Int32, DataType.Int64,
                DataType.Int128, DataType.Int256,
                DataType.UInt8, DataType.UInt16, DataType.UInt32, DataType.UInt64,
                DataType.UInt128, DataType.UInt256,
        }) {
            ByteBuffer b = fresh();
            b.writeDefaultValue(col(t), dv);
            assertNonEmpty(b, t);
        }
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_floatFamilies() {
        DefaultValues dv = new DefaultValues();
        ByteBuffer b = fresh();
        b.writeDefaultValue(col(DataType.Float32), dv);
        b.writeDefaultValue(col(DataType.Float64), dv);

        assertEquals(b.length(), 12);
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_dateAndDateTimeFamilies() {
        DefaultValues dv = new DefaultValues();
        for (DataType t : new DataType[] {
                DataType.Date, DataType.DateTime, DataType.DateTime64,
        }) {
            ByteBuffer b = fresh();
            b.writeDefaultValue(col(t), dv);
            assertNonEmpty(b, t);
        }
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_decimalFamilies() {
        // scale > 0 so inner writeDecimal* doesn't divide by zero.
        DefaultValues dv = new DefaultValues();
        ByteBuffer b = fresh();
        b.writeDefaultValue(col(DataType.Decimal, 0, 18, 2), dv);
        b.writeDefaultValue(col(DataType.Decimal32, 0, 9, 2), dv);
        b.writeDefaultValue(col(DataType.Decimal64, 0, 18, 2), dv);
        b.writeDefaultValue(col(DataType.Decimal128, 0, 38, 2), dv);
        b.writeDefaultValue(col(DataType.Decimal256, 0, 76, 2), dv);

        assertNonEmpty(b, DataType.Decimal);
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_stringAndFixedStringAndUuid() {
        DefaultValues dv = new DefaultValues();
        for (DataType t : new DataType[] {
                DataType.Str, DataType.FixedStr, DataType.UUID,
        }) {
            ByteBuffer b = fresh();
            b.writeDefaultValue(col(t, 8, 0, 0), dv);
            assertNonEmpty(b, t);
        }
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_enumFamilies() {
        DefaultValues dv = new DefaultValues();
        for (DataType t : new DataType[] {
                DataType.Enum, DataType.Enum8, DataType.Enum16,
        }) {
            ByteBuffer b = fresh();
            b.writeDefaultValue(col(t), dv);
            assertNonEmpty(b, t);
        }
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_ipv4AndIpv6() {
        DefaultValues dv = new DefaultValues();
        ByteBuffer b = fresh();
        b.writeDefaultValue(col(DataType.IPv4), dv);
        b.writeDefaultValue(col(DataType.IPv6), dv);

        assertNonEmpty(b, DataType.IPv4);
    }

    @Test(groups = { "unit" })
    public void writeDefaultValue_fluentReturn() {
        ByteBuffer b = fresh();
        ByteBuffer ret = b.writeDefaultValue(col(DataType.Int32), new DefaultValues());

        assertEquals(ret, b, "writeDefaultValue must return the same buffer for chaining");
    }
}
