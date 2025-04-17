#!/bin/sh

set -e
set -x

./workspace-job/docker-build-local-and-publish-on-ci.sh
./workspace-manager/docker-build-local-and-publish-on-ci.sh
