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

import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

public class LogContextTest {
    @Test(groups = { "unit" })
    public void testSetGetClear() {
        try {
            assertNull(LogContext.getDataSource());

            LogContext.setDataSource("ds-1");
            assertEquals(LogContext.getDataSource(), "ds-1");

            LogContext.setDataSource("ds-2");
            assertEquals(LogContext.getDataSource(), "ds-2");
        } finally {
            LogContext.clear();
        }
        assertNull(LogContext.getDataSource());
    }

    @Test(groups = { "unit" })
    public void testThreadIsolation() throws InterruptedException {
        try {
            LogContext.setDataSource("main-ds");

            AtomicReference<String> seenInOtherThread = new AtomicReference<>();
            Thread other = new Thread(() -> seenInOtherThread.set(LogContext.getDataSource()));
            other.start();
            other.join(5000L);

            assertNull(seenInOtherThread.get(), "datasource must not leak across threads");
            assertEquals(LogContext.getDataSource(), "main-ds");
        } finally {
            LogContext.clear();
        }
    }
}
