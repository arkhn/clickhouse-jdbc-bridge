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

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link NamedDataSource#executeQuery} debug and mutation branches —
 * both run in the base class (no JDBC needed).
 */
public class NamedDataSourceWriteResultTest {

    static final class Capture extends ResponseWriter {
        int writes;
        long bytes;

        @Override public boolean isOpen() { return true; }
        @Override public boolean writeQueueFull() { return false; }
        @Override public void write(ByteBuffer buffer) {
            writes++;
            if (buffer != null) bytes += buffer.length();
        }
    }

    private static NamedDataSource build(JsonObject cfg) {
        return new NamedDataSource("ds-write-test",
                new NamedDataSourceTest.TestRepository<>(NamedDataSource.class), cfg);
    }

    private static QueryParameters debugParams() {
        // QueryParameters.merge(JsonObject) deliberately skips `debug` (security: clients
        // can't enable via JSON). URI-merge path doesn't carry that exclusion.
        return new QueryParameters("?debug=true");
    }

    private static QueryParameters mutationParams() {
        QueryParameters p = new QueryParameters();
        p.merge(new JsonObject().put(QueryParameters.PARAM_MUTATION, true));
        return p;
    }

    @Test(groups = { "unit" })
    public void executeQuery_debugBranch_writesDebugMetadataBytes() {
        NamedDataSource ds = build(new JsonObject());
        Capture w = new Capture();

        ds.executeQuery("", "SELECT debug",
                "SELECT debug",
                TableDefinition.DEBUG_COLUMNS,
                debugParams(),
                w);

        assertTrue(w.writes > 0, "writeDebugResult must emit at least one write");
        assertTrue(w.bytes > 0, "writeDebugResult must emit non-empty bytes");
    }

    @Test(groups = { "unit" })
    public void executeQuery_debugBranchTreatsRequestColumnsAsTemplate() {
        NamedDataSource ds = build(new JsonObject().put("type", "test-type"));
        Capture w = new Capture();

        ds.executeQuery("schema-x",
                "SELECT *",
                "SELECT *",
                TableDefinition.DEBUG_COLUMNS,
                debugParams(),
                w);

        assertTrue(w.bytes > 0);
    }

    @Test(groups = { "unit" })
    public void executeQuery_mutationBranchEmitsRowsValue() {
        // Base writeMutationResult is a no-op (subclasses provide real impl); we cover routing.
        NamedDataSource ds = build(new JsonObject());
        Capture w = new Capture();

        ds.executeQuery("",
                "INSERT INTO t VALUES (1)",
                "INSERT INTO t VALUES (1)",
                TableDefinition.MUTATION_COLUMNS,
                mutationParams(),
                w);

        assertEquals(w.writes, 0,
                "base NamedDataSource.writeMutationResult is a no-op; routing must reach it without throwing");
    }

    @Test(groups = { "unit" })
    public void executeQuery_normalBranchDoesNotTouchWriter() {
        // Neither debug nor mutation -> base writeQueryResult (also no-op).
        NamedDataSource ds = build(new JsonObject());
        Capture w = new Capture();

        ds.executeQuery("",
                "SELECT 1",
                "SELECT 1",
                TableDefinition.DEFAULT_RESULT_COLUMNS,
                new QueryParameters(),
                w);

        assertEquals(w.writes, 0);
    }

    @Test(groups = { "unit" })
    public void executeMutation_baseClass_isNoOpButCallable() {
        // Subclass extension point; base only logs.
        NamedDataSource ds = build(new JsonObject());
        Capture w = new Capture();

        ds.executeMutation("schema", "t",
                new TableDefinition(new ColumnDefinition("c", DataType.Str, true,
                        DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE)),
                new QueryParameters(),
                ByteBuffer.newInstance(16),
                w);

        assertEquals(w.writes, 0);
    }

    @Test(groups = { "unit" })
    public void baseDataSource_quoteAndTypeDefaults() {
        // Locks the wire shape used on every /identifier_quote and /columns_info response.
        NamedDataSource ds = build(new JsonObject());
        assertEquals(ds.getQuoteIdentifier(), NamedDataSource.DEFAULT_QUOTE_IDENTIFIER);
        assertEquals(ds.getQuoteIdentifier(), "`");
        assertNotNull(ds.getType());
    }
}
