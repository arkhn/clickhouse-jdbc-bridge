/*
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

import java.util.Locale;

/**
 * Classifies a failed connection attempt (from the {@code POST /test} endpoint)
 * into a small, stable set of codes with friendly, secret-free messages. The
 * raw driver exception is a multi-line Java blob and may echo the credential-
 * bearing URL, so it is never surfaced — only the code + message are returned.
 */
public final class ConnectionTest {
    private ConnectionTest() {
    }

    /** Walk the cause chain and map it to auth / host / tls / driver / generic. */
    public static String classify(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        while (c != null) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append('\n');
            }
            sb.append(c.getClass().getName()).append('\n');
            if (c.getCause() == c) {
                break;
            }
            c = c.getCause();
        }
        String m = sb.toString().toLowerCase(Locale.ROOT);

        if (m.contains("authentication failed") || m.contains("access denied")
                || m.contains("login failed") || m.contains("password")) {
            return "auth";
        }
        if (m.contains("pkix") || m.contains("unable to find valid certification")
                || m.contains("certificate") || m.contains("sslhandshake")
                || m.contains("ssl handshake")) {
            return "tls";
        }
        if (m.contains("unknownhost") || m.contains("connection refused")
                || m.contains("no route to host") || m.contains("timed out")
                || m.contains("connect timed out")
                || m.contains("connection is not available")) {
            return "host";
        }
        if (m.contains("no suitable driver") || m.contains("classnotfound")) {
            return "driver";
        }
        return "generic";
    }

    public static String message(String code) {
        switch (code) {
            case "auth":
                return "Authentication failed - check the username and password.";
            case "host":
                return "Could not reach the server - check the host, port and network.";
            case "tls":
                return "TLS certificate is not trusted - check the server certificate "
                        + "or provide its CA certificate.";
            case "driver":
                return "No JDBC driver is available for this data source type on the bridge.";
            default:
                return "Connection failed - check the connection settings.";
        }
    }
}
