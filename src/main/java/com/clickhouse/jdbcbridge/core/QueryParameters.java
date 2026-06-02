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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.vertx.core.json.JsonObject;

/**
 * This class defines parameters can be used along with query, to change
 * behaviour of query exuection and result normalization.
 * 
 * @since 2.0
 */
public class QueryParameters {
    public static final String PARAM_BATCH_SIZE = "batch_size";
    public static final String PARAM_CUSTOM_COLUMNS = "custom_columns";
    public static final String PARAM_DATASOURCE_COLUMN = "datasource_column";
    public static final String PARAM_FETCH_SIZE = "fetch_size";
    public static final String PARAM_MAX_ROWS = "max_rows";
    public static final String PARAM_MUTATION = "mutation";
    public static final String PARAM_NO_CACHE = "no_cache";
    public static final String PARAM_NULL_AS_DEFAULT = "null_as_default";
    public static final String PARAM_OFFSET = "offset";
    public static final String PARAM_POSITION = "position";
    public static final String PARAM_TIMEOUT = "timeout";
    public static final String PARAM_USE_DATETIME = "use_datetime";

    public static final String PARAM_DEBUG = "debug";

    public static final int DEFAULT_BATCH_SIZE = 4096;
    public static final int DEFAULT_FETCH_SIZE = 16384;
    public static final int DEFAULT_MAX_ROWS = 0;
    public static final int DEFAULT_OFFSET = 0;
    public static final int DEFAULT_POSITION = 0;
    public static final int DEFAULT_TIMEOUT = -1;

    private final TypedParameter<Integer> batchSize;
    private final TypedParameter<Boolean> customColumns;
    private final TypedParameter<Boolean> datasourceColumn;
    private final TypedParameter<Integer> fetchSize;
    private final TypedParameter<Integer> maxRows;
    private final TypedParameter<Boolean> mutation;
    private final TypedParameter<Boolean> noCache;
    private final TypedParameter<Boolean> nullAsDefault;
    private final TypedParameter<Integer> offset;
    private final TypedParameter<Integer> position;
    private final TypedParameter<Integer> timeout;

    private final TypedParameter<Boolean> debug;

    private final Map<String, TypedParameter<?>> params = new TreeMap<>();

    public QueryParameters() {
        Utils.addTypedParameter(params,
                this.batchSize = new TypedParameter<>(Integer.class, PARAM_BATCH_SIZE, DEFAULT_BATCH_SIZE));
        Utils.addTypedParameter(params,
                this.customColumns = new TypedParameter<>(Boolean.class, PARAM_CUSTOM_COLUMNS, false));
        Utils.addTypedParameter(params,
                this.datasourceColumn = new TypedParameter<>(Boolean.class, PARAM_DATASOURCE_COLUMN, false));
        Utils.addTypedParameter(params,
                this.fetchSize = new TypedParameter<>(Integer.class, PARAM_FETCH_SIZE, DEFAULT_FETCH_SIZE));
        Utils.addTypedParameter(params,
                this.maxRows = new TypedParameter<>(Integer.class, PARAM_MAX_ROWS, DEFAULT_MAX_ROWS));
        Utils.addTypedParameter(params, this.mutation = new TypedParameter<>(Boolean.class, PARAM_MUTATION, false));
        Utils.addTypedParameter(params, this.noCache = new TypedParameter<>(Boolean.class, PARAM_NO_CACHE, false));
        Utils.addTypedParameter(params,
                this.nullAsDefault = new TypedParameter<>(Boolean.class, PARAM_NULL_AS_DEFAULT, false));
        Utils.addTypedParameter(params,
                this.offset = new TypedParameter<>(Integer.class, PARAM_OFFSET, DEFAULT_OFFSET));
        Utils.addTypedParameter(params,
                this.position = new TypedParameter<>(Integer.class, PARAM_POSITION, DEFAULT_POSITION));
        Utils.addTypedParameter(params,
                this.timeout = new TypedParameter<>(Integer.class, PARAM_TIMEOUT, DEFAULT_TIMEOUT));

        Utils.addTypedParameter(params, this.debug = new TypedParameter<>(Boolean.class, PARAM_DEBUG, false));
    }

    public QueryParameters(String uri) {
        this();

        merge(uri);
    }

    public QueryParameters(JsonObject... params) {
        this();

        for (JsonObject parameters : params) {
            merge(parameters);
        }
    }

    public QueryParameters merge(QueryParameters p) {
        if (p != null) {
            for (TypedParameter<?> tp : p.params.values()) {
                TypedParameter<?> x = this.params.get(tp.getName());
                if (x == null) {
                    this.params.put(tp.getName(), tp);
                } else if (x.getType() == tp.getType()) {
                    // Merge through the TypedParameter overload (not the raw value) so
                    // provenance is honoured: a source still carrying its compiled
                    // default does not clobber a value we set explicitly. This is what
                    // lets datasource-level parameters survive the per-request merge.
                    mergeTyped(x, tp);
                }
            }
        }

        return this;
    }

    @SuppressWarnings("unchecked")
    private static <T> void mergeTyped(TypedParameter<?> target, TypedParameter<?> source) {
        ((TypedParameter<T>) target).merge((TypedParameter<T>) source);
    }

    public QueryParameters merge(JsonObject p) {
        if (p != null) {
            HashSet<String> names = new HashSet<>(p.fieldNames());

            for (TypedParameter<?> tp : this.params.values()) {
                names.remove(tp.getName());

                if (!PARAM_DEBUG.equals(tp.getName())) {
                    tp.merge(p);
                }
            }

            for (String name : names) {
                String value = Objects.toString(p.getValue(name));
                // Extra (untyped) params only exist because the key was present in
                // the source, so they are explicit by definition — merge(value) flags
                // them as such, so a request-level extra still overrides a
                // datasource-level one of the same name.
                this.params.put(name, new TypedParameter<>(String.class, name, value).merge(value));
            }
        }

        return this;
    }

    public QueryParameters merge(String uri) {
        int index = uri == null ? -1 : uri.indexOf('?');
        if (index >= 0 && uri.length() > index) {
            String query = uri.substring(index + 1);

            for (String param : Utils.splitByChar(query, '&')) {
                index = param.indexOf('=');
                if (index > 0) {
                    String key = param.substring(0, index);
                    String value = param.substring(index + 1);

                    TypedParameter<?> p = this.params.get(key);
                    if (p != null) {
                        p.merge(value);
                    } else {
                        // Untyped URI param: explicit by definition (see merge(JsonObject)).
                        this.params.put(key, new TypedParameter<String>(String.class, key, value).merge(value));
                    }
                } else {
                    TypedParameter<?> p = this.params.get(param);
                    if (p != null && p.getDefaultValue() instanceof Boolean) {
                        p.merge(Boolean.TRUE.toString());
                    }
                }
            }
        }

        return this;
    }

    public int getBatchSize() {
        return this.batchSize.getValue();
    }

    public int getFetchSize() {
        return this.fetchSize.getValue();
    }

    /**
     * Whether the named parameter was set explicitly (via datasource config or the
     * request URI) rather than left at its compiled default. Used by datasources to
     * decide whether an engine-specific default (e.g. Oracle's lower fetch size)
     * should apply, without overriding an operator's explicit choice.
     *
     * @param name parameter name, e.g. {@link #PARAM_FETCH_SIZE}
     * @return {@code true} only if the parameter exists and was explicitly set
     */
    public boolean isExplicitlySet(String name) {
        TypedParameter<?> p = this.params.get(name);
        return p != null && p.isExplicitlySet();
    }

    public int getMaxRows() {
        return this.maxRows.getValue();
    }

    public boolean isMutation() {
        return this.mutation.getValue();
    }

    public boolean doNotUseCache() {
        return this.noCache.getValue();
    }

    public boolean nullAsDefault() {
        return this.nullAsDefault.getValue();
    }

    public int getOffset() {
        return this.offset.getValue();
    }

    public int getPosition() {
        return this.position.getValue();
    }

    public int getTimeout() {
        return this.timeout.getValue();
    }

    public boolean showDatasourceColumn() {
        return this.datasourceColumn.getValue();
    }

    public boolean showCustomColumns() {
        return this.customColumns.getValue();
    }

    public boolean isDebug() {
        return this.debug.getValue();
    }

    @SuppressWarnings("unchecked")
    public <T> T getParameterDefaultValue(String key, Class<T> type) {
        return ((TypedParameter<T>) this.params.get(key)).getDefaultValue();
    }

    @SuppressWarnings("unchecked")
    public <T> T getParameterValue(String key, Class<T> type) {
        return ((TypedParameter<T>) this.params.get(key)).getValue();
    }

    public Map<String, String> asVariables() {
        Map<String, String> map = new HashMap<String, String>(this.params.size());

        for (TypedParameter<?> tp : this.params.values()) {
            Object value = tp.getValue();
            if (value instanceof String) {
                map.put(tp.getName(), (String) value);
            }
        }

        return map;
    }

    public String toQueryString() {
        StringBuilder sb = new StringBuilder();

        for (TypedParameter<?> p : this.params.values()) {
            sb.append('&').append(p.toKeyValuePairString());
        }

        if (sb.length() > 0) {
            sb.deleteCharAt(0);
        }

        return sb.toString();
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();

        for (Map.Entry<String, TypedParameter<?>> p : this.params.entrySet()) {
            obj.put(p.getKey(), String.valueOf(p.getValue().getValue()));
        }

        return obj;
    }
}