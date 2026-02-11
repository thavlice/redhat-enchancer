package org.jboss.sbomer.redhat.enhancer.adapter.out;

import static org.jboss.sbomer.redhat.enhancer.core.ApplicationConstants.*;
import static org.jboss.sbomer.redhat.enhancer.core.service.TaskRunFactory.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.redhat.enhancer.core.domain.model.EnhancementTask;
import org.jboss.sbomer.redhat.enhancer.core.exception.EnhancementValidationException;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.EnhancementExecutor;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.redhat.enhancer.core.service.TaskRunFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.v1beta1.TaskRun;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class TektonEnhancementExecutor implements EnhancementExecutor {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    TaskRunFactory taskRunFactory;

    @Inject
    FailureNotifier failureNotifier;

    @ConfigProperty(name = "quarkus.kubernetes-client.namespace")
    String namespace;

    @Override
    public void scheduleEnhancement(EnhancementTask enhancementTask) {
        log.info("Scheduling TaskRun for enhancement: {}", enhancementTask.enhancementId());

        // Safety Check: Ensure options exist
        if (enhancementTask.enhancerOptions() == null || !enhancementTask.enhancerOptions().containsKey("type")) {
            throw new EnhancementValidationException(
                    "Enhancement " + enhancementTask.enhancementId() + " is missing the required 'type' expected by '" + COMPONENT_NAME + "' in enhancer options."
            );
        }

        TaskRun taskRun = null;
        String type = enhancementTask.enhancerOptions().get("type");

        // Factory Logic
        if (REDHAT_IMAGE_ENHANCER_SUBTYPE.equals(type)) {
            taskRun = taskRunFactory.createImageEnhancerTaskRun(enhancementTask);
        } else {
            // Throw Exception for Unknown Type
            throw new EnhancementValidationException(
                    String.format(
                            "Unsupported enhancer type '%s' for enhancement %s. Expected: %s",
                            type,
                            enhancementTask.enhancementId(),
                            REDHAT_IMAGE_ENHANCER_SUBTYPE
                    )
            );
        }

        // Execute against the cluster
        kubernetesClient.resources(TaskRun.class).inNamespace(namespace).resource(taskRun).create();
    }

    @Override
    public void abortEnhancement(String enhancementId) {
        log.info("Aborting enhancement: {}", enhancementId);
        kubernetesClient.resources(TaskRun.class)
                .inNamespace(namespace)
                .withLabel(LABEL_ENHANCEMENT_ID, enhancementId)
                .delete();
    }

    // In this specific implementation, basically same logic as abortGeneration
    @Override
    public void cleanupEnhancement(String enhancementId) {
        log.info("Cleaning up enhancement: {}", enhancementId);
        kubernetesClient.resources(TaskRun.class)
                .inNamespace(namespace)
                .withLabel(LABEL_ENHANCEMENT_ID, enhancementId)
                .delete();
    }

    @Override
    public int countActiveExecutions() {
        // Count TaskRuns for THIS enhancer that are NOT finished.
        // This is the input for the Throttling logic.
        return (int) kubernetesClient.resources(TaskRun.class).inNamespace(namespace)
                .withLabel(LABEL_ENHANCER_TYPE, LABEL_ENHANCER_VALUE)
                .list()
                .getItems()
                .stream()
                .filter(tr -> !isFinished(tr))
                .count();
    }

    /**
     * Helper to check Tekton Status Conditions
     */
    private boolean isFinished(TaskRun taskRun) {
        if (taskRun.getStatus() == null || taskRun.getStatus().getConditions() == null) {
            return false; // No status means it's initializing/running
        }

        // Check for "Succeeded" condition with Status "True" or "False" (False means failed, but it is still 'finished')
        return taskRun.getStatus().getConditions().stream()
                .anyMatch(c -> "Succeeded".equals(c.getType()) &&
                        ("True".equals(c.getStatus()) || "False".equals(c.getStatus())));
    }
}
