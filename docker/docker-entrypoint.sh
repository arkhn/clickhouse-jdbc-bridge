#!/bin/bash

set -e

start_server() {
	# change work directory explicitly
	cd $JDBC_BRIDGE_HOME

	if [ "$(echo ${CUSTOM_DRIVER_LOADER:="true"} | tr '[:upper:]' '[:lower:]')" != "true" ]; then
		local classpath="./clickhouse-jdbc-bridge-shaded.jar:$(echo $(ls ${DRIVER_DIR:="drivers"}/*.jar) | tr ' ' ':'):."
		java -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError \
			-Djava.util.logging.config.file=$JDBC_BRIDGE_HOME/logging.properties \
			-Djava.security.properties=$JDBC_BRIDGE_HOME/java.security.override \
			${JDBC_BRIDGE_JVM_OPTS:=""} -cp $classpath com.clickhouse.jdbcbridge.JdbcBridgeVerticle 2>&1 | grep --line-buffered -vE "(sun\.misc\.Unsafe|WARNING: Please consider reporting this to the maintainers|WARNING: A terminally deprecated method)"
	else
		java -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError \
			-Djava.util.logging.config.file=$JDBC_BRIDGE_HOME/logging.properties \
			-Djava.security.properties=$JDBC_BRIDGE_HOME/java.security.override \
			${JDBC_BRIDGE_JVM_OPTS:=""} -jar clickhouse-jdbc-bridge-shaded.jar 2>&1 | grep --line-buffered -vE "(sun\.misc\.Unsafe|WARNING: Please consider reporting this to the maintainers|WARNING: A terminally deprecated method)"
	fi
}

if [ $# -eq 0 ]; then
	start_server
else
	exec "$@"
fi
