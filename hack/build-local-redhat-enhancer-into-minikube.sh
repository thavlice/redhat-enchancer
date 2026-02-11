#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and redhat-enhancer
REDHAT_ENHANCER_IMAGE="redhat-enhancer:latest"
PROFILE="sbomer"
TAR_FILE="redhat-enhancer.tar"

echo "--- Building and inserting redhat-enhancer image into Minikube registry ---"

bash ./hack/build-with-schemas.sh prod

podman build --format docker -t "$REDHAT_ENHANCER_IMAGE" -f src/main/docker/Dockerfile.jvm .

echo "--- Exporting redhat-enhancer image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$REDHAT_ENHANCER_IMAGE"

echo "--- Loading redhat-enhancer into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$REDHAT_ENHANCER_IMAGE' is ready in cluster '$PROFILE'."