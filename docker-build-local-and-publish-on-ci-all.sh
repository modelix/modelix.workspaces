#!/bin/sh

set -e
set -x

./gradlew buildImages
# TODO MODELIX-597 Also publish the images for the sync steps.
# Build and publish was separated, so that Gradle could build the images and use them in future test (e.g. e2e-tests)
#./instances-manager/docker-build-local-and-publish-on-ci.sh # TODO MODELIX-597 Do not commit
#./workspace-client/docker-build-local-and-publish-on-ci.sh # TODO MODELIX-597 Do not commit
#./workspace-job/docker-build-local-and-publish-on-ci.sh # TODO MODELIX-597 Do not commit
./workspace-manager/docker-build-local-and-publish-on-ci.sh