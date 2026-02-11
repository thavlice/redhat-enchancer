package org.jboss.sbomer.redhat.enhancer.core.port.spi;

import org.jboss.sbomer.redhat.enhancer.core.domain.model.EnhancementTask;

/**
 * Driven Port (SPI) for executing the actual enhancement work.
 * <p>
 * Adapters implementing this interface handle the interaction with the
 * execution environment (e.g., Kubernetes, Tekton, Jenkins, Local Process).
 * </p>
 */
public interface EnhancementExecutor {

    /**
     * Schedules the generation payload for execution.
     * <p>
     * In a Kubernetes/Tekton implementation, this creates the TaskRun resource.
     * </p>
     *
     * @param enhancementTask The object carrying information about an enhancement task
     */
    void scheduleEnhancement(EnhancementTask enhancementTask);

    /**
     * Aborts resources associated with a specific enhancement.
     * <p>
     * Used for manual cancellation.
     * </p>
     *
     * @param enhancementId The unique ID to identify the resources.
     */
    void abortEnhancement(String enhancementId);

    /**
     * Cleans up resources associated with a specific enhancement.
     * <p>
     * Used for cleaning up the environment after the enhancement has ended.
     * </p>
     *
     * @param enhancementId The unique ID to identify the resources.
     */
    void cleanupEnhancement(String enhancementId);

    /**
     * Returns the number of currently active/running executions managed by this enhancer.
     * <p>
     * This is critical for the Core Domain's "Throttling" logic.
     * </p>
     *
     * @return count of active jobs.
     */
    int countActiveExecutions();
}
