#!/bin/sh
#
# Maven Wrapper Script
#
# This script is used to download and execute Maven if not already installed.
#

set -e

MAVEN_VERSION=3.9.6
MAVEN_DISTRIBUTION_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.zip"
MAVEN_WRAPPER_JAR_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar"

WRAPPER_DIR="$(dirname "$0")/.mvn/wrapper"
WRAPPER_JAR="${WRAPPER_DIR}/maven-wrapper.jar"
WRAPPER_PROPERTIES="${WRAPPER_DIR}/maven-wrapper.properties"

# Download wrapper jar if not present
if [ ! -f "${WRAPPER_JAR}" ]; then
    echo "Downloading Maven Wrapper..."
    mkdir -p "${WRAPPER_DIR}"
    curl -fsSL -o "${WRAPPER_JAR}" "${MAVEN_WRAPPER_JAR_URL}"
fi

# Execute Maven Wrapper
exec java -jar "${WRAPPER_JAR}" "$@"