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

import com.clickhouse.jdbcbridge.core.NamedDataSource;

import org.testng.annotations.Test;

/**
 * Unit tests for the stateless handler contracts of {@link JdbcBridgeVerticle},
 * exercising extracted pure logic without deploying a full Vert.x HttpServer.
 */
public class JdbcBridgeVerticleHandlersTest {

    @Test(groups = { "unit" })
    public void testPingResponseConstant() {
        // ClickHouse health-probes /ping in odbc-bridge mode; pin the exact bytes.
        assertEquals(JdbcBridgeVerticle.PING_RESPONSE, "Ok.\n");
    }

    @Test(groups = { "unit" })
    public void testSchemaAllowedResponseConstant() {
        // Trailing newline is load-bearing for ClickHouse's response parsing.
        assertEquals(JdbcBridgeVerticle.SCHEMA_ALLOWED_RESPONSE, "1\n");
    }

    @Test(groups = { "unit" })
    public void testIdentifierQuoteResponseConstant() {
        // Pin so any change to NamedDataSource.DEFAULT_QUOTE_IDENTIFIER fails loudly.
        assertEquals(JdbcBridgeVerticle.IDENTIFIER_QUOTE_RESPONSE, "`");
        assertEquals(JdbcBridgeVerticle.IDENTIFIER_QUOTE_RESPONSE,
                NamedDataSource.DEFAULT_QUOTE_IDENTIFIER);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_withFailureAndStatus() {
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new IllegalStateException("boom"), 404);
        assertNotNull(r);
        assertEquals(r.status, 404);
        assertEquals(r.body, "boom");
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_failureWithoutMessageFallsBackToDefaultBody() {
        // Regression catch: Throwable without message must NOT serialise as "null" on the wire.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new RuntimeException(), 502);
        assertEquals(r.status, 502);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_nullFailureUsesDefaultBody() {
        // Vert.x can invoke failureHandler without a Throwable (e.g. request timeouts).
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, 408);
        assertEquals(r.status, 408);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_zeroStatusFallsBackTo500() {
        // ctx.statusCode() returns 0 when no status set — must coerce to 500.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new RuntimeException("kaboom"), 0);
        assertEquals(r.status, JdbcBridgeVerticle.DEFAULT_ERROR_STATUS);
        assertEquals(r.body, "kaboom");
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_negativeStatusFallsBackTo500() {
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, -1);
        assertEquals(r.status, JdbcBridgeVerticle.DEFAULT_ERROR_STATUS);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_nullFailureAndZeroStatusYieldsCanonical500() {
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, 0);
        assertEquals(r.status, 500);
        assertEquals(r.body, "Internal server error");
    }

    private static final class FakeRepo
            implements com.clickhouse.jdbcbridge.core.Repository<com.clickhouse.jdbcbridge.core.NamedDataSource> {
        private final RuntimeException throwOnGet;
        private final com.clickhouse.jdbcbridge.core.NamedDataSource ds;

        FakeRepo(RuntimeException throwOnGet, com.clickhouse.jdbcbridge.core.NamedDataSource ds) {
            this.throwOnGet = throwOnGet;
            this.ds = ds;
        }

        @Override public Class<com.clickhouse.jdbcbridge.core.NamedDataSource> getEntityClass() {
            return com.clickhouse.jdbcbridge.core.NamedDataSource.class;
        }
        @Override public boolean accept(Class<?> c) {
            return com.clickhouse.jdbcbridge.core.NamedDataSource.class.equals(c);
        }
        @Override public String resolve(String name) { return name; }
        @Override public java.util.List<com.clickhouse.jdbcbridge.core.UsageStats> getUsageStats() {
            return java.util.Collections.emptyList();
        }
        @Override public void registerType(String type,
                com.clickhouse.jdbcbridge.core.Extension<com.clickhouse.jdbcbridge.core.NamedDataSource> ext) { }
        @Override public void put(String id, com.clickhouse.jdbcbridge.core.NamedDataSource entity) { }
        @Override public com.clickhouse.jdbcbridge.core.NamedDataSource get(String id) {
            if (throwOnGet != null) throw throwOnGet;
            return ds;
        }
    }

    @org.testng.annotations.Test(groups = { "unit" })
    public void getDataSource_iaeFromRepo_isConvertedToNull() {
        // BaseRepository.get throws IAE for bare-name miss and unknown type prefix.
        // getDataSource must collapse both to null so the handler's 404 fallback fires.
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        // Disable adhoc so null isn't re-promoted into a fresh adhoc NamedDataSource.
        v.setAdhocPolicy(new com.clickhouse.jdbcbridge.core.AdhocPolicy(
                false, java.util.Collections.emptyList()));

        FakeRepo throwingRepo = new FakeRepo(
                new IllegalArgumentException("NamedDataSource [unknown] does not exist!"),
                null);

        assertEquals(v.getDataSource(throwingRepo, "unknown", false), null,
                "IAE from repo.get must convert to null");

        assertEquals(v.getDataSource(throwingRepo, "unknown", true), null,
                "IAE + orCreate=true + adhocPolicy.disabled must still yield null");
    }

    @org.testng.annotations.Test(groups = { "unit" })
    public void getDataSource_nonIaeFromRepo_isPropagated() {
        // Only IAE normalizes to null. Other RuntimeExceptions must propagate (500, not 404).
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        v.setAdhocPolicy(new com.clickhouse.jdbcbridge.core.AdhocPolicy(
                false, java.util.Collections.emptyList()));

        FakeRepo brokenRepo = new FakeRepo(new RuntimeException("disk full"), null);

        try {
            v.getDataSource(brokenRepo, "anything", false);
            org.testng.Assert.fail("non-IAE must propagate");
        } catch (RuntimeException expected) {
            org.testng.Assert.assertFalse(expected instanceof IllegalArgumentException);
            org.testng.Assert.assertEquals(expected.getMessage(), "disk full");
        }
    }

    @org.testng.annotations.Test(groups = { "unit" })
    public void getDataSource_repoReturnsNonNull_passesThrough() {
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        com.clickhouse.jdbcbridge.core.NamedDataSource real =
                new com.clickhouse.jdbcbridge.core.NamedDataSource("real", null, null);
        FakeRepo happyRepo = new FakeRepo(null, real);

        assertEquals(v.getDataSource(happyRepo, "real", false), real);
    }
}
