#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
# Gradle start up script for UN*X
#

set -e

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Resolve script dir
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
APP_HOME="$SCRIPT_DIR"

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

JAVA_EXE="java"
if [ -n "$JAVA_HOME" ] ; then
    JAVA_EXE="$JAVA_HOME/bin/java"
fi

exec "$JAVA_EXE" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
