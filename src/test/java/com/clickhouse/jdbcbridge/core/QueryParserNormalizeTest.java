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

import io.vertx.core.MultiMap;
import org.testng.annotations.Test;

/**
 * Edge-case tests for {@link QueryParser#normalizeQuery} and
 * {@link QueryParser#extractTableName} — the parser that pulls a
 * table identifier out of arbitrary SQL the bridge receives from
 * ClickHouse. Comments, quotes, schema-qualified names, and escape
 * sequences all have their own branches; existing tests cover a
 * subset.
 *
 * <p>This is the path that hits on every {@code /columns_info} +
 * {@code POST /} request — the read-intensive hot loop.</p>
 */
public class QueryParserNormalizeTest {

    private static QueryParser parser(String table) {
        return new QueryParser(null, null, table, null, null, "false",
                MultiMap.caseInsensitiveMultiMap());
    }

    // ---------- getNormalizedQuery with FROM clause ----------

    @Test(groups = { "unit" })
    public void normalize_extractsBacktickedTable() {
        QueryParser p = parser("SELECT * FROM `mytable`");
        String norm = p.getNormalizedQuery();

        // Bare table identifier is extracted from the FROM clause.
        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_extractsDoubleQuotedTable() {
        QueryParser p = parser("SELECT * FROM \"mytable\"");
        String norm = p.getNormalizedQuery();

        // Double quotes are the SQL standard quoting; same handling.
        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_extractsSchemaDotTable() {
        QueryParser p = parser("SELECT * FROM `myschema`.`mytable`");
        String norm = p.getNormalizedQuery();

        // schema.table notation: extract just the table portion.
        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesEscapedQuoteInLiteral() {
        // \' inside the query is an escaped single quote — the parser
        // must not start/stop a string region on it. The trailing
        // `mytable` then becomes the extracted identifier.
        QueryParser p = parser("SELECT * FROM `mytable` WHERE a = 'a\\'b'");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesDoubleQuoteEscapeInString() {
        // '' is the SQL standard for an embedded single quote in a literal.
        QueryParser p = parser("SELECT * FROM `mytable` WHERE a = 'a''b'");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesSingleLineComment() {
        // The parser must skip past -- ... \n comments rather than
        // mis-interpreting them as quoted regions.
        QueryParser p = parser("SELECT * FROM `mytable` -- a comment\n WHERE 1=1");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesMultilineComment() {
        // /* ... */ blocks are also skipped.
        QueryParser p = parser("SELECT * FROM `mytable` /* ignore me */ WHERE 1=1");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_inlineAdhocQueryPassesThroughTrimmed() {
        // No FROM keyword -> the whole input is returned trimmed (it's
        // treated as an adhoc query body, not a table reference).
        QueryParser p = parser("  SELECT 1  ");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "SELECT 1");
    }

    // ---------- escape sequence unescaping ----------

    @Test(groups = { "unit" })
    public void normalize_unescapesTabAndNewline() {
        // \t and \n are replaced with their actual control characters.
        QueryParser p = parser("a\\tb\\nc");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "a\tb\nc");
    }

    @Test(groups = { "unit" })
    public void normalize_unescapesBackslashAndQuote() {
        // \\ -> \ and \' -> '
        QueryParser p = parser("path\\\\\\'quote");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "path\\'quote");
    }

    // ---------- extractTableName ----------

    @Test(groups = { "unit" })
    public void extractTableName_pullsFromBareIdentifier() {
        QueryParser p = parser("ignored");
        // Pass a query through the public extractTable wrapper.
        String t = p.extractTable("SELECT col FROM mytable");
        // The parser returns the bare identifier (no quotes here).
        assertEquals(t, "mytable");
    }

    @Test(groups = { "unit" })
    public void extractTableName_nullInputReturnsNull() {
        QueryParser p = parser("ignored");

        // extractTableName(null) returns null; extractTable then falls
        // back to extractTableName(this.table), which on "ignored" returns
        // null too. Final fallback: return this.table.
        String t = p.extractTable(null);

        assertEquals(t, "ignored");
    }

    @Test(groups = { "unit" })
    public void extractTableName_emptyInputReturnsEmpty() {
        QueryParser p = parser("ignored");
        String t = p.extractTable("");

        // extractTableName("") returns "" (empty-string short-circuit),
        // which is non-null so extractTable does NOT fall back to
        // this.table. The empty string is the returned value as-is.
        assertEquals(t, "");
    }

    // ---------- whitespace + EOF after FROM ----------

    @Test(groups = { "unit" })
    public void normalize_fromAtEndOfStringIsHandledGracefully() {
        // Query ends with "FROM" — no table identifier follows. The
        // parser must not throw; it falls back to the raw query trimmed.
        QueryParser p = parser("SELECT * FROM ");
        String norm = p.getNormalizedQuery();

        assertTrue(norm != null && !norm.isEmpty(),
                "FROM at EOF must not crash normalize; got: " + norm);
    }
}
