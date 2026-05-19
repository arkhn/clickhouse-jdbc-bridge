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
 * the per-primitive type-matcher rows of the constructor lookup,
 * loadClass success + miss paths, the explicit-name constructor, and
 * the constructor-invocation-failure wrap.
 *
 * <p>Extension is the bridge's plugin spine — every JdbcDataSource /
 * NamedSchema / NamedQuery / converter gets instantiated through it.
 * A regression in the type-matcher would silently route the wrong
 * constructor for a Long vs Integer arg.</p>
 */
public class ExtensionPrimitiveMatchTest {

    /** Constructor-rich class so we can probe every primitive matcher. */
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

    /** Constructor that always throws, to exercise the failure-wrap. */
    static class ThrowingCtor {
        public ThrowingCtor(String why) {
            throw new IllegalStateException("boom: " + why);
        }
    }

    static class NoMatchingCtor {
        public NoMatchingCtor(String a, String b) {
            // Two-arg constructor only; newInstance() called with no args
            // or wrong count must throw UnsupportedOperationException.
        }
    }

    // ---------- per-primitive matcher rows ----------

    @Test(groups = { "unit" })
    public void newInstance_byteMatcherRoutesToByteCtor() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        // Args must be wrapped in Object[] so newInstance treats them as a
        // single positional list. Each primitive matcher routes to its
        // matching ctor — pin the routing table.
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

    // ---------- failure paths ----------

    @Test(groups = { "unit" })
    public void newInstance_throwingCtor_wrapsAsIllegalArgumentException() {
        Extension<ThrowingCtor> ext = new Extension<>(ThrowingCtor.class);

        try {
            ext.newInstance(new Object[] { "why" });
            org.testng.Assert.fail("expected IAE wrap of the ctor's ISE");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Failed to create instance"),
                    "wrap message must include the diagnostic prefix: " + e.getMessage());
            // The underlying ISE is preserved as the cause.
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IllegalStateException,
                    "cause must be the original ISE, got: " + e.getCause());
        }
    }

    @Test(groups = { "unit" })
    public void newInstance_wrongArgCount_throwsUnsupportedOperationException() {
        Extension<NoMatchingCtor> ext = new Extension<>(NoMatchingCtor.class);

        // No matching ctor for zero-arg or one-arg invocation. UOE is the
        // "your config calls an extension with the wrong shape" signal.
        assertThrows(UnsupportedOperationException.class, ext::newInstance);
        assertThrows(UnsupportedOperationException.class,
                () -> ext.newInstance(new Object[] { "only-one" }));
    }

    // ---------- explicit-name constructor ----------

    @Test(groups = { "unit" })
    public void explicitNameCtor_overridesClassSimpleName() {
        // Default name is class.getSimpleName(); the 2-arg ctor lets a
        // caller override that. Important for ExtensionManager's type
        // registry — without this override every config datasource would
        // be stuck with the auto-derived name.
        Extension<PrimitiveCtors> ext = new Extension<>("custom-name", PrimitiveCtors.class);

        assertEquals(ext.getName(), "custom-name");
        assertSame(ext.getProviderClass(), PrimitiveCtors.class);
    }

    // ---------- loadClass ----------

    @Test(groups = { "unit" })
    public void loadClass_findsKnownClass() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);

        Class<?> c = ext.loadClass("java.lang.String");

        assertSame(c, String.class,
                "loadClass must resolve well-known JDK classes via the context loader");
    }

    @Test(groups = { "unit" })
    public void loadClass_unknownClassReturnsNullNotThrows() {
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);

        // The contract is "warn and return null" — extensions can probe
        // for optional dependencies without crashing the bridge.
        Class<?> c = ext.loadClass("com.example.DefinitelyDoesNotExist");

        assertNull(c, "missing class must yield null, not throw");
    }

    // ---------- initialize() with non-newInstance-bearing class ----------

    @Test(groups = { "unit" })
    public void initialize_classWithoutInitializeMethod_isNoOp() {
        // PrimitiveCtors doesn't declare a static `initialize(ExtensionManager)`
        // method — Extension's reflection lookup finds nothing and stores
        // initMethod as null. initialize() then short-circuits without
        // throwing. (Pinned via ExtensionTest.testExtension flag flip;
        // here we pin the no-init-method path explicitly.)
        Extension<PrimitiveCtors> ext = new Extension<>(PrimitiveCtors.class);
        ext.initialize(null); // null is fine since we never call into it
    }
}
