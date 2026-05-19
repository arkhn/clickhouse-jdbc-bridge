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

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

/**
 * Tests for {@link Extension} failure paths — the catch blocks that
 * normalize reflective failures into IllegalStateException /
 * IllegalArgumentException. These run during plugin loading and during
 * adhoc datasource construction, so the conversion contract matters:
 * the caller sees a consistent exception type with the underlying root
 * cause preserved.
 */
public class ExtensionFailurePathsTest {

    /**
     * Extension class whose static {@code newInstance(Object[])} method
     * throws — exercises the catch around {@code newMethod.invoke}.
     */
    public static class ThrowingNewInstance {
        public static ThrowingNewInstance newInstance(Object... args) {
            throw new RuntimeException("intentional new-instance failure");
        }
    }

    /**
     * Extension class with no {@code newInstance(Object[])} static method
     * at all — exercises the constructor's silent catch (newMethod stays
     * null) and the fallback to constructor-based instantiation.
     */
    public static class NoStaticNewInstance {
        public NoStaticNewInstance(String name) {
            // ok
        }
    }

    /**
     * Extension class whose constructor throws — exercises the catch
     * block in newInstance's reflective constructor-invocation branch.
     */
    public static class ThrowingConstructor {
        public ThrowingConstructor(String name) {
            throw new RuntimeException("intentional ctor failure");
        }
    }

    /**
     * Extension class whose static {@code initialize(ExtensionManager)}
     * throws — exercises the catch in {@link Extension#initialize}.
     */
    public static class ThrowingInitialize {
        public static void initialize(ExtensionManager manager) {
            throw new RuntimeException("intentional init failure");
        }
    }

    // ---------- newInstance: static method throws ----------

    @Test(groups = { "unit" })
    public void newInstance_staticMethodThrows_isWrappedInIllegalStateException() {
        Extension<ThrowingNewInstance> ext = new Extension<>(ThrowingNewInstance.class);

        try {
            ext.newInstance("arg");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Wrapped error must preserve the root cause so operators can
            // see WHY the extension failed (the printed stderr dump is
            // diagnostic-only; this field is the contract).
            assertNotNull(expected.getCause());
            assertTrue(expected.getMessage().contains(ThrowingNewInstance.class.getName()),
                    "IllegalStateException message must name the failing class: " + expected.getMessage());
        }
    }

    // ---------- newInstance: constructor throws ----------

    @Test(groups = { "unit" })
    public void newInstance_constructorThrows_isWrappedInIllegalArgumentException() {
        // Without a static newInstance method, Extension falls back to
        // reflective constructor invocation. When the ctor throws,
        // Extension wraps it in IllegalArgumentException (not ISE — the
        // two reflective paths have different wrapping types).
        Extension<ThrowingConstructor> ext = new Extension<>(ThrowingConstructor.class);

        try {
            ext.newInstance("arg");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getCause());
            assertTrue(expected.getMessage().contains(ThrowingConstructor.class.getName()),
                    "IAE message must name the failing class: " + expected.getMessage());
        }
    }

    // ---------- initialize: static method throws ----------

    @Test(groups = { "unit" })
    public void initialize_staticMethodThrows_isSwallowed() {
        // Per Extension.initialize: failures are LOGGED but not re-thrown
        // (extensions are best-effort during boot). Pin so a future
        // tightening that re-throws shows up as a test break.
        Extension<ThrowingInitialize> ext = new Extension<>(ThrowingInitialize.class);

        // Must not throw.
        ext.initialize(null);
    }

    // ---------- loadClass: missing class ----------

    @Test(groups = { "unit" })
    public void loadClass_missingClass_returnsNullAndLogs() {
        // loadClass catches ClassNotFoundException -> returns null.
        // The caller (BaseRepository.createFromConfig etc) checks for
        // null. Pin so a refactor doesn't switch this to throw.
        Extension<NoStaticNewInstance> ext = new Extension<>(NoStaticNewInstance.class);

        assertNull(ext.loadClass("com.example.this.class.definitely.does.not.exist.Foo"));
    }

    @Test(groups = { "unit" })
    public void loadClass_validClass_returnsClassObject() {
        // Sanity-pin the happy path — loadClass returns the requested
        // Class for a known name. Without this, a refactor that breaks
        // the success branch could hide behind the null-on-miss assertion
        // above.
        Extension<NoStaticNewInstance> ext = new Extension<>(NoStaticNewInstance.class);

        Class<?> got = ext.loadClass("java.lang.String");
        assertNotNull(got);
        org.testng.Assert.assertEquals(got, String.class);
    }
}
