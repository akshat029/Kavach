#!/bin/sh

#
# Gradle start script for POSIX shells.
#
# This is a deliberately slim wrapper rather than a copy of the stock Gradle script.
# gradle-wrapper.jar is a binary and is not committed to this repository, so the stock
# script's only possible failure mode here is an opaque ClassNotFoundException. This
# version checks for the jar first and tells you exactly how to get one.
#
# CI regenerates the jar automatically; see .github/workflows/build.yml.
#

set -e

APP_HOME=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

die() {
    echo "" >&2
    echo "$*" >&2
    echo "" >&2
    exit 1
}

# ---------------------------------------------------------------- locate Java

if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Set JAVA_HOME to a JDK 17 installation, or remove it to use the java on your PATH."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1; then
        die "ERROR: No Java found.

Kavach needs JDK 17. Install it and either put java on your PATH or set JAVA_HOME."
    fi
fi

# ------------------------------------------------------- locate the wrapper jar

if [ ! -f "$WRAPPER_JAR" ]; then
    GRADLE_VERSION="8.9"
    if [ -f "$WRAPPER_PROPERTIES" ]; then
        PARSED=$(sed -n 's/^distributionUrl=.*gradle-\([0-9][0-9.]*\)-.*$/\1/p' "$WRAPPER_PROPERTIES" | head -n 1)
        if [ -n "$PARSED" ]; then
            GRADLE_VERSION="$PARSED"
        fi
    fi

    # A system Gradle can regenerate the wrapper for us, which is the friendliest
    # possible outcome. Otherwise, explain the one command that fixes this.
    if command -v gradle >/dev/null 2>&1; then
        echo "gradle-wrapper.jar is missing. Regenerating it with your system Gradle..." >&2
        (cd "$APP_HOME" && gradle wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin)
    else
        die "ERROR: gradle/wrapper/gradle-wrapper.jar is missing.

This repository is text-only, so the wrapper binary is not committed. Pick one:

  1. Open the project in Android Studio. It will set the wrapper up for you.
  2. Install Gradle $GRADLE_VERSION and run:  gradle wrapper --gradle-version $GRADLE_VERSION
  3. Let CI build it: push to GitHub and download the APK from the Build workflow."
    fi
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    die "ERROR: gradle-wrapper.jar could not be created. See the messages above."
fi

# ----------------------------------------------------------------------- launch

exec "$JAVACMD" \
    -Xmx64m -Xms64m \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
