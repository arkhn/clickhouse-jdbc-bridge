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

import static org.testng.Assert.*;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.testng.annotations.Test;

import io.vertx.core.json.JsonObject;

public class JsonLogFormatterTest {
    private final JsonLogFormatter formatter = new JsonLogFormatter();

    private static LogRecord newRecord(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("com.clickhouse.jdbcbridge.test");
        return record;
    }

    private static void assertSingleLine(String formatted) {
        assertTrue(formatted.endsWith("\n"), "output must end with a newline: " + formatted);
        assertEquals(formatted.indexOf('\n'), formatted.length() - 1,
                "output must not contain interior newlines: " + formatted);
    }

    @Test(groups = { "unit" })
    public void testMultiLineMessageStaysOnOneLine() {
        String sql = "SELECT a,\n\tb\nFROM some_table\nWHERE c = 1";
        String formatted = formatter.format(newRecord(Level.INFO, sql));

        assertSingleLine(formatted);

        // the message survives the round-trip, newlines included
        JsonObject parsed = new JsonObject(formatted);
        assertEquals(parsed.getString("message"), sql);
        assertEquals(parsed.getString("log.level"), "INFO");
        assertEquals(parsed.getString("log.logger"), "com.clickhouse.jdbcbridge.test");
        assertEquals(parsed.getString("service.name"), "clickhouse-jdbc-bridge");
        assertEquals(parsed.getString("process.thread.name"), Thread.currentThread().getName());
        assertNotNull(parsed.getString("@timestamp"));
    }

    @Test(groups = { "unit" })
    public void testQuotesAndBackslashesAreEscaped() {
        String message = "path \"C:\\temp\\x\" and a tab\there";
        String formatted = formatter.format(newRecord(Level.INFO, message));

        assertSingleLine(formatted);
        // parses as JSON and round-trips the original message
        JsonObject parsed = new JsonObject(formatted);
        assertEquals(parsed.getString("message"), message);
    }

    @Test(groups = { "unit" })
    public void testControlCharactersAreEscaped() {
        String message = "ding\u0007dong\u0001";
        String formatted = formatter.format(newRecord(Level.INFO, message));

        assertSingleLine(formatted);
        assertTrue(formatted.contains("\\u0007"), formatted);
        assertTrue(formatted.contains("\\u0001"), formatted);
        assertEquals(new JsonObject(formatted).getString("message"), message);
    }

    @Test(groups = { "unit" })
    public void testDataSourceField() {
        try {
            LogContext.setDataSource("my-ds");
            JsonObject parsed = new JsonObject(formatter.format(newRecord(Level.INFO, "hello")));
            assertEquals(parsed.getString("datasource"), "my-ds");
        } finally {
            LogContext.clear();
        }

        JsonObject parsed = new JsonObject(formatter.format(newRecord(Level.INFO, "hello")));
        assertFalse(parsed.containsKey("datasource"), "datasource must be absent after clear()");
    }

    @Test(groups = { "unit" })
    public void testQuerySchemaTableFields() {
        try {
            LogContext.setDataSource("my-ds");
            LogContext.setQuery("SELECT *\nFROM my_table");
            LogContext.setSchema("public");
            LogContext.setTable("my_table");

            String formatted = formatter.format(newRecord(Level.INFO, "Executing query"));
            assertSingleLine(formatted);

            JsonObject parsed = new JsonObject(formatted);
            assertEquals(parsed.getString("datasource"), "my-ds");
            assertEquals(parsed.getString("query"), "SELECT *\nFROM my_table");
            assertEquals(parsed.getString("schema"), "public");
            assertEquals(parsed.getString("table"), "my_table");
        } finally {
            LogContext.clear();
        }

        JsonObject parsed = new JsonObject(formatter.format(newRecord(Level.INFO, "hello")));
        assertFalse(parsed.containsKey("query"), "query must be absent after clear()");
        assertFalse(parsed.containsKey("schema"), "schema must be absent after clear()");
        assertFalse(parsed.containsKey("table"), "table must be absent after clear()");
    }

    @Test(groups = { "unit" })
    public void testLevelMapping() {
        assertEquals(JsonLogFormatter.mapLevel(Level.SEVERE), "ERROR");
        assertEquals(JsonLogFormatter.mapLevel(Level.WARNING), "WARN");
        assertEquals(JsonLogFormatter.mapLevel(Level.INFO), "INFO");
        assertEquals(JsonLogFormatter.mapLevel(Level.CONFIG), "INFO");
        assertEquals(JsonLogFormatter.mapLevel(Level.FINE), "DEBUG");
        assertEquals(JsonLogFormatter.mapLevel(Level.FINER), "DEBUG");
        assertEquals(JsonLogFormatter.mapLevel(Level.FINEST), "TRACE");

        // and through the full formatter as well
        assertEquals(new JsonObject(formatter.format(newRecord(Level.SEVERE, "boom"))).getString("log.level"),
                "ERROR");
        assertEquals(new JsonObject(formatter.format(newRecord(Level.FINEST, "chatty"))).getString("log.level"),
                "TRACE");
    }

    @Test(groups = { "unit" })
    public void testThrownExceptionIsInlined() {
        LogRecord record = newRecord(Level.SEVERE, "query failed");
        Throwable thrown;
        try {
            throw new IllegalStateException("bad \"state\"");
        } catch (IllegalStateException e) {
            thrown = e;
        }
        record.setThrown(thrown);

        String formatted = formatter.format(record);
        assertSingleLine(formatted);

        JsonObject parsed = new JsonObject(formatted);
        assertEquals(parsed.getString("error.type"), "java.lang.IllegalStateException");
        assertEquals(parsed.getString("error.message"), "bad \"state\"");
        String stackTrace = parsed.getString("error.stack_trace");
        assertNotNull(stackTrace);
        assertTrue(stackTrace.contains("java.lang.IllegalStateException"), stackTrace);
        assertTrue(stackTrace.contains("at " + JsonLogFormatterTest.class.getName()), stackTrace);
    }

    @Test(groups = { "unit" })
    public void testCarriageReturnBackspaceAndFormFeedAreEscaped() {
        String message = "line1\r\nline2\bback\ffeed";
        String formatted = formatter.format(newRecord(Level.INFO, message));

        assertSingleLine(formatted);
        assertTrue(formatted.contains("\\r\\n"), formatted);
        assertTrue(formatted.contains("\\b"), formatted);
        assertTrue(formatted.contains("\\f"), formatted);
        assertEquals(new JsonObject(formatted).getString("message"), message);
    }

    @Test(groups = { "unit" })
    public void testCustomJulLevelFallsBackToRawName() {
        // JUL allows custom levels; anything outside the standard set must
        // keep its own name rather than being coerced to an SLF4J level.
        Level custom = new Level("NOTICE", 850) {
        };
        assertEquals(JsonLogFormatter.mapLevel(custom), "NOTICE");
        assertEquals(new JsonObject(formatter.format(newRecord(custom, "heads up"))).getString("log.level"),
                "NOTICE");
    }

    @Test(groups = { "unit" })
    public void testEmptyContextValuesAreOmitted() {
        try {
            LogContext.setDataSource("");
            LogContext.setQuery("");
            JsonObject parsed = new JsonObject(formatter.format(newRecord(Level.INFO, "hello")));
            assertFalse(parsed.containsKey("datasource"), "empty datasource must be omitted");
            assertFalse(parsed.containsKey("query"), "empty query must be omitted");
        } finally {
            LogContext.clear();
        }
    }

    @Test(groups = { "unit" })
    public void testThrownWithoutMessageOmitsErrorMessage() {
        LogRecord record = newRecord(Level.SEVERE, "query failed");
        record.setThrown(new IllegalStateException());

        JsonObject parsed = new JsonObject(formatter.format(record));
        assertEquals(parsed.getString("error.type"), "java.lang.IllegalStateException");
        assertFalse(parsed.containsKey("error.message"), "null throwable message must be omitted");
        assertNotNull(parsed.getString("error.stack_trace"));
    }

    @Test(groups = { "unit" })
    public void testErrorFieldsAbsentWithoutThrown() {
        JsonObject parsed = new JsonObject(formatter.format(newRecord(Level.WARNING, "just a warning")));
        assertFalse(parsed.containsKey("error.type"));
        assertFalse(parsed.containsKey("error.message"));
        assertFalse(parsed.containsKey("error.stack_trace"));
    }
}
