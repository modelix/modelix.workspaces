#!/bin/sh

set -e

MPS_VERSION=$( ./helper/mps-version.sh )
VERSION_FILE="../modelix.version"

if [ -f "${VERSION_FILE}" ]; then
    MODELIX_VERSION=$(cat ${VERSION_FILE})
else
    echo "Unable to find modelix.version in root - will generate SNAPSHOT version"
    MODELIX_VERSION="${MPS_VERSION}-$(date +"%Y%m%d%H%M")-SNAPSHOT"
    echo "$MODELIX_VERSION" > ${VERSION_FILE}
fi

echo "${MODELIX_VERSION}"
