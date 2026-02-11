#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and redhat-enhancer-agent
REDHAT_ENHANCER_AGENT_IMAGE="redhat-enchancer-agent:latest"
PROFILE="sbomer"
TAR_FILE="redhat-enhancer-agent.tar"

echo "--- Building and inserting redhat-enchancer-agent image into Minikube registry ---"

podman build --format docker -t "$REDHAT_ENHANCER_AGENT_IMAGE" -f podman/redhat-enhancer-agent/Containerfile .

echo "--- Exporting redhat-enchancer-agent image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$REDHAT_ENHANCER_AGENT_IMAGE"

echo "--- Loading redhat-enchancer-agent into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$REDHAT_ENHANCER_AGENT_IMAGE' is ready in cluster '$PROFILE'."