#!/usr/bin/env sh
#
# Gradle start up script for UN*X (minimal, wrapper-based)
#
APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "${0%/*}" >/dev/null; pwd -P`

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
