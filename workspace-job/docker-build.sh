#!/bin/sh

set -e
set -x

getProperty() {
   PROPERTY_KEY=$1
   PROPERTY_VALUE=$(grep "$PROPERTY_KEY" < ../gradle.properties | cut -d'=' -f2)
   echo "$PROPERTY_VALUE"
}

MODELIX_WORKSPACES_VERSION="$(cat ../workspaces-version.txt)"

# Create a workspace-job image for each MPS version
for MPS_MAJOR_VERSION in $(getProperty mpsMajorVersions | tr "," "\n"); do
  TAG=${MODELIX_WORKSPACES_VERSION}-${MPS_MAJOR_VERSION}
  if [ "${CI}" = "true" ]; then
    docker buildx build \
      --platform linux/amd64,linux/arm64 \
      --push \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  else
    docker build \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  fi
done