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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.testng.annotations.Test;

/**
 * Tests for {@link Utils} — checkArgument family, configuration-resolution chain,
 * file IO helpers, small predicates.
 */
public class UtilsAdditionalTest {

    @Test(groups = { "unit" })
    public void checkArgument_byteArrayLength_acceptsAtOrUnderLimit() {
        Utils.checkArgument(new byte[3], 3);
        Utils.checkArgument(new byte[3], 4);
        Utils.checkArgument(new byte[0], 0);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void checkArgument_byteArrayLength_throwsOnExceed() {
        Utils.checkArgument(new byte[5], 3);
    }

    @Test(groups = { "unit" })
    public void checkArgument_intMin_passesAtOrAbove() {
        Utils.checkArgument(5, 5);
        Utils.checkArgument(10, 5);
        Utils.checkArgument(0, -1);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void checkArgument_intMin_throwsBelow() {
        Utils.checkArgument(2, 5);
    }

    @Test(groups = { "unit" })
    public void checkArgument_longMin_passesAtOrAbove() {
        Utils.checkArgument(5L, 5L);
        Utils.checkArgument(Long.MAX_VALUE, 0L);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void checkArgument_longMin_throwsBelow() {
        Utils.checkArgument(-1L, 0L);
    }

    @Test(groups = { "unit" })
    public void checkArgument_intRange_passesInside() {
        Utils.checkArgument(5, 0, 10);
        Utils.checkArgument(0, 0, 10);
        Utils.checkArgument(10, 0, 10);
    }

    @Test(groups = { "unit" })
    public void checkArgument_intRange_throwsOutside() {
        assertThrows(IllegalArgumentException.class, () -> Utils.checkArgument(-1, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Utils.checkArgument(11, 0, 10));
    }

    @Test(groups = { "unit" })
    public void checkArgument_longRange_throwsOutside() {
        assertThrows(IllegalArgumentException.class, () -> Utils.checkArgument(-1L, 0L, 10L));
        assertThrows(IllegalArgumentException.class, () -> Utils.checkArgument(11L, 0L, 10L));
    }

    @Test(groups = { "unit" })
    public void checkArgument_bigIntegerMin_passesAtOrAbove() {
        Utils.checkArgument(BigInteger.TEN, BigInteger.ZERO);
        Utils.checkArgument(BigInteger.ZERO, BigInteger.ZERO);
    }

    @Test(groups = { "unit" },
          expectedExceptions = IllegalArgumentException.class)
    public void checkArgument_bigIntegerMin_throwsBelow() {
        Utils.checkArgument(BigInteger.valueOf(-1), BigInteger.ZERO);
    }

    @Test(groups = { "unit" })
    public void checkArgument_bigIntegerRange_throwsOutside() {
        assertThrows(IllegalArgumentException.class,
                () -> Utils.checkArgument(BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.TEN));
        assertThrows(IllegalArgumentException.class,
                () -> Utils.checkArgument(BigInteger.valueOf(11), BigInteger.ZERO, BigInteger.TEN));
    }

    @Test(groups = { "unit" })
    public void containsWhitespace_matchesEachWhitespaceKind() {
        assertTrue(Utils.containsWhitespace("a b"));
        assertTrue(Utils.containsWhitespace("a\tb"));
        assertTrue(Utils.containsWhitespace("a\nb"));
        assertTrue(Utils.containsWhitespace(" leading"));
        assertTrue(Utils.containsWhitespace("trailing "));
    }

    @Test(groups = { "unit" })
    public void containsWhitespace_rejectsNullEmptyAndSolidStrings() {
        assertFalse(Utils.containsWhitespace(null));
        assertFalse(Utils.containsWhitespace(""));
        assertFalse(Utils.containsWhitespace("solidstring"));
        assertFalse(Utils.containsWhitespace("12345"));
    }

    @Test(groups = { "unit" })
    public void getValueOrEmptyString_nullBecomesEmpty() {
        assertEquals(Utils.getValueOrEmptyString(null), Utils.EMPTY_STRING);
    }

    @Test(groups = { "unit" })
    public void getValueOrEmptyString_passesThroughNonNull() {
        assertEquals(Utils.getValueOrEmptyString("hello"), "hello");
        assertEquals(Utils.getValueOrEmptyString(""), "");
    }

    @Test(groups = { "unit" })
    public void getConfiguration_systemPropertyTakesPrecedence() {
        String key = "utils-additional-test." + UUID.randomUUID();
        System.setProperty(key, "from-sysprop");
        try {
            String result = Utils.getConfiguration("default-fallback", "DEFINITELY_NOT_SET_" + UUID.randomUUID(), key);
            assertEquals(result, "from-sysprop");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test(groups = { "unit" })
    public void getConfiguration_fallsBackToDefaultWhenNothingSet() {
        String result = Utils.getConfiguration("the-default", "UTILS_ADD_NOT_SET_" + UUID.randomUUID(),
                "utils.additional.not.set." + UUID.randomUUID());
        assertEquals(result, "the-default");
    }

    @Test(groups = { "unit" })
    public void getConfiguration_nullSystemPropertyArgIsTolerated() {
        String result = Utils.getConfiguration("fallback", "UTILS_ADD_NOT_SET_" + UUID.randomUUID(), null);
        assertEquals(result, "fallback");
    }

    @Test(groups = { "unit" })
    public void getConfiguration_nullDefaultYieldsEmptyStringWhenNothingMatches() {
        // getConfiguration must never return null — returns EMPTY_STRING sentinel.
        String result = Utils.getConfiguration(null, "UTILS_ADD_NOT_SET_" + UUID.randomUUID(),
                "utils.additional.not.set." + UUID.randomUUID());
        assertEquals(result, Utils.EMPTY_STRING);
    }

    @Test(groups = { "unit" })
    public void fileExists_returnsFalseForMissingPathsAndNull() {
        assertFalse(Utils.fileExists("/nonexistent/path/" + UUID.randomUUID() + ".txt"));
        // null path caught in try/catch -> false.
        assertFalse(Utils.fileExists(null));
    }

    @Test(groups = { "unit" })
    public void fileExists_returnsTrueForRealFile() throws Exception {
        Path tmp = Files.createTempFile("utils-add-", ".txt");
        try {
            assertTrue(Utils.fileExists(tmp.toString()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test(groups = { "unit" })
    public void loadTextFromFile_readsBackTheBytesWeWrote() throws Exception {
        Path tmp = Files.createTempFile("utils-add-text-", ".txt");
        try {
            Files.writeString(tmp, "line one\nline two\n");
            String loaded = Utils.loadTextFromFile(tmp.toString());

            assertTrue(loaded.contains("line one"),
                    "expected loaded text to contain 'line one', got: " + loaded);
            assertTrue(loaded.contains("line two"), "got: " + loaded);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test(groups = { "unit" })
    public void addTypedParameter_registersByName() {
        Map<String, TypedParameter<?>> reg = new HashMap<>();
        TypedParameter<Integer> p = new TypedParameter<>(Integer.class, "max_rows", 0);

        Utils.addTypedParameter(reg, p);

        assertEquals(reg.size(), 1);
        assertEquals(reg.get("max_rows"), p);
    }

    @Test(groups = { "unit" })
    public void splitByChar_nonTokenizingPreservesEmptyAndWhitespaceFields() {
        // Non-tokenizing overload preserves empty fields (CSV column-index stability).
        java.util.List<String> tokens = Utils.splitByChar("a,,c, ", ',', false);
        assertEquals(tokens.size(), 4);
        assertEquals(tokens.get(0), "a");
        assertEquals(tokens.get(1), "");
        assertEquals(tokens.get(2), "c");
        assertEquals(tokens.get(3), " ");
    }

    @Test(groups = { "unit" })
    public void splitByChar_nullStringYieldsEmptyList() {
        assertTrue(Utils.splitByChar(null, ',').isEmpty());
    }

    @Test(groups = { "unit" })
    public void digest_nullAndEmptyStringYieldEmpty() {
        assertEquals(Utils.digest((String) null), Utils.EMPTY_STRING);
        assertEquals(Utils.digest(""), Utils.EMPTY_STRING);
    }

    @Test(groups = { "unit" })
    public void digest_isStableAcrossCalls() {
        String d1 = Utils.digest("the same content");
        String d2 = Utils.digest("the same content");
        assertEquals(d1, d2, "digest must be deterministic");
        assertFalse(d1.isEmpty(), "non-empty input must produce non-empty digest");
    }
}
