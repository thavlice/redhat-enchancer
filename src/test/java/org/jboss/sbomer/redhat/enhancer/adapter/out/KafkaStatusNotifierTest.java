package org.jboss.sbomer.redhat.enhancer.adapter.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.sbomer.events.enhancer.EnhancementUpdate;
import org.jboss.sbomer.redhat.enhancer.core.domain.EnhancementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

@QuarkusTest
class KafkaStatusNotifierTest {

    @Inject
    KafkaStatusNotifier notifier;

    // Inject the In-Memory Connector to inspect channels
    @Inject
    @Any
    InMemoryConnector connector;

    @BeforeEach
    void clear() {
        // Clear the sink before each test to ensure no data bleed-over
        connector.sink("enhancement-update").clear();
    }

    @Test
    void testNotifyStatusSuccess() {
        // 1. Act
        notifier.notifyStatus("ENH-123", EnhancementStatus.FINISHED, "Success", List.of("http://url"));

        // 2. Assert (Check memory sink)
        InMemorySink<EnhancementUpdate> results = connector.sink("enhancement-update");
        assertEquals(1, results.received().size());

        Message<EnhancementUpdate> message = results.received().get(0);
        EnhancementUpdate event = message.getPayload();

        // Validate content
        assertEquals("ENH-123", event.getData().getEnhancementId());
        assertEquals("FINISHED", event.getData().getStatus());
        assertEquals(0, event.getData().getResultCode()); // 0 for Success
        assertEquals("http://url", event.getData().getEnhancedSbomUrls().get(0));

        // Validate Context
        assertNotNull(event.getContext().getEventId());
        assertEquals("redhat-enhancer", event.getContext().getSource());
    }

    @Test
    void testNotifyStatusFailed() {
        // 1. Act
        notifier.notifyStatus("ENH-FAIL", EnhancementStatus.FAILED, "Something exploded", null);

        // 2. Assert
        InMemorySink<EnhancementUpdate> results = connector.sink("enhancement-update");
        assertEquals(1, results.received().size());

        EnhancementUpdate event = results.received().get(0).getPayload();

        assertEquals("ENH-FAIL", event.getData().getEnhancementId());
        assertEquals("FAILED", event.getData().getStatus());
        assertEquals(1, event.getData().getResultCode()); // 1 for Failure
        assertEquals("Something exploded", event.getData().getReason());
    }
}