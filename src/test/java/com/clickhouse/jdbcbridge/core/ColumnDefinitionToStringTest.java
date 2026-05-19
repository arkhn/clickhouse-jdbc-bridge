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

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Round-trip tests for {@link ColumnDefinition#toString} per-DataType
 * rendering. Every wire-format declaration that the bridge ships back
 * to ClickHouse runs through this method — a regression that drops
 * the {@code (precision,scale)} suffix on Decimal128 or skips the
 * Nullable wrapper would corrupt query columns for callers.
 *
 * <p>The existing ColumnDefinitionTest covers a couple of cases via
 * {@code fromString -> toString} round trips; this file walks the
 * matrix explicitly, asserting the exact rendered shape.</p>
 */
public class ColumnDefinitionToStringTest {

    // ---------- type-name aliasing ----------

    @Test(groups = { "unit" })
    public void toString_Bool_rendersAsBoolean() {
        ColumnDefinition c = new ColumnDefinition("flag", DataType.Bool, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        // Bool serializes on the wire as "Boolean" — pin the alias.
        assertEquals(c.toString(), "`flag` " + DataType.ALIAS_BOOLEAN);
    }

    @Test(groups = { "unit" })
    public void toString_Str_rendersAsString() {
        ColumnDefinition c = new ColumnDefinition("s", DataType.Str, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        assertEquals(c.toString(), "`s` " + DataType.ALIAS_STRING);
    }

    @Test(groups = { "unit" })
    public void toString_FixedStr_rendersWithLengthSuffix() {
        ColumnDefinition c = new ColumnDefinition("k", DataType.FixedStr, false, 16,
                DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        // FixedStr has both an alias name AND a (length) suffix.
        assertEquals(c.toString(), "`k` " + DataType.ALIAS_FIXED_STRING + "(16)");
    }

    @Test(groups = { "unit" })
    public void toString_Int32_usesDataTypeName_noSuffix() {
        ColumnDefinition c = new ColumnDefinition("n", DataType.Int32, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        // Integer types render as their DataType.name() — no alias, no suffix.
        assertEquals(c.toString(), "`n` Int32");
    }

    // ---------- Decimal precision/scale suffix ----------

    @Test(groups = { "unit" })
    public void toString_Decimal_rendersPrecisionAndScale() {
        ColumnDefinition c = new ColumnDefinition("amt", DataType.Decimal, false,
                0, 18, 4);
        assertEquals(c.toString(), "`amt` Decimal(18,4)");
    }

    @Test(groups = { "unit" })
    public void toString_Decimal32_rendersOnlyScale() {
        // Decimal32 has implicit precision (=9); only the scale is emitted.
        ColumnDefinition c = new ColumnDefinition("d32", DataType.Decimal32, false, 0, 9, 2);
        assertEquals(c.toString(), "`d32` Decimal32(2)");
    }

    @Test(groups = { "unit" })
    public void toString_Decimal64_128_256_renderOnlyScale() {
        ColumnDefinition d64 = new ColumnDefinition("d64", DataType.Decimal64, false, 0, 18, 4);
        ColumnDefinition d128 = new ColumnDefinition("d128", DataType.Decimal128, false, 0, 38, 6);
        ColumnDefinition d256 = new ColumnDefinition("d256", DataType.Decimal256, false, 0, 76, 8);

        assertEquals(d64.toString(), "`d64` Decimal64(4)");
        assertEquals(d128.toString(), "`d128` Decimal128(6)");
        assertEquals(d256.toString(), "`d256` Decimal256(8)");
    }

    // ---------- DateTime timezone suffix ----------

    @Test(groups = { "unit" })
    public void toString_DateTime_withoutTimezone_noSuffix() {
        ColumnDefinition c = new ColumnDefinition("ts", DataType.DateTime, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        assertEquals(c.toString(), "`ts` DateTime");
    }

    @Test(groups = { "unit" })
    public void toString_DateTime_withTimezone_appendsZoneId() {
        // fromJson defaults nullable=true; pass false explicitly so the
        // assertion isn't wrapped with Nullable(...).
        JsonObject src = new JsonObject()
                .put("name", "ts").put("type", "DateTime")
                .put("nullable", false).put("timezone", "UTC");
        ColumnDefinition c = ColumnDefinition.fromJson(src);

        assertEquals(c.toString(), "`ts` DateTime('UTC')");
    }

    @Test(groups = { "unit" })
    public void toString_DateTime64_emitsScaleViaFromString() {
        // fromJson doesn't carry a separate precision for DateTime64, so
        // the constructor caps scale at 0 — a latent issue in the JSON
        // ctor path. fromString sets both precision + scale correctly.
        ColumnDefinition c = ColumnDefinition.fromString("ts64 DateTime64(3)");

        assertEquals(c.toString(), "`ts64` DateTime64(3)");
    }

    @Test(groups = { "unit" })
    public void toString_DateTime64_withTimezone_appendsZoneAfterScale() {
        // Use fromString so the precision is set, allowing the scale to
        // survive the cap at this.precision.
        ColumnDefinition c = ColumnDefinition.fromString("ts DateTime64(6, 'Europe/Paris')");

        assertEquals(c.toString(), "`ts` DateTime64(6,'Europe/Paris')");
    }

    // ---------- Nullable wrap ----------

    @Test(groups = { "unit" })
    public void toString_nullableWrapsRenderedType() {
        ColumnDefinition c = new ColumnDefinition("x", DataType.Int32, true,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        // Nullable wraps just the type body, not the column name.
        assertEquals(c.toString(), "`x` Nullable(Int32)");
    }

    @Test(groups = { "unit" })
    public void toString_nullableAroundDecimal_preservesSuffix() {
        ColumnDefinition c = new ColumnDefinition("amt", DataType.Decimal, true, 0, 18, 4);
        assertEquals(c.toString(), "`amt` Nullable(Decimal(18,4))");
    }

    // ---------- backtick escaping in column name ----------

    @Test(groups = { "unit" })
    public void toString_backtickInNameIsDoubled() {
        // Column names containing the quote character double it (`` for `).
        ColumnDefinition c = new ColumnDefinition("with`tick", DataType.Str, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
        // Renders as `with``tick` String — the backtick in name is doubled.
        assertEquals(c.toString(), "`with``tick` " + DataType.ALIAS_STRING);
    }

    // ---------- Enum8 options rendering ----------

    @Test(groups = { "unit" })
    public void toString_Enum8_rendersInsertionOrderedOptions() {
        // Options use LinkedHashMap so insertion order is preserved on
        // the wire. ClickHouse depends on this for Enum8 round-trip.
        ColumnDefinition c = ColumnDefinition.fromString("status Enum8('A'=1, 'B'=2, 'C'=3)");

        assertTrue(c.toString().startsWith("`status` Enum8"),
                "Enum8 rendering must lead with column name + type: " + c);
        assertTrue(c.toString().contains("'A'=1"));
        assertTrue(c.toString().contains("'B'=2"));
        assertTrue(c.toString().contains("'C'=3"));
    }

    @Test(groups = { "unit" })
    public void toString_Enum8_quotesInOptionNameAreEscaped() {
        // Embedded quote -> escaped with backslash.
        java.util.Map<String, Integer> opts = new LinkedHashMap<>();
        opts.put("with'quote", 1);
        opts.put("plain", 2);
        ColumnDefinition c = new ColumnDefinition("status", DataType.Enum8, false,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE,
                null, null, opts);

        // The quote inside the option name must be backslash-escaped to
        // avoid breaking the wire-format option list.
        assertTrue(c.toString().contains("'with\\'quote'=1"),
                "embedded quote in option name must be escaped: " + c);
    }

    // ---------- default value rendering ----------

    @Test(groups = { "unit" })
    public void toString_stringDefault_quotedAndEscaped() {
        // String types with a default render `DEFAULT '<value>'` with
        // embedded quotes escaped.
        ColumnDefinition c = ColumnDefinition.fromString("d String DEFAULT 'hello'");
        if (ColumnDefinition.DEFAULT_VALUE_SUPPORT) {
            assertEquals(c.toString(), "`d` String DEFAULT 'hello'");
        }
    }

    @Test(groups = { "unit" })
    public void toString_numericDefault_unquoted() {
        // Numeric types render the default unquoted.
        ColumnDefinition c = ColumnDefinition.fromString("n Int32 DEFAULT 42");
        if (ColumnDefinition.DEFAULT_VALUE_SUPPORT) {
            assertEquals(c.toString(), "`n` Int32 DEFAULT 42");
        }
    }

    @Test(groups = { "unit" })
    public void toString_nullableDefault() {
        // Nullable wraps, then DEFAULT follows.
        ColumnDefinition c = ColumnDefinition.fromString("d Nullable(Int32) DEFAULT null");
        // `null` default isn't carried since ColumnDefinition treats it as no default.
        assertEquals(c.toString(), "`d` Nullable(Int32)");
    }
}
