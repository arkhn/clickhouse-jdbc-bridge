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

import static com.clickhouse.jdbcbridge.core.DataType.*;
import static org.testng.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

public class TableDefinitionTest {
    @Test(groups = { "unit" })
    public void testUpdateValues() {
        TableDefinition list = new TableDefinition(
                new ColumnDefinition("column1", DataType.Int32, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE),
                new ColumnDefinition("column2", DataType.Str, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE),
                new ColumnDefinition("column3", DataType.Int32, true, DEFAULT_LENGTH, DEFAULT_PRECISION,
                        DEFAULT_SCALE));

        Object[] expectedValues = new Object[] { 0, "", 0 };
        for (int i = 0; i < list.size(); i++) {
            assertEquals(list.getColumn(i).value.getValue(), expectedValues[i]);
        }

        list.updateValues(null);
        for (int i = 0; i < list.size(); i++) {
            assertEquals(list.getColumn(i).value.getValue(), expectedValues[i]);
        }

        List<ColumnDefinition> refs = new ArrayList<ColumnDefinition>();
        list.updateValues(refs);
        for (int i = 0; i < list.size(); i++) {
            assertEquals(list.getColumn(i).value.getValue(), expectedValues[i]);
        }

        refs.add(new ColumnDefinition("xcolumn", DataType.Str, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE,
                null, "x", null));
        list.updateValues(refs);
        for (int i = 0; i < list.size(); i++) {
            assertEquals(list.getColumn(i).value.getValue(), expectedValues[i]);
        }

        refs.add(new ColumnDefinition("column2", DataType.Int16, true, DEFAULT_LENGTH, DEFAULT_PRECISION, DEFAULT_SCALE,
                null, "22", null));
        list.updateValues(refs);
        expectedValues = new Object[] { 0, "22", 0 };
        for (int i = 0; i < list.size(); i++) {
            assertEquals(list.getColumn(i).value.getValue(), expectedValues[i]);
        }
    }

    @Test(groups = { "unit" })
    public void testFromString_parsesInlineNullableAndEnumColumns() {
        // Comma-separated inline column schema (the form ClickHouse sends in
        // the `columns` form-field). Used to only assert getColumn(1).toString()
        // was non-null — that's vacuously true since toString builds a
        // StringBuilder. Pin actual parsed column structure instead.
        String inlineSchema = "a Nullable(UInt8) default 3, b Enum8('N/A'=1, 'SB'=2)";
        TableDefinition def = TableDefinition.fromString(inlineSchema);

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "a");
        assertTrue(def.getColumn(0).isNullable(),
                "`a Nullable(UInt8)` must yield a nullable column");
        assertEquals(def.getColumn(0).getType(), DataType.UInt8);

        assertEquals(def.getColumn(1).getName(), "b");
        // Enum8 round-trips through toString and contains the literal enum values.
        assertTrue(def.getColumn(1).toString().contains("Enum8"),
                "rendered Enum8 column must mention its type: " + def.getColumn(1));
        assertTrue(def.getColumn(1).toString().contains("SB"),
                "rendered Enum8 column must include its labels: " + def.getColumn(1));
    }
}