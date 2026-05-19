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

import java.math.BigDecimal;
import java.math.BigInteger;

import org.testng.annotations.Test;

/**
 * Constructor tests for {@link TypedParameter} — the supported-types
 * guard, the chType auto-derivation matrix, and the null-arg null-checks.
 * Existing TypedParameterTest covers the basic per-type happy paths via
 * merge logic; this file targets the constructor's validation surface.
 */
public class TypedParameterCtorTest {

    // ---------- supported-type guard ----------

    @Test(groups = { "unit" })
    public void ctor_unsupportedType_throwsIAE() {
        // Only primitives + String + BigInteger + BigDecimal are accepted.
        // Anything else (here: java.util.Date) trips the guard.
        assertThrows(IllegalArgumentException.class,
                () -> new TypedParameter<>(java.util.Date.class, "ts", new java.util.Date()));
        // java.net.URI: also unsupported.
        assertThrows(IllegalArgumentException.class,
                () -> new TypedParameter<>(java.net.URI.class, "u", java.net.URI.create("x:y")));
    }

    @Test(groups = { "unit" })
    public void ctor_acceptsAllListedTypes() {
        // Sanity-pin every supported type so a future tightening of the
        // guard (dropping one) trips this test.
        new TypedParameter<>(String.class, "s", "");
        new TypedParameter<>(Boolean.class, "b", false);
        new TypedParameter<>(Byte.class, "by", (byte) 0);
        new TypedParameter<>(Character.class, "c", 'a');
        new TypedParameter<>(Short.class, "sh", (short) 0);
        new TypedParameter<>(Integer.class, "i", 0);
        new TypedParameter<>(Long.class, "l", 0L);
        new TypedParameter<>(Float.class, "f", 0.0f);
        new TypedParameter<>(Double.class, "d", 0.0);
        new TypedParameter<>(BigInteger.class, "bi", BigInteger.ZERO);
        new TypedParameter<>(BigDecimal.class, "bd", BigDecimal.ZERO);
    }

    // ---------- chType auto-derivation when null ----------

    @Test(groups = { "unit" })
    public void ctor_chTypeNull_autoDerivesFromDefaultValue() {
        // Walk every branch of the chType auto-derivation:
        // - BigDecimal -> Decimal
        // - Float -> Float32
        // - Double -> Float64
        // - Long -> UInt64
        // - other Number (Byte/Short/Integer) -> Int32
        // - fallback (String etc.) -> Str
        //
        // The chType field is read by writeValueTo; here we just construct
        // and trust the test won't throw. There's no public getter for
        // chType, but TypedParameter#writeValueTo's dispatch matrix is
        // pinned by TypedParameterMergeTest.
        new TypedParameter<>(BigDecimal.class, "bd", BigDecimal.ONE);
        new TypedParameter<>(Float.class, "f", 1.0f);
        new TypedParameter<>(Double.class, "d", 1.0);
        new TypedParameter<>(Long.class, "l", 1L);
        new TypedParameter<>(Integer.class, "i", 1);
        new TypedParameter<>(Byte.class, "b", (byte) 1);
        new TypedParameter<>(Short.class, "sh", (short) 1);
        new TypedParameter<>(String.class, "s", "x");
    }

    // ---------- null-arg null-checks ----------

    @Test(groups = { "unit" })
    public void ctor_nullName_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(Integer.class, null, 0));
    }

    @Test(groups = { "unit" })
    public void ctor_nullDefaultValue_throwsNPE() {
        // defaultValue is Objects.requireNonNull — drives both the
        // chType auto-derivation AND becomes the initial value.
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(Integer.class, "n", null));
    }

    @Test(groups = { "unit" })
    public void ctor_nullType_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(null, "n", 0));
    }

    // ---------- value differs from defaultValue ----------

    @Test(groups = { "unit" })
    public void ctor_valueDifferentFromDefault_isHonored() {
        // The 4-arg ctor lets caller pass a different initial value than
        // the default. defaultValue is fixed at construction; value is
        // mutable via merge*.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 42);

        assertEquals(p.getDefaultValue(), Integer.valueOf(0));
        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    // ---------- defaultValue immutability across merge ----------

    @Test(groups = { "unit" })
    public void merge_doesNotMutateDefaultValue() {
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 0);

        p.merge("99");

        assertEquals(p.getValue(), Integer.valueOf(99));
        // defaultValue stays at its construction value — merge only
        // touches `value`. Pin so a refactor doesn't accidentally clobber.
        assertEquals(p.getDefaultValue(), Integer.valueOf(0));
    }
}
