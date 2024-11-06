#!/bin/sh

set -e
set -x

cd "$(dirname "$0")"

getProperty() {
   PROPERTY_FILE=$1
   PROPERTY_KEY=$2
   PROPERTY_VALUE=$(grep "$PROPERTY_KEY" < "$PROPERTY_FILE" | cut -d'=' -f2)
   echo "$PROPERTY_VALUE"
}

JAVA_11_IMAGE=$(getProperty ../version.properties javaImage11)
JAVA_17_IMAGE=$(getProperty ../version.properties javaImage17)
MODELIX_WORKSPACES_VERSION="$(cat ../workspaces-version.txt)"

# Create a workspace-job image for each MPS version
for MPS_MAJOR_VERSION in $(getProperty ../gradle.properties mpsMajorVersions | tr "," "\n"); do
  TAG=${MODELIX_WORKSPACES_VERSION}-${MPS_MAJOR_VERSION}

  case $MPS_MAJOR_VERSION in
  "2020.3" | "2021.1" | "2021.2" | "2021.3" )
    JAVA_IMAGE=$JAVA_11_IMAGE
    ;;
  "2022.2" | "2022.3" | "2023.2" | "2023.3" | "2024.1" )\
    JAVA_IMAGE=$JAVA_17_IMAGE
    ;;
  *)
    echo "Unknown MPS version ${MPS_MAJOR_VERSION}. Check MPS version or extend case statement." 1>&2
    exit 1
  esac

  echo "Build workspace-job container with MPS ${MPS_MAJOR_VERSION} and Java ${JAVA_IMAGE}."

  (
    cd ..
    ./gradlew ":workspace-job:resolveMps${MPS_MAJOR_VERSION}"
  )

  if [ "${CI}" = "true" ]; then
    docker buildx build \
      --platform linux/amd64,linux/arm64 \
      --push \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      --build-arg JAVA_IMAGE=${JAVA_IMAGE}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  else
    docker build \
      --build-arg MPS_MAJOR_VERSION=${MPS_MAJOR_VERSION}\
      --build-arg JAVA_IMAGE=${JAVA_IMAGE}\
      -t "modelix/modelix-workspace-job:${TAG}" .
  fi

  (
    cd ..
    ./gradlew ":workspace-job:deleteMps${MPS_MAJOR_VERSION}"
  )
done