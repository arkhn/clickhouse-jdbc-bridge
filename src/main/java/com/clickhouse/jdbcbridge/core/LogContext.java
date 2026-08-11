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

/**
 * Per-thread logging context. The SLF4J binding in use (slf4j-jdk14) has a
 * no-op MDC, so this small ThreadLocal holder is the channel between request
 * handlers and {@link JsonLogFormatter}: handlers set the datasource id at
 * the beginning of a request and clear it in a {@code finally} block (worker
 * threads are pooled), and the formatter reads it synchronously on the same
 * thread when a log record is written.
 *
 * @since 2.0
 */
public final class LogContext {
    private static final ThreadLocal<String> DATASOURCE = new ThreadLocal<>();

    private LogContext() {
    }

    public static void setDataSource(String id) {
        DATASOURCE.set(id);
    }

    public static String getDataSource() {
        return DATASOURCE.get();
    }

    public static void clear() {
        DATASOURCE.remove();
    }
}
