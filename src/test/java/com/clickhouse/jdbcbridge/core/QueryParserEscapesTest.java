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

import io.vertx.core.MultiMap;
import org.testng.annotations.Test;

/**
 * Walks every escape-sequence branch of {@link QueryParser#normalizeQuery}'s
 * unescape switch — \t, \b, \n, \r, \f, \', \", \`, \\, and the
 * default (any other character after a backslash). These run on every
 * incoming SQL string (read-intensive path), so a regression that
 * mangles a control character would silently corrupt the rewritten
 * query.
 *
 * <p>QueryParserNormalizeTest already covers \t \n \\ \' — this file
 * extends the matrix.</p>
 */
public class QueryParserEscapesTest {

    private static QueryParser parser(String table) {
        return new QueryParser(null, null, table, null, null, "false",
                MultiMap.caseInsensitiveMultiMap());
    }

    @Test(groups = { "unit" })
    public void unescape_backspace() {
        assertEquals(parser("a\\bb").getNormalizedQuery(), "a\bb");
    }

    @Test(groups = { "unit" })
    public void unescape_carriageReturn() {
        // Note: a trailing \r would be trimmed away by the final trim(),
        // so embed it between non-whitespace characters.
        assertEquals(parser("a\\rb").getNormalizedQuery(), "a\rb");
    }

    @Test(groups = { "unit" })
    public void unescape_formfeed() {
        assertEquals(parser("a\\fb").getNormalizedQuery(), "a\fb");
    }

    @Test(groups = { "unit" })
    public void unescape_doubleQuote() {
        assertEquals(parser("a\\\"b").getNormalizedQuery(), "a\"b");
    }

    @Test(groups = { "unit" })
    public void unescape_backtick() {
        assertEquals(parser("a\\`b").getNormalizedQuery(), "a`b");
    }

    @Test(groups = { "unit" })
    public void unescape_unrecognizedEscape_preservesBackslash() {
        // \z is not in the switch — falls through to default: append the
        // backslash and continue. The `z` is then emitted on the next
        // iteration as a normal char. So "a\\zb" -> "a\zb".
        assertEquals(parser("a\\zb").getNormalizedQuery(), "a\\zb");
    }

    @Test(groups = { "unit" })
    public void unescape_trailingBackslashKept() {
        // i+1 < len fails for a trailing backslash — the else branch
        // appends the lone char. Pin so the parser never throws AIOOBE.
        // (Trim collapses trailing whitespace, but \ isn't whitespace.)
        assertEquals(parser("ab\\").getNormalizedQuery(), "ab\\");
    }

    // ---------- public unescape (URL-decode) ----------

    @Test(groups = { "unit" })
    public void publicUnescape_decodesUrlEncoded() {
        // The package-private unescape() is called by fromRequest for
        // sample_block parsing. It URL-decodes "a%20b" -> "a b" and
        // "+" -> " " per the URL-decode spec.
        assertEquals(QueryParser.unescape("a%20b"), "a b");
        assertEquals(QueryParser.unescape("a+b"), "a b");
        assertEquals(QueryParser.unescape("plain"), "plain");
    }

    // ---------- normalizeQuery: comments inside FROM-clause scan ----------

    @Test(groups = { "unit" })
    public void normalize_singleLineCommentAfterQuotedTable_extractsTable() {
        // The FROM-clause scan has its own comment handling for the
        // identifier-extraction phase (separate from extractTableName).
        // Pin that a -- comment after a quoted identifier doesn't trip
        // the scan.
        QueryParser p = parser("SELECT 1 FROM `t` -- skip me\n");
        assertEquals(p.getNormalizedQuery(), "t");
    }

    @Test(groups = { "unit" })
    public void normalize_multilineCommentAfterQuotedTable_extractsTable() {
        // Same, for /* ... */.
        QueryParser p = parser("SELECT 1 FROM `t` /* skip */ WHERE 1");
        assertEquals(p.getNormalizedQuery(), "t");
    }
}
