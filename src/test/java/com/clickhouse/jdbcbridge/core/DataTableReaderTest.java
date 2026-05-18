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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;

/**
 * Unit tests for the {@link DataTableReader#process(String, ColumnDefinition[],
 * ColumnDefinition[], ColumnDefinition[], DefaultValues, TimeZone, QueryParameters,
 * ResponseWriter)} streaming pipeline, exercised via an in-memory fake reader so
 * no JDBC driver, network or HTTP plumbing is required.
 */
public class DataTableReaderTest {

    private static ColumnDefinition strColumn(String name, boolean nullable) {
        return new ColumnDefinition(name, DataType.Str, nullable,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    /**
     * In-memory fake. Each row is an array of values (null entries are honored).
     * Tracks per-cell isNull/read invocation counts so tests can assert the
     * interface contract.
     */
    static final class InMemoryReader implements DataTableReader {
        private final Object[][] rows;
        private int cursor = -1; // before-first
        final AtomicInteger isNullCalls = new AtomicInteger();
        final AtomicInteger readCalls = new AtomicInteger();

        InMemoryReader(Object[][] rows) {
            this.rows = rows;
        }

        @Override
        public boolean nextRow() {
            return ++cursor < rows.length;
        }

        @Override
        public boolean isNull(int row, int column, ColumnDefinition metadata) {
            isNullCalls.incrementAndGet();
            return rows[cursor][column] == null;
        }

        @Override
        public void read(int row, int column, ColumnDefinition metadata, ByteBuffer buffer) {
            readCalls.incrementAndGet();
            Object v = rows[cursor][column];
            buffer.writeString(v == null ? "" : String.valueOf(v), false);
        }
    }

    /**
     * ResponseWriter fake. Records each {@link #write(ByteBuffer)} call as a
     * "batch" with its byte length, so tests can assert the batching behavior
     * without touching Netty/Vert.x.
     */
    static final class RecordingResponseWriter extends ResponseWriter {
        final List<Integer> batchLengths = new ArrayList<>();
        int totalBytes = 0;
        boolean queueFull = false;

        @Override
        public void write(ByteBuffer buffer) {
            int len = buffer.length();
            batchLengths.add(len);
            totalBytes += len;
        }

        @Override
        public boolean writeQueueFull() {
            return queueFull;
        }

        @Override
        public void setDrainHanlder(Handler<Void> handler) {
            // no-op in fakes
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static QueryParameters paramsWith(String queryString) {
        // QueryParameters.merge(String) treats anything after the first '?' as a
        // URL-encoded parameter string, which is the simplest way to set arbitrary
        // params without touching every getter.
        return new QueryParameters("?" + queryString);
    }

    private static QueryParameters params() {
        return new QueryParameters((JsonObject[]) new JsonObject[0]);
    }

    // ---------------------------------------------------------------------
    // Smoke / contract tests (these should all pass)
    // ---------------------------------------------------------------------

    @Test(groups = { "unit" })
    public void testSingleRowSingleColumn_writesOneBatch() {
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "hello" }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", false) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), params(), writer);

        // batchSize defaults to a large number, so a single row produces a single
        // trailing flush.
        assertEquals(writer.batchLengths.size(), 1, "expected exactly one batch flush");
        assertTrue(writer.totalBytes > 0, "expected non-empty bytes for a non-null value");
        assertEquals(reader.readCalls.get(), 1);
        // non-nullable: isNull MUST NOT be called
        assertEquals(reader.isNullCalls.get(), 0,
                "isNull must not be called for non-nullable columns");
    }

    @Test(groups = { "unit" })
    public void testNullableColumn_isNullCalledExactlyOncePerCell() {
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" },
                new Object[] { "b" },
                new Object[] { "c" }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", true) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), params(), writer);

        assertEquals(reader.isNullCalls.get(), 3,
                "isNull must be called exactly once per nullable cell with non-null data");
        assertEquals(reader.readCalls.get(), 3);
    }

    @Test(groups = { "unit" })
    public void testNullableColumn_nullValue_writesNullMarker() {
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { (Object) null }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", true) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), params(), writer);

        // null path: isNull called, read NOT called (the loop continues after writing
        // the null marker).
        assertEquals(reader.isNullCalls.get(), 1);
        assertEquals(reader.readCalls.get(), 0, "read must not be called when isNull returns true");
        assertEquals(writer.batchLengths.size(), 1);
        // null marker is at least one byte (\x01); the fake never writes a value here.
        assertTrue(writer.totalBytes >= 1);
    }

    @Test(groups = { "unit" })
    public void testEmptyResult_producesNoBatches() {
        InMemoryReader reader = new InMemoryReader(new Object[0][0]);
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", false) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), params(), writer);

        assertEquals(reader.readCalls.get(), 0);
        assertEquals(writer.batchLengths.size(), 0,
                "no rows -> no batches (not even an empty one)");
    }

    @Test(groups = { "unit" })
    public void testBatchSizeBoundary_flushesAtEachBoundary() {
        // 5 rows, batchSize=2 -> flushes after rows 2 and 4, trailing flush for row 5.
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "r1" }, new Object[] { "r2" },
                new Object[] { "r3" }, new Object[] { "r4" },
                new Object[] { "r5" }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", false) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), paramsWith("batch_size=2"), writer);

        assertEquals(reader.readCalls.get(), 5);
        assertEquals(writer.batchLengths.size(), 3,
                "expected three flushes: 2 + 2 + 1");
    }

    @Test(groups = { "unit" })
    public void testNonNullableColumn_skipsIsNullDispatch() {
        // P1 (nullableMask) hoist: non-nullable columns should never dispatch isNull.
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" }, new Object[] { "b" }, new Object[] { "c" }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", false) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), params(), writer);

        assertEquals(reader.isNullCalls.get(), 0);
        assertEquals(reader.readCalls.get(), 3);
        assertNotNull(writer.batchLengths);
    }

    // ---------------------------------------------------------------------
    // FAILING TEST: offset semantics
    // ---------------------------------------------------------------------

    /**
     * Conventional SQL OFFSET semantics: {@code offset=N} skips the first N rows
     * and returns rows N+1..end. The bridge currently leaves the cursor at row N
     * after skipping and then uses a {@code skipped=true} flag that processes the
     * row at the cursor — so {@code offset=2} on a 5-row result yields rows 2..5
     * (4 rows) instead of rows 3..5 (3 rows). This test pins the correct behavior
     * and fails against the current implementation.
     */
    @Test(groups = { "unit" })
    public void testOffsetSkipsNRows_thenStreamsRest() {
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "r1" }, new Object[] { "r2" }, new Object[] { "r3" },
                new Object[] { "r4" }, new Object[] { "r5" }
        });
        ColumnDefinition[] cols = new ColumnDefinition[] { strColumn("c", false) };
        RecordingResponseWriter writer = new RecordingResponseWriter();

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), paramsWith("offset=2"), writer);

        assertEquals(reader.readCalls.get(), 3,
                "offset=2 on 5 rows must produce exactly 3 reads (rows 3..5)");
    }
}
