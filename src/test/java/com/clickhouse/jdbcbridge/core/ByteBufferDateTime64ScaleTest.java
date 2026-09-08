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

import java.sql.Timestamp;
import java.util.TimeZone;

import org.testng.annotations.Test;

/**
 * A DateTime64(N) tick is 10^-N second, so the value written on the wire must be
 * rescaled from the millisecond value carried by java.sql.Timestamp.
 */
public class ByteBufferDateTime64ScaleTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    // 2026-01-15 08:30:00 UTC
    private static final long EPOCH_MILLIS = 1768465800000L;

    private long writtenTick(int scale) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(new Timestamp(EPOCH_MILLIS), scale, UTC);
        return buffer.readInt64();
    }

    @Test(groups = { "unit" })
    public void testSecondTickForScale0() {
        assertEquals(writtenTick(0), 1768465800L);
    }

    @Test(groups = { "unit" })
    public void testCoarserTicksBelowMillisecond() {
        assertEquals(writtenTick(1), 17684658000L);
        assertEquals(writtenTick(2), 176846580000L);
    }

    @Test(groups = { "unit" })
    public void testMillisecondTickIsWrittenAsIs() {
        assertEquals(writtenTick(3), EPOCH_MILLIS);
    }

    @Test(groups = { "unit" })
    public void testFinerTickAboveMillisecond() {
        assertEquals(writtenTick(6), 1768465800000000L);
    }

    /**
     * A negative scale is not a valid DateTime64 precision. Writing the value
     * unchanged is what the caller used to get, so keep it rather than dividing
     * by a nonsensical factor.
     */
    @Test(groups = { "unit" })
    public void testNegativeScaleLeavesValueUntouched() {
        assertEquals(writtenTick(-1), EPOCH_MILLIS);
    }
}
