#!/bin/sh

set -e

echo "### DONE build-startKubernetesJob ###"

cd /


# Base image
echo "### START build-buildBaseImage ###"

wget -q "$BASEIMAGE_CONTEXT_URL"

if (
  if [ "$SKIP_TLS_VERIFY_PULL" = "true" ]; then
    /kaniko/executor \
    --dockerfile=Dockerfile \
    --context="tar:///context.tar.gz" \
    --destination="$BASEIMAGE_TARGET" \
    --insecure-registry="$TARGET_REGISTRY" \
    --insecure \
    --skip-tls-verify-pull \
    --cache=true \
    --cache-run-layers \
    --cache-copy-layers
  else
    /kaniko/executor \
    --dockerfile=Dockerfile \
    --context="tar:///context.tar.gz" \
    --destination="$BASEIMAGE_TARGET" \
    --insecure-registry="$TARGET_REGISTRY" \
    --insecure \
    --cache=true \
    --cache-run-layers \
    --cache-copy-layers
  fi
)
then
  echo "### DONE build-buildBaseImage ###"
else
  echo "### FAILED build-buildBaseImage ###"
fi

rm context.tar.gz


# Workspace image
wget -q --header="Authorization: Bearer $INITIAL_JWT_TOKEN" "$WORKSPACE_CONTEXT_URL"

if /kaniko/executor \
  --dockerfile=Dockerfile \
  --context="tar:///context.tar.gz" \
  --destination="$TARGET_REGISTRY/$WORKSPACE_DESTINATION_IMAGE_NAME:$WORKSPACE_DESTINATION_IMAGE_TAG" \
  --insecure-registry="$TARGET_REGISTRY" \
  --insecure \
  --cache=true \
  --cache-run-layers \
  --cache-copy-layers
then
  echo "### DONE build-uploadImage ###"
  echo "###WorkspaceBuildStatus = AllSuccessful###"
else
  echo "### FAILED build-uploadImage ###"
  echo "###WorkspaceBuildStatus = FailedBuild###"
fi

