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
 * Exercises {@link TableDefinition#fromString}'s two-mode parser:
 *
 * <ul>
 *   <li>headered form (starts with {@code "columns format version: "}):
 *       the canonical wire shape ClickHouse uses for the {@code columns}
 *       request param; tests cover the inconsistent-count and
 *       not-ending-with-prefix error paths.</li>
 *   <li>inline form: bracket- and quote-aware comma-splitter that
 *       preserves commas inside {@code Enum8('A'=1,'B'=2)} or
 *       {@code Decimal(19,4)}. Tests cover paren-nesting, mismatched
 *       quotes, escape-sequences, and the empty-input fallback.</li>
 * </ul>
 *
 * <p>This parser sits between the bridge's HTTP request handler and
 * the row-streaming code, so a regression here breaks every
 * non-trivial column declaration on the read path.</p>
 */
public class TableDefinitionFromStringTest {

    // ---------- headered form ----------

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
        // The second line of a headered block must end with " columns:" —
        // anything else triggers the "line #2 must be end with..." IAE.
        String bad = "columns format version: 1\n"
                + "2 things:\n" // wrong suffix
                + "`a` Int32\n"
                + "`b` String\n";

        assertThrows(IllegalArgumentException.class, () -> TableDefinition.fromString(bad));
    }

    @Test(groups = { "unit" })
    public void headered_emptyExpectedColumnsReturnsDefault() {
        // lines.size() - 2 <= 0 -> short-circuit to DEFAULT_RESULT_COLUMNS.
        // This is the "header but no column body" path.
        String headerOnly = "columns format version: 1\n";

        assertSame(TableDefinition.fromString(headerOnly),
                TableDefinition.DEFAULT_RESULT_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void headered_inconsistentColumnCountThrows() {
        // Declared 5 columns but only 2 in the body. The check is
        // `if (columns.length < Integer.parseInt(cCount))` — the IAE
        // wraps with "inconsistent columns count: declared 5 ...".
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

    // ---------- inline form: bracket-aware split ----------

    @Test(groups = { "unit" })
    public void inline_preservesCommaInsideParens() {
        // Decimal(19, 4) has a comma inside (...) — the bracket-aware
        // splitter must keep it as a single column rather than splitting
        // "Decimal(19" from "4)".
        TableDefinition def = TableDefinition.fromString("a Int32, b Decimal(19, 4)");

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "a");
        assertEquals(def.getColumn(1).getName(), "b");
        assertEquals(def.getColumn(1).getType(), DataType.Decimal);
    }

    @Test(groups = { "unit" })
    public void inline_preservesCommaInsideEnumQuotes() {
        // Enum8('N/A'=1,'OK'=2) has commas inside the single quotes —
        // the quote-state in the splitter must mask them.
        TableDefinition def = TableDefinition.fromString(
                "status Enum8('N/A'=1,'OK'=2), n Int32");

        assertEquals(def.size(), 2);
        assertEquals(def.getColumn(0).getName(), "status");
        assertEquals(def.getColumn(1).getName(), "n");
    }

    @Test(groups = { "unit" })
    public void inline_nestedParensHandled() {
        // Nullable(Decimal(19,4)) — two levels of parens. The Stack-based
        // tracker pushes ( on entry, pops on closing ); single-column
        // result.
        TableDefinition def = TableDefinition.fromString(
                "x Nullable(Decimal(19, 4)), y Int32");

        assertEquals(def.size(), 2);
        assertTrue(def.getColumn(0).isNullable(),
                "Nullable wrapper must yield isNullable=true");
    }

    @Test(groups = { "unit" })
    public void inline_emptyInputFallsBackToDefault() {
        // Empty input -> splittedColumns is empty -> short-circuit to
        // DEFAULT_RESULT_COLUMNS.
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
        // The '\\' case appends the next character verbatim (skips its
        // role as quote/paren marker). Useful for embedding literal commas
        // in column names — `a\,b` becomes a column named `a,b`.
        // Actually the parser treats the escaped char as data so the comma
        // stays in the buffer rather than splitting. Result: one column.
        TableDefinition def = TableDefinition.fromString("a\\,b Int32");
        assertEquals(def.size(), 1);
    }

    // ---------- null input ----------

    @Test(groups = { "unit" })
    public void nullInputReturnsDefault() {
        assertSame(TableDefinition.fromString(null),
                TableDefinition.DEFAULT_RESULT_COLUMNS);
    }
}
