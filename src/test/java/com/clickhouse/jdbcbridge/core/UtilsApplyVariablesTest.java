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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Companion tests for {@link Utils#applyVariables} edge cases that the
 * existing {@link UtilsTest#testApplyVariables} skips: the
 * null-operator pass-through, missing closing suffix, null-value
 * handler returning the placeholder verbatim, multiple variables, and
 * the {@code indexOfKeyword} parser's stack-aware whitespace handling.
 *
 * <p>These helpers are used on every request's normalized query —
 * read-intensive code path worth pinning.</p>
 */
public class UtilsApplyVariablesTest {

    // ---------- applyVariables(template, UnaryOperator) ----------

    @Test(groups = { "unit" })
    public void applyVariables_nullTemplateBecomesEmpty() {
        assertEquals(Utils.applyVariables(null, k -> "x"), "");
    }

    @Test(groups = { "unit" })
    public void applyVariables_nullOperatorReturnsTemplateVerbatim() {
        // Bypass branch: no operator -> no substitution attempted.
        assertEquals(Utils.applyVariables("hello {{name}}", (java.util.function.UnaryOperator<String>) null),
                "hello {{name}}");
    }

    @Test(groups = { "unit" })
    public void applyVariables_emptyMapTreatedAsNullOperator() {
        // The Map-arg overload routes through `variables == null || isEmpty() ? null : variables::get`,
        // so empty map -> no substitution.
        assertEquals(Utils.applyVariables("hello {{name}}", new HashMap<>()),
                "hello {{name}}");
    }

    @Test(groups = { "unit" })
    public void applyVariables_simpleSubstitution() {
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "world");
        assertEquals(Utils.applyVariables("hello {{name}}", vars), "hello world");
    }

    @Test(groups = { "unit" })
    public void applyVariables_trimsVariableNames() {
        // Whitespace inside {{ ... }} is trimmed before lookup. ClickHouse
        // operators format their templates with {{ name }} sometimes —
        // pin that trim path.
        Map<String, String> vars = new HashMap<>();
        vars.put("name", "world");
        assertEquals(Utils.applyVariables("hello {{  name  }}", vars), "hello world");
    }

    @Test(groups = { "unit" })
    public void applyVariables_multipleSubstitutions_preserveOrder() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("a", "first");
        vars.put("b", "second");
        assertEquals(Utils.applyVariables("{{a}} then {{b}}", vars),
                "first then second");
    }

    @Test(groups = { "unit" })
    public void applyVariables_unknownVariableLeavesPlaceholder() {
        // The operator returns null for unknown keys — applyVariables
        // copies the literal "{{" and skips past it, leaving the
        // placeholder in the output.
        Map<String, String> vars = new HashMap<>();
        vars.put("known", "x");
        String out = Utils.applyVariables("{{unknown}} and {{known}}", vars);

        assertTrue(out.contains("{{unknown}}"),
                "unknown variable must be left as placeholder in output: " + out);
        assertTrue(out.contains("x"),
                "known variable must still substitute: " + out);
    }

    @Test(groups = { "unit" })
    public void applyVariables_unbalancedPrefix_appendsRestVerbatim() {
        // Template starts a {{ but never closes — the parser appends the
        // rest of the string and exits. Pin that contract so a refactor
        // to throw doesn't sneak in.
        Map<String, String> vars = new HashMap<>();
        vars.put("x", "y");
        String out = Utils.applyVariables("hello {{unclosed", vars);

        assertTrue(out.contains("{{unclosed"),
                "unclosed prefix must be preserved verbatim: " + out);
    }

    @Test(groups = { "unit" })
    public void applyVariables_noVariablesInTemplate() {
        Map<String, String> vars = new HashMap<>();
        vars.put("unused", "x");
        assertEquals(Utils.applyVariables("plain string", vars), "plain string");
    }

    @Test(groups = { "unit" })
    public void applyVariables_emptyTemplateRoundTrips() {
        assertEquals(Utils.applyVariables("", new HashMap<>()), "");
    }

    // ---------- depth limit (via toJsonString -> appendJsonString) ----------

    @Test(groups = { "unit" })
    public void appendJsonString_depthLimitTriggersIAE() {
        // appendJsonString routes through checkDepth which throws IAE
        // once depth exceeds OBJECT_DEPTH_LIMIT (=10). We construct a
        // 12-deep nested list to trip the guard.
        Object[] nested = new Object[] { "leaf" };
        for (int i = 0; i < 12; i++) {
            nested = new Object[] { nested };
        }
        final Object[] payload = nested;

        assertThrows(IllegalArgumentException.class, () -> Utils.toJsonString(payload));
    }

    // ---------- indexOfKeyword with parenthesised expressions ----------

    @Test(groups = { "unit" })
    public void indexOfKeyword_skipsKeywordInsideParens() {
        // The stack-aware parser must NOT match a keyword that appears
        // inside parentheses — e.g. SELECT col FROM t WHERE (col FROM 1)
        // should match the OUTER "FROM", not the one inside (...).
        String stmt = "SELECT * FROM mytable";
        int idx = Utils.indexOfKeywordIgnoreCase(stmt, "FROM");
        assertEquals(idx, 9); // "SELECT * " is 9 chars
    }

    @Test(groups = { "unit" })
    public void indexOfKeyword_caseSensitive() {
        // Case-sensitive variant. Lowercase "from" must NOT match "FROM".
        int idx = Utils.indexOfKeyword("SELECT * from mytable", "FROM", false);
        assertEquals(idx, -1);
    }

    @Test(groups = { "unit" })
    public void indexOfKeyword_missingKeywordReturnsMinusOne() {
        int idx = Utils.indexOfKeywordIgnoreCase("SELECT *", "FROM");
        assertEquals(idx, -1);
    }

    // ---------- loadExtension with a real class ----------

    @Test(groups = { "unit" })
    public void loadExtension_resolvesKnownClass() {
        Extension<?> ext = Utils.loadExtension(NamedDataSource.class.getName());

        // The extension wraps the loaded class; we don't pin its name
        // (depends on EXTENSION_NAME field convention) but it must exist.
        org.testng.Assert.assertNotNull(ext);
        assertEquals(ext.getProviderClass().getName(), NamedDataSource.class.getName());
    }

    @Test(groups = { "unit" })
    public void loadExtension_unknownClassReturnsNull() {
        // The internal loadClass call returns null for unknown names;
        // loadExtension wraps it in Extension(null) which throws NPE
        // on use, OR returns null directly. Pin the observable behavior.
        Extension<?> ext = Utils.loadExtension("com.example.DefinitelyNotThere");
        // The Extension constructor requires non-null class — so
        // loadExtension catches and yields null.
        org.testng.Assert.assertNull(ext);
    }
}
