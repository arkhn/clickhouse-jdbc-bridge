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

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

public class QueryParametersTest {
    @Test(groups = { "unit" })
    public void testMergeUri() {
        QueryParameters params = new QueryParameters();

        assertEquals(params.isDebug(), false);
        assertEquals(params.nullAsDefault(), false);
        assertEquals(params.showCustomColumns(), false);
        assertEquals(params.showDatasourceColumn(), false);

        params.merge("ds?" + QueryParameters.PARAM_NULL_AS_DEFAULT);
        assertEquals(params.nullAsDefault(), true);
        params.merge("ds?" + QueryParameters.PARAM_CUSTOM_COLUMNS);
        assertEquals(params.showCustomColumns(), true);
        params.merge("ds?" + QueryParameters.PARAM_DATASOURCE_COLUMN + "&" + QueryParameters.PARAM_DEBUG);
        assertEquals(params.showDatasourceColumn(), true);
        assertEquals(params.isDebug(), true);
    }

    @Test(groups = { "unit" })
    public void isExplicitlySet_tracksProvenance() {
        QueryParameters compiled = new QueryParameters();
        assertFalse(compiled.isExplicitlySet(QueryParameters.PARAM_FETCH_SIZE));
        assertEquals(compiled.getFetchSize(), QueryParameters.DEFAULT_FETCH_SIZE);

        QueryParameters fromJson = new QueryParameters(
                new JsonObject().put(QueryParameters.PARAM_FETCH_SIZE, 2000));
        assertTrue(fromJson.isExplicitlySet(QueryParameters.PARAM_FETCH_SIZE));
        assertEquals(fromJson.getFetchSize(), 2000);
        // a key absent from the JSON stays implicit
        assertFalse(fromJson.isExplicitlySet(QueryParameters.PARAM_BATCH_SIZE));

        QueryParameters fromUri = new QueryParameters("ds?" + QueryParameters.PARAM_FETCH_SIZE + "=512");
        assertTrue(fromUri.isExplicitlySet(QueryParameters.PARAM_FETCH_SIZE));
        assertEquals(fromUri.getFetchSize(), 512);
    }

    /**
     * Reproduces the propagation bug: a datasource-level {@code fetch_size} must
     * survive the per-request merge even though the request carries the compiled
     * default for every key it doesn't mention. Mirrors
     * {@code NamedDataSource.newQueryParameters}: base &lt; datasource &lt; request.
     */
    @Test(groups = { "unit" })
    public void layeredMerge_datasourceValueSurvivesRequestDefaults() {
        QueryParameters datasource = new QueryParameters(
                new JsonObject().put(QueryParameters.PARAM_FETCH_SIZE, 2000));
        QueryParameters request = new QueryParameters("ds"); // no fetch_size in URI

        QueryParameters effective = new QueryParameters().merge(datasource).merge(request);

        assertEquals(effective.getFetchSize(), 2000, "datasource fetch_size must not be clobbered by request default");
        assertTrue(effective.isExplicitlySet(QueryParameters.PARAM_FETCH_SIZE));
    }

    @Test(groups = { "unit" })
    public void layeredMerge_requestOverridesDatasource() {
        QueryParameters datasource = new QueryParameters(
                new JsonObject().put(QueryParameters.PARAM_FETCH_SIZE, 2000));
        QueryParameters request = new QueryParameters("ds?" + QueryParameters.PARAM_FETCH_SIZE + "=512");

        QueryParameters effective = new QueryParameters().merge(datasource).merge(request);

        assertEquals(effective.getFetchSize(), 512, "explicit request fetch_size must win");
    }

    @Test(groups = { "unit" })
    public void layeredMerge_requestExtraVariableOverridesDatasourceExtra() {
        // Arbitrary template variables (not typed knobs) must still layer correctly:
        // a request-level extra overrides a datasource-level extra of the same name.
        QueryParameters datasource = new QueryParameters(new JsonObject().put("region", "eu"));
        QueryParameters request = new QueryParameters("ds?region=us");

        QueryParameters effective = new QueryParameters().merge(datasource).merge(request);

        assertEquals(effective.asVariables().get("region"), "us");
    }

    @Test(groups = { "unit" })
    public void layeredMerge_unsetEverywhereStaysImplicitDefault() {
        QueryParameters datasource = new QueryParameters((JsonObject) null);
        QueryParameters request = new QueryParameters("ds");

        QueryParameters effective = new QueryParameters().merge(datasource).merge(request);

        assertEquals(effective.getFetchSize(), QueryParameters.DEFAULT_FETCH_SIZE);
        assertFalse(effective.isExplicitlySet(QueryParameters.PARAM_FETCH_SIZE),
                "left implicit so engine-specific defaults (e.g. Oracle 2000) can apply");
    }
}