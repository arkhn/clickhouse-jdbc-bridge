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
 * Unit tests for {@link JdbcBridgeVerticle#resolveColumnsTableDef}, the pure-logic
 * seam extracted from {@code handleColumnsInfo}.
 */
public class JdbcBridgeVerticleColumnsInfoTest {

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

    private static final class CapturingDataSource extends NamedDataSource {
        final TableDefinition stub;
        volatile boolean called;
        volatile String capturedSchema;
        volatile String capturedQuery;

        CapturingDataSource(String id, TableDefinition stub) {
            // null config => constructor's defaults branch (safe: no sealing/aliases/converters here)
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

    private static JsonObject schemaConfig(String columnName, String columnType) {
        return new JsonObject().put("columns",
                new JsonArray().add(new JsonObject().put("name", columnName).put("type", columnType)));
    }

    private static NamedSchema schema(String id, String columnName, String columnType) {
        return new NamedSchema(id,
                new InMemoryRepo<>(NamedSchema.class),
                schemaConfig(columnName, columnType));
    }

    private static NamedQuery query(String id, String queryText, String schemaRef,
            String columnName, String columnType) {
        JsonObject cfg = schemaConfig(columnName, columnType)
                .put("query", queryText);
        if (schemaRef != null) {
            cfg.put("schema", schemaRef);
        }
        return new NamedQuery(id, new InMemoryRepo<>(NamedQuery.class), cfg);
    }

    @Test(groups = { "unit" })
    public void namedSchemaWinsOverEverything() {
        NamedSchema ns = schema("s_win", "col_named", "Int32");
        CapturingDataSource ds = new CapturingDataSource("ds1", TableDefinition.DEFAULT_RESULT_COLUMNS);
        NamedQuery nq = query("q", "SELECT 1", null, "col_query", "Int64");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                ns, "inline Int32, also Int32",
                nq, ds, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, ns.getColumns(),
                "named schema must short-circuit ahead of inline/query/datasource paths");
        assertEquals(ds.called, false, "datasource inferTypes must not run when named schema wins");
    }

    @Test(groups = { "unit" })
    public void inlineSchemaParsedWhenNamedSchemaMisses() {
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
        // A bare token like "users" must NOT be fed to TableDefinition.fromString (would silently
        // return DEFAULT_RESULT_COLUMNS). Fall through to datasource instead.
        CapturingDataSource ds = new CapturingDataSource("ds3", TableDefinition.DEBUG_COLUMNS);

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "users",
                null, ds, null,
                "users", "SELECT 1", new QueryParameters());

        assertEquals(ds.called, true, "no-space normalized schema must fall through to datasource");
        assertSame(out, TableDefinition.DEBUG_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void namedQueryWithoutSchemaReturnsQueryColumns() {
        NamedQuery nq = query("q_solo", "SELECT 1", null, "only_col", "UInt16");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, null,
                new InMemoryRepo<>(NamedSchema.class),
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, nq.getColumns());
        assertEquals(out.getColumns()[0].getName(), "only_col");
    }

    @Test(groups = { "unit" })
    public void namedQueryWithReferencedSchemaUpgradesToSchemaColumns() {
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
        CapturingDataSource ds = new CapturingDataSource("ds4", TableDefinition.DEBUG_COLUMNS);
        NamedQuery nq = query("q_win", "SELECT 1", null, "qc", "UInt8");

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                nq, ds, null,
                "raw-schema", "SELECT 1", new QueryParameters());

        assertSame(out, nq.getColumns());
        assertEquals(ds.called, false, "datasource inferTypes must not run when named query hit");
    }

    @Test(groups = { "unit" })
    public void datasourceFallbackInvokedWhenAllLookupsMiss() {
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
        // The params instance must be forwarded verbatim — handler already mutated it via
        // ds.newQueryParameters(...), downstream expects to see that result.
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

    @Test(groups = { "unit" })
    public void returnsNullWhenAllLookupsMissAndNoDatasource() {
        // null result is the 404 sentinel for the caller.
        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, null, null,
                "raw", "SELECT 1", new QueryParameters());

        assertNull(out, "null result is the 404 sentinel for the caller");
    }

    @Test(groups = { "unit" })
    public void returnsNullWhenNormalizedSchemaHasNoSpaceAndNothingElseMatches() {
        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "bare_token",
                null, null, null,
                "bare_token", "SELECT 1", new QueryParameters());

        assertNull(out);
    }

    @Test(groups = { "unit" })
    public void nullNormalizedSchemaIsTolerated() {
        CapturingDataSource ds = new CapturingDataSource("ds7", TableDefinition.DEBUG_COLUMNS);

        TableDefinition out = JdbcBridgeVerticle.resolveColumnsTableDef(
                null, null,
                null, ds, null,
                "raw", "SELECT 1", new QueryParameters());

        assertSame(out, TableDefinition.DEBUG_COLUMNS);
    }

    @Test(groups = { "unit" })
    public void malformedInlineSchemaPropagatesAsIllegalArgument() {
        // Headered form with unparseable version number => TableDefinition.fromString throws.
        assertThrows(IllegalArgumentException.class, () -> JdbcBridgeVerticle.resolveColumnsTableDef(
                null, "columns format version: NOT_AN_INT\n1 columns:\na Int32",
                null, null, null,
                "raw", "SELECT 1", new QueryParameters()));
    }
}
