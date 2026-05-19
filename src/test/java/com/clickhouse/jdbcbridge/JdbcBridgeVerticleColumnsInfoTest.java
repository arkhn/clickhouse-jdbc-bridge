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
package com.clickhouse.jdbcbridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import com.clickhouse.jdbcbridge.core.BaseRepository;
import com.clickhouse.jdbcbridge.core.ManagedEntity;
import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.NamedQuery;
import com.clickhouse.jdbcbridge.core.NamedSchema;
import com.clickhouse.jdbcbridge.core.QueryParameters;
import com.clickhouse.jdbcbridge.core.Repository;
import com.clickhouse.jdbcbridge.core.TableDefinition;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.testng.annotations.Test;

/**
 * Unit tests for {@link JdbcBridgeVerticle#resolveColumnsTableDef}, the
 * pure-logic seam extracted from {@code handleColumnsInfo}. The seam decides
 * which {@link TableDefinition} should describe the response columns based on
 * the (already-performed) repository lookups plus the parser's outputs.
 *
 * <p>Tests deliberately avoid spinning up a Vert.x server / JDBC backend: the
 * seam is a static method that takes plain objects. Where a real
 * {@link NamedDataSource} is needed, a tiny subclass overrides
 * {@code inferTypes} to return a sentinel — that's the only path
 * {@code getResultColumns} can reach without a live JDBC connection.
 */
public class JdbcBridgeVerticleColumnsInfoTest {

    /**
     * Minimal in-memory repository: enough to satisfy
     * {@link BaseRepository#put}/get without touching DNS resolution, scan
     * watchers, or extension class loading. We can't reuse
     * {@code NamedDataSourceTest.TestRepository} because it's package-private
     * to {@code core}.
     */
    private static final class InMemoryRepo<T extends ManagedEntity> extends BaseRepository<T> {
        InMemoryRepo(Class<T> clazz) {
            super(clazz);
        }

        @Override
        protected void atomicAdd(T entity) {
        }

        @Override
        protected void atomicRemove(T entity) {
        }
    }

    /**
     * NamedDataSource subclass that captures whether {@code inferTypes} was
     * invoked and what arguments it received. Lets us assert both the
     * "datasource path was chosen" and the "right parameters were threaded
     * through" parts of the contract.
     */
    private static final class CapturingDataSource extends NamedDataSource {
        final TableDefinition stub;
        volatile boolean called;
        volatile String capturedSchema;
        volatile String capturedQuery;

        CapturingDataSource(String id, TableDefinition stub) {
            // null config => the constructor's defaults branch, which is safe
            // because we never exercise sealing / aliases / converters here.
            super(id, new InMemoryRepo<>(NamedDataSource.class), null);
            this.stub = stub;
        }

        @Override
        protected TableDefinition inferTypes(String schema, String originalQuery, String loadedQuery,
                QueryParameters params) {
            this.called = true;
            this.capturedSchema = schema;
            this.capturedQuery = originalQuery;
            return this.stub;
        }
    }

    /**
     * Minimum-viable JSON config for a {@link NamedSchema}: a single-column
     * table. The TableDefinition contract rejects empty column arrays.
     */
    private static JsonObject schemaConfig(String columnName, String columnType) {
        return new JsonObject().put("columns",
                new JsonArray().add(new JsonObject().put("name", columnName).put("type", columnType)));
    }

    private static NamedSchema schema(String id, String columnName, String columnType) {
        return new NamedSchema(id,
                new InMemoryRepo<>(NamedSchema.class),
                schemaConfig(columnName, columnType));
    }

    /**
     * Build a NamedQuery whose own columns differ from any referenced schema's
     * columns. This lets a test prove which one was actually returned.
     */
    private static NamedQuery query(String id, String queryText, String schemaRef,
            String columnName, String columnType) {
        JsonObject cfg = schemaConfig(columnName, columnType)
                .put("query", queryText);
        if (schemaRef != null) {
            cfg.put("schema", schemaRef);
        }
        return new NamedQuery(id, new InMemoryRepo<>(NamedQuery.class), cfg);
    }

    // ---- Priority step 1: named schema wins outright ---------------------

    @Test(groups = { "unit" })
    public void namedSchemaWinsOverEverything() {
        // When a named schema resolved on rawSchema, the handler must return
        // its columns regardless of what other inputs say — this is the
        // highest-priority branch.
        NamedSchema ns = schema("s_win", "col_named", "Int32");

        // Salt the call with an inline-looking normalizedSchema, a non-null
        // named query, and a datasource that would otherwise be used: none of
        // these should be consulted.
        CapturingDataSource ds = new CapturingDataSource("ds1", TableDefinition.DEFAULT_RESULT_COLUMNS);
        NamedQuery nq = query("q", "SELECT 1", null, "col_query", "Int64");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                ns, "inline Int32, also Int32",
                nq, ds, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, ns.getColumns(),
                "named schema must short-circuit ahead of inline/query/datasource paths");
        // The datasource must not have been consulted at all.
        assertEquals(ds.called, false, "datasource inferTypes must not run when named schema wins");
    }

    // ---- Priority step 2: inline schema (whitespace-bearing) -------------

    @Test(groups = { "unit" })
    public void inlineSchemaParsedWhenNamedSchemaMisses() {
        // No named schema, but the normalized schema string contains a space
        // => parsed via TableDefinition.fromString. The two-column inline must
        // round-trip into a two-column TableDefinition.
        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "a Int32, b String",
                null, null, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertNotNull(out);
        assertEquals(out.getColumns().length, 2);
        assertEquals(out.getColumns()[0].getName(), "a");
        assertEquals(out.getColumns()[1].getName(), "b");
    }

    @Test(groups = { "unit" })
    public void inlineSchemaTakesPrecedenceOverNamedQueryAndDatasource() {
        // Once an inline schema parses, downstream branches must not be
        // consulted: this pins the priority ordering.
        CapturingDataSource ds = new CapturingDataSource("ds2",
                TableDefinition.DEBUG_COLUMNS);
        NamedQuery nq = query("q", "SELECT 1", null, "col_query", "Int64");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "x UInt8",
                nq, ds, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertNotNull(out);
        assertEquals(out.getColumns().length, 1);
        assertEquals(out.getColumns()[0].getName(), "x");
        assertEquals(ds.called, false, "datasource inferTypes must not run after inline schema parse");
    }

    @Test(groups = { "unit" })
    public void normalizedSchemaWithoutSpaceIsNotTreatedAsInline() {
        // A bare token like "users" must NOT be fed to TableDefinition.fromString
        // (it would otherwise return DEFAULT_RESULT_COLUMNS silently — but only
        // by accident). The seam falls through to the next priority instead.
        CapturingDataSource ds = new CapturingDataSource("ds3", TableDefinition.DEBUG_COLUMNS);

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "users",
                null, ds, null,
                "users", "SELECT 1", new QueryParameters());

        assertEquals(ds.called, true, "no-space normalized schema must fall through to datasource");
        assertSame(out, TableDefinition.DEBUG_COLUMNS);
    }

    // ---- Priority step 3: named query lookup -----------------------------

    @Test(groups = { "unit" })
    public void namedQueryWithoutSchemaReturnsQueryColumns() {
        // No named schema, no inline, but a named query resolved: when the
        // query's `schema` field is empty (NamedQuery defaults it to ""), no
        // upgrade happens and the query's own columns are returned.
        NamedQuery nq = query("q_solo", "SELECT 1", null, "only_col", "UInt16");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, null,
                // empty schemaRepo: schemaRepo.get("") will miss
                new InMemoryRepo<>(NamedSchema.class),
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, nq.getColumns());
        assertEquals(out.getColumns()[0].getName(), "only_col");
    }

    @Test(groups = { "unit" })
    public void namedQueryWithReferencedSchemaUpgradesToSchemaColumns() {
        // The query references a schema id that exists in the repo => the
        // referenced schema's columns win over the query's own columns. This
        // is the "named-schema upgrade via named-query reference" branch.
        NamedSchema referenced = schema("ref_schema", "ref_col", "Float32");
        Repository<NamedSchema> schemaRepo = new InMemoryRepo<>(NamedSchema.class);
        schemaRepo.put("ref_schema", referenced);

        NamedQuery nq = query("q_with_schema", "SELECT 1", "ref_schema", "query_col", "Int64");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, null, schemaRepo,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, referenced.getColumns(),
                "referenced named schema must trump the query's own columns");
        assertEquals(out.getColumns()[0].getName(), "ref_col");
    }

    @Test(groups = { "unit" })
    public void namedQueryWithMissingReferencedSchemaFallsBackToQueryColumns() {
        // The query references a schema id that does NOT exist in the repo =>
        // fall back to the query's columns. Pins the null-safety of the
        // referenced-schema lookup.
        Repository<NamedSchema> schemaRepo = new InMemoryRepo<>(NamedSchema.class);
        NamedQuery nq = query("q_missing_ref", "SELECT 1", "nonexistent", "query_col", "Int64");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, null, schemaRepo,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, nq.getColumns());
    }

    @Test(groups = { "unit" })
    public void namedQueryTakesPrecedenceOverDatasourceInference() {
        // Datasource is non-null, but the named query lookup hit: we must NOT
        // call inferTypes. This locks in the priority ordering for the most
        // common production case (named query + jdbc URL).
        CapturingDataSource ds = new CapturingDataSource("ds4", TableDefinition.DEBUG_COLUMNS);
        NamedQuery nq = query("q_win", "SELECT 1", null, "qc", "UInt8");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, ds, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, nq.getColumns());
        assertEquals(ds.called, false, "datasource inferTypes must not run when named query hit");
    }

    // ---- Priority step 4: datasource inferTypes fallback -----------------

    @Test(groups = { "unit" })
    public void datasourceFallbackInvokedWhenAllLookupsMiss() {
        // No named schema, no inline, no named query: the datasource is asked
        // to infer columns. Asserts both the chosen branch and that the parser
        // outputs (rawSchema, normalizedQuery) are threaded through verbatim.
        TableDefinition stub = TableDefinition.DEBUG_COLUMNS;
        CapturingDataSource ds = new CapturingDataSource("ds5", stub);

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, ds, null,
                "my-schema", "SELECT a FROM t", new QueryParameters());

        assertSame(out, stub);
        assertEquals(ds.called, true);
        assertEquals(ds.capturedSchema, "my-schema",
                "rawSchema must be forwarded to ds.getResultColumns unchanged");
        assertEquals(ds.capturedQuery, "SELECT a FROM t",
                "normalizedQuery must be forwarded to ds.getResultColumns unchanged");
    }

    @Test(groups = { "unit" })
    public void datasourceFallbackForwardsTheSameParamsInstance() {
        // The params object handed to the seam must be the exact instance
        // forwarded to inferTypes — no defensive copy, no merge. The handler
        // already mutated it via ds.newQueryParameters(...) before calling
        // the seam, and downstream code expects to see that result.
        final QueryParameters[] captured = new QueryParameters[1];
        NamedDataSource ds = new NamedDataSource("ds6",
                new InMemoryRepo<>(NamedDataSource.class), null) {
            @Override
            protected TableDefinition inferTypes(String schema, String originalQuery, String loadedQuery,
                    QueryParameters params) {
                captured[0] = params;
                return TableDefinition.DEFAULT_RESULT_COLUMNS;
            }
        };

        QueryParameters input = new QueryParameters();

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, ds, null,
                "raw-schema", "SELECT 1", input);

        assertSame(out, TableDefinition.DEFAULT_RESULT_COLUMNS);
        assertSame(captured[0], input,
                "QueryParameters instance must be forwarded verbatim to inferTypes");
    }

    // ---- Priority step 5: nothing matches => null (404 signal) -----------

    @Test(groups = { "unit" })
    public void returnsNullWhenAllLookupsMissAndNoDatasource() {
        // Every priority step misses and there's no datasource to fall back to.
        // The seam must return null so the caller can emit a 404.
        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, null, null,
                "raw", "SELECT 1", new QueryParameters());

        assertNull(out, "null result is the 404 sentinel for the caller");
    }

    @Test(groups = { "unit" })
    public void returnsNullWhenNormalizedSchemaHasNoSpaceAndNothingElseMatches() {
        // Defensive: a non-null but non-inline normalizedSchema must not be
        // mis-parsed; with no named query and no datasource, the seam still
        // returns null.
        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "bare_token",
                null, null, null,
                "bare_token", "SELECT 1", new QueryParameters());

        assertNull(out);
    }

    // ---- Edge cases ------------------------------------------------------

    @Test(groups = { "unit" })
    public void nullNormalizedSchemaIsTolerated() {
        // Production callers always pass parser.getNormalizedSchema() which
        // is non-null, but the seam should still cope with null defensively
        // so a future caller change can't NPE the lookup.
        CapturingDataSource ds = new CapturingDataSource("ds7", TableDefinition.DEBUG_COLUMNS);

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, ds, null,
                "raw", "SELECT 1", new QueryParameters());

        assertSame(out, TableDefinition.DEBUG_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void malformedInlineSchemaPropagatesAsIllegalArgument() {
        // Inline schema parsing failures escape the seam: the handler's outer
        // try/catch maps them to ctx.fail(e). Asserts we don't swallow parse
        // errors silently. Uses the headered form with an unparseable version
        // number so TableDefinition.fromString throws.
        assertThrows(IllegalArgumentException.class, () -> JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "columns format version: NOT_AN_INT\n1 columns:\na Int32",
                null, null, null,
                "raw", "SELECT 1", new QueryParameters()));
    }
}
