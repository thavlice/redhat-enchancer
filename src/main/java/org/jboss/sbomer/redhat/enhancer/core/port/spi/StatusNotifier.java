package org.jboss.sbomer.redhat.enhancer.core.port.spi;

import java.util.List;

import org.jboss.sbomer.redhat.enhancer.core.domain.EnhancementStatus;

/**
 * Driven Port (SPI) for notifying the core system (sbom-service) of state changes happening in an SBOM generation.
 * <p>
 * Adapters implementing this handle the transport layer (e.g., Kafka, HTTP Webhooks).
 * </p>
 */
public interface StatusNotifier {

    /**
     * Sends a status update event to the orchestrator (sbom-service).
     *
     * @param enhancementId The unique ID of the enhancement.
     * @param status       The new status (e.g., GENERATING, FINISHED, FAILED).
     * @param reason       Optional human-readable reason (e.g., "TaskRun started", "OOMKilled").
     * @param resultUrls   Optional list of URLs (only relevant for FINISHED status).
     */
    void notifyStatus(String enhancementId, EnhancementStatus status, String reason, List<String> resultUrls);
}
