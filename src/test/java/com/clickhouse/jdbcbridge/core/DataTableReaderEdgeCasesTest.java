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
import static org.testng.Assert.assertTrue;

import java.util.TimeZone;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.DataTableReaderTest.InMemoryReader;
import com.clickhouse.jdbcbridge.core.DataTableReaderTest.RecordingResponseWriter;

/**
 * Edge-case tests for {@link DataTableReader#process} covering branches
 * the existing smoke tests skip:
 *
 * <ul>
 *   <li>"datasource" virtual column path
 *   ({@code params.showDatasourceColumn()})</li>
 *   <li>custom-columns path ({@code params.showCustomColumns()})</li>
 *   <li>nullAsDefault — null cells replaced with the column's default
 *       value rather than the null marker</li>
 *   <li>unknown column — request asks for a column missing from both
 *       result set and custom columns; must throw IAE naming both the
 *       missing column and the available ones</li>
 *   <li>skipRows position-mode (vs offset-mode); negative position +
 *       negative offset error paths; overshoot when fewer rows than
 *       requested</li>
 * </ul>
 */
public class DataTableReaderEdgeCasesTest {

    private static ColumnDefinition strCol(String name, boolean nullable) {
        return new ColumnDefinition(name, DataType.Str, nullable,
                DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE);
    }

    // ---------- datasource virtual column ----------

    @Test(groups = { "unit" })
    public void process_datasourceVirtualColumn_emittedFromDataSourceId() {
        // requestColumns includes a column named exactly
        // TableDefinition.COLUMN_DATASOURCE; with params.show_datasource_column=true
        // the reader emits the ds id directly without touching the result set.
        ColumnDefinition[] resultCols = new ColumnDefinition[] { strCol("c", false) };
        ColumnDefinition[] requestCols = new ColumnDefinition[] {
                strCol(TableDefinition.COLUMN_DATASOURCE, false),
                strCol("c", false),
        };
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "row1" },
                new Object[] { "row2" },
        });
        RecordingResponseWriter writer = new RecordingResponseWriter();

        // Use the URI-merge path to flip show_datasource_column on (the
        // JSON-merge skips PARAM_DEBUG only, but we follow the same idiom
        // here for consistency).
        QueryParameters p = new QueryParameters("?datasource_column=true");

        reader.process("my-ds-id", requestCols, new ColumnDefinition[0], resultCols,
                new DefaultValues(), TimeZone.getDefault(), p, writer);

        // 2 rows x 2 columns = 4 cells. The datasource column is virtual
        // (no isNull/read on it), so isNullCalls=0 and readCalls=2 (only the
        // real column).
        assertEquals(reader.readCalls.get(), 2,
                "read() must be called only for the real (non-virtual) column");
        assertTrue(writer.totalBytes > 0, "expected datasource-id bytes to be in the response");
    }

    // ---------- custom columns ----------

    @Test(groups = { "unit" })
    public void process_customColumnsCarriedFromDatasourceConfig() {
        ColumnDefinition[] resultCols = new ColumnDefinition[] { strCol("c", false) };
        // Custom columns defined at the ds level — typically "env=prod" tags.
        ColumnDefinition[] customCols = new ColumnDefinition[] {
                new ColumnDefinition("env", DataType.Str, false,
                        DataType.DEFAULT_LENGTH, DataType.DEFAULT_PRECISION, DataType.DEFAULT_SCALE,
                        null, "prod", null),
        };
        ColumnDefinition[] requestCols = new ColumnDefinition[] {
                strCol("env", false),
                strCol("c", false),
        };
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "row1" },
        });
        RecordingResponseWriter writer = new RecordingResponseWriter();

        QueryParameters p = new QueryParameters("?custom_columns=true");

        reader.process("ds", requestCols, customCols, resultCols,
                new DefaultValues(), TimeZone.getDefault(), p, writer);

        // Custom columns are also virtual — read() only fires for the real column.
        assertEquals(reader.readCalls.get(), 1);
        assertTrue(writer.totalBytes > 0);
    }

    // ---------- nullAsDefault ----------

    @Test(groups = { "unit" })
    public void process_nullAsDefault_substitutesDefaultForNullCells() {
        ColumnDefinition[] cols = new ColumnDefinition[] { strCol("c", true) };
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { null },
                new Object[] { "x" },
        });
        RecordingResponseWriter writer = new RecordingResponseWriter();

        QueryParameters p = new QueryParameters("?null_as_default=true");

        reader.process("ds", cols, new ColumnDefinition[0], cols,
                new DefaultValues(), TimeZone.getDefault(), p, writer);

        // Both rows produce output; the null row goes through writeDefaultValue
        // (non-null marker + default bytes), the non-null row goes through read().
        assertEquals(reader.isNullCalls.get(), 2,
                "isNull must be consulted on every cell of a nullable column");
        // Only the non-null row hits read() — the null row hits writeDefaultValue.
        assertEquals(reader.readCalls.get(), 1);
        assertTrue(writer.totalBytes > 0);
    }

    // ---------- unknown column error ----------

    @Test(groups = { "unit" })
    public void process_unknownColumn_throwsWithAvailableColumnsList() {
        // requestColumns asks for "missing" which isn't in resultCols and isn't
        // in customCols either. The reader must throw IAE that names both the
        // missing column and the available result columns — this is the
        // diagnostic ClickHouse-side users see when their column list doesn't
        // match the underlying schema.
        ColumnDefinition[] resultCols = new ColumnDefinition[] {
                strCol("first", false), strCol("second", false),
        };
        ColumnDefinition[] requestCols = new ColumnDefinition[] { strCol("missing", false) };
        InMemoryReader reader = new InMemoryReader(new Object[0][0]);
        RecordingResponseWriter writer = new RecordingResponseWriter();

        try {
            reader.process("ds", requestCols, new ColumnDefinition[0], resultCols,
                    new DefaultValues(), TimeZone.getDefault(),
                    new QueryParameters((io.vertx.core.json.JsonObject[]) new io.vertx.core.json.JsonObject[0]),
                    writer);
            org.testng.Assert.fail("expected IllegalArgumentException for unknown column");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("missing"),
                    "error must name the missing column: " + e.getMessage());
            assertTrue(e.getMessage().contains("first") && e.getMessage().contains("second"),
                    "error must list available result columns: " + e.getMessage());
        }
    }

    // ---------- skipRows: position mode ----------

    @Test(groups = { "unit" })
    public void skipRows_positionMode_advancesToAbsoluteRow() {
        // position=2 means "start AT row 2" (1-based); skipRows advances the
        // cursor through 2 rows. Returning rowCount = 2 (matches.
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" },
                new Object[] { "b" },
                new Object[] { "c" },
                new Object[] { "d" },
        });

        QueryParameters p = new QueryParameters("?position=2");
        int skipped = reader.skipRows(p);

        assertEquals(skipped, 2,
                "position=2 must advance the cursor 2 rows before streaming starts");
    }

    @Test(groups = { "unit" })
    public void skipRows_negativePosition_isRejected() {
        InMemoryReader reader = new InMemoryReader(new Object[][] { new Object[] { "a" } });
        QueryParameters p = new QueryParameters("?position=-1");

        assertThrows(IllegalArgumentException.class, () -> reader.skipRows(p));
    }

    @Test(groups = { "unit" })
    public void skipRows_negativeOffset_isRejected() {
        InMemoryReader reader = new InMemoryReader(new Object[][] { new Object[] { "a" } });
        QueryParameters p = new QueryParameters("?offset=-1");

        assertThrows(IllegalArgumentException.class, () -> reader.skipRows(p));
    }

    @Test(groups = { "unit" })
    public void skipRows_positionBeyondRowCount_throwsIllegalState() {
        // Requested position is past the end of the result set — the reader
        // must throw rather than silently returning fewer rows skipped, so
        // callers can distinguish "position not reachable" from "empty result".
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" },
                new Object[] { "b" },
        });
        QueryParameters p = new QueryParameters("?position=5");

        assertThrows(IllegalStateException.class, () -> reader.skipRows(p));
    }

    @Test(groups = { "unit" })
    public void skipRows_offsetMode_skipsExactlyOffsetRows() {
        // offset=2 means "skip 2 rows, start at row 3". skipRows returns 2.
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" },
                new Object[] { "b" },
                new Object[] { "c" },
                new Object[] { "d" },
        });

        QueryParameters p = new QueryParameters("?offset=2");
        int skipped = reader.skipRows(p);

        assertEquals(skipped, 2);
    }

    @Test(groups = { "unit" })
    public void skipRows_offsetBeyondRowCount_throwsIllegalState() {
        InMemoryReader reader = new InMemoryReader(new Object[][] {
                new Object[] { "a" },
                new Object[] { "b" },
        });
        QueryParameters p = new QueryParameters("?offset=5");

        assertThrows(IllegalStateException.class, () -> reader.skipRows(p));
    }

    @Test(groups = { "unit" })
    public void skipRows_nullParameters_returnsZero() {
        InMemoryReader reader = new InMemoryReader(new Object[][] { new Object[] { "a" } });
        assertEquals(reader.skipRows(null), 0);
    }
}
