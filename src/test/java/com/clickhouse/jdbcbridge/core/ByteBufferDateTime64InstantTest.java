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
 * ClickHouse stores a DateTime64 tick as a signed Int64, so the bridge must be
 * able to write the whole range it accepts (1900 to 2299), not only instants
 * after the Unix epoch.
 */
public class ByteBufferDateTime64InstantTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static final long LOWER_BOUND = -2208988800000L; // 1900-01-01 00:00:00 UTC
    private static final long PRE1970 = -616865400000L; // 1950-06-15 08:30:00 UTC
    private static final long BEFORE_EPOCH = -1L; // 1969-12-31 23:59:59.999 UTC
    private static final long EPOCH = 0L; // 1970-01-01 00:00:00 UTC
    private static final long AFTER_EPOCH = 1L; // 1970-01-01 00:00:00.001 UTC
    private static final long PRESENT = 1768465800000L; // 2026-01-15 08:30:00 UTC
    private static final long UPPER_BOUND = 10413791999000L; // 2299-12-31 23:59:59 UTC

    private static Timestamp at(long millis) {
        return new Timestamp(millis);
    }

    private static Timestamp at(long millis, int nanos) {
        Timestamp ts = new Timestamp(millis);
        ts.setNanos(nanos);
        return ts;
    }

    private long tick(Timestamp ts, int scale) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(ts, scale, UTC);
        return buffer.readInt64();
    }

    private long tick(long millis, int scale) {
        return tick(at(millis), scale);
    }

    // ---------- before the epoch ----------

    @Test(groups = { "unit" })
    public void testPre1970KeepsItsInstantAtEveryScale() {
        assertEquals(tick(PRE1970, 0), -616865400L);
        assertEquals(tick(PRE1970, 3), -616865400000L);
        assertEquals(tick(PRE1970, 6), -616865400000000L);
    }

    @Test(groups = { "unit" })
    public void testLowerBoundOfTheClickHouseRange() {
        assertEquals(tick(LOWER_BOUND, 0), -2208988800L);
        assertEquals(tick(LOWER_BOUND, 3), -2208988800000L);
    }

    @Test(groups = { "unit" })
    public void testOneMillisecondBeforeTheEpoch() {
        assertEquals(tick(BEFORE_EPOCH, 3), -1L);
        // -0.001 s, and a tick is a whole second here, so the floor is -1
        assertEquals(tick(BEFORE_EPOCH, 0), -1L);
    }

    /**
     * Rounding is a floor, not a truncation towards zero: a tick is the number of
     * whole units elapsed, and -0.877 s sits inside the second that started at
     * -1 s. It follows what ClickHouse does when parsing such a literal and what
     * toStartOfSecond returns. Its own CAST between two DateTime64 truncates
     * towards zero instead, so the two disagree by one tick for a sub-second
     * instant before 1970.
     */
    @Test(groups = { "unit" })
    public void testSubSecondBeforeTheEpochIsFloored() {
        long millis = -877L; // 1969-12-31 23:59:59.123 UTC
        assertEquals(tick(millis, 3), -877L);
        assertEquals(tick(millis, 0), -1L);
    }

    // ---------- the epoch itself ----------

    /**
     * A JDBC TIME of 00:00:00 lands on this very instant, since a TIME column
     * carries no date, so both cases are the same tick of zero.
     */
    @Test(groups = { "unit" })
    public void testReferenceDateIsNotShiftedByOneTick() {
        assertEquals(tick(EPOCH, 0), 0L);
        assertEquals(tick(EPOCH, 3), 0L);
        assertEquals(tick(EPOCH, 6), 0L);
        assertEquals(tick(EPOCH, 9), 0L);
    }

    @Test(groups = { "unit" })
    public void testOneMillisecondAfterTheEpoch() {
        assertEquals(tick(AFTER_EPOCH, 3), 1L);
        assertEquals(tick(AFTER_EPOCH, 0), 0L);
    }

    // ---------- JDBC TIME columns ----------

    /**
     * A JDBC TIME column carries no date, so the driver reports it as an instant
     * on 1970-01-01. MySQL TIME also accepts negative values, down to -838:59:59.
     */
    @Test(groups = { "unit" })
    public void testTimeOfDayMorning() {
        assertEquals(tick(30600000L, 0), 30600L); // 08:30:00
        assertEquals(tick(30600000L, 3), 30600000L);
    }

    @Test(groups = { "unit" })
    public void testNegativeTimeOfDay() {
        assertEquals(tick(-3600000L, 0), -3600L); // -01:00:00
        assertEquals(tick(-3600000L, 3), -3600000L);
    }

    // ---------- sub-millisecond precision ----------

    /**
     * java.sql.Timestamp carries nanoseconds, so a scale finer than 3 must keep
     * them. Rescaling through a double loses everything below the microsecond.
     */
    @Test(groups = { "unit" })
    public void testNanosecondSurvivesAtScale9() {
        assertEquals(tick(at(PRESENT, 1), 9), 1768465800000000001L);
    }

    @Test(groups = { "unit" })
    public void testMicrosecondSurvivesAtScale6() {
        assertEquals(tick(at(PRESENT, 456789000), 6), 1768465800456789L);
    }

    @Test(groups = { "unit" })
    public void testNanosecondIsDroppedByACoarserScale() {
        assertEquals(tick(at(PRESENT, 456789000), 3), 1768465800456L);
        assertEquals(tick(at(PRESENT, 456789000), 0), 1768465800L);
    }

    @Test(groups = { "unit" })
    public void testNanosecondBeforeTheEpochSurvivesAtScale9() {
        // 1950-06-15 08:30:00.000000001 UTC
        assertEquals(tick(at(PRE1970, 1), 9), -616865399999999999L);
    }

    // ---------- present and upper bound ----------

    @Test(groups = { "unit" })
    public void testPresentIsUnchanged() {
        assertEquals(tick(PRESENT, 0), 1768465800L);
        assertEquals(tick(PRESENT, 3), PRESENT);
        assertEquals(tick(PRESENT, 6), 1768465800000000L);
    }

    @Test(groups = { "unit" })
    public void testUpperBoundOfTheClickHouseRange() {
        assertEquals(tick(UPPER_BOUND, 0), 10413791999L);
        assertEquals(tick(UPPER_BOUND, 3), UPPER_BOUND);
    }

    // ---------- ticks that no Int64 can hold ----------

    /**
     * A scale of 9 covers 1677 to 2262, which is narrower than the range
     * ClickHouse itself accepts, and a source can hand over an instant outside
     * it. Clamping would store a date that still looks plausible, so both ends
     * fail instead. ClickHouse answers DECIMAL_OVERFLOW on the same input.
     */
    @Test(groups = { "unit" })
    public void testInstantTooLateForTheScaleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> tick(UPPER_BOUND, 9));
    }

    @Test(groups = { "unit" })
    public void testInstantTooEarlyForTheScaleIsRejected() {
        long year1500 = -14830992000000L; // 1500-01-01, reachable from an Oracle DATE
        assertThrows(IllegalArgumentException.class, () -> tick(year1500, 9));
    }

    @Test(groups = { "unit" })
    public void testScale9HoldsTheClickHouseLowerBound() {
        assertEquals(tick(LOWER_BOUND, 9), -2208988800000000000L);
    }

    @Test(groups = { "unit" })
    public void testNegativeScaleLeavesTheMillisecondValueUntouched() {
        assertEquals(tick(PRESENT, -1), PRESENT);
        assertEquals(tick(PRE1970, -1), PRE1970);
    }

    // ---------- behaviour left as is, pinned so a change is deliberate ----------

    /**
     * The writer shifts the instant by the offset of the target timezone, while
     * a column inferred from JDBC metadata declares no timezone. The tick then
     * carries local wall-clock time rather than the instant. Out of scope here,
     * pinned so that changing it stays a deliberate decision.
     */
    @Test(groups = { "unit" })
    public void testTimezoneOffsetIsAppliedToTheInstant() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(at(PRESENT), 3, TimeZone.getTimeZone("Europe/Paris"));
        assertEquals(buffer.readInt64(), PRESENT + 3600000L); // winter offset
    }
}
