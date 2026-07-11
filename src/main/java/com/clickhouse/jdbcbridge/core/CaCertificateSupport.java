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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Properties;

/**
 * Wires a per-datasource CA certificate (an inline PEM string on the datasource
 * config, key {@code caCertificate}) into the JDBC driver's trust so a
 * self-signed / private-CA TLS server can be reached.
 *
 * <p>There is no cross-vendor JDBC property for a trust anchor, so the PEM is
 * materialized to a temp file and the vendor's own "root cert" / "trust store"
 * connection property is injected as a HikariCP {@code dataSource.*} property
 * (passed straight through to the driver):
 *
 * <ul>
 *   <li>PostgreSQL / ClickHouse — {@code sslrootcert} (PEM path)</li>
 *   <li>MariaDB / MySQL — {@code serverSslCert} (PEM path)</li>
 *   <li>SQL Server — {@code trustStore} + {@code trustStorePassword} +
 *       {@code trustStoreType=PKCS12} (a PKCS12 truststore built from the PEM)</li>
 * </ul>
 *
 * TLS itself (sslmode/encrypt/ssl=…) is expected to already be requested via the
 * JDBC URL; this class only supplies the trust anchor.
 */
public final class CaCertificateSupport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaCertificateSupport.class);

    public static final String CONF_CA_CERTIFICATE = "caCertificate";

    private static final String DS_PREFIX = "dataSource.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaCertificateSupport() {
    }

    /**
     * Materialize {@code pem} and inject the vendor-appropriate trust property
     * into {@code props}. Best-effort: on any failure it logs and leaves props
     * untouched (the connection then fails at handshake time, which is the
     * correct, visible outcome rather than silently trusting everything).
     *
     * @param id           datasource id (used only for temp-file naming / logs)
     * @param pem          the CA certificate(s), PEM encoded
     * @param jdbcUrl      the datasource jdbcUrl (drives vendor detection)
     * @param props        HikariCP properties being assembled (mutated in place)
     */
    public static void apply(String id, String pem, String jdbcUrl, Properties props) {
        if (pem == null || pem.trim().isEmpty()) {
            return;
        }
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase();
        try {
            if (url.startsWith("jdbc:postgresql") || url.startsWith("jdbc:clickhouse")) {
                String pemPath = writePem(id, pem);
                props.setProperty(DS_PREFIX + "sslrootcert", pemPath);
                log.info("Datasource id={}: trusting inline CA via sslrootcert={}", id, pemPath);
            } else if (url.startsWith("jdbc:mariadb") || url.startsWith("jdbc:mysql")) {
                String pemPath = writePem(id, pem);
                props.setProperty(DS_PREFIX + "serverSslCert", pemPath);
                log.info("Datasource id={}: trusting inline CA via serverSslCert={}", id, pemPath);
            } else if (url.startsWith("jdbc:sqlserver")) {
                char[] password = randomPassword();
                String storePath = writeTrustStore(id, pem, password);
                props.setProperty(DS_PREFIX + "trustStore", storePath);
                props.setProperty(DS_PREFIX + "trustStorePassword", new String(password));
                props.setProperty(DS_PREFIX + "trustStoreType", "PKCS12");
                log.info("Datasource id={}: trusting inline CA via trustStore={}", id, storePath);
            } else {
                log.warn("Datasource id={}: caCertificate is set but is not supported for jdbcUrl '{}'; "
                        + "the certificate was ignored.", id, jdbcUrl);
            }
        } catch (Exception e) {
            log.error("Datasource id={}: failed to apply inline caCertificate: {}", id, e.getMessage(), e);
        }
    }

    private static Collection<? extends Certificate> parseCertificates(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = factory
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate found in caCertificate");
        }
        return certs;
    }

    private static String writePem(String id, String pem) throws Exception {
        // Validate before writing so a bogus PEM fails loudly instead of
        // producing a file the driver later rejects with an opaque error.
        parseCertificates(pem);
        File file = File.createTempFile("chjb-ca-" + sanitize(id) + "-", ".pem");
        file.deleteOnExit();
        Files.write(file.toPath(), pem.getBytes(StandardCharsets.UTF_8));
        return file.getAbsolutePath();
    }

    private static String writeTrustStore(String id, String pem, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        int index = 0;
        for (Certificate cert : parseCertificates(pem)) {
            store.setCertificateEntry("ca-" + index++, cert);
        }
        File file = File.createTempFile("chjb-ca-" + sanitize(id) + "-", ".p12");
        file.deleteOnExit();
        try (OutputStream out = new FileOutputStream(file)) {
            store.store(out, password);
        }
        return file.getAbsolutePath();
    }

    private static char[] randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(Integer.toHexString(b & 0xff));
        }
        return sb.toString().toCharArray();
    }

    private static String sanitize(String id) {
        return id == null ? "ds" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
