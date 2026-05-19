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
 * Unit tests for the stateless handler contracts of
 * {@link JdbcBridgeVerticle}, exercising the extracted pure logic without
 * deploying a full Vert.x HttpServer.
 *
 * <p>The previous attempt at coverage here ({@code JdbcBridgeVerticleHttpIT})
 * deployed the verticle and curl'd it via the loopback interface, which proved
 * sporadically flaky on CI due to a separately-investigable empty-body race on
 * the streaming response path. This test file deliberately avoids that
 * pipeline and asserts only what the handler bodies actually do.
 */
public class JdbcBridgeVerticleHandlersTest {

    // ---- Trivial stateless response bodies -------------------------------
    // These three handlers each amount to "write one constant string and
    // return". The contract worth pinning down is the exact byte sequence on
    // the wire: ClickHouse depends on "Ok.\n", "1\n", and the backtick char.

    @Test(groups = { "unit" })
    public void testPingResponseConstant() {
        // /ping must respond with literally "Ok.\n" (with trailing newline).
        // ClickHouse uses this for health probing in odbc-bridge mode.
        assertEquals(JdbcBridgeVerticle.PING_RESPONSE, "Ok.\n");
    }

    @Test(groups = { "unit" })
    public void testSchemaAllowedResponseConstant() {
        // /schema_allowed must respond with "1\n". The trailing newline is
        // load-bearing for ClickHouse's response parsing.
        assertEquals(JdbcBridgeVerticle.SCHEMA_ALLOWED_RESPONSE, "1\n");
    }

    @Test(groups = { "unit" })
    public void testIdentifierQuoteResponseConstant() {
        // /identifier_quote returns the JDBC identifier-quote character used
        // by the rest of the bridge. Today that's a backtick; pin it here so
        // any change to NamedDataSource.DEFAULT_QUOTE_IDENTIFIER fails this
        // test loudly.
        assertEquals(JdbcBridgeVerticle.IDENTIFIER_QUOTE_RESPONSE, "`");
        assertEquals(JdbcBridgeVerticle.IDENTIFIER_QUOTE_RESPONSE,
                NamedDataSource.DEFAULT_QUOTE_IDENTIFIER);
    }

    // ---- Error handler resolution ----------------------------------------
    // resolveErrorResponse(Throwable, int) is the pure-logic seam extracted
    // from errorHandler(RoutingContext). It encodes two pieces of contract:
    //   1. status <= 0 falls back to 500
    //   2. message null OR failure null falls back to "Internal server error"

    @Test(groups = { "unit" })
    public void testErrorResolution_withFailureAndStatus() {
        // Normal path: a real failure with a real status code propagates
        // both verbatim. This is what ctx.fail(404, new IllegalStateException("..."))
        // produces in handleColumnsInfo / handleQuery.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new IllegalStateException("boom"), 404);
        assertNotNull(r);
        assertEquals(r.status, 404);
        assertEquals(r.body, "boom");
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_failureWithoutMessageFallsBackToDefaultBody() {
        // A Throwable without a message must NOT serialise as the string
        // "null" on the wire (which would be a regression worth catching).
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new RuntimeException(), 502);
        assertEquals(r.status, 502);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_nullFailureUsesDefaultBody() {
        // Vert.x can invoke the failureHandler without a Throwable (e.g.
        // request timeouts where the router just sets the status). Body must
        // still be the default sentinel, never null/empty.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, 408);
        assertEquals(r.status, 408);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_zeroStatusFallsBackTo500() {
        // ctx.statusCode() returns 0 (or negative) when no status was set,
        // which would be an invalid HTTP status on the wire. Must coerce to 500.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(new RuntimeException("kaboom"), 0);
        assertEquals(r.status, JdbcBridgeVerticle.DEFAULT_ERROR_STATUS);
        assertEquals(r.body, "kaboom");
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_negativeStatusFallsBackTo500() {
        // Defensive: any non-positive status code coerces to 500.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, -1);
        assertEquals(r.status, JdbcBridgeVerticle.DEFAULT_ERROR_STATUS);
        assertEquals(r.body, JdbcBridgeVerticle.DEFAULT_ERROR_BODY);
    }

    @Test(groups = { "unit" })
    public void testErrorResolution_nullFailureAndZeroStatusYieldsCanonical500() {
        // The "we have no idea what went wrong" case: produces a canonical
        // 500 / "Internal server error" pair. Useful as a smoke baseline.
        JdbcBridgeVerticle.ErrorResponse r =
                JdbcBridgeVerticle.resolveErrorResponse(null, 0);
        assertEquals(r.status, 500);
        assertEquals(r.body, "Internal server error");
    }

    // ---------- getDataSource: IAE -> null conversion ----------

    /**
     * Tiny test double — implements just enough Repository surface for
     * getDataSource. Different instances throw, return null, or return
     * a real datasource depending on the test.
     */
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
        // BaseRepository.get throws IllegalArgumentException for both
        //  - bare-name miss: "[xxx] does not exist!"
        //  - unknown type prefix: "Unsupported type of NamedDataSource: xxx"
        // The verticle's getDataSource must collapse both to null so the
        // handler's 404 fallback fires rather than 500ing.
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        // Disable adhoc so the null result isn't re-promoted into a fresh
        // adhoc NamedDataSource. Single-arg adhocPolicy constructor isn't
        // visible; use the AdhocPolicy(false, []) ctor directly.
        v.setAdhocPolicy(new com.clickhouse.jdbcbridge.core.AdhocPolicy(
                false, java.util.Collections.emptyList()));

        FakeRepo throwingRepo = new FakeRepo(
                new IllegalArgumentException("NamedDataSource [unknown] does not exist!"),
                null);

        // orCreate=false: returns null after IAE caught.
        assertEquals(v.getDataSource(throwingRepo, "unknown", false), null,
                "IAE from repo.get must convert to null");

        // orCreate=true with adhocPolicy disabled: still returns null
        // (the adhoc-promotion branch is gated).
        assertEquals(v.getDataSource(throwingRepo, "unknown", true), null,
                "IAE + orCreate=true + adhocPolicy.disabled must still yield null");
    }

    @org.testng.annotations.Test(groups = { "unit" })
    public void getDataSource_nonIaeFromRepo_isPropagated() {
        // Only IAE is normalized to null. A different RuntimeException
        // (genuine internal failure) must propagate so the handler
        // surfaces it as a 500 rather than a misleading 404.
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        v.setAdhocPolicy(new com.clickhouse.jdbcbridge.core.AdhocPolicy(
                false, java.util.Collections.emptyList()));

        FakeRepo brokenRepo = new FakeRepo(new RuntimeException("disk full"), null);

        try {
            v.getDataSource(brokenRepo, "anything", false);
            org.testng.Assert.fail("non-IAE must propagate");
        } catch (RuntimeException expected) {
            // The IAE catch is narrow on purpose — any other RuntimeException
            // signals an actual internal failure and must NOT be swallowed.
            org.testng.Assert.assertFalse(expected instanceof IllegalArgumentException);
            org.testng.Assert.assertEquals(expected.getMessage(), "disk full");
        }
    }

    @org.testng.annotations.Test(groups = { "unit" })
    public void getDataSource_repoReturnsNonNull_passesThrough() {
        // Sanity-pin: when repo.get() succeeds with a real datasource,
        // getDataSource returns that datasource (no IAE caught, no
        // adhoc fallback). This is the happy path that runs on every
        // request — pin it.
        JdbcBridgeVerticle v = new JdbcBridgeVerticle();
        com.clickhouse.jdbcbridge.core.NamedDataSource real =
                new com.clickhouse.jdbcbridge.core.NamedDataSource("real", null, null);
        FakeRepo happyRepo = new FakeRepo(null, real);

        assertEquals(v.getDataSource(happyRepo, "real", false), real);
    }
}
