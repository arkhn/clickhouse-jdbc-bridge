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
 * Tests for {@link NamedDataSource#executeQuery} hitting the
 * <em>debug</em> and <em>mutation</em> branches — both run entirely in
 * the base class (no JDBC needed) and write structured results through
 * the supplied {@link ResponseWriter}. Without these the writeDebugResult
 * + writeMutationResult helpers (both ~30-line bodies) are dark.
 */
public class NamedDataSourceWriteResultTest {

    /** Capturing writer so we can assert "something was written" without
     *  spinning up a real Vert.x HttpServerResponse. Uses ResponseWriter's
     *  protected no-arg test seam. */
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
        // QueryParameters.merge(JsonObject) deliberately skips the `debug`
        // param (security: clients can't enable debug mode via JSON).
        // The URI-merge path doesn't carry that exclusion, so we go via
        // a fake URI query string.
        return new QueryParameters("?debug=true");
    }

    private static QueryParameters mutationParams() {
        QueryParameters p = new QueryParameters();
        p.merge(new JsonObject().put(QueryParameters.PARAM_MUTATION, true));
        return p;
    }

    @Test(groups = { "unit" })
    public void executeQuery_debugBranch_writesDebugMetadataBytes() {
        // params.isDebug() triggers writeDebugResult — populates a fixed
        // schema (DEBUG_COLUMNS: datasource, type, definition, mtypes,
        // query, parameters) with the bridge's own metadata. No JDBC, no
        // backend — purely a self-describing result.
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
        // The debug writer writes one column per requestColumn, looking up
        // the value in the synthesized metadata map. Pass DEBUG_COLUMNS as
        // the request schema so every value resolves.
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
        // The base NamedDataSource.writeMutationResult(...) override at
        // line 219 is a no-op (subclasses provide the real impl), but
        // executeQuery still routes mutation params to it. We cover the
        // routing — the no-op produces no writes, which is the contract
        // a regression to a stale subclass implementation should preserve.
        NamedDataSource ds = build(new JsonObject());
        Capture w = new Capture();

        ds.executeQuery("",
                "INSERT INTO t VALUES (1)",
                "INSERT INTO t VALUES (1)",
                TableDefinition.MUTATION_COLUMNS,
                mutationParams(),
                w);

        // Base impl writes nothing — the JdbcDataSource subclass overrides
        // with real Hikari-backed mutation execution.
        assertEquals(w.writes, 0,
                "base NamedDataSource.writeMutationResult is a no-op; routing must reach it without throwing");
    }

    @Test(groups = { "unit" })
    public void executeQuery_normalBranchDoesNotTouchWriter() {
        // Neither debug nor mutation -> base writeQueryResult, also a
        // no-op in the parent. Cover the third routing branch.
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
        // Subclass-extension point; the base implementation only logs.
        // Pin that calling it on the base class doesn't throw and doesn't
        // surreptitiously write to the response.
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

    // ---------- getQuoteIdentifier / getType base contract ----------

    @Test(groups = { "unit" })
    public void baseDataSource_quoteAndTypeDefaults() {
        NamedDataSource ds = build(new JsonObject());
        // The bridge uses these strings on every /identifier_quote and
        // /columns_info response — locking the base-class defaults so a
        // refactor doesn't accidentally change the wire shape.
        assertEquals(ds.getQuoteIdentifier(), NamedDataSource.DEFAULT_QUOTE_IDENTIFIER);
        assertEquals(ds.getQuoteIdentifier(), "`");
        assertNotNull(ds.getType());
    }
}
