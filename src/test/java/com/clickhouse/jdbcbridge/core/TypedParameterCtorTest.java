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
 * Constructor tests for {@link TypedParameter} — supported-types guard,
 * chType auto-derivation matrix, null-arg null-checks.
 */
public class TypedParameterCtorTest {

    @Test(groups = { "unit" })
    public void ctor_unsupportedType_throwsIAE() {
        // Only primitives + String + BigInteger + BigDecimal accepted.
        assertThrows(IllegalArgumentException.class,
                () -> new TypedParameter<>(java.util.Date.class, "ts", new java.util.Date()));
        assertThrows(IllegalArgumentException.class,
                () -> new TypedParameter<>(java.net.URI.class, "u", java.net.URI.create("x:y")));
    }

    @Test(groups = { "unit" })
    public void ctor_acceptsAllListedTypes() {
        // Sanity-pin every supported type.
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

    @Test(groups = { "unit" })
    public void ctor_chTypeNull_autoDerivesFromDefaultValue() {
        // Walk chType auto-derivation: BigDecimal->Decimal, Float->Float32, Double->Float64,
        // Long->UInt64, other Number->Int32, fallback->Str.
        new TypedParameter<>(BigDecimal.class, "bd", BigDecimal.ONE);
        new TypedParameter<>(Float.class, "f", 1.0f);
        new TypedParameter<>(Double.class, "d", 1.0);
        new TypedParameter<>(Long.class, "l", 1L);
        new TypedParameter<>(Integer.class, "i", 1);
        new TypedParameter<>(Byte.class, "b", (byte) 1);
        new TypedParameter<>(Short.class, "sh", (short) 1);
        new TypedParameter<>(String.class, "s", "x");
    }

    @Test(groups = { "unit" })
    public void ctor_nullName_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(Integer.class, null, 0));
    }

    @Test(groups = { "unit" })
    public void ctor_nullDefaultValue_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(Integer.class, "n", null));
    }

    @Test(groups = { "unit" })
    public void ctor_nullType_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> new TypedParameter<>(null, "n", 0));
    }

    @Test(groups = { "unit" })
    public void ctor_valueDifferentFromDefault_isHonored() {
        // 4-arg ctor: defaultValue fixed at construction; value mutable via merge.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 42);

        assertEquals(p.getDefaultValue(), Integer.valueOf(0));
        assertEquals(p.getValue(), Integer.valueOf(42));
    }

    @Test(groups = { "unit" })
    public void merge_doesNotMutateDefaultValue() {
        // merge only touches `value`; pin so a refactor doesn't clobber defaultValue.
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "n", 0, 0);

        p.merge("99");

        assertEquals(p.getValue(), Integer.valueOf(99));
        assertEquals(p.getDefaultValue(), Integer.valueOf(0));
    }
}
