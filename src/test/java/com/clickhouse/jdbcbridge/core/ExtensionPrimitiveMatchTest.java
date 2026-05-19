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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * Tests for {@link Extension} branches the existing ExtensionTest skips:
 * per-primitive type-matcher rows, loadClass success/miss, explicit-name
 * constructor, constructor-invocation-failure wrap.
 */
public class ExtensionPrimitiveMatchTest {

    static class PrimitiveCtors {
        final String tag;

        public PrimitiveCtors(byte b) { tag = "byte:" + b; }
        public PrimitiveCtors(short s) { tag = "short:" + s; }
        public PrimitiveCtors(int i) { tag = "int:" + i; }
        public PrimitiveCtors(long l) { tag = "long:" + l; }
        public PrimitiveCtors(float f) { tag = "float:" + f; }
        public PrimitiveCtors(double d) { tag = "double:" + d; }
        public PrimitiveCtors(boolean v) { tag = "boolean:" + v; }
        public PrimitiveCtors(char c) { tag = "char:" + c; }
    }

    static class ThrowingCtor {
        public ThrowingCtor(String why) {
            throw new IllegalStateException("boom: " + why);
        }
    }

    static class NoMatchingCtor {
        public NoMatchingCtor(String a, String b) {
        }
    }

    @Test(groups = { "unit" })
    public void newInstance_byteMatcherRoutesToByteCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { (byte) 5 });
        assertEquals(r.tag, "byte:5");
    }

    @Test(groups = { "unit" })
    public void newInstance_shortMatcherRoutesToShortCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { (short) 7 });
        assertEquals(r.tag, "short:7");
    }

    @Test(groups = { "unit" })
    public void newInstance_intMatcherRoutesToIntCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { 9 });
        assertEquals(r.tag, "int:9");
    }

    @Test(groups = { "unit" })
    public void newInstance_longMatcherRoutesToLongCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { 11L });
        assertEquals(r.tag, "long:11");
    }

    @Test(groups = { "unit" })
    public void newInstance_floatMatcherRoutesToFloatCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { 1.5f });
        assertEquals(r.tag, "float:1.5");
    }

    @Test(groups = { "unit" })
    public void newInstance_doubleMatcherRoutesToDoubleCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { 2.5d });
        assertEquals(r.tag, "double:2.5");
    }

    @Test(groups = { "unit" })
    public void newInstance_booleanMatcherRoutesToBooleanCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { true });
        assertEquals(r.tag, "boolean:true");
    }

    @Test(groups = { "unit" })
    public void newInstance_charMatcherRoutesToCharCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        PrimitiveCtors r = ext.newInstance(new Object[] { 'X' });
        assertEquals(r.tag, "char:X");
    }

    @Test(groups = { "unit" })
    public void newInstance_throwingCtor_wrapsAsIllegalArgumentException() {
        Extension<ThrowingCtor> ext = new Extension<>(ThrowingCtor.class);

        try {
            ext.newInstance(new Object[] { "why" });
            org.testng.Assert.fail("expected IAE wrap of the ctor's ISE");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Failed to create instance"),
                    "wrap message must include the diagnostic prefix: " + e.getMessage());
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IllegalStateException,
                    "cause must be the original ISE, got: " + e.getCause());
        }
    }

    @Test(groups = { "unit" })
    public void newInstance_wrongArgCount_throwsUnsupportedOperationException() {
        // UOE is the "config calls extension with wrong shape" signal.
        Extension<NoMatchingCtor> ext = new Extension<>(NoMatchingCtor.class);

        assertThrows(UnsupportedOperationException.class, ext::newInstance);
        assertThrows(UnsupportedOperationException.class,
                () -> ext.newInstance(new Object[] { "only-one" }));
    }

    @Test(groups = { "unit" })
    public void explicitNameCtor_overridesClassSimpleName() {
        // 2-arg ctor overrides class.getSimpleName() default — needed for ExtensionManager's type registry.
        Extension<PrimitiveCtors> ext = new Extension<>("custom-name", PrimitiveCtors.class);

        assertEquals(ext.getName(), "custom-name");
        assertSame(ext.getProviderClass(), PrimitiveCtors.class);
    }

    @Test(groups = { "unit" })
    public void loadClass_findsKnownClass() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);

        Class<?> c = ext.loadClass("java.lang.String");

        assertSame(c, String.class,
                "loadClass must resolve well-known JDK classes via the context loader");
    }

    @Test(groups = { "unit" })
    public void loadClass_unknownClassReturnsNullNotThrows() {
        // Contract: warn and return null — extensions can probe optional deps without crashing.
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);

        Class<?> c = ext.loadClass("com.example.DefinitelyDoesNotExist");

        assertNull(c, "missing class must yield null, not throw");
    }

    @Test(groups = { "unit" })
    public void initialize_classWithoutInitializeMethod_isNoOp() {
        // No static initialize() method -> initMethod is null -> short-circuit without throwing.
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        ext.initialize(null);
    }
}
