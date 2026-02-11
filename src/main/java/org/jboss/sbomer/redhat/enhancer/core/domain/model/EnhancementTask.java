package org.jboss.sbomer.redhat.enhancer.core.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Internal domain model representing a unit of work waiting in the queue.
 * It decouples the internal scheduling logic from the external Kafka event structure.
 */
public record EnhancementTask(
    String enhancementId,
    String generationId,
    Map<String, String> enhancerOptions,
    List<String> inputSbomUrls,
    int retryCount, // NOT max retries, the number of it retries it's currently on
    String memoryOverride // i.e. 2Gi
) {
    public EnhancementTask(String enhancementId, String generationId, Map<String, String> enhancerOptions, List<String> inputSbomUrls) {
        this(enhancementId, generationId, enhancerOptions, inputSbomUrls, 0, null);
    }
}
