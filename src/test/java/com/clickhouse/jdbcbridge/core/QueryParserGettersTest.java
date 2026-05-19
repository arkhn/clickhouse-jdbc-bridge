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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import io.vertx.core.MultiMap;

/**
 * Companion tests for {@link QueryParser} — covers the lazy-init getters
 * and the public extractTable helper. Existing QueryParserTest covers
 * the static helpers (normalizeQuery, extractSchemaName, extractTableName)
 * and the QueryParameters round-trip; this file fills the rest.
 */
public class QueryParserGettersTest {

    /** Build a parser with the same shape that QueryParser.fromRequest produces. */
    private static QueryParser build(String uri, String schema, String table,
            String columnsInfo, String inputFormat) {
        return new QueryParser(uri, schema, table, columnsInfo, inputFormat, "false",
                MultiMap.caseInsensitiveMultiMap());
    }

    @Test(groups = { "unit" })
    public void constructor_simpleGetters() {
        QueryParser p = build("jdbc:ds?max_rows=10", "ch-cluster",
                "SELECT 1", "columns format version: 1\n1 columns:\n`x` Int32\n", "RowBinary");

        assertEquals(p.getConnectionString(), "jdbc:ds?max_rows=10");
        assertEquals(p.getRawSchema(), "ch-cluster");
        assertEquals(p.getRawQuery(), "SELECT 1");
        assertNotNull(p.getStreamOptions());
    }

    @Test(groups = { "unit" })
    public void usingRowBinaryInput_caseSensitiveExactMatch() {
        // The check is `"RowBinary".equals(inputFormat)` — exact match,
        // case-sensitive. CH-side variations like "RowBinaryWithNames" land
        // outside this branch.
        assertTrue(build(null, null, null, null, "RowBinary").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, "rowbinary").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, "JSON").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, null).usingRowBinaryInput());
    }

    @Test(groups = { "unit" })
    public void useNullable_parsesBooleanString() {
        // The ctor takes the useNull arg as a String (matches the
        // external_table_functions_use_nulls HTTP param). Anything except
        // "true" (case-insensitive) ends up false — that's Boolean.parseBoolean.
        QueryParser t = new QueryParser("u", "s", "t", "c", "RowBinary", "true",
                MultiMap.caseInsensitiveMultiMap());
        QueryParser f = new QueryParser("u", "s", "t", "c", "RowBinary", "false",
                MultiMap.caseInsensitiveMultiMap());
        QueryParser nul = new QueryParser("u", "s", "t", "c", "RowBinary", null,
                MultiMap.caseInsensitiveMultiMap());

        assertTrue(t.useNullable());
        assertFalse(f.useNullable());
        // Boolean.parseBoolean(null) is false (not NPE).
        assertFalse(nul.useNullable());
    }

    @Test(groups = { "unit" })
    public void getTable_lazyInitsTableDefinitionFromColumnsInfo() {
        String cols = "columns format version: 1\n1 columns:\n`x` Int32\n";
        QueryParser p = build("uri", "", "SELECT 1", cols, "RowBinary");

        TableDefinition first = p.getTable();
        TableDefinition second = p.getTable();

        // Cached on first call.
        assertSame(second, first);
        assertEquals(first.size(), 1);
        assertEquals(first.getColumn(0).getName(), "x");
    }

    @Test(groups = { "unit" })
    public void getQueryParameters_lazyInitsFromUri() {
        // QueryParameters are constructed lazily from the URI; once built,
        // subsequent calls return the same instance so they can be mutated
        // by downstream code (ds.newQueryParameters merges into it).
        QueryParser p = build("ds?max_rows=42", "", "SELECT 1", null, "RowBinary");

        QueryParameters first = p.getQueryParameters();
        QueryParameters second = p.getQueryParameters();

        assertSame(second, first, "getQueryParameters must cache the parsed instance");
        assertEquals(first.getMaxRows(), 42);
    }

    @Test(groups = { "unit" })
    public void getNormalizedSchema_unescapesBackslashQuote() {
        // unescapeQuotes turns `\'` (a quote preceded by a backslash, as it
        // appears in SQL-escaped CH-side input) into a plain `'`. Pin the
        // observed contract: result is "ab'cd", NOT "ab''cd".
        QueryParser p = build("uri", "ab\\'cd", "SELECT 1", null, "RowBinary");

        String norm = p.getNormalizedSchema();

        assertEquals(norm, "ab'cd",
                "unescapeQuotes must strip the backslash before the quote: " + norm);
    }

    @Test(groups = { "unit" })
    public void getNormalizedSchema_isCached() {
        QueryParser p = build("uri", "myschema", "SELECT 1", null, "RowBinary");
        String first = p.getNormalizedSchema();
        String second = p.getNormalizedSchema();

        assertSame(second, first);
    }

    @Test(groups = { "unit" })
    public void getNormalizedQuery_isCached() {
        QueryParser p = build("uri", "", "SELECT 1 FROM t", null, "RowBinary");
        String first = p.getNormalizedQuery();
        String second = p.getNormalizedQuery();

        assertSame(second, first);
    }

    @Test(groups = { "unit" })
    public void extractTable_pullsQuotedTableNameFromQuery() {
        // The parser returns the table identifier WITH its surrounding
        // quote characters intact — the bridge later strips them where
        // needed. Pin this so a refactor to "unquoted name" doesn't
        // accidentally land.
        QueryParser p = build("uri", "", "ignored", null, "RowBinary");

        String extracted = p.extractTable("SELECT * FROM `mytable`");

        assertEquals(extracted, "`mytable`");
    }
}
