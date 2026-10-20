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
import java.time.Instant;
import java.util.TimeZone;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ByteBufferDateTime64Test {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone PARIS = TimeZone.getTimeZone("Europe/Paris");

    // what the PostgreSQL driver returns for -infinity and infinity
    private static final long POSTGRES_MINUS_INFINITY = -9223372036832400000L;
    private static final long POSTGRES_INFINITY = 9223372036825200000L;

    private static Timestamp at(String instant) {
        return Timestamp.from(Instant.parse(instant));
    }

    @DataProvider(name = "encode")
    Object[][] encode() {
        return new Object[][] {
            // label,                           value,                                         scale, tick
            { "present at 0",                   at("2026-01-15T08:30:00Z"),                    0, 1768465800L },
            { "present at 1",                   at("2026-01-15T08:30:00Z"),                    1, 17684658000L },
            { "present at 2",                   at("2026-01-15T08:30:00Z"),                    2, 176846580000L },
            { "present at 3",                   at("2026-01-15T08:30:00Z"),                    3, 1768465800000L },
            { "present at 6",                   at("2026-01-15T08:30:00Z"),                    6, 1768465800000000L },
            { "sub-second dropped at 0",        at("2026-01-15T08:30:00.123Z"),                0, 1768465800L },
            { "millisecond kept at 3",          at("2026-01-15T08:30:00.123Z"),                3, 1768465800123L },
            { "microsecond floored at 3",       at("2026-01-15T08:30:00.456789Z"),             3, 1768465800456L },
            { "microsecond kept at 6",          at("2026-01-15T08:30:00.456789Z"),             6, 1768465800456789L },
            { "nanosecond kept at 9",           at("2026-01-15T08:30:00.000000001Z"),          9, 1768465800000000001L },
            { "1950 at 0",                      at("1950-06-15T08:30:00Z"),                    0, -616865400L },
            { "1950 at 3",                      at("1950-06-15T08:30:00Z"),                    3, -616865400000L },
            { "1950 at 6",                      at("1950-06-15T08:30:00Z"),                    6, -616865400000000L },
            { "1950 nanosecond at 9",           at("1950-06-15T08:30:00.000000001Z"),          9, -616865399999999999L },
            { "1 ms before epoch at 3",         at("1969-12-31T23:59:59.999Z"),                3, -1L },
            { "1 ms before epoch floored at 0", at("1969-12-31T23:59:59.999Z"),                0, -1L },
            { "sub-second before epoch at 3",   at("1969-12-31T23:59:59.123Z"),                3, -877L },
            { "sub-second before epoch at 0",   at("1969-12-31T23:59:59.123Z"),                0, -1L },
            { "epoch at 0",                     at("1970-01-01T00:00:00Z"),                    0, 0L },
            { "epoch at 3",                     at("1970-01-01T00:00:00Z"),                    3, 0L },
            { "epoch at 9",                     at("1970-01-01T00:00:00Z"),                    9, 0L },
            { "1 ms after epoch floored at 0",  at("1970-01-01T00:00:00.001Z"),                0, 0L },
            { "JDBC TIME 08:30 at 0",           at("1970-01-01T08:30:00Z"),                    0, 30600L },
            { "1900 lower bound at 0",          at("1900-01-01T00:00:00Z"),                    0, -2208988800L },
            { "1900 lower bound at 9",          at("1900-01-01T00:00:00Z"),                    9, -2208988800000000000L },
            { "2299 upper bound at 0",          at("2299-12-31T23:59:59Z"),                    0, 10413791999L },
            { "2299 upper bound at 3",          at("2299-12-31T23:59:59.999Z"),                3, 10413791999999L },
            { "last instant an Int64 holds",    at("2262-04-11T23:47:16.854775806Z"),          9, Long.MAX_VALUE - 1 },
            { "before 1900 saturates at 0",     at("1899-12-31T23:59:59Z"),                    0, -2208988800L },
            { "before 1900 saturates at 9",     at("1500-01-01T00:00:00Z"),                    9, -2208988800000000000L },
            { "after 2299 saturates at 0",      at("2300-01-01T00:00:00Z"),                    0, 10413791999L },
            { "after 2299 saturates at 3",      at("9999-12-31T23:59:59Z"),                    3, 10413791999999L },
            { "after 2262 saturates at 9",      at("2299-12-31T23:59:59Z"),                    9, Long.MAX_VALUE },
            { "PostgreSQL -infinity at 6",      new Timestamp(POSTGRES_MINUS_INFINITY),        6, -2208988800000000L },
            { "PostgreSQL infinity at 6",       new Timestamp(POSTGRES_INFINITY),              6, 10413791999999999L },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "encode")
    public void writeDateTime64_writesTheTickOfTheScale(String label, Timestamp value, int scale, long tick) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(value, scale, UTC);
        assertEquals(buffer.readInt64(), tick, label);
    }

    @DataProvider(name = "decode")
    Object[][] decode() {
        return new Object[][] {
            // label,                         tick,                  scale, instant
            { "second tick",                  1768465800L,           0, "2026-01-15T08:30:00Z" },
            { "millisecond tick",             1768465800123L,        3, "2026-01-15T08:30:00.123Z" },
            { "microsecond tick",             1768465800456789L,     6, "2026-01-15T08:30:00.456789Z" },
            { "nanosecond tick",              1768465800000000001L,  9, "2026-01-15T08:30:00.000000001Z" },
            { "1950 at 0",                    -616865400L,           0, "1950-06-15T08:30:00Z" },
            { "1950 at 3",                    -616865400000L,        3, "1950-06-15T08:30:00Z" },
            { "1950 nanosecond at 9",         -616865399999999999L,  9, "1950-06-15T08:30:00.000000001Z" },
            { "sub-second before epoch at 3", -877L,                 3, "1969-12-31T23:59:59.123Z" },
            { "epoch at 0",                   0L,                    0, "1970-01-01T00:00:00Z" },
            { "before 1900 saturates at 0",   -30610224000L,         0, "1900-01-01T00:00:00Z" },
            { "lowest Int64 saturates at 3",  Long.MIN_VALUE,        3, "1900-01-01T00:00:00Z" },
            { "highest Int64 saturates at 0", Long.MAX_VALUE,        0, "2299-12-31T23:59:59Z" },
            { "highest Int64 at 9",           Long.MAX_VALUE,        9, "2262-04-11T23:47:16.854775807Z" },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "decode")
    public void readDateTime64_readsTheTickOfTheScale(String label, long tick, int scale, String instant) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeInt64(tick);
        assertEquals(buffer.readDateTime64(scale, UTC).toInstant(), Instant.parse(instant), label);
    }

    @Test(groups = { "unit" })
    public void dateTime64_refusesAScaleClickHouseDoesNotHave() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        assertThrows(IllegalArgumentException.class, () -> buffer.writeDateTime64(at("2026-01-15T08:30:00Z"), 10, UTC));
        assertThrows(IllegalArgumentException.class, () -> buffer.writeDateTime64(at("2026-01-15T08:30:00Z"), -1, UTC));
        buffer.writeInt64(0L);
        assertThrows(IllegalArgumentException.class, () -> buffer.readDateTime64(10, UTC));
    }

    // Pinned, not endorsed: the tick carries the wall clock of the zone, not the instant.
    @Test(groups = { "unit" })
    public void writeDateTime64_shiftsTheInstantByTheZone() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDateTime64(at("2026-01-15T08:30:00Z"), 3, PARIS);
        assertEquals(buffer.readInt64(), 1768465800000L + 3600000L);
    }

    @Test(groups = { "unit" })
    public void readDateTime64_subtractsTheOffsetOfTheZone() {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeInt64(0L);
        assertEquals(buffer.readDateTime64(3, PARIS).getTime(), -3600000L);
    }
}
