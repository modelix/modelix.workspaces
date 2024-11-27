#!/bin/sh

set -e
set -x

./instances-manager/docker-build-local-and-publish-on-ci.sh
./workspace-manager/docker-build-local-and-publish-on-ci.sh