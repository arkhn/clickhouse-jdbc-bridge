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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * Tests for {@link TableDefinition#fromString}'s two-mode parser:
 * headered form (ClickHouse columns wire shape) and inline form
 * (bracket- and quote-aware comma-splitter).
 */
public class TableDefinitionFromStringTest {

    @Test(groups = { "unit" })
    public void headered_validParseRoundTrip() {
        String headered = "columns format version: 1\n"
                + "2 columns:\n"
                + "`a` Int32\n"
                + "`b` String\n";

        TableDefinition def = TableDefinition.fromString(headered);

        assertEquals(def.getVersion(), 1);
        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "a");
        assertEquals(def.getColumn(0).getType(), DataType.Int32);
        assertEquals(def.getColumn(1).getName(), "b");
        assertEquals(def.getColumn(1).getType(), DataType.Str);
    }

    @Test(groups = { "unit" })
    public void headered_columnsCountLineMustEndWithSuffix() {
        // Line #2 must end with " columns:" — anything else triggers IAE.
        String bad = "columns format version: 1\n"
                + "2 things:\n"
                + "`a` Int32\n"
                + "`b` String\n";

        assertThrows(IllegalArgumentException.class, () -> TableDefinition.fromString(bad));
    }

    @Test(groups = { "unit" })
    public void headered_emptyExpectedColumnsReturnsDefault() {
        // lines.size() - 2 <= 0 -> short-circuit to DEFAULT_RESULT_COLUMNS.
        String headerOnly = "columns format version: 1\n";

        assertSame(TableDefinition.fromString(headerOnly),
                TableDefinition.DEFAULT_RESULT_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void headered_inconsistentColumnCountThrows() {
        String inconsistent = "columns format version: 1\n"
                + "5 columns:\n"
                + "`a` Int32\n"
                + "`b` String\n";

        try {
            TableDefinition.fromString(inconsistent);
            org.testng.Assert.fail("expected IAE for inconsistent count");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("failed to parse"),
                    "wrapper IAE must explain which line failed: " + e.getMessage());
        }
    }

    @Test(groups = { "unit" })
    public void inline_preservesCommaInsideParens() {
        // Decimal(19, 4) — bracket-aware splitter must keep it as a single column.
        TableDefinition def = TableDefinition.fromString("a Int32, b Decimal(19, 4)");

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "a");
        assertEquals(def.getColumn(1).getName(), "b");
        assertEquals(def.getColumn(1).getType(), DataType.Decimal);
    }

    @Test(groups = { "unit" })
    public void inline_preservesCommaInsideEnumQuotes() {
        // Quote-state must mask commas inside Enum8('N/A'=1,'OK'=2).
        TableDefinition def = TableDefinition.fromString(
                "status Enum8('N/A'=1,'OK'=2), n Int32");

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "status");
        assertEquals(def.getColumn(1).getName(), "n");
    }

    @Test(groups = { "unit" })
    public void inline_nestedParensHandled() {
        // Nullable(Decimal(19,4)) — Stack-based tracker pushes ( on entry, pops on ).
        TableDefinition def = TableDefinition.fromString(
                "x Nullable(Decimal(19, 4)), y Int32");

        assertEquals(def.size(), 2);
        assertTrue(def.getColumn(0).isNullable(),
                "Nullable wrapper must yield isNullable=true");
    }

    @Test(groups = { "unit" })
    public void inline_emptyInputFallsBackToDefault() {
        assertSame(TableDefinition.fromString(""),
                TableDefinition.DEFAULT_RESULT_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void inline_singleColumnNoComma() {
        TableDefinition def = TableDefinition.fromString("only_one Int32");
        assertEquals(def.size(), 1);
        assertEquals(def.getColumn(0).getName(), "only_one");
    }

    @Test(groups = { "unit" })
    public void inline_backslashEscapeStepsPastNextChar() {
        // '\\' appends next char verbatim — escaped comma stays in buffer; result: one column.
        TableDefinition def = TableDefinition.fromString("a\\,b Int32");
        assertEquals(def.size(), 1);
    }

    @Test(groups = { "unit" })
    public void nullInputReturnsDefault() {
        assertSame(TableDefinition.fromString(null),
                TableDefinition.DEFAULT_RESULT_COLUMNS);
    }
}
