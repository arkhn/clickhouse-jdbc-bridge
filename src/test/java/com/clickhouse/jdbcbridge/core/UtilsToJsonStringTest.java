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
 * Tests for {@link Utils#toJsonString} branches: char[]/Character[] quoting,
 * Enumeration, Map, and JSON-string escape.
 */
public class UtilsToJsonStringTest {

    @Test(groups = { "unit" })
    public void charArray_quotesEachCharacter() {
        // char[] -> JSON array of quoted single chars (not single string).
        assertEquals(Utils.toJsonString(new char[] { 'a', 'b', 'c' }),
                "[\"a\",\"b\",\"c\"]");
    }

    @Test(groups = { "unit" })
    public void charArray_singleElement_noLeadingComma() {
        assertEquals(Utils.toJsonString(new char[] { 'x' }), "[\"x\"]");
    }

    @Test(groups = { "unit" })
    public void charArray_specialChar_isJsonEscaped() {
        // \ and " round-trip through JsonStringEncoder.
        String json = Utils.toJsonString(new char[] { '"', '\\' });
        assertTrue(json.startsWith("[\"") && json.endsWith("\"]"),
                "char[] json must be bracket-quoted: " + json);
        assertTrue(json.contains("\\\""), "double-quote must be JSON-escaped: " + json);
        assertTrue(json.contains("\\\\"), "backslash must be JSON-escaped: " + json);
    }

    @Test(groups = { "unit" })
    public void characterArray_quotesEachCharacter() {
        assertEquals(Utils.toJsonString(new Character[] { 'a', 'b' }),
                "[\"a\",\"b\"]");
    }

    @Test(groups = { "unit" })
    public void characterArray_nullElement_rendersAsQuotedNullToken() {
        // Quirk: null Character[] element appends NULL_STRING inside surrounding quotes -> "null".
        String json = Utils.toJsonString(new Character[] { 'a', null, 'b' });

        assertEquals(json, "[\"a\",\"null\",\"b\"]");
    }

    @Test(groups = { "unit" })
    public void enumeration_multiElement_isCommaSeparated() {
        Vector<Integer> v = new Vector<>(Arrays.asList(1, 2, 3));

        assertEquals(Utils.toJsonString(v.elements()), "[1,2,3]");
    }

    @Test(groups = { "unit" })
    public void iterable_listWithStrings_quotesEach() {
        String json = Utils.toJsonString(Arrays.asList("a", "b"));

        assertEquals(json, "[\"a\",\"b\"]");
    }

    @Test(groups = { "unit" })
    public void iterable_listWithNull_rendersBareNull() {
        // Iterable null -> bare null (NOT quoted, distinct from Character[] null).
        String json = Utils.toJsonString(Arrays.asList("a", null, "b"));

        assertEquals(json, "[\"a\",null,\"b\"]");
    }

    @Test(groups = { "unit" })
    public void map_multiKey_isJsonObject() {
        // LinkedHashMap pins insertion order.
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

    @Test(groups = { "unit" })
    public void plainString_isQuotedAndEscaped() {
        assertEquals(Utils.toJsonString("hello"), "\"hello\"");
        assertEquals(Utils.toJsonString("with\"quote"), "\"with\\\"quote\"");
        assertEquals(Utils.toJsonString("with\\slash"), "\"with\\\\slash\"");
    }
}
