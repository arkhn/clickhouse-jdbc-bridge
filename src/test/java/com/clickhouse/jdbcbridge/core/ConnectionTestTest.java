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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import javax.net.ssl.SSLHandshakeException;

import org.testng.annotations.Test;

public class ConnectionTestTest {

    @Test(groups = { "unit" })
    public void classify_authFromMessage() {
        assertEquals(ConnectionTest.classify(
                new SQLException("FATAL: password authentication failed for user \"x\"")),
                "auth");
        assertEquals(ConnectionTest.classify(new SQLException("Access denied for user")), "auth");
        assertEquals(ConnectionTest.classify(new SQLException("Login failed for user 'sa'")), "auth");
    }

    @Test(groups = { "unit" })
    public void classify_tlsFromPkixAndHandshake() {
        assertEquals(ConnectionTest.classify(new SQLException(
                "PKIX path building failed: unable to find valid certification path")), "tls");
        assertEquals(ConnectionTest.classify(
                new RuntimeException(new SSLHandshakeException("handshake_failure"))), "tls");
    }

    @Test(groups = { "unit" })
    public void classify_hostFromNetworkErrors() {
        assertEquals(ConnectionTest.classify(new UnknownHostException("db.internal")), "host");
        assertEquals(ConnectionTest.classify(new ConnectException("Connection refused")), "host");
        // HikariCP masks the real cause behind this message when it times out.
        assertEquals(ConnectionTest.classify(new SQLException(
                "connection-test - Connection is not available, request timed out")), "host");
    }

    @Test(groups = { "unit" })
    public void classify_driverMissing() {
        assertEquals(ConnectionTest.classify(
                new SQLException("No suitable driver found for jdbc:weird://x")), "driver");
        assertEquals(ConnectionTest.classify(new IllegalStateException(
                "Not able to find suitable driver for datasource: x")), "driver");
    }

    @Test(groups = { "unit" })
    public void classify_walksTheCauseChain() {
        // The real cause is buried under two wrappers, as HikariCP/JDBC nest them.
        Throwable buried = new RuntimeException("pool init failed",
                new SQLException("could not connect", new UnknownHostException("nope")));
        assertEquals(ConnectionTest.classify(buried), "host");
    }

    @Test(groups = { "unit" })
    public void classify_genericFallback() {
        assertEquals(ConnectionTest.classify(new RuntimeException("something odd")), "generic");
        // must not blow up on a null message
        assertEquals(ConnectionTest.classify(new RuntimeException((String) null)), "generic");
    }

    @Test(groups = { "unit" })
    public void classify_tlsFromBareCertificateAndSpacedHandshake() {
        // reaches the bare "certificate" operand (no pkix / no "sslhandshake" token)
        assertEquals(ConnectionTest.classify(
                new SQLException("server certificate was rejected by the client")), "tls");
        // reaches the spaced "ssl handshake" operand (distinct from the token form)
        assertEquals(ConnectionTest.classify(
                new SQLException("Remote host terminated the SSL handshake")), "tls");
    }

    @Test(groups = { "unit" })
    public void classify_hostFromNoRouteToHost() {
        assertEquals(ConnectionTest.classify(new SQLException("No route to host")), "host");
    }

    @Test(groups = { "unit" })
    public void classify_hostFromDnsResolutionFailure() {
        // DNS failure: the host name does not resolve. The driver surfaces this
        // as an UnknownHostException, usually buried under the pool/SQL wrappers.
        assertEquals(ConnectionTest.classify(
                new UnknownHostException("db.internal.invalid: Name or service not known")),
                "host");
        Throwable wrapped = new SQLException("Unable to connect to server",
                new UnknownHostException("no-such-host.example"));
        assertEquals(ConnectionTest.classify(wrapped), "host");
    }

    @Test(groups = { "unit" })
    public void classify_hostFromConnectAndReadTimeouts() {
        // Connect timeout (TCP SYN never answered) and socket read timeout both
        // arrive as SocketTimeoutException with a "timed out" message.
        assertEquals(ConnectionTest.classify(
                new SocketTimeoutException("connect timed out")), "host");
        assertEquals(ConnectionTest.classify(
                new SocketTimeoutException("Read timed out")), "host");
        // ...and the same wrapped in the JDBC/pool exception chain.
        Throwable wrapped = new SQLException("could not establish connection",
                new SocketTimeoutException("connect timed out"));
        assertEquals(ConnectionTest.classify(wrapped), "host");
    }

    @Test(groups = { "unit" })
    public void classify_driverFromClassNotFoundAndNoDriver() {
        // the exception's own class name carries the "classnotfound" token
        assertEquals(ConnectionTest.classify(
                new ClassNotFoundException("org.postgresql.Driver")), "driver");
        assertEquals(ConnectionTest.classify(
                new SQLException("no driver available for this url")), "driver");
    }

    @Test(groups = { "unit" })
    public void classify_stopsOnSelfReferencingCause() {
        // A cause chain that points at itself must terminate via the
        // c.getCause() == c guard rather than loop forever. Java forbids
        // initCause(this), so model the self-reference with an override.
        Throwable selfCause = new RuntimeException("weird self-referential failure") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(ConnectionTest.classify(selfCause), "generic");
    }

    @Test(groups = { "unit" })
    public void message_isNonEmptyForEveryCode_andSecretFree() {
        for (String code : new String[] { "ok", "auth", "host", "tls", "driver", "generic" }) {
            String msg = ConnectionTest.message(code);
            assertNotNull(msg);
            assertFalse(msg.isEmpty());
            assertFalse(msg.toLowerCase().contains("password="));
        }
    }
}
