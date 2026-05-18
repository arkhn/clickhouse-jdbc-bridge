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

import static org.testng.Assert.assertNotNull;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.clickhouse.jdbcbridge.core.NamedDataSource;
import com.clickhouse.jdbcbridge.core.Repository;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

public class JdbcDataSourceTest {

    private List<Driver> initialDrivers;

    @BeforeMethod(groups = { "unit" })
    public void snapshotDrivers() {
        initialDrivers = new ArrayList<>();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            initialDrivers.add(drivers.nextElement());
        }
    }

    @AfterMethod(groups = { "unit" })
    public void restoreDrivers() throws Exception {
        // JdbcDataSource.deregisterJdbcDriver removes the auto-detected/specified
        // driver from DriverManager. Re-register any driver we lost so this test
        // doesn't leak side effects into the rest of the suite.
        Enumeration<Driver> current = DriverManager.getDrivers();
        Set<Driver> currentSet = new HashSet<>();
        while (current.hasMoreElements()) {
            currentSet.add(current.nextElement());
        }
        for (Driver d : initialDrivers) {
            if (!currentSet.contains(d)) {
                DriverManager.registerDriver(d);
            }
        }
    }

    @Test(groups = { "unit" })
    public void testLegacyDriverSynonymAccepted() {
        // Real datasource configs in the wild use the legacy "driver" key instead of
        // HikariCP's canonical "driverClassName". The upstream bridge passes "driver"
        // straight through to HikariConfig, which has no such setter and throws:
        //   RuntimeException("Property driver does not exist on target class
        //                     com.clickhouse.jdbcbridge.internal.zaxxer.hikari.HikariConfig")
        // The fix must alias "driver" -> "driverClassName" before HikariConfig is built.
        JsonObject config = new JsonObject()
                .put("driver", "com.mysql.cj.jdbc.Driver")
                .put("jdbcUrl", "jdbc:mysql://localhost:3306/test?useSSL=false");

        Repository<NamedDataSource> repo = new JsonFileRepository<>(NamedDataSource.class);

        JdbcDataSource ds = new JdbcDataSource("legacy-driver-ds", repo, config);
        assertNotNull(ds);
    }

    @Test(groups = { "unit" })
    public void testUnknownPropertyIsIgnored() {
        // A property name that is neither a HikariCP setter nor a known alias must
        // not crash datasource init. Same failure mode as the legacy "driver" case.
        JsonObject config = new JsonObject()
                .put("driverClassName", "com.mysql.cj.jdbc.Driver")
                .put("jdbcUrl", "jdbc:mysql://localhost:3306/test?useSSL=false")
                .put("totallyUnknownProperty", "value");

        Repository<NamedDataSource> repo = new JsonFileRepository<>(NamedDataSource.class);

        JdbcDataSource ds = new JdbcDataSource("unknown-prop-ds", repo, config);
        assertNotNull(ds);
    }

    @Test(groups = { "unit" })
    public void testConstructor_doesNotMutateThreadContextClassLoader() throws Exception {
        // The constructor used to swap the calling thread's context classloader
        // for HikariCP init; under concurrent datasource registration that
        // swap-and-restore dance could leak the wrong loader into a sibling
        // datasource's HikariConfig. The fix routes the driver classloader
        // through HikariConfig.setDriverClassLoader instead. This test guards
        // against a regression to the old behavior.
        Thread t = Thread.currentThread();
        ClassLoader sentinel = new java.net.URLClassLoader(new java.net.URL[0], t.getContextClassLoader());
        ClassLoader original = t.getContextClassLoader();
        t.setContextClassLoader(sentinel);
        try {
            JsonObject config = new JsonObject()
                    .put("driverClassName", "com.mysql.cj.jdbc.Driver")
                    .put("jdbcUrl", "jdbc:mysql://localhost:3306/test?useSSL=false");
            Repository<NamedDataSource> repo = new JsonFileRepository<>(NamedDataSource.class);

            JdbcDataSource ds = new JdbcDataSource("ctx-loader-ds", repo, config);
            assertNotNull(ds);

            org.testng.Assert.assertSame(t.getContextClassLoader(), sentinel,
                    "JdbcDataSource constructor must not mutate the thread context classloader");
        } finally {
            t.setContextClassLoader(original);
        }
    }
}
