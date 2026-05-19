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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link ColumnDefinition#fromJson} option-parsing branches
 * — the Enum8/Enum16 declarative-options input can arrive as JsonArray
 * (objects or strings), JsonObject (name→index map), Java Map,
 * Enumeration, Iterable, Object[] array, or a CSV-style string. All
 * are accepted by parseOptions; existing tests only cover a couple.
 *
 * <p>This is the path that gets hit every time the bridge sees an
 * Enum-typed column in a request — quietly part of the read-intensive
 * code path.</p>
 */
public class ColumnDefinitionParseOptionsTest {

    /** Build a minimal Enum8 column whose options field comes from the
     *  passed value (whatever shape parseOptions accepts). */
    private static ColumnDefinition enumCol(Object options) {
        JsonObject json = new JsonObject().put("name", "status").put("type", "Enum8");
        if (options instanceof JsonObject) {
            json.put("options", (JsonObject) options);
        } else if (options instanceof JsonArray) {
            json.put("options", (JsonArray) options);
        } else if (options != null) {
            // Vert.x JsonObject.put for plain Object goes through its
            // Json checker; we bypass by using a side channel — pass the
            // raw options through ColumnDefinition.fromObject with a Map.
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", "status");
            m.put("type", "Enum8");
            m.put("options", options);
            return ColumnDefinition.fromObject(m);
        }
        return ColumnDefinition.fromJson(json);
    }

    // ---------- JsonObject options ----------

    @Test(groups = { "unit" })
    public void parseOptions_jsonObject_numberValues() {
        JsonObject opts = new JsonObject().put("A", 1).put("B", 2).put("C", 3);
        ColumnDefinition c = enumCol(opts);

        Map<String, Integer> options = c.getOptions();
        assertEquals(options.get("A"), Integer.valueOf(1));
        assertEquals(options.get("B"), Integer.valueOf(2));
        assertEquals(options.get("C"), Integer.valueOf(3));
    }

    @Test(groups = { "unit" })
    public void parseOptions_jsonObject_stringValues_parsedAsInt() {
        // The number-or-string branch: Integer.parseInt fallback.
        JsonObject opts = new JsonObject().put("A", "10").put("B", "20");
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().get("A"), Integer.valueOf(10));
        assertEquals(c.getOptions().get("B"), Integer.valueOf(20));
    }

    @Test(groups = { "unit" })
    public void parseOptions_jsonObject_nullValueSkipped() {
        JsonObject opts = new JsonObject().put("A", 1).putNull("B").put("C", 3);
        ColumnDefinition c = enumCol(opts);

        assertTrue(c.getOptions().containsKey("A"));
        assertTrue(c.getOptions().containsKey("C"));
        assertEquals(c.getOptions().size(), 2,
                "null-valued entries must be skipped");
    }

    // ---------- JsonArray options ----------

    @Test(groups = { "unit" })
    public void parseOptions_jsonArray_objectsWithNameAndValue() {
        JsonArray opts = new JsonArray()
                .add(new JsonObject().put("name", "A").put("value", 1))
                .add(new JsonObject().put("name", "B").put("value", 2));
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().get("A"), Integer.valueOf(1));
        assertEquals(c.getOptions().get("B"), Integer.valueOf(2));
    }

    @Test(groups = { "unit" })
    public void parseOptions_jsonArray_objectsMissingNameOrValueSkipped() {
        JsonArray opts = new JsonArray()
                .add(new JsonObject().put("name", "A").put("value", 10))
                // missing value
                .add(new JsonObject().put("name", "B"))
                // missing name
                .add(new JsonObject().put("value", 20))
                .add(new JsonObject().put("name", "C").put("value", 30));
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().size(), 2,
                "entries missing name or value must be silently dropped");
        assertTrue(c.getOptions().containsKey("A"));
        assertTrue(c.getOptions().containsKey("C"));
    }

    @Test(groups = { "unit" })
    public void parseOptions_jsonArray_bareStrings_useIndexAsValue() {
        // Strings in the array are added with an incrementing index value.
        JsonArray opts = new JsonArray().add("X").add("Y").add("Z");
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().get("X"), Integer.valueOf(0));
        assertEquals(c.getOptions().get("Y"), Integer.valueOf(1));
        assertEquals(c.getOptions().get("Z"), Integer.valueOf(2));
    }

    @Test(groups = { "unit" })
    public void parseOptions_jsonArray_nullsSkipped() {
        JsonArray opts = new JsonArray().add("A").addNull().add("B");
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().size(), 2);
    }

    // ---------- Map options (via fromObject Map path) ----------

    @Test(groups = { "unit" })
    public void parseOptions_javaMap_keyValueParsed() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("A", 1);
        opts.put("B", "20");
        opts.put("C", 30);
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().get("A"), Integer.valueOf(1));
        assertEquals(c.getOptions().get("B"), Integer.valueOf(20));
        assertEquals(c.getOptions().get("C"), Integer.valueOf(30));
    }

    @Test(groups = { "unit" })
    public void parseOptions_javaMap_nullKeyOrValueSkipped() {
        Map<Object, Object> opts = new HashMap<>();
        opts.put("A", 1);
        opts.put(null, 99); // null key
        opts.put("C", null); // null value
        opts.put("D", 4);
        ColumnDefinition c = enumCol(opts);

        assertEquals(c.getOptions().size(), 2,
                "null key or value -> entry skipped");
    }

    // ---------- Enumeration / Iterable / Object[] ----------

    @Test(groups = { "unit" })
    public void parseOptions_enumeration_indexed() {
        Vector<String> v = new Vector<>(Arrays.asList("A", "B", "C"));
        ColumnDefinition c = enumCol(v.elements()); // Enumeration<String>

        assertEquals(c.getOptions().get("A"), Integer.valueOf(0));
        assertEquals(c.getOptions().get("B"), Integer.valueOf(1));
        assertEquals(c.getOptions().get("C"), Integer.valueOf(2));
    }

    @Test(groups = { "unit" })
    public void parseOptions_iterable_indexed() {
        ColumnDefinition c = enumCol(Arrays.asList("X", "Y"));

        assertEquals(c.getOptions().get("X"), Integer.valueOf(0));
        assertEquals(c.getOptions().get("Y"), Integer.valueOf(1));
    }

    @Test(groups = { "unit" })
    public void parseOptions_objectArray_indexed() {
        ColumnDefinition c = enumCol(new Object[] { "A", "B", "C" });

        assertEquals(c.getOptions().size(), 3);
        assertEquals(c.getOptions().get("C"), Integer.valueOf(2));
    }

    // ---------- String fallback (CSV-style) ----------

    @Test(groups = { "unit" })
    public void parseOptions_csvString_indexed() {
        // The default branch: no quote in input -> split by comma, each
        // token gets the next sequential index. This is the path that
        // hits for inline schema strings like "Enum8('A','B','C')".
        ColumnDefinition c = enumCol("A,B,C");

        assertEquals(c.getOptions().get("A"), Integer.valueOf(0));
        assertEquals(c.getOptions().get("B"), Integer.valueOf(1));
        assertEquals(c.getOptions().get("C"), Integer.valueOf(2));
    }

    @Test(groups = { "unit" })
    public void parseOptions_emptyJsonObject_isAccepted() {
        ColumnDefinition c = enumCol(new JsonObject());
        assertEquals(c.getOptions().size(), 0);
    }

    @Test(groups = { "unit" })
    public void parseOptions_emptyIterable_isAccepted() {
        ColumnDefinition c = enumCol(Collections.emptyList());
        assertEquals(c.getOptions().size(), 0);
    }
}
