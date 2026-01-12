#
# Copyright (C) 2019-2025, Zhichun Wu
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Full image: Extends base image with all JDBC drivers pre-installed
ARG BASE_IMAGE=arkhn/clickhouse:base
FROM ${BASE_IMAGE}

# Labels
LABEL app_name="ClickHouse JDBC Bridge" variant="full"

# Download all JDBC drivers
RUN wget -P $JDBC_BRIDGE_HOME/drivers \
	https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/0.9.2/clickhouse-jdbc-0.9.2-all.jar \
	https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.4/mariadb-java-client-3.5.4.jar \
	https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
	https://repo1.maven.org/maven2/org/neo4j/neo4j-jdbc-driver/4.0.10/neo4j-jdbc-driver-4.0.10.jar \
	https://repo1.maven.org/maven2/com/amazon/opendistroforelasticsearch/client/opendistro-sql-jdbc/1.13.0.0/opendistro-sql-jdbc-1.13.0.0.jar \
	https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar \
	https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar \
	https://repo1.maven.org/maven2/io/trino/trino-jdbc/479/trino-jdbc-479.jar
