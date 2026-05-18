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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Policy gate for "adhoc" JDBC URLs supplied in inbound requests
 * (e.g. {@code jdbc('jdbc:mysql://host/db', 'select 1')}). When disabled,
 * the bridge refuses to construct a datasource from a caller-controlled URI;
 * the named-datasource lookup is the only allowed path.
 *
 * <p>Configuration (boot-time, no hot reload):</p>
 * <ul>
 *   <li>{@code ALLOW_ADHOC_CONNECTIONS} env / {@code jdbc-bridge.adhoc.allow}
 *       sysprop — boolean kill switch. Default: {@code false}.</li>
 *   <li>{@code ADHOC_ALLOWED_JDBC_PREFIXES} env /
 *       {@code jdbc-bridge.adhoc.allowed-prefixes} sysprop — comma-separated
 *       list of {@code jdbc:<vendor>:} prefixes. Empty (default) means
 *       "any prefix" when the kill switch is on. Has no effect when the kill
 *       switch is off.</li>
 * </ul>
 */
public final class AdhocPolicy {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdhocPolicy.class);

    public static AdhocPolicy fromEnvironment() {
        boolean allowed = Boolean.parseBoolean(Utils.getConfiguration(
                "false", "ALLOW_ADHOC_CONNECTIONS", "jdbc-bridge.adhoc.allow"));
        String prefixes = Utils.getConfiguration(
                "", "ADHOC_ALLOWED_JDBC_PREFIXES", "jdbc-bridge.adhoc.allowed-prefixes");
        List<String> allowlist = parsePrefixes(prefixes);
        AdhocPolicy policy = new AdhocPolicy(allowed, allowlist);
        if (allowed) {
            log.warn("Adhoc JDBC connections are ENABLED. Allowed prefixes: {}",
                    allowlist.isEmpty() ? "<any>" : allowlist);
        } else {
            log.info("Adhoc JDBC connections are disabled (secure default).");
        }
        return policy;
    }

    static List<String> parsePrefixes(String csv) {
        if (csv == null || csv.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private final boolean allowed;
    private final List<String> allowedPrefixes;

    public AdhocPolicy(boolean allowed, List<String> allowedPrefixes) {
        this.allowed = allowed;
        this.allowedPrefixes = allowedPrefixes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(allowedPrefixes));
    }

    public boolean isAllowed() {
        return allowed;
    }

    public List<String> getAllowedPrefixes() {
        return allowedPrefixes;
    }

    /**
     * @param uri caller-supplied datasource URI (typically beginning with {@code jdbc:})
     * @return {@code true} if the policy permits constructing an adhoc datasource
     *         from this URI; {@code false} otherwise (kill switch off, or kill
     *         switch on with a non-empty allowlist that the URI doesn't match)
     */
    public boolean allows(String uri) {
        if (!allowed) {
            return false;
        }
        if (allowedPrefixes.isEmpty()) {
            return true;
        }
        if (uri == null) {
            return false;
        }
        for (String prefix : allowedPrefixes) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
