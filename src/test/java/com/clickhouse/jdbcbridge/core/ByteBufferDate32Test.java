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

import java.time.LocalDate;
import java.util.TimeZone;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ByteBufferDate32Test {
    // what the PostgreSQL driver returns for -infinity and infinity
    private static final long POSTGRES_MINUS_INFINITY = -9223372036832400000L;
    private static final long POSTGRES_INFINITY = 9223372036825200000L;

    private static java.sql.Date on(String date) {
        return java.sql.Date.valueOf(LocalDate.parse(date));
    }

    @DataProvider(name = "encode")
    Object[][] encode() {
        return new Object[][] {
            // label,                         date,                                         day
            { "present",                      on("2026-01-15"),                             20468 },
            { "epoch",                        on("1970-01-01"),                             0 },
            { "day before epoch",             on("1969-12-31"),                             -1 },
            { "1950",                         on("1950-06-15"),                             -7140 },
            { "1900 lower bound",             on("1900-01-01"),                             -25567 },
            { "2299 upper bound",             on("2299-12-31"),                             120529 },
            { "before 1900 saturates",        on("1899-12-31"),                             -25567 },
            { "lowest MySQL DATE saturates",  on("1000-01-01"),                             -25567 },
            { "after 2299 saturates",         on("9999-12-31"),                             120529 },
            { "PostgreSQL -infinity",         new java.sql.Date(POSTGRES_MINUS_INFINITY),   -25567 },
            { "PostgreSQL infinity",          new java.sql.Date(POSTGRES_INFINITY),         120529 },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "encode")
    public void writeDate32_writesTheDayNumber(String label, java.sql.Date date, int day) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeDate32(date);
        assertEquals(buffer.readInt32(), day, label);
    }

    @DataProvider(name = "decode")
    Object[][] decode() {
        return new Object[][] {
            // label,                         day,                date
            { "present",                      20468,              "2026-01-15" },
            { "epoch",                        0,                  "1970-01-01" },
            { "1950",                         -7140,              "1950-06-15" },
            { "lowest Int32 saturates",       Integer.MIN_VALUE,  "1900-01-01" },
            { "highest Int32 saturates",      Integer.MAX_VALUE,  "2299-12-31" },
        };
    }

    @Test(groups = { "unit" }, dataProvider = "decode")
    public void readDate32_readsTheDayNumber(String label, int day, String date) {
        ByteBuffer buffer = ByteBuffer.newInstance(64);
        buffer.writeInt32(day);
        assertEquals(buffer.readDate32().toLocalDate(), LocalDate.parse(date), label);
    }

    @Test(groups = { "unit" })
    public void writeDate32_ignoresTheZoneOfTheBuffer() {
        ByteBuffer buffer = ByteBuffer.newInstance(64, TimeZone.getTimeZone("Pacific/Kiritimati"));
        buffer.writeDate32(on("1950-06-15"));
        assertEquals(buffer.readInt32(), -7140);
    }
}
