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
package com.clickhouse.jdbcbridge.impl;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

import com.clickhouse.jdbcbridge.core.EngineDefaults;
import com.clickhouse.jdbcbridge.core.QueryParameters;

import io.vertx.core.json.JsonObject;

/**
 * Unit coverage for {@link JdbcDataSource#resolveFetchSize(String, QueryParameters)}:
 * the engine default applies only when the operator left {@code fetch_size}
 * implicit, and an explicit datasource/request value always wins.
 */
public class JdbcDataSourceFetchSizeTest {

    @Test(groups = { "unit" })
    public void oracleDefaultsTo2000_whenNotSet() {
        QueryParameters params = new QueryParameters(); // nothing explicit
        assertEquals(JdbcDataSource.resolveFetchSize(EngineDefaults.DRIVER_ORACLE, params), 2000);
        assertEquals(JdbcDataSource.resolveFetchSize(EngineDefaults.DRIVER_ORACLE_LEGACY, params), 2000);
    }

    @Test(groups = { "unit" })
    public void nonOracleKeepsCompiledDefault_whenNotSet() {
        QueryParameters params = new QueryParameters();
        assertEquals(JdbcDataSource.resolveFetchSize(EngineDefaults.DRIVER_POSTGRES, params),
                QueryParameters.DEFAULT_FETCH_SIZE);
        assertEquals(JdbcDataSource.resolveFetchSize(null, params),
                QueryParameters.DEFAULT_FETCH_SIZE);
    }

    @Test(groups = { "unit" })
    public void explicitDatasourceValueWins_evenForOracle() {
        QueryParameters params = new QueryParameters(
                new JsonObject().put(QueryParameters.PARAM_FETCH_SIZE, 8000));
        assertEquals(JdbcDataSource.resolveFetchSize(EngineDefaults.DRIVER_ORACLE, params), 8000);
    }

    @Test(groups = { "unit" })
    public void explicitRequestValueWins_evenForOracle() {
        QueryParameters params = new QueryParameters("ds?" + QueryParameters.PARAM_FETCH_SIZE + "=512");
        assertEquals(JdbcDataSource.resolveFetchSize(EngineDefaults.DRIVER_ORACLE, params), 512);
    }
}
