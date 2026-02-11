package org.jboss.sbomer.redhat.enhancer.core.service;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.redhat.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.redhat.enhancer.core.domain.model.EnhancementTask;
import org.jboss.sbomer.redhat.enhancer.core.port.api.EnhancementOrchestrator;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.EnhancementExecutor;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.StatusNotifier;
import org.jboss.sbomer.redhat.enhancer.core.utility.FailureUtility;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class EnhancerService implements EnhancementOrchestrator {

    @Inject
    EnhancementExecutor executor;

    @Inject
    StatusNotifier notifier;

    @Inject
    FailureNotifier failureNotifier;

    @ConfigProperty(name = "sbomer.enhancer.max-concurrent", defaultValue = "20")
    int maxConcurrent;

    // Config: How many times to retry OOM?
    @ConfigProperty(name = "sbomer.enhancer.oom-retries", defaultValue = "3")
    int maxOomRetries;

    // Config: Multiplier (e.g. 2.0 = double memory each time)
    @ConfigProperty(name = "sbomer.enhancer.memory-multiplier", defaultValue = "1.5")
    double memoryMultiplier;

    // Default memory to start multiplying from (if not defined in original request)
    @ConfigProperty(name = "sbomer.enhancer.default-memory", defaultValue = "1Gi")
    String defaultMemory;

    // In-memory buffer (FOR NOW - SHOULD LATER BE PERSISTENT)
    private final Queue<EnhancementTask> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, EnhancementTask> activeTasks = new ConcurrentHashMap<>();

    @Override
    public void acceptRequest(String enhancementId, String generationId, Map<String, String> enhancerOptions, List<String> inputSbomUrls) {
        log.info("Accepted request for enhancement: {} of generation: {}", enhancementId, generationId);
        // We don't execute immediately, we queue it to respect the throttling limit
        pendingQueue.add(new EnhancementTask(enhancementId, generationId, enhancerOptions, inputSbomUrls));
    }

    @Override
    public void handleUpdate(String enhancementId, EnhancementStatus status, String reason, List<String> resultUrls) {
        log.info("Handling update for enhancement {}: {}", enhancementId, status);

        // If we hit OOM, we retry with more resources
        if (status == EnhancementStatus.FAILED && "OOMKilled".equals(reason)) {
            handleOomRetry(enhancementId);
            return; // Stop here. Method will do its own notification if needed
        }

        // Notify the status (sbom-service will listen to this)
        notifier.notifyStatus(enhancementId, status, reason, resultUrls);

        // If it was a running job that finished, trigger a cleanup
        // via the executor (e.g. delete the TaskRun)
        doCleanupIfFinished(enhancementId, status);
    }

    @Scheduled(every = "{sbomer.generator.poll-interval:10s}")
    public void processQueue() {
        if (pendingQueue.isEmpty()) {
            return;
        }

        int activeCount = executor.countActiveExecutions();
        int slots = maxConcurrent - activeCount;

        if (slots <= 0) {
            log.debug("Cluster at capacity ({}/{})", activeCount, maxConcurrent);
            return;
        }

        log.info("Cluster has capacity. Scheduling {} tasks...", slots);

        for (int i = 0; i < slots; i++) {
            EnhancementTask task = pendingQueue.poll();
            if (task == null) {
                break;
            }

            try {
                // Put into active tasks
                activeTasks.put(task.enhancementId(), task);

                executor.scheduleEnhancement(task);

                // Send an event out to declare it has started generating
                notifier.notifyStatus(
                        task.enhancementId(),
                        EnhancementStatus.ENHANCING,
                        "Scheduled in execution environment",
                        null
                );

            } catch (Exception e) {
                log.error("Failed to schedule generation {}", task.enhancementId(), e);
                notifier.notifyStatus(task.enhancementId(), EnhancementStatus.FAILED, e.getMessage(), null);
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), task.enhancementId(), null);
                doCleanupIfFinished(task.enhancementId(), EnhancementStatus.FAILED);
            }
        }
    }

    private void handleOomRetry(String enhancementId) {
        EnhancementTask task = activeTasks.get(enhancementId);
        if (task == null) {
            log.warn("Cannot retry OOM for {}, task state lost.", enhancementId);
            notifier.notifyStatus(enhancementId, EnhancementStatus.FAILED, "OOMKilled (Retry failed - state lost)", null);
            return;
        }

        if (task.retryCount() >= maxOomRetries) {
            log.warn("Max OOM retries reached for {}. Giving up.", enhancementId);
            // We fail the task, notify it failed, and do cleanup
            EnhancementStatus newStatus = EnhancementStatus.FAILED;
            notifier.notifyStatus(enhancementId, newStatus, "OOMKilled (Max retries exceeded)", null);
            doCleanupIfFinished(enhancementId, newStatus);
            return;
        }

        // Calculate new memory
        String currentMemory = task.memoryOverride() != null ? task.memoryOverride() : defaultMemory;
        String newMemory = calculateNewMemory(currentMemory);

        log.info("Retrying {} due to OOM. Attempt {}/{}. Increasing memory: {} -> {}",
                enhancementId, task.retryCount() + 1, maxOomRetries, currentMemory, newMemory);

        // Create new task with incremented count and new memory
        EnhancementTask retryTask = new EnhancementTask(
                task.enhancementId(),
                task.generationId(),
                task.enhancerOptions(),
                task.inputSbomUrls(),
                task.retryCount() + 1,
                newMemory
        );

        // Update state and re-queue
        activeTasks.put(enhancementId, retryTask);
        pendingQueue.add(retryTask);
    }

    private String calculateNewMemory(String current) {
        // Simple parser assuming "Gi" or "Mi"
        // For robust parsing, use Fabric8 Quantity class or regex
        // Logic: 1Gi -> 2Gi
        try {
            // Quick hack for PoC: assume Gi
            double val = Double.parseDouble(current.replace("Gi", "").replace("Mi", ""));
            // If Mi, convert to Gi for simplicity or just multiply
            if (current.endsWith("Mi")) val = val / 1024.0;

            double newVal = val * memoryMultiplier;
            return (int)Math.ceil(newVal) + "Gi";
        } catch (Exception e) {
            return "2Gi"; // Fallback
        }
    }

    private void doCleanupIfFinished(String enhancementId, EnhancementStatus status) {
        if (status == EnhancementStatus.FINISHED || status == EnhancementStatus.FAILED) {
            activeTasks.remove(enhancementId);
            executor.cleanupEnhancement(enhancementId);
        }
    }

}