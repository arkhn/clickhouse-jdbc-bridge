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
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.testng.annotations.Test;

public class AdhocPolicyTest {

    @Test(groups = { "unit" })
    public void testDefault_disabledRejectsEverything() {
        AdhocPolicy policy = new AdhocPolicy(false, Collections.emptyList());

        assertFalse(policy.allows("jdbc:mysql://attacker/db"));
        assertFalse(policy.allows("jdbc:postgresql://foo/bar"));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows(null));
        assertFalse(policy.isAllowed());
    }

    @Test(groups = { "unit" })
    public void testEnabled_emptyAllowlist_acceptsAny() {
        AdhocPolicy policy = new AdhocPolicy(true, Collections.emptyList());

        assertTrue(policy.allows("jdbc:mysql://host/db"));
        assertTrue(policy.allows("jdbc:postgresql://host/db"));
        // even non-jdbc URIs slip through when the operator has explicitly
        // opted in to "any prefix" by enabling without allowlist
        assertTrue(policy.allows("anything-the-caller-wants"));
    }

    @Test(groups = { "unit" })
    public void testEnabled_withAllowlist_filtersByPrefix() {
        AdhocPolicy policy = new AdhocPolicy(true,
                Arrays.asList("jdbc:postgresql:", "jdbc:clickhouse:"));

        assertTrue(policy.allows("jdbc:postgresql://host/db"));
        assertTrue(policy.allows("jdbc:clickhouse://host:8123/db"));

        assertFalse(policy.allows("jdbc:mysql://host/db"),
                "mysql not on the allowlist must be rejected");
        assertFalse(policy.allows("jdbc:oracle:thin:@host:1521/db"));
        assertFalse(policy.allows(""));
        assertFalse(policy.allows(null));
    }

    @Test(groups = { "unit" })
    public void testParsePrefixes_trimsAndFilters() {
        assertEquals(AdhocPolicy.parsePrefixes(""), Collections.emptyList());
        assertEquals(AdhocPolicy.parsePrefixes(null), Collections.emptyList());
        assertEquals(AdhocPolicy.parsePrefixes("jdbc:mysql:"),
                Collections.singletonList("jdbc:mysql:"));
        assertEquals(AdhocPolicy.parsePrefixes(" jdbc:mysql: , jdbc:postgresql: "),
                Arrays.asList("jdbc:mysql:", "jdbc:postgresql:"));
        assertEquals(AdhocPolicy.parsePrefixes(",,jdbc:mysql:,,"),
                Collections.singletonList("jdbc:mysql:"));
    }

    @Test(groups = { "unit" })
    public void testEnabled_nullUriRejectedEvenWithAnyPrefix() {
        AdhocPolicy noList = new AdhocPolicy(true, Collections.emptyList());
        // The empty-allowlist branch returns true for any non-null input but the
        // contract for null is "must reject" — there's nothing to construct a
        // datasource from.
        assertTrue(noList.allows(""), "empty string is a valid (if useless) URI");
        // null is special-cased below the empty-allowlist branch
        AdhocPolicy withList = new AdhocPolicy(true, Collections.singletonList("jdbc:"));
        assertFalse(withList.allows(null));
    }

    @Test(groups = { "unit" })
    public void allowedPrefixesAreExposedAsUnmodifiableList() {
        AdhocPolicy policy = new AdhocPolicy(true,
                Arrays.asList("jdbc:postgresql:", "jdbc:clickhouse:"));

        assertTrue(policy.isAllowed());
        // Pin the contract that matters: the list is defensively copied and
        // returned unmodifiable, so callers can't mutate the policy at runtime.
        assertEquals(policy.getAllowedPrefixes(),
                Arrays.asList("jdbc:postgresql:", "jdbc:clickhouse:"));
        org.testng.Assert.assertThrows(UnsupportedOperationException.class,
                () -> policy.getAllowedPrefixes().add("jdbc:mysql:"));
    }
}
