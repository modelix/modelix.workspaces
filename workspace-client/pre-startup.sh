#!/usr/bin/env sh

if [ ! -f "workspace-client.tar" ]; then
  wget "${modelix_workspace_server}static/workspace-client.tar"
  tar -xf workspace-client.tar
fi
./workspace-client/bin/workspace-client

rm -rf /mps-projects/default-mps-project
