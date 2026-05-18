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

import static org.testng.Assert.*;

import java.sql.JDBCType;
import java.sql.Types;

import org.testng.annotations.Test;

public class DataTypeMappingTest {
    @Test(groups = { "unit" })
    public void testConstructor() {
        DataTypeMapping m = new DataTypeMapping(Types.BOOLEAN, null, DataType.Bool);
        assertEquals(m.getSourceJdbcType(), JDBCType.BOOLEAN);
        assertEquals(m.getSourceNativeType(), null);
        assertTrue(m.accept(JDBCType.BOOLEAN, null));
        assertEquals(m.getMappedType(), DataType.Bool);

        m = new DataTypeMapping("boolean", "bool", "String");
        assertEquals(m.getSourceJdbcType(), JDBCType.BOOLEAN);
        assertEquals(m.getSourceNativeType(), "bool");
        assertFalse(m.accept(JDBCType.BOOLEAN, null));
        assertFalse(m.accept(JDBCType.BOOLEAN, "Bool"));
        assertTrue(m.accept(JDBCType.VARCHAR, "bool"));
        assertEquals(m.getMappedType(), DataType.Str);

        m = new DataTypeMapping("bit", "*", "Int8");
        assertEquals(m.getSourceJdbcType(), JDBCType.BIT);
        assertEquals(m.getSourceNativeType(), "*");
        assertTrue(m.getSourceNativeType() == DataTypeMapping.ANY_NATIVE_TYPE);
        assertTrue(m.accept(JDBCType.BOOLEAN, null));
        assertTrue(m.accept(JDBCType.BIT, "Bool"));
        assertTrue(m.accept(JDBCType.VARCHAR, "bit"));
        assertEquals(m.getMappedType(), DataType.Int8);
    }

    @Test(groups = { "unit" })
    public void testFromVendorTypeCode() {
        // microsoft.sql.Types.DATETIMEOFFSET — not in java.sql.Types, so JDBCType.valueOf throws.
        try {
            JDBCType.valueOf(-155);
            fail("Expected IllegalArgumentException for vendor-only JDBC type code -155");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(DataTypeMapping.fromVendorTypeCode(-155), DataType.DateTime64);

        // oracle.jdbc.OracleType.BINARY_FLOAT
        assertEquals(DataTypeMapping.fromVendorTypeCode(100), DataType.Float32);

        // oracle.jdbc.OracleType.BINARY_DOUBLE
        assertEquals(DataTypeMapping.fromVendorTypeCode(101), DataType.Float64);

        // Unknown vendor code returns null so the caller can decide how to fall back.
        assertNull(DataTypeMapping.fromVendorTypeCode(-9999));

        // Standard java.sql.Types codes are NOT served by the vendor lookup; this stays
        // a vendor-only escape hatch and shouldn't shadow the normal converter path.
        assertNull(DataTypeMapping.fromVendorTypeCode(Types.VARCHAR));
    }
}