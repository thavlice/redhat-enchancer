package org.jboss.sbomer.redhat.enhancer.core.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jboss.sbomer.redhat.enhancer.core.domain.EnhancementStatus;
import org.jboss.sbomer.redhat.enhancer.core.domain.model.EnhancementTask;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.EnhancementExecutor;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.FailureNotifier;
import org.jboss.sbomer.redhat.enhancer.core.port.spi.StatusNotifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class EnhancerServiceTest {

    @Inject
    EnhancerService enhancerService;

    @InjectMock
    EnhancementExecutor executor;

    @InjectMock
    StatusNotifier notifier;

    @InjectMock
    FailureNotifier failureNotifier;

    @BeforeEach
    void setup() {
        // Default behavior: Cluster is empty (0 active executions)
        when(executor.countActiveExecutions()).thenReturn(0);
    }

    @Test
    void testHappyPathScheduling() {
        String enhId = "ENH-123";
        String genId = "GEN-456";
        Map<String, String> options = Map.of("type", "redhat-image-enhancer");
        List<String> urls = List.of("http://sbom.url");

        // 1. Queue the request
        enhancerService.acceptRequest(enhId, genId, options, urls);

        // 2. Trigger the scheduler manually
        enhancerService.processQueue();

        // 3. Executor should be called to create the TaskRun
        verify(executor, times(1)).scheduleEnhancement(argThat(task ->
                task.enhancementId().equals(enhId)
                        && task.generationId().equals(genId)
                        && task.retryCount() == 0
        ));

        // 4. Notification sent (ENHANCING)
        verify(notifier).notifyStatus(
                eq(enhId),
                eq(EnhancementStatus.ENHANCING),
                any(),
                isNull() // URLs are null for status updates
        );
    }

    @Test
    void testThrottling() {
        // Simulate cluster is FULL (Max is 20 by default)
        when(executor.countActiveExecutions()).thenReturn(20);

        // Queue a request
        enhancerService.acceptRequest("ENH-THROTTLE", "GEN-999", Collections.emptyMap(), Collections.emptyList());

        // Trigger scheduler
        enhancerService.processQueue();

        // NOTHING should happen because cluster is full
        verify(executor, never()).scheduleEnhancement(any());
        verify(notifier, never()).notifyStatus(any(), any(), any(), any());
    }

    @Test
    void testOomRetryLogic() {
        String enhId = "ENH-OOM";
        String genId = "GEN-OOM";

        // 1. We must have an active task running first to retry it.
        // Queue it -> Process it -> It becomes "Active" inside the service map
        enhancerService.acceptRequest(enhId, genId, Collections.emptyMap(), Collections.emptyList());
        enhancerService.processQueue();

        // Clear mocks so we don't count the initial scheduling interaction
        clearInvocations(executor, notifier);

        // 2. Simulate the Reconciler reporting an OOM Failure
        // The service sees "OOMKilled" and should trigger internal retry logic
        enhancerService.handleUpdate(enhId, EnhancementStatus.FAILED, "OOMKilled", null);

        // 3. It should NOT notify the core system of failure yet (Silent Retry)
        verify(notifier, never()).notifyStatus(any(), any(), any(), any());

        // 4. It SHOULD re-queue the task internally
        // Trigger scheduler again to pick up the new "Retry Task"
        enhancerService.processQueue();

        // 5. Capture the task passed to the executor to verify memory override
        var taskCaptor = ArgumentCaptor.forClass(EnhancementTask.class);
        verify(executor).scheduleEnhancement(taskCaptor.capture());

        EnhancementTask retryTask = taskCaptor.getValue();

        // Validate Retry Logic
        Assertions.assertEquals(1, retryTask.retryCount());
        // Default 1Gi * 1.5 (default multiplier) = 2Gi (Ceiling logic in service)
        Assertions.assertEquals("2Gi", retryTask.memoryOverride());
    }
}