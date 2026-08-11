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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * JUL formatter emitting one ECS-style JSON object per log record, on a
 * single line, so log shippers (filebeat and friends) can forward each record
 * to Elasticsearch without multiline parsing — SQL queries and stack traces
 * stay inside a single JSON string. No external JSON library is used because
 * of the shade-plugin relocations; escaping is hand-rolled below.
 *
 * <p>Emitted fields (null/empty ones are omitted): {@code @timestamp},
 * {@code log.level}, {@code log.logger}, {@code message}, {@code datasource},
 * {@code query}, {@code schema}, {@code table} (all four from
 * {@link LogContext}), {@code process.thread.name},
 * {@code service.name}, and for records carrying a throwable:
 * {@code error.type}, {@code error.message}, {@code error.stack_trace}.</p>
 *
 * @since 2.0
 */
public class JsonLogFormatter extends Formatter {
    private static final String SERVICE_NAME = "clickhouse-jdbc-bridge";

    // ISO-8601 UTC with fixed millisecond precision, e.g. 2026-08-11T09:30:01.234Z
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String format(LogRecord record) {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{');

        appendField(builder, "@timestamp", TIMESTAMP_FORMAT.format(record.getInstant()));
        appendField(builder, "log.level", mapLevel(record.getLevel()));
        appendField(builder, "log.logger", record.getLoggerName());
        appendField(builder, "message", formatMessage(record));
        appendField(builder, "datasource", LogContext.getDataSource());
        appendField(builder, "query", LogContext.getQuery());
        appendField(builder, "schema", LogContext.getSchema());
        appendField(builder, "table", LogContext.getTable());
        appendField(builder, "process.thread.name", Thread.currentThread().getName());
        appendField(builder, "service.name", SERVICE_NAME);

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            appendField(builder, "error.type", thrown.getClass().getName());
            appendField(builder, "error.message", thrown.getMessage());
            StringWriter trace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            appendField(builder, "error.stack_trace", trace.toString());
        }

        return builder.append('}').append('\n').toString();
    }

    /**
     * Maps a JUL level to its SLF4J name — the exact reverse of the
     * slf4j-jdk14 binding's level mapping. Unknown (custom) levels fall back
     * to their raw JUL name.
     */
    static String mapLevel(Level level) {
        if (level == Level.SEVERE) {
            return "ERROR";
        } else if (level == Level.WARNING) {
            return "WARN";
        } else if (level == Level.INFO || level == Level.CONFIG) {
            return "INFO";
        } else if (level == Level.FINE || level == Level.FINER) {
            return "DEBUG";
        } else if (level == Level.FINEST) {
            return "TRACE";
        }

        return level.getName();
    }

    private static void appendField(StringBuilder builder, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }

        if (builder.length() > 1) {
            builder.append(',');
        }
        builder.append('"');
        escape(builder, name);
        builder.append("\":\"");
        escape(builder, value);
        builder.append('"');
    }

    private static void escape(StringBuilder builder, String value) {
        for (int i = 0, len = value.length(); i < len; i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                default:
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                    break;
            }
        }
    }
}
