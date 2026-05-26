#
# Copyright (C) 2019-2021, Zhichun Wu
# Copyright (C) 2024-2026, Arkhn
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

# Multi-stage build for the ClickHouse JDBC bridge.
#   - `builder` compiles the shaded jar
#   - `base`    minimal runtime image (no JDBC drivers vendored)
#   - `full`    base + all JDBC drivers pre-installed (default target)
#
# Build the full image:
#   docker build -t arkhn/clickhouse-jdbc-bridge:dev .
# Build only the base image (no drivers):
#   docker build --target base -t arkhn/clickhouse-jdbc-bridge:base .

# -----------------------------------------------------------------------------
# Stage 1/3: Compile the project
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jammy AS builder

WORKDIR /build

COPY pom.xml ./
COPY src ./src
COPY misc ./misc
COPY LICENSE NOTICE ./

# Build then rename the shaded jar to a version-independent path so downstream
# stages don't have to track pom.xml's <version>. The actual project version
# comes from pom.xml directly (Maven reads it); the Dockerfile no longer needs
# a matching REVISION arg.
RUN apt-get update \
	&& apt-get install -y maven \
	&& mvn clean package -DskipTests -Dnotice.skip=true -Dlicense.skip=true \
	&& mv target/clickhouse-jdbc-bridge-*-shaded.jar target/clickhouse-jdbc-bridge-shaded.jar \
	&& apt-get clean \
	&& rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# Stage 2/3: Base runtime image (no JDBC drivers)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy AS base

LABEL maintainer="infra@arkhn.com"

ENV JDBC_BRIDGE_HOME=/app

# Use a single shared classloader that includes every jar in $JDBC_BRIDGE_HOME/drivers/.
# Default upstream behaviour ("true") requires each datasource to declare its own
# driverUrls; flipping this lets datasources rely on jars dropped into the drivers dir.
ENV CUSTOM_DRIVER_LOADER=false

# Dispatch the blocking /query and /write handlers onto a virtual-thread executor
# instead of the Vert.x worker pool. Reduces bulk-read heap by ~15-26% on the
# JDBC streaming path (verified in misc/bench). Throughput is within a few % of
# the platform-thread pool at moderate concurrency. Override with
# VIRTUAL_THREADS=false to fall back to blockingHandler on the worker pool.
ENV VIRTUAL_THREADS=true

LABEL app_name="ClickHouse JDBC Bridge" variant="base"

RUN apt-get update \
	&& DEBIAN_FRONTEND=noninteractive apt-get install -y --allow-unauthenticated apache2-utils \
		apt-transport-https curl htop iftop iptraf iputils-ping jq lsof net-tools tzdata wget \
	&& apt-get clean \
	&& mkdir -p $JDBC_BRIDGE_HOME/drivers $JDBC_BRIDGE_HOME/extra \
	&& rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

COPY --from=builder --chown=root:root /build/target/clickhouse-jdbc-bridge-shaded.jar $JDBC_BRIDGE_HOME/
COPY --chown=root:root LICENSE NOTICE $JDBC_BRIDGE_HOME/
COPY --chown=root:root docker/ $JDBC_BRIDGE_HOME

RUN chmod +x $JDBC_BRIDGE_HOME/*.sh \
	&& mkdir -p $JDBC_BRIDGE_HOME/logs /usr/local/lib/java \
	&& ln -s $JDBC_BRIDGE_HOME/logs /var/log/clickhouse-jdbc-bridge \
	&& ln -s $JDBC_BRIDGE_HOME/clickhouse-jdbc-bridge-shaded.jar \
		/usr/local/lib/java/clickhouse-jdbc-bridge-shaded.jar \
	&& ln -s $JDBC_BRIDGE_HOME /etc/clickhouse-jdbc-bridge

WORKDIR $JDBC_BRIDGE_HOME

EXPOSE 9019

VOLUME ["${JDBC_BRIDGE_HOME}/drivers", "${JDBC_BRIDGE_HOME}/extra", "${JDBC_BRIDGE_HOME}/extensions", "${JDBC_BRIDGE_HOME}/logs", "${JDBC_BRIDGE_HOME}/scripts"]

CMD "./docker-entrypoint.sh"

# -----------------------------------------------------------------------------
# Stage 3/3: Full image — base + every supported JDBC driver
# -----------------------------------------------------------------------------
FROM base AS full

LABEL app_name="ClickHouse JDBC Bridge" variant="full"

RUN wget -P $JDBC_BRIDGE_HOME/drivers \
	https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/0.9.2/clickhouse-jdbc-0.9.2-all.jar \
	https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.4/mariadb-java-client-3.5.4.jar \
	https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
	https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/12.10.0.jre11/mssql-jdbc-12.10.0.jre11.jar \
	https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.7.0.25.01/ojdbc11-23.7.0.25.01.jar \
	https://repo1.maven.org/maven2/com/intersystems/intersystems-jdbc/3.10.5/intersystems-jdbc-3.10.5.jar \
	https://repo1.maven.org/maven2/org/neo4j/neo4j-jdbc-driver/4.0.10/neo4j-jdbc-driver-4.0.10.jar \
	https://repo1.maven.org/maven2/com/amazon/opendistroforelasticsearch/client/opendistro-sql-jdbc/1.13.0.0/opendistro-sql-jdbc-1.13.0.0.jar \
	https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar \
	https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.51.1.0/sqlite-jdbc-3.51.1.0.jar \
	https://repo1.maven.org/maven2/io/trino/trino-jdbc/479/trino-jdbc-479.jar
