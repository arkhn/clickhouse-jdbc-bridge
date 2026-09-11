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

import java.sql.Timestamp;
import java.util.TimeZone;

import org.testng.annotations.Test;

/**
 * The read path decodes what ClickHouse sends when writing to a JDBC source. It
 * must apply the same tick unit as the write path, otherwise a DateTime64(0)
 * parameter is off by a factor of 1000 in the other direction.
 */
public class ByteBufferDateTime64ReadTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final long PRESENT = 1768465800000L; // 2026-01-15 08:30:00 UTC
    private static final long PRE1970 = -616865400000L; // 1950-06-15 08:30:00 UTC

    private Timestamp read(long tick, int scale) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeInt64(tick);
        return buffer.readDateTime64(scale, UTC);
    }

    private Timestamp roundTrip(Timestamp value, int scale) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(value, scale, UTC);
        return buffer.readDateTime64(scale, UTC);
    }

    // ---------- the tick unit must follow the scale ----------

    @Test(groups = { "unit" })
    public void testSecondTickIsReadAsSeconds() {
        assertEquals(read(1768465800L, 0).getTime(), PRESENT);
    }

    @Test(groups = { "unit" })
    public void testMillisecondTickIsReadAsMilliseconds() {
        assertEquals(read(1768465800123L, 3).getTime(), 1768465800123L);
    }

    @Test(groups = { "unit" })
    public void testMicrosecondTickKeepsItsNanos() {
        Timestamp read = read(1768465800456789L, 6);
        assertEquals(read.getTime(), 1768465800456L);
        assertEquals(read.getNanos(), 456789000);
    }

    @Test(groups = { "unit" })
    public void testNanosecondTickKeepsItsNanos() {
        assertEquals(read(1768465800000000001L, 9).getNanos(), 1);
    }

    // ---------- before the epoch ----------

    @Test(groups = { "unit" })
    public void testNegativeTickIsReadAsAPre1970Instant() {
        assertEquals(read(-616865400000L, 3).getTime(), PRE1970);
        assertEquals(read(-616865400L, 0).getTime(), PRE1970);
    }

    @Test(groups = { "unit" })
    public void testEpochTickStaysTheEpoch() {
        assertEquals(read(0L, 0).getTime(), 0L);
        assertEquals(read(0L, 3).getTime(), 0L);
    }

    // ---------- round trips ----------

    @Test(groups = { "unit" })
    public void testRoundTripOfThePresentAtEveryScale() {
        for (int scale : new int[] { 0, 3, 6, 9 }) {
            Timestamp value = new Timestamp(PRESENT);
            assertEquals(roundTrip(value, scale).getTime(), PRESENT, "scale " + scale);
        }
    }

    @Test(groups = { "unit" })
    public void testRoundTripOfAPre1970Instant() {
        for (int scale : new int[] { 0, 3, 6 }) {
            Timestamp value = new Timestamp(PRE1970);
            assertEquals(roundTrip(value, scale).getTime(), PRE1970, "scale " + scale);
        }
    }

    @Test(groups = { "unit" })
    public void testRoundTripKeepsSubMillisecondAtScale9() {
        Timestamp value = new Timestamp(PRESENT);
        value.setNanos(456789123);
        assertEquals(roundTrip(value, 9).getNanos(), 456789123);
    }

    /**
     * A scale-0 tick only names a whole second, so the sub-second part of the
     * value does not come back. The instant must still be the right second.
     */
    @Test(groups = { "unit" })
    public void testRoundTripAtScale0DropsTheSubSecond() {
        Timestamp present = new Timestamp(PRESENT + 123L);
        assertEquals(roundTrip(present, 0).getTime(), PRESENT);

        // floored, so a sub-second instant before 1970 lands on the second below
        Timestamp before = new Timestamp(-877L);
        assertEquals(roundTrip(before, 0).getTime(), -1000L);
    }

    /**
     * A tick no Timestamp can hold must not wrap into a plausible looking date,
     * the same way the write path refuses to emit one.
     */
    @Test(groups = { "unit" })
    public void testTickTooLargeForATimestampIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> read(Long.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> read(Long.MIN_VALUE, 0));
    }

    /**
     * The overload without a scale is what the existing callers use, and they
     * have always written milliseconds.
     */
    @Test(groups = { "unit" })
    public void testOverloadWithoutScaleReadsMilliseconds() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(new Timestamp(PRESENT), 3, UTC);
        assertEquals(buffer.readDateTime64(UTC).getTime(), PRESENT);
    }

    /**
     * That overload used to replace any negative tick by 1. It now returns the
     * instant, which is the whole point of the change.
     */
    @Test(groups = { "unit" })
    public void testOverloadWithoutScaleKeepsANegativeTick() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeInt64(PRE1970);
        assertEquals(buffer.readDateTime64(UTC).getTime(), PRE1970);
    }
}
