#!/bin/sh

#
# Gradle Wrapper start-up script (minimal, for local cached-distribution use).
#

set -e

APP_NAME="Gradle"
CLASSPATH=gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Resolve links: $0 may be a link.
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

exec "$JAVACMD" \
    -classpath "$APP_HOME/$CLASSPATH" \
    -Dorg.gradle.appname="$APP_NAME" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
