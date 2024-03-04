#!/bin/sh
# assumes to be executed in the docker folder

set -e

MPS_VERSION=$(cat "../gradle/mps.versions.toml" | grep "mpsbase =" | cut -d " " -f3 | tr -d "\"")


if [ -z "${MPS_VERSION}" ]; then
    echo "Error: Unable to read MPS version from gradle toml"
    exit 1
fi

echo "${MPS_VERSION}"
