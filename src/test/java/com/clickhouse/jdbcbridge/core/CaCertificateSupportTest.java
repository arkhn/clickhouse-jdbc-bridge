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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Properties;

import org.testng.annotations.Test;

public class CaCertificateSupportTest {

    // A real self-signed X.509 (EC P-256) certificate, PEM encoded.
    private static final String PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBgzCCASmgAwIBAgIUDFQOdTGyGASy4ldToGamGLAgzD8wCgYIKoZIzj0EAwIw
            FzEVMBMGA1UEAwwMY2hqYi10ZXN0LWNhMB4XDTI2MDcxMTE4MTUwNVoXDTM2MDcw
            ODE4MTUwNVowFzEVMBMGA1UEAwwMY2hqYi10ZXN0LWNhMFkwEwYHKoZIzj0CAQYI
            KoZIzj0DAQcDQgAEeF1yjpJE2/hgg/0Dr0JI2nlAV4q/JC4FpxSft0VHVN44w2Q7
            jwXr5Rqv9tjdFUok559HjPdTOXJAfI6si0wl/qNTMFEwHQYDVR0OBBYEFBPbhiIq
            uKtExhrLG3n+Grt66wAbMB8GA1UdIwQYMBaAFBPbhiIquKtExhrLG3n+Grt66wAb
            MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgGI5azYK3muyn9dgw
            iD2crcFTq+W+swodQ4Ius4FNjrUCIQDo0NQq1eLq+zGgYWsaq6nFv07ldjRYP9It
            42R62sTMZQ==
            -----END CERTIFICATE-----
            """;

    @Test(groups = { "unit" })
    public void postgres_setsSslRootCertPemPath() throws Exception {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:postgresql://h:5432/db", p);
        String path = p.getProperty("dataSource.sslrootcert");
        assertNotNull(path, "sslrootcert must be set for postgres");
        assertTrue(new File(path).isFile());
        assertEquals(new String(Files.readAllBytes(Paths.get(path))), PEM);
    }

    @Test(groups = { "unit" })
    public void clickhouse_setsSslRootCert() {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:clickhouse://h:8443/default", p);
        assertNotNull(p.getProperty("dataSource.sslrootcert"));
    }

    @Test(groups = { "unit" })
    public void mariadb_setsServerSslCert() {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:mariadb://h:3306/db", p);
        assertNotNull(p.getProperty("dataSource.serverSslCert"));
        assertNull(p.getProperty("dataSource.sslrootcert"));
    }

    @Test(groups = { "unit" })
    public void sqlserver_buildsPkcs12TrustStore() throws Exception {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:sqlserver://h:1433;databaseName=db", p);

        String store = p.getProperty("dataSource.trustStore");
        String pwd = p.getProperty("dataSource.trustStorePassword");
        assertNotNull(store, "trustStore must be set for sqlserver");
        assertEquals(p.getProperty("dataSource.trustStoreType"), "PKCS12");
        assertNotNull(pwd);
        assertFalse(pwd.isEmpty());

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store)) {
            ks.load(in, pwd.toCharArray());
        }
        assertTrue(ks.size() >= 1, "the CA must be present in the truststore");
    }

    @Test(groups = { "unit" })
    public void mysql_setsServerSslCert() {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:mysql://h:3306/db", p);
        assertNotNull(p.getProperty("dataSource.serverSslCert"));
        assertNull(p.getProperty("dataSource.sslrootcert"));
    }

    @Test(groups = { "unit" })
    public void nullJdbcUrl_isIgnoredNotThrown() {
        Properties p = new Properties();
        // a null jdbcUrl must not NPE; with no vendor detected nothing is injected.
        CaCertificateSupport.apply("ds", PEM, null, p);
        assertTrue(p.isEmpty());
    }

    @Test(groups = { "unit" })
    public void unsupportedVendor_isIgnored() {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", PEM, "jdbc:oracle:thin:@//h:1521/x", p);
        assertTrue(p.isEmpty(), "oracle is not supported — nothing must be injected");
    }

    @Test(groups = { "unit" })
    public void blankCertificate_isNoOp() {
        Properties p = new Properties();
        CaCertificateSupport.apply("ds", "", "jdbc:postgresql://h/db", p);
        CaCertificateSupport.apply("ds", null, "jdbc:postgresql://h/db", p);
        CaCertificateSupport.apply("ds", "   ", "jdbc:postgresql://h/db", p);
        assertTrue(p.isEmpty());
    }

    @Test(groups = { "unit" })
    public void invalidCertificate_isIgnoredNotThrown() {
        Properties p = new Properties();
        // Best-effort: a bogus PEM must not raise, and must not set a trust prop
        // (the connection then fails visibly at handshake time instead).
        CaCertificateSupport.apply("ds", "not a certificate", "jdbc:postgresql://h/db", p);
        assertNull(p.getProperty("dataSource.sslrootcert"));
    }
}
