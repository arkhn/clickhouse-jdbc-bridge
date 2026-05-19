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
 * Tests for {@link QueryParser} lazy-init getters and the extractTable helper.
 */
public class QueryParserGettersTest {

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
        // Exact match; "RowBinaryWithNames" falls outside.
        assertTrue(build(null, null, null, null, "RowBinary").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, "rowbinary").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, "JSON").usingRowBinaryInput());
        assertFalse(build(null, null, null, null, null).usingRowBinaryInput());
    }

    @Test(groups = { "unit" })
    public void useNullable_parsesBooleanString() {
        // Boolean.parseBoolean: only "true" (CI) -> true; null -> false (not NPE).
        QueryParser t = new QueryParser("u", "s", "t", "c", "RowBinary", "true",
                MultiMap.caseInsensitiveMultiMap());
        QueryParser f = new QueryParser("u", "s", "t", "c", "RowBinary", "false",
                MultiMap.caseInsensitiveMultiMap());
        QueryParser nul = new QueryParser("u", "s", "t", "c", "RowBinary", null,
                MultiMap.caseInsensitiveMultiMap());

        assertTrue(t.useNullable());
        assertFalse(f.useNullable());
        assertFalse(nul.useNullable());
    }

    @Test(groups = { "unit" })
    public void getTable_lazyInitsTableDefinitionFromColumnsInfo() {
        String cols = "columns format version: 1\n1 columns:\n`x` Int32\n";
        QueryParser p = build("uri", "", "SELECT 1", cols, "RowBinary");

        TableDefinition first = p.getTable();
        TableDefinition second = p.getTable();

        assertSame(second, first);
        assertEquals(first.size(), 1);
        assertEquals(first.getColumn(0).getName(), "x");
    }

    @Test(groups = { "unit" })
    public void getQueryParameters_lazyInitsFromUri() {
        // Cached so downstream code (ds.newQueryParameters merge) can mutate it.
        QueryParser p = build("ds?max_rows=42", "", "SELECT 1", null, "RowBinary");

        QueryParameters first = p.getQueryParameters();
        QueryParameters second = p.getQueryParameters();

        assertSame(second, first, "getQueryParameters must cache the parsed instance");
        assertEquals(first.getMaxRows(), 42);
    }

    @Test(groups = { "unit" })
    public void getNormalizedSchema_unescapesBackslashQuote() {
        // unescapeQuotes: `\'` -> `'`. Result is "ab'cd", NOT "ab''cd".
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
        // Quotes intact — bridge strips them later. Pin so refactor to unquoted doesn't slip in.
        QueryParser p = build("uri", "", "ignored", null, "RowBinary");

        String extracted = p.extractTable("SELECT * FROM `mytable`");

        assertEquals(extracted, "`mytable`");
    }
}
