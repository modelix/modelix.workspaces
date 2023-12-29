#!/bin/sh

set -e
set -x

getProperty() {
   PROPERTY_KEY=$1
   PROPERTY_VALUE=$(grep "$PROPERTY_KEY" < ../gradle.properties | cut -d'=' -f2)
   echo "$PROPERTY_VALUE"
}

MODELIX_WORKSPACES_VERSION="$(cat ../workspaces-version.txt)"

# Create a workspace-client image for each MPS version
for MPS_MAJOR_VERSION in $(getProperty mpsMajorVersions | tr "," "\n"); do
  MODELIX_MPS_COMPONENTS_VERSION=$(getProperty modelixMpsComponentsVersion"$MPS_MAJOR_VERSION")
  TAG=${MODELIX_WORKSPACES_VERSION}-${MPS_MAJOR_VERSION}
  if [ "${CI}" = "true" ]; then
    docker buildx build \
      --platform linux/amd64 \
      --push \
      --build-arg MODELIX_MPS_COMPONENTS_VERSION=${MODELIX_MPS_COMPONENTS_VERSION} \
      -t "modelix/modelix-workspace-client:${TAG}" .
  # Only linux/amd64 (especially not linux/arm64)  is not supported
  # Therefore build image with platform linux/amd64
  # See https://issues.modelix.org/issue/MODELIX-490/modelix-modelix-workspace-client-for-linux-arm64-does-not-run-correctly
  elif [ "$(uname -m)" != "x86_64" ]; then
        docker buildx build \
          --platform linux/amd64 \
          --build-arg MODELIX_MPS_COMPONENTS_VERSION=${MODELIX_MPS_COMPONENTS_VERSION} \
          -t "modelix/modelix-workspace-client:${TAG}" .
  else
    docker build \
      --build-arg MODELIX_MPS_COMPONENTS_VERSION=${MODELIX_MPS_COMPONENTS_VERSION}\
      -t "modelix/modelix-workspace-client:${TAG}" .
  fi
done