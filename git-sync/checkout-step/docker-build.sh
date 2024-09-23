#!/bin/sh

set -e
set -x

cd "$(dirname "$0")"

if [ "${CI}" = "true" ]; then
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    -t "modelix/modelix-workspace-git-sync-checkout-step:${MODELIX_WORKSPACES_VERSION}" .
else
  docker build \
    -t "modelix/modelix-workspace-git-sync-checkout-step:${MODELIX_WORKSPACES_VERSION}" .
fi