#!/usr/bin/env bash

# Exit immediately if any command fails
set -e

REPO_URL="https://github.com/sbomer-project/sbomer-local-dev.git"
DIR_NAME="sbomer-local-dev"

echo "--- Preparing Local Dev Repository ---"

if [ -d "$DIR_NAME" ]; then
    echo "Directory '$DIR_NAME' found."
    echo "Updating repository..."

    # Enter the directory temporarily to pull changes
    pushd "$DIR_NAME" > /dev/null
    git pull
    popd > /dev/null
else
    echo "Directory '$DIR_NAME' not found."
    echo "Cloning repository..."
    git clone "$REPO_URL"
fi

echo ""
echo "--- Running Minikube Setup ---"

# Enter the directory to run the script
pushd "$DIR_NAME"

if [ -f "setup-minikube.sh" ]; then
    bash setup-minikube.sh
else
    echo "Error: 'setup-minikube.sh' not found inside $DIR_NAME"
    exit 1
fi

# Return to original directory
popd