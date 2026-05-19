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
 * {@link QueryParser#extractTableName} — hot loop on every
 * /columns_info + POST request.
 */
public class QueryParserNormalizeTest {

    private static QueryParser parser(String table) {
        return new QueryParser(null, null, table, null, null, "false",
                MultiMap.caseInsensitiveMultiMap());
    }

    @Test(groups = { "unit" })
    public void normalize_extractsBacktickedTable() {
        QueryParser p = parser("SELECT * FROM `mytable`");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_extractsDoubleQuotedTable() {
        QueryParser p = parser("SELECT * FROM \"mytable\"");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_extractsSchemaDotTable() {
        QueryParser p = parser("SELECT * FROM `myschema`.`mytable`");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesEscapedQuoteInLiteral() {
        // \' inside query is escaped single quote — parser must not toggle string region on it.
        QueryParser p = parser("SELECT * FROM `mytable` WHERE a = 'a\\'b'");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesDoubleQuoteEscapeInString() {
        // '' is SQL-standard embedded single quote.
        QueryParser p = parser("SELECT * FROM `mytable` WHERE a = 'a''b'");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesSingleLineComment() {
        QueryParser p = parser("SELECT * FROM `mytable` -- a comment\n WHERE 1=1");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_handlesMultilineComment() {
        QueryParser p = parser("SELECT * FROM `mytable` /* ignore me */ WHERE 1=1");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "mytable");
    }

    @Test(groups = { "unit" })
    public void normalize_inlineAdhocQueryPassesThroughTrimmed() {
        // No FROM -> whole input returned trimmed (adhoc query body).
        QueryParser p = parser("  SELECT 1  ");
        String norm = p.getNormalizedQuery();

        assertEquals(norm, "SELECT 1");
    }

    @Test(groups = { "unit" })
    public void normalize_unescapesTabAndNewline() {
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

    @Test(groups = { "unit" })
    public void extractTableName_pullsFromBareIdentifier() {
        QueryParser p = parser("ignored");
        String t = p.extractTable("SELECT col FROM mytable");
        assertEquals(t, "mytable");
    }

    @Test(groups = { "unit" })
    public void extractTableName_nullInputReturnsNull() {
        // extractTableName(null) -> null; falls back to extractTableName(this.table) which on
        // "ignored" returns null; final fallback: this.table.
        QueryParser p = parser("ignored");

        String t = p.extractTable(null);

        assertEquals(t, "ignored");
    }

    @Test(groups = { "unit" })
    public void extractTableName_emptyInputReturnsEmpty() {
        // extractTableName("") -> "" (empty-string short-circuit); non-null so no fallback.
        QueryParser p = parser("ignored");
        String t = p.extractTable("");

        assertEquals(t, "");
    }

    @Test(groups = { "unit" })
    public void normalize_fromAtEndOfStringIsHandledGracefully() {
        // No identifier after FROM — parser must not throw; falls back to trimmed raw query.
        QueryParser p = parser("SELECT * FROM ");
        String norm = p.getNormalizedQuery();

        assertTrue(norm != null && !norm.isEmpty(),
                "FROM at EOF must not crash normalize; got: " + norm);
    }
}
