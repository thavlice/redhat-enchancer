#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local podman-compose development environment.
#
# It is intended to be run from the root of the project.

set -e

PROFILE=sbomer
NAMESPACE=sbomer-test
PLATFORM_REPO="https://github.com/sbomer-project/sbomer-platform.git"
PLATFORM_DIR="sbomer-platform"
LOCAL_CHART_PATH="helm/redhat-enhancer-chart"
ENV_FILE=".env"

# --- 1. Load Environment Variables ---
if [ -f "$ENV_FILE" ]; then
    echo "Loading configuration from $ENV_FILE..."
    # set -a automatically exports variables defined in the source
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "Error: $ENV_FILE not found!"
    echo "Please create a .env file with PNC_HOST, INDY_HOST, KOJI_HUB_URL, KOJI_WEB_URL, KOJI_DOWNLOAD_HOST correctly set."
    exit 1
fi

echo "--- Checking Minikube status (Profile: $PROFILE) ---"

if ! minikube -p "$PROFILE" status > /dev/null 2>&1; then
    echo "Error: Minikube cluster '$PROFILE' is NOT running."
    echo ""
    echo "Please run the setup script first to start the cluster and install dependencies (tekton):"
    echo "./hack/setup-minikube.sh"
    echo ""
    exit 1
fi

# Temporarily using current gen image for agent as PoC, bypassing this part
# echo "--- Building local redhat-enhancer-agent image inside of Minikube ---"
# Will have local image redhat-enhancer-agent:latest in minikube
# bash ./hack/build-local-redhat-enhancer-agent-into-minikube.sh

echo "--- Building local redhat-enhancer image inside of Minikube ---"
# Will have local image redhat-enhancer:latest in minikube
bash ./hack/build-local-redhat-enhancer-into-minikube.sh

echo "--- Setting up SBOMer Platform Chart ---"

# Clone the platform chart if it doesn't exist
if [ ! -d "$PLATFORM_DIR" ]; then
    echo "Cloning sbomer-platform..."
    git clone "$PLATFORM_REPO" "$PLATFORM_DIR"
else
    echo "sbomer-platform directory exists, updating..."
    git -C "$PLATFORM_DIR" pull
fi

# Update dependencies to pull the local chart
echo "Updating Helm dependencies..."
helm dependency update "$PLATFORM_DIR"

echo "--- Deploying to Minikube ---"

# 1. Platform Release
# If it exists but is failed, uninstall it first to prevent the "no deployed releases" error
if helm status sbomer-release -n $NAMESPACE | grep -q "STATUS: failed"; then
    echo "Previous platform release failed. Uninstalling..."
    helm uninstall sbomer-release -n $NAMESPACE
fi

helm upgrade --install sbomer-release "./$PLATFORM_DIR" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set global.includeKafka=true \
    --set global.includeApicurio=true \
    --set global.includeApiGateway=true

# 2. Enhancer Release
helm upgrade --install redhat-enhancer "./helm/redhat-enhancer-chart" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set image.repository=localhost/redhat-enhancer \
    --set image.tag=latest \
    --set image.pullPolicy=Never \
    --set config.kafka.bootstrapServers="sbomer-release-kafka:9092" \
    --set config.kafka.schemaRegistryUrl="http://sbomer-release-apicurio:8080/apis/registry/v2" \
    --set config.storageUrl="http://sbomer-release-service:8085" \
    --set pnc.host="$PNC_HOST" \
    --set indy.host="$INDY_HOST" \
    --set koji.hub.url="$KOJI_HUB_URL" \
    --set koji.web.url="$KOJI_WEB_URL" \
    --set koji.download.host="$KOJI_DOWNLOAD_HOST"

echo "--- Forcing Rolling Restart to pick up new local image ---"
# Using 'instance' is safer as it matches the release name we just set
kubectl rollout restart deployment -n $NAMESPACE -l app.kubernetes.io/instance=redhat-enhancer || true

echo "--- Deployment Complete ---"
echo "You can check status with: kubectl get pods -n $NAMESPACE"
echo "You can port-forward with: kubectl port-forward svc/sbomer-release-gateway 8080:8080 -n $NAMESPACE"