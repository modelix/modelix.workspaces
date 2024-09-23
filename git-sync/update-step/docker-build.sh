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

TAG=${MODELIX_WORKSPACES_VERSION}-${MPS_MAJOR_VERSION}

JAVA_11_IMAGE=$(getProperty ../../version.properties javaImage11)
JAVA_17_IMAGE=$(getProperty ../../version.properties javaImage17)

case $MPS_MAJOR_VERSION in
"2020.3" | "2021.1" | "2021.2" | "2021.3" )
  JAVA_IMAGE=$JAVA_11_IMAGE
  ;;
"2022.2" | "2022.3" | "2023.2" )\
  JAVA_IMAGE=$JAVA_17_IMAGE
  ;;
*)
  echo "Unknown MPS version ${MPS_MAJOR_VERSION}. Check MPS version or extend case statement." 1>&2
  exit 1
esac

echo "Build git-sync-update-step container with MPS ${MPS_MAJOR_VERSION} and Java ${JAVA_IMAGE}."

if [ "${CI}" = "true" ]; then
  docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --build-arg JAVA_IMAGE="${JAVA_IMAGE}"\
    -t "modelix/modelix-workspace-git-sync-update-step:${TAG}" \
    --build-context mps-context=../../build/mps"${MPS_MAJOR_VERSION}" \
    .
else
  docker build \
    --build-arg JAVA_IMAGE="${JAVA_IMAGE}"\
    -t "modelix/modelix-workspace-git-sync-update-step:${TAG}" \
    --build-context mps-context=../../build/mps"${MPS_MAJOR_VERSION}" \
    .
fi