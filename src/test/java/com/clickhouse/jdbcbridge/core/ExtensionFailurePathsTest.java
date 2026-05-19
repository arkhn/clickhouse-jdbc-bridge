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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

/**
 * {@link Extension} reflective-failure paths: static newInstance throws -> ISE wrap;
 * static initialize throws -> swallowed.
 */
public class ExtensionFailurePathsTest {

    public static class ThrowingNewInstance {
        public static ThrowingNewInstance newInstance(Object... args) {
            throw new RuntimeException("intentional new-instance failure");
        }
    }

    public static class ThrowingInitialize {
        public static void initialize(ExtensionManager manager) {
            throw new RuntimeException("intentional init failure");
        }
    }

    @Test(groups = { "unit" })
    public void newInstance_staticMethodThrows_isWrappedInIllegalStateException() {
        // Distinct from ThrowingCtor (IAE wrap): static factory throw -> ISE wrap.
        Extension<ThrowingNewInstance> ext = new Extension<>(ThrowingNewInstance.class);
        try {
            ext.newInstance("arg");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getCause());
            assertTrue(expected.getMessage().contains(ThrowingNewInstance.class.getName()),
                    "ISE message must name the failing class: " + expected.getMessage());
        }
    }

    @Test(groups = { "unit" })
    public void initialize_staticMethodThrows_isSwallowed() {
        // Initialize failures are logged not re-thrown (best-effort during boot).
        new Extension<>(ThrowingInitialize.class).initialize(null);
    }
}
