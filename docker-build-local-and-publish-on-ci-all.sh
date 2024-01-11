#!/bin/sh

set -e
set -x

(
 cd instances-manager
 ./docker-build-local-and-publish-on-ci.sh
)
(
 cd workspace-client
 ./docker-build-local-and-publish-on-ci.sh
)
(
 cd workspace-job
 ./docker-build-local-and-publish-on-ci.sh
)
(
 cd workspace-manager
 ./docker-build-local-and-publish-on-ci.sh
)