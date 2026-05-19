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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import io.vertx.core.json.JsonObject;
import org.testng.annotations.Test;

/**
 * Tests for {@link TypedParameter#equals} / {@link TypedParameter#hashCode},
 * the {@code (type, chType, name, defaultValue)} 4-arg constructor, and
 * the {@code merge(JsonObject, name=null)} fallback. The equals/hashCode
 * contract isn't exercised today; the parameter map relies on default
 * Object identity for safety, but the methods exist and need pinning.
 */
public class TypedParameterEqualsTest {

    // ---------- equals/hashCode contract ----------

    @Test(groups = { "unit" })
    public void equals_sameInstance_isTrue() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0);

        assertTrue(p.equals(p), "equals must be reflexive");
        // hashCode is stable across calls.
        assertEquals(p.hashCode(), p.hashCode());
    }

    @Test(groups = { "unit" })
    public void equals_null_returnsFalse() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0);

        assertFalse(p.equals(null));
    }

    @Test(groups = { "unit" })
    public void equals_differentClass_returnsFalse() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0);

        assertFalse(p.equals("not a typed param"));
    }

    @Test(groups = { "unit" })
    public void equals_sameFields_returnsTrue() {
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, "n", 0);

        assertTrue(p1.equals(p2), "two parameters with identical fields must be equal");
        assertTrue(p2.equals(p1), "equals must be symmetric");
        assertEquals(p1.hashCode(), p2.hashCode(),
                "equal objects must have equal hashCodes");
    }

    @Test(groups = { "unit" })
    public void equals_differentValue_returnsFalse() {
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0, 1);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, "n", 0, 2);

        assertNotEquals(p1, p2);
    }

    @Test(groups = { "unit" })
    public void equals_differentName_returnsFalse() {
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, "m", 0);

        assertNotEquals(p1, p2);
    }

    @Test(groups = { "unit" })
    public void equals_differentType_returnsFalse() {
        // Distinct generic types -> different `type` field.
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0);
        TypedParameter<String> p2 = new TypedParameter<>(String.class, "n", "0");

        assertNotEquals(p1, p2);
    }

    @Test(groups = { "unit" })
    public void equals_differentChType_returnsFalse() {
        // chType is auto-derived from defaultValue but can be overridden
        // via the 4-arg ctor. Same defaults, different chType -> not equal.
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, DataType.Int8, "n", 0);

        assertNotEquals(p1, p2);
    }

    @Test(groups = { "unit" })
    public void equals_differentDefaultValue_returnsFalse() {
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, "n", 0, 5);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, "n", 1, 5);

        assertNotEquals(p1, p2);
    }

    // ---------- 4-arg ctor (type, chType, name, defaultValue) ----------

    @Test(groups = { "unit" })
    public void ctor4_typeChTypeNameDefault_passesChTypeThrough() {
        // The 4-arg ctor lets caller pin chType explicitly rather than
        // auto-deriving from defaultValue. Pin by constructing with a
        // non-default chType and verifying via equals against an explicit
        // 5-arg construction.
        TypedParameter<Integer> p1 = new TypedParameter<>(Integer.class, DataType.Int8, "n", 0);
        TypedParameter<Integer> p2 = new TypedParameter<>(Integer.class, DataType.Int8, "n", 0, 0);

        assertEquals(p1, p2);
    }

    // ---------- merge(JsonObject, null name) ----------

    @Test(groups = { "unit" })
    public void mergeJson_nullName_fallsBackToParamName() {
        // merge(JsonObject, name) accepts a null name argument and
        // falls back to this.name — exercising the null branch at the
        // top of the method.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0);

        p.merge(new JsonObject().put("n", 42), null);

        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void mergeJson_nullObject_isNoOp() {
        // merge(null, anyName) early-returns the same parameter unchanged.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 7);

        p.merge((JsonObject) null, "n");

        assertEquals(p.getValue(), Integer.valueOf(7));
    }
}
