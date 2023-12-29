#!/bin/sh

set -e
set -x

(
 cd instances-manager
 ./docker-build.sh
)
(
 cd workspace-client
 ./docker-build.sh
)
(
 cd workspace-job
 ./docker-build.sh
)
(
 cd workspace-manager
 ./docker-build.sh
)