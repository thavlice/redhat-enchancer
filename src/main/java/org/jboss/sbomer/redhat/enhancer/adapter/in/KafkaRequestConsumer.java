package org.jboss.sbomer.redhat.enhancer.adapter.in;

import static org.jboss.sbomer.redhat.enhancer.core.ApplicationConstants.COMPONENT_NAME;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.sbomer.events.orchestration.EnhancementCreated;
import org.jboss.sbomer.redhat.enhancer.core.port.api.EnhancementOrchestrator;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.redhat.enhancer.core.utility.FailureUtility;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class KafkaRequestConsumer {

    @Inject
    EnhancementOrchestrator orchestrator;
    @Inject
    FailureNotifier failureNotifier;

    @Incoming("enhancement-created")
    public void receive(EnhancementCreated event) {
        try {
            log.debug("Received event ID: {}", event.getContext().getEventId());

            if (isMyEnhancer(event)) {
                log.info("{} received task for enhancement: {}", COMPONENT_NAME,
                        event.getData().getEnhancementId());

                orchestrator.acceptRequest(
                        event.getData().getEnhancementId(),
                        event.getData().getGenerationId(),
                        event.getData().getEnhancer().getOptions(),
                        event.getData().getInputSbomUrls()
                );
            }
        } catch (Exception e) {
            // Catch exceptions so we don't crash the consumer loop.
            log.error("Skipping malformed or incompatible event: {}", event, e);
            if (event != null) {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), event.getContext().getCorrelationId(), event);
            } else {
                failureNotifier.notify(FailureUtility.buildFailureSpecFromException(e), null, null);
            }

        }
    }

    // For now checks if just the enhancer name matches
    private boolean isMyEnhancer(EnhancementCreated event) {
        // Safety checks to prevent NPEs
        if (event.getData() == null
                || event.getData().getEnhancer() == null) {
            return false;
        }
        return COMPONENT_NAME.equals(event.getData().getEnhancer().getName());
    }
}
