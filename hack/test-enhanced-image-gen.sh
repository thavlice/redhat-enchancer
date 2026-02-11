#!/usr/bin/env bash

# TODO this script can be adapted to test a red hat-enhanced image generation

# Usage: ./test-enhanced-image-gen.sh [API_URL] [IMAGE_TO_SCAN]

# Configuration
API_URL="${1:-http://localhost:8080}"
IMAGE="${2:-quay.io/pct-security/mequal:latest}"
ENDPOINT="${API_URL}/api/v1/generations"

echo "Target: $ENDPOINT"
echo "Image:  $IMAGE"

# Construct JSON Payload
PAYLOAD=$(cat <<EOF
{
  "generationRequests": [
    {
      "target": {
        "type": "CONTAINER_IMAGE",
        "identifier": "${IMAGE}"
      }
    }
  ]
}
EOF
)

# Execute Request
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  "$ENDPOINT")

# Capture the exit code of the curl command
CURL_EXIT_CODE=$?
if [ $CURL_EXIT_CODE -ne 0 ]; then
    echo "Error: Connection failed (curl exit code: $CURL_EXIT_CODE)"
    exit 1
fi

# Process Response
HTTP_BODY=$(echo "$RESPONSE" | sed '$d')
HTTP_STATUS=$(echo "$RESPONSE" | tail -n 1)

# Ensure HTTP_STATUS is a 3-digit number
if [[ ! "$HTTP_STATUS" =~ ^[0-9]{3}$ ]]; then
    echo "Error: Failed to parse HTTP status code."
    echo "Full Response: $RESPONSE"
    exit 1
fi

if [[ "$HTTP_STATUS" == "202" ]]; then
    echo "Status: Success ($HTTP_STATUS)"

    if command -v jq &> /dev/null; then
        ID=$(echo "$HTTP_BODY" | jq -r '.id')
        echo "Generation Request ID: $ID"

        echo "Waiting for system to initialize generation request..."
        sleep 2

        echo "--- Initial Status ---"
        curl -s "$API_URL/api/v1/admin/requests/$ID/generations" | jq .

        echo ""
        echo "Status can be tracked via the command below:"
        echo "curl -s $API_URL/api/v1/admin/requests/$ID/generations | jq"
    else
        echo "Response: $HTTP_BODY"
    fi
else
    echo "Status: Failed ($HTTP_STATUS)"
    echo "Response: $HTTP_BODY"
    exit 1
fi