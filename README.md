# Red Hat Enhancer Service (Experimental)

The **Red Hat Enhancer Service** is a specialized microservice within the SBOMer architecture responsible for executing Red Hat-specific SBOM enhancement requests.

It acts as a **Kubernetes Operator** that listens for enhancement events, manages a queue of work, and reconciles Tekton TaskRuns to enrich SBOMs with Red Hat-related information (e.g., downloading source code, adjusting manifests, and finding errata).

## Architecture

This service follows **Hexagonal Architecture (Ports and Adapters)** to decouple the scheduling logic from the execution infrastructure.



### 1. Core Domain (Business Logic)
* **`EnhancerService`:** The "Brain". It manages the internal work queue (Leaky Bucket pattern), enforces concurrency limits, and handles retry policies (e.g., OOM handling).
* **`TaskRunFactory`:** Translates generic `EnhancementTask` objects into specific Tekton `TaskRun` definitions (YAML), injecting configuration for PNC, Indy, and Koji based on the environment.

### 2. Driving Adapters (Input)
* **`KafkaRequestConsumer`:** Listens to the `enhancement.created` topic. If the request matches `enhancer.name=redhat-enhancer`, it queues it for execution.
* **`TaskReconciler`:** A Kubernetes Controller (using Java Operator SDK) that watches for `TaskRun` completion. It filters for `sbomer.jboss.org/enhancer-type=redhat-enhancer` and updates the core domain when a task succeeds or fails.

### 3. Driven Adapters (Output)
* **`TektonEnhancementExecutor`:** Uses the Fabric8 Kubernetes Client to create/delete TaskRuns in the cluster.
* **`KafkaStatusNotifier`:** Sends `enhancement.update` events (`ENHANCING`, `FINISHED`, `FAILED`) back to the `sbom-service` control plane.

---

## Features

### 1. Throttling & Queueing
To prevent overwhelming the Kubernetes cluster and external services (PNC/Koji), this service maintains an internal **Priority Queue**.
* **`sbomer.enhancer.max-concurrent`**: Controls how many TaskRuns can exist simultaneously.
* New requests are queued in memory.
* A scheduler runs every 10s to drain the queue into the cluster as slots become available.

### 2. Self-Healing (OOM Retries)
The service detects if a TaskRun was killed due to **Out Of Memory (OOM)** issues (common when analyzing large container images).
* **Detection:** The Reconciler parses the container termination reason (`OOMKilled`).
* **Reaction:** Instead of failing immediately, the service calculates a new memory limit (using a configurable multiplier) and re-schedules the task transparently.
* **Result:** The `sbom-service` only sees `ENHANCING` -> `FINISHED`, unaware of the retries happening in the background.

### 3. Atomic Batch Uploads
The enhanced SBOMs are uploaded directly from the TaskRun pod to the [Manifest Storage Service](https://github.com/sbomer-project/manifest-storage-service). The Enhancer Service receives the resulting URLs via the TaskRun results and passes them back to the Orchestrator.

---

## Configuration

| Property | Description | Default |
| :--- | :--- |:---|
| `sbomer.redhat-image-enhancer.task-name` | The Tekton Task name to instantiate. | `redhat-image-enhancer` |
| `sbomer.enhancer.max-concurrent` | Max active TaskRuns allowed. | `20` |
| `sbomer.enhancer.oom-retries` | Number of times to retry on OOM. | `3` |
| `sbomer.enhancer.memory-multiplier` | Factor to increase memory by on retry (e.g. 1.5x). | `1.5` |
| `sbomer.storage.url` | Internal URL of the storage service reachable by Pods. | `http://<svc-name>:8085` |
| `quarkus.kubernetes-client.namespace` | The namespace where TaskRuns are created. | `sbomer` |

---

## Development Environment Setup

We can run this component in a **Minikube Environment** by injecting it as part of the `sbomer-platform` helm chart.

We provide helper scripts in the `hack/` directory to automate the networking and configuration between these two environments.

### 1. Prerequisites
* **Podman** (for building images)
* **Minikube** (Kubernetes cluster)
* **Helm** (Package manager)
* **Maven** & **Java 17+**
* **Kubectl**

### 2. Prepare the Cluster
First, ensure you have the `sbomer` Minikube profile running with Tekton installed.

```bash
./hack/setup-local-dev.sh
```

### 3. Configure External Services (.env)

Since this component interacts with Red Hat internal services (PNC, Indy, Koji), you must provide their URLs via a `.env` file in the project root. This file is ignored by git.

**Create a `.env` file:**

```bash
PNC_HOST=value
INDY_HOST=value
KOJI_HUB_URL=value
KOJI_WEB_URL=value
KOJI_DOWNLOAD_HOST=value
```

### 4. Run the Component

Use the `./hack/run-helm-with-local-build.sh` script to start the system. This script performs several critical steps:

- Clones `sbomer-platform` to the component repo.
- Builds the `redhat-enhancer` image locally and loads it into Minikube.
- Injects the `.env` configuration into the Helm chart values.
- Installs the `sbomer-platform` helm chart with your locally built component.

```bash
./hack/run-helm-with-local-build.sh
```

### 5. Verification

Once deployed, you can verify the service is running:

```bash
# Check the Enhancer Pod
kubectl get pods -n sbomer-test -l app.kubernetes.io/instance=redhat-enhancer

# Check logs to confirm it connected to Kubernetes and Kafka
kubectl logs -f -n sbomer-test -l app.kubernetes.io/instance=redhat-enhancer
```

### 6. Triggering an Enhancement (Manual Test) (WIP - Need to update request definition)

To test the full flow, you can trigger a generation request via the `sbom-service` API (exposed via the gateway).

First, expose the gateway:
```bash
# Port-forward the API Gateway
kubectl port-forward svc/sbomer-release-gateway 8080:8080 -n sbomer-test
```
Then, send a request (example):
```bash
curl -X POST http://localhost:8080/api/v1/sboms/generate \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "[registry.access.redhat.com/ubi8/ubi:latest](https://registry.access.redhat.com/ubi8/ubi:latest)",
    "type": "CONTAINER",
    "generator": "syft",
    "processors": ["default"]
  }'
```
You can watch the resulting TaskRun in the cluster:
```bash
kubectl get taskruns -n sbomer-test -w
```
