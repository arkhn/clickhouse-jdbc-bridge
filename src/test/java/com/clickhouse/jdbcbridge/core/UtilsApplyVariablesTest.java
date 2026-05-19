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
 * Edge-case tests for {@link Utils#applyVariables} and {@code indexOfKeyword}
 * — hot path on every normalized query.
 */
public class UtilsApplyVariablesTest {

    @Test(groups = { "unit" })
    public void applyVariables_nullTemplateBecomesEmpty() {
        assertEquals(Utils.applyVariables(null, k -> "x"), "");
    }

    @Test(groups = { "unit" })
    public void applyVariables_nullOperatorReturnsTemplateVerbatim() {
        assertEquals(Utils.applyVariables("hello {{name}}", (java.util.function.UnaryOperator<String>) null),
                "hello {{name}}");
    }

    @Test(groups = { "unit" })
    public void applyVariables_emptyMapTreatedAsNullOperator() {
        // Empty map -> no operator -> no substitution.
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
        // Whitespace inside {{ ... }} is trimmed before lookup.
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
        // null from operator -> applyVariables copies literal "{{" and skips past.
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
        // Unclosed {{ -> parser appends rest verbatim and exits (pin so refactor to throw doesn't sneak in).
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

    @Test(groups = { "unit" })
    public void appendJsonString_depthLimitTriggersIAE() {
        // checkDepth throws IAE once depth exceeds OBJECT_DEPTH_LIMIT (=10).
        Object[] nested = new Object[] { "leaf" };
        for (int i = 0; i < 12; i++) {
            nested = new Object[] { nested };
        }
        final Object[] payload = nested;

        assertThrows(IllegalArgumentException.class, () -> Utils.toJsonString(payload));
    }

    @Test(groups = { "unit" })
    public void indexOfKeyword_skipsKeywordInsideParens() {
        // Stack-aware: must match OUTER FROM, not one inside (...).
        String stmt = "SELECT * FROM mytable";
        int idx = Utils.indexOfKeywordIgnoreCase(stmt, "FROM");
        assertEquals(idx, 9);
    }

    @Test(groups = { "unit" })
    public void indexOfKeyword_caseSensitive() {
        int idx = Utils.indexOfKeyword("SELECT * from mytable", "FROM", false);
        assertEquals(idx, -1);
    }

    @Test(groups = { "unit" })
    public void indexOfKeyword_missingKeywordReturnsMinusOne() {
        int idx = Utils.indexOfKeywordIgnoreCase("SELECT *", "FROM");
        assertEquals(idx, -1);
    }

    @Test(groups = { "unit" })
    public void loadExtension_resolvesKnownClass() {
        Extension<?> ext = Utils.loadExtension(NamedDataSource.class.getName());

        org.testng.Assert.assertNotNull(ext);
        assertEquals(ext.getProviderClass().getName(), NamedDataSource.class.getName());
    }

    @Test(groups = { "unit" })
    public void loadExtension_unknownClassReturnsNull() {
        // Extension ctor requires non-null class — loadExtension catches and yields null.
        Extension<?> ext = Utils.loadExtension("com.example.DefinitelyNotThere");
        org.testng.Assert.assertNull(ext);
    }
}
