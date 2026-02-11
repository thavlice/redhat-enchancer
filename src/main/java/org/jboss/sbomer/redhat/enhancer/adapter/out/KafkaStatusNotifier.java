package org.jboss.sbomer.redhat.enhancer.adapter.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.sbomer.events.common.ContextSpec;
import org.jboss.sbomer.events.enhancer.EnhancementUpdate;
import org.jboss.sbomer.events.enhancer.EnhancementUpdateData;
import org.jboss.sbomer.redhat.enhancer.core.ApplicationConstants;
import org.jboss.sbomer.redhat.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.StatusNotifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class KafkaStatusNotifier implements StatusNotifier {

    @Inject
    @Channel("enhancement-update")
    Emitter<EnhancementUpdate> emitter;

    @Override
    public void notifyStatus(String enhancementId, EnhancementStatus status, String reason, List<String> resultUrls) {

        log.info("Preparing to send status update: ID={} Status={}", enhancementId, status);

        EnhancementUpdateData data = EnhancementUpdateData.newBuilder()
                .setEnhancementId(enhancementId)
                .setStatus(status.name())
                .setReason(reason)
                .setResultCode(status == EnhancementStatus.FAILED ? 1 : 0)
                .setEnhancedSbomUrls(resultUrls)
                .build();

        EnhancementUpdate event = EnhancementUpdate.newBuilder()
                .setContext(createContext())
                .setData(data)
                .build();

        emitter.send(event).whenComplete((success, error) -> {
            if (error != null) {
                log.error("FAILED to send status update for enhancement {}", enhancementId, error);
            } else {
                log.debug("Successfully sent status update for enhancement {}", enhancementId);
            }
        });
    }

    private ContextSpec createContext() {
        return ContextSpec.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setSource(ApplicationConstants.COMPONENT_NAME)
                .setType("EnhancementUpdate")
                .setTimestamp(Instant.now())
                .setEventVersion("1.0")
                .build();
    }
}
