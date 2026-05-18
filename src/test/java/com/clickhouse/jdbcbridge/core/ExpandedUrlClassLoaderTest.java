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

import static com.clickhouse.jdbcbridge.core.ExpandedUrlClassLoader.FILE_URL_PREFIX;
import static org.testng.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import org.testng.annotations.Test;

public class ExpandedUrlClassLoaderTest {
    private static final String TMP_DIR_PREFIX = "jdbc-bridge-test_";

    @Test(groups = { "unit" })
    public void testExpandURLs() throws IOException {
        // invalid URLs
        URL[] urls = ExpandedUrlClassLoader.expandURLs("a", "b", ".", "..", "", null, File.separator);
        assertNotNull(urls);
        assertEquals(urls.length, 5);

        // remote URLs are rejected — only file: / local paths are allowed
        assertThrows(IllegalArgumentException.class,
                () -> ExpandedUrlClassLoader.expandURLs("https://some.host1.com/path1/a.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> ExpandedUrlClassLoader.expandURLs("http://some.host.com/b.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> ExpandedUrlClassLoader.expandURLs("ftp://some.host.com/c.jar"));

        // now, local paths
        String url1 = FILE_URL_PREFIX + ".";
        urls = ExpandedUrlClassLoader.expandURLs(url1, null, url1);
        assertNotNull(urls);
        assertEquals(urls.length, 1);

        File tmpDir = Files.createTempDirectory(TMP_DIR_PREFIX).toFile();
        tmpDir.deleteOnExit();

        for (String file : new String[] { "a.jar", "b.jar" }) {
            File tmpFile = new File(tmpDir.getPath() + File.separator + file);
            tmpFile.deleteOnExit();
            tmpFile.createNewFile();
        }
        url1 = FILE_URL_PREFIX + tmpDir.getPath();
        String url2 = FILE_URL_PREFIX + tmpDir.getPath() + File.separator + "a.jar";
        String url3 = FILE_URL_PREFIX + tmpDir.getPath() + File.separator + "non-exist.jar";
        urls = ExpandedUrlClassLoader.expandURLs(url1, url2, url1, url3, url2);
        assertNotNull(urls);
        assertEquals(urls.length, 4);

        url1 = "test" + File.separator + "a";
        url2 = "." + File.separator + "test" + File.separator + "a";
        url3 = FILE_URL_PREFIX + "." + File.separator + "test" + File.separator + "a";
        urls = ExpandedUrlClassLoader.expandURLs(url1, url2, url3);
        assertNotNull(urls);
        assertEquals(urls.length, 2);
    }
}
