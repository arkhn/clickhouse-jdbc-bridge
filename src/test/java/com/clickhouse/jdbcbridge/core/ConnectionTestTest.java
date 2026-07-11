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
    public void message_isNonEmptyForEveryCode_andSecretFree() {
        for (String code : new String[] { "ok", "auth", "host", "tls", "driver", "generic" }) {
            String msg = ConnectionTest.message(code);
            assertNotNull(msg);
            assertFalse(msg.isEmpty());
            assertFalse(msg.toLowerCase().contains("password="));
        }
    }
}
