#!/bin/sh

set -e
set -x

cd "$(dirname "$0")"

getProperty() {
   PROPERTY_KEY=$1
   PROPERTY_VALUE=$(grep "$PROPERTY_KEY" < ../gradle.properties | cut -d'=' -f2)
   echo "$PROPERTY_VALUE"
}

MODELIX_WORKSPACES_VERSION="$(cat ../workspaces-version.txt)"

# Create a workspace-job image for each MPS version
for MPS_MAJOR_VERSION in $(getProperty mpsMajorVersions | tr "," "\n"); do
  TAG=${MODELIX_WORKSPACES_VERSION}-${MPS_MAJOR_VERSION}

  case $MPS_MAJOR_VERSION in
  "2020.3" | "2021.1" | "2021.2" | "2021.3" )
    JAVA_VERSION=11
    ;;
  "2022.2" | "2022.3" | "2023.2" )\
    JAVA_VERSION=17
    ;;
  *)
    echo "Unknown MPS version ${MPS_MAJOR_VERSION}. Check MPS version or extend case statement." 1>&2
    exit 1
  esac

  echo "Build workspace-job container with MPS ${MPS_MAJOR_VERSION} and Java ${JAVA_VERSION}."

  (
    cd ..
    ./gradlew ":workspace-job:resolveMps${MPS_MAJOR_VERSION}"
  )

  if [ "${CI}" = "true" ]; then
    docker buildx build \
      --platform linux/amd64,linux/arm64 \
      --push \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      --build-arg JAVA_VERSION=${JAVA_VERSION}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  else
    docker build \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      --build-arg JAVA_VERSION=${JAVA_VERSION}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  fi

  (
    cd ..
    ./gradlew ":workspace-job:deleteMps${MPS_MAJOR_VERSION}"
  )
done