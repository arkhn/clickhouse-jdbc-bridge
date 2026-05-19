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

import org.testng.annotations.Test;

import io.vertx.core.MultiMap;

/**
 * Tests for {@link StreamOptions} — the streaming write-chunk size
 * lever. The bridge calls {@code response.setWriteQueueMaxSize(maxBlockSize)}
 * on every response, so the default and override must both round-trip
 * cleanly.
 */
public class StreamOptionsTest {

    @Test(groups = { "unit" })
    public void defaultBlockSize_isAppliedWhenParamAbsent() {
        StreamOptions opts = new StreamOptions(MultiMap.caseInsensitiveMultiMap());

        assertEquals(opts.getMaxBlockSize(), StreamOptions.DEFAULT_BLOCK_SIZE);
    }

    @Test(groups = { "unit" })
    public void validIntegerOverride_isHonored() {
        MultiMap m = MultiMap.caseInsensitiveMultiMap();
        m.add("max_block_size", "8192");

        StreamOptions opts = new StreamOptions(m);

        assertEquals(opts.getMaxBlockSize(), 8192);
    }

    @Test(groups = { "unit" })
    public void invalidIntegerOverride_fallsBackToDefault() {
        // NumberFormatException is swallowed by the empty catch — the
        // bridge must NOT crash on a malformed client-supplied option;
        // it falls back to the default and continues streaming.
        MultiMap m = MultiMap.caseInsensitiveMultiMap();
        m.add("max_block_size", "not-a-number");

        StreamOptions opts = new StreamOptions(m);

        assertEquals(opts.getMaxBlockSize(), StreamOptions.DEFAULT_BLOCK_SIZE);
    }

    @Test(groups = { "unit" })
    public void zeroOverride_isHonored() {
        // The parser doesn't validate >0 — a zero block size is accepted
        // as-is and forwarded to setWriteQueueMaxSize. Pin so a future
        // sanity check shows up as a test break.
        MultiMap m = MultiMap.caseInsensitiveMultiMap();
        m.add("max_block_size", "0");

        StreamOptions opts = new StreamOptions(m);

        assertEquals(opts.getMaxBlockSize(), 0);
    }

    @Test(groups = { "unit" })
    public void negativeOverride_isHonored() {
        // Same: negative is currently accepted as-is. Pin behavior so a
        // future input-validation tightening is intentional.
        MultiMap m = MultiMap.caseInsensitiveMultiMap();
        m.add("max_block_size", "-1");

        StreamOptions opts = new StreamOptions(m);

        assertEquals(opts.getMaxBlockSize(), -1);
    }
}
