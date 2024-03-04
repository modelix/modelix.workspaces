#!/bin/sh

set -e
set -x

cd "$(dirname "$0")"

MODELIX_WORKSPACES_VERSION="$(cat ../workspaces-version.txt)"

if [ "${CI}" = "true" ]; then
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    -t "modelix/modelix-instances-manager:${MODELIX_WORKSPACES_VERSION}" .

  # avoid running out of disk space on the github actions runner
  docker rm "modelix/modelix-instances-manager:${MODELIX_WORKSPACES_VERSION}"
else
  docker build \
    -t "modelix/modelix-instances-manager:${MODELIX_WORKSPACES_VERSION}" .
fi