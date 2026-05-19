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

import java.util.LinkedHashMap;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Round-trip tests for {@link ColumnDefinition#toString} per-DataType rendering.
 * Every wire-format declaration shipped back to ClickHouse runs through this.
 */
public class ColumnDefinitionToStringTest {

    @DataProvider(name = "ctorRendering")
    Object[][] ctorRendering() {
        // Cases using the long ctor (where fromString round-trips differently for some).
        return new Object[][] {
            { new ColumnDefinition("flag", DataType.Bool, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`flag` " + DataType.ALIAS_BOOLEAN },
            { new ColumnDefinition("s", DataType.Str, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`s` " + DataType.ALIAS_STRING },
            { new ColumnDefinition("k", DataType.FixedStr, false, 16,
                    DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`k` " + DataType.ALIAS_FIXED_STRING + "(16)" },
            { new ColumnDefinition("n", DataType.Int32, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`n` Int32" },
            { new ColumnDefinition("amt", DataType.Decimal, false, 0, 18, 4),
                    "`amt` Decimal(18,4)" },
            { new ColumnDefinition("d32", DataType.Decimal32, false, 0, 9, 2),
                    "`d32` Decimal32(2)" },
            { new ColumnDefinition("d64", DataType.Decimal64, false, 0, 18, 4),
                    "`d64` Decimal64(4)" },
            { new ColumnDefinition("d128", DataType.Decimal128, false, 0, 38, 6),
                    "`d128` Decimal128(6)" },
            { new ColumnDefinition("d256", DataType.Decimal256, false, 0, 76, 8),
                    "`d256` Decimal256(8)" },
            { new ColumnDefinition("ts", DataType.DateTime, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`ts` DateTime" },
            { new ColumnDefinition("x", DataType.Int32, true,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`x` Nullable(Int32)" },
            { new ColumnDefinition("amt", DataType.Decimal, true, 0, 18, 4),
                    "`amt` Nullable(Decimal(18,4))" },
            // Backtick in column name is doubled (`` for `).
            { new ColumnDefinition("with`tick", DataType.Str, false,
                    DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE),
                    "`with``tick` " + DataType.ALIAS_STRING },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "ctorRendering")
    public void toString_ctorRendering(ColumnDefinition c, String expected) {
        assertEquals(c.toString(), expected);
    }

    @DataProvider(name = "fromStringRendering")
    Object[][] fromStringRendering() {
        // Cases where fromString sets fields the JSON/long-ctor paths cannot.
        return new Object[][] {
            // DateTime64: fromString sets both precision + scale (JSON caps scale at 0).
            { "ts64 DateTime64(3)",                       "`ts64` DateTime64(3)" },
            { "ts DateTime64(6, 'Europe/Paris')",         "`ts` DateTime64(6,'Europe/Paris')" },
            // null default isn't carried — treated as no default.
            { "d Nullable(Int32) DEFAULT null",           "`d` Nullable(Int32)" },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "fromStringRendering")
    public void toString_fromStringRendering(String input, String expected) {
        assertEquals(ColumnDefinition.fromString(input).toString(), expected);
    }

    @Test(groups = { "unit" })
    public void toString_DateTime_withTimezone_fromJsonAppendsZoneId() {
        // nullable=false explicit so assertion isn't wrapped with Nullable(...).
        JsonObject src = new JsonObject()
                .put("name", "ts").put("type", "DateTime")
                .put("nullable", false).put("timezone", "UTC");
        assertEquals(ColumnDefinition.fromJson(src).toString(), "`ts` DateTime('UTC')");
    }

    @Test(groups = { "unit" })
    public void toString_Enum8_rendersInsertionOrderedOptionsAndEscapesQuotes() {
        // LinkedHashMap preserves insertion order on the wire — ClickHouse depends on this.
        ColumnDefinition fromStr = ColumnDefinition.fromString("status Enum8('A'=1, 'B'=2, 'C'=3)");
        assertTrue(fromStr.toString().startsWith("`status` Enum8"), fromStr.toString());
        assertTrue(fromStr.toString().contains("'A'=1"));
        assertTrue(fromStr.toString().contains("'B'=2"));
        assertTrue(fromStr.toString().contains("'C'=3"));

        // Embedded quote in option name must be backslash-escaped.
        java.util.Map<String, Integer> opts = new LinkedHashMap<>();
        opts.put("with'quote", 1);
        opts.put("plain", 2);
        ColumnDefinition c = new ColumnDefinition("status", DataType.Enum8, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE,
                null, null, opts);
        assertTrue(c.toString().contains("'with\\'quote'=1"), c.toString());
    }

    @Test(groups = { "unit" })
    public void toString_defaults_renderWhenSupportEnabled() {
        // DEFAULT_VALUE_SUPPORT flag gates the DEFAULT clause emission.
        if (!ColumnDefinition.DEFAULT_VALUE_SUPPORT) return;
        assertEquals(ColumnDefinition.fromString("d String DEFAULT 'hello'").toString(),
                "`d` String DEFAULT 'hello'");
        assertEquals(ColumnDefinition.fromString("n Int32 DEFAULT 42").toString(),
                "`n` Int32 DEFAULT 42");
    }
}
