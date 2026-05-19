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

import java.util.Arrays;
import java.util.Collections;
import java.util.Vector;

import org.testng.annotations.Test;

/**
 * Tests for the {@link Utils#toJsonString} branches not pinned by
 * the existing UtilsTest: char[]/Character[] quoting, multi-element
 * Enumeration, multi-key Map, and the JSON-string escape branch for
 * any other Object.
 *
 * <p>These cover the appendJsonString switch arms that were missed
 * by the existing happy paths — the bridge serializes JDBC values
 * through this code on the read path, so escape correctness matters
 * for char/Character columns and for nested results.</p>
 */
public class UtilsToJsonStringTest {

    // ---------- char[] ----------

    @Test(groups = { "unit" })
    public void charArray_quotesEachCharacter() {
        // char[] is serialized as a JSON array of quoted single chars
        // (not as a single string).
        assertEquals(Utils.toJsonString(new char[] { 'a', 'b', 'c' }),
                "[\"a\",\"b\",\"c\"]");
    }

    @Test(groups = { "unit" })
    public void charArray_singleElement_noLeadingComma() {
        assertEquals(Utils.toJsonString(new char[] { 'x' }), "[\"x\"]");
    }

    @Test(groups = { "unit" })
    public void charArray_specialChar_isJsonEscaped() {
        // \ and " in chars round-trip through JsonStringEncoder.
        String json = Utils.toJsonString(new char[] { '"', '\\' });
        // The encoder backslash-escapes both — at minimum the output
        // must not contain unescaped " mid-element or unescaped \.
        assertTrue(json.startsWith("[\"") && json.endsWith("\"]"),
                "char[] json must be bracket-quoted: " + json);
        assertTrue(json.contains("\\\""), "double-quote must be JSON-escaped: " + json);
        assertTrue(json.contains("\\\\"), "backslash must be JSON-escaped: " + json);
    }

    // ---------- Character[] ----------

    @Test(groups = { "unit" })
    public void characterArray_quotesEachCharacter() {
        assertEquals(Utils.toJsonString(new Character[] { 'a', 'b' }),
                "[\"a\",\"b\"]");
    }

    @Test(groups = { "unit" })
    public void characterArray_nullElement_rendersAsQuotedNullToken() {
        // Per appendJsonString: when a Character[] element is null,
        // it appends the NULL_STRING token inside the surrounding quotes.
        // This produces "null" (with quotes) — a quirk the test pins.
        String json = Utils.toJsonString(new Character[] { 'a', null, 'b' });

        // Element ordering and the null token shape are pinned exactly.
        assertEquals(json, "[\"a\",\"null\",\"b\"]");
    }

    // ---------- Enumeration with multiple elements ----------

    @Test(groups = { "unit" })
    public void enumeration_multiElement_isCommaSeparated() {
        // The Enumeration branch's loop body (the comma after the first
        // element) is exercised only when there are >=2 elements.
        Vector<Integer> v = new Vector<>(Arrays.asList(1, 2, 3));

        assertEquals(Utils.toJsonString(v.elements()), "[1,2,3]");
    }

    // ---------- Iterable (List) with mixed-type elements ----------

    @Test(groups = { "unit" })
    public void iterable_listWithStrings_quotesEach() {
        // List<String> -> Iterable branch. Each element runs through
        // appendJsonString recursively, hitting the default String case.
        String json = Utils.toJsonString(Arrays.asList("a", "b"));

        assertEquals(json, "[\"a\",\"b\"]");
    }

    @Test(groups = { "unit" })
    public void iterable_listWithNull_rendersBareNull() {
        // Iterable elements pass through appendJsonString which handles
        // the null branch directly (NULL_STRING) — NOT through the
        // null-as-quoted-string branch of Character[].
        String json = Utils.toJsonString(Arrays.asList("a", null, "b"));

        assertEquals(json, "[\"a\",null,\"b\"]");
    }

    // ---------- Map ----------

    @Test(groups = { "unit" })
    public void map_multiKey_isJsonObject() {
        // Map<String,Integer> -> JSON object with quoted keys. Use
        // LinkedHashMap to pin insertion order.
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);

        assertEquals(Utils.toJsonString(m), "{\"a\":1,\"b\":2}");
    }

    @Test(groups = { "unit" })
    public void map_emptyMap_isEmptyObject() {
        assertEquals(Utils.toJsonString(Collections.emptyMap()), "{}");
    }

    @Test(groups = { "unit" })
    public void map_valueWithSpecialChars_isEscaped() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        m.put("k", "a\"b");
        assertEquals(Utils.toJsonString(m), "{\"k\":\"a\\\"b\"}");
    }

    // ---------- Default branch (String / generic Object) ----------

    @Test(groups = { "unit" })
    public void plainString_isQuotedAndEscaped() {
        // The else-branch at the bottom of appendJsonString.
        assertEquals(Utils.toJsonString("hello"), "\"hello\"");
        assertEquals(Utils.toJsonString("with\"quote"), "\"with\\\"quote\"");
        assertEquals(Utils.toJsonString("with\\slash"), "\"with\\\\slash\"");
    }
}
