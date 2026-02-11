package org.jboss.sbomer.redhat.enhancer.core.service;

import static org.jboss.sbomer.redhat.enhancer.core.ApplicationConstants.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.redhat.enhancer.core.domain.model.EnhancementTask;

import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.tekton.v1beta1.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TaskRunFactory {

    @ConfigProperty(name = "sbomer.redhat-image-enhancer.task-name", defaultValue = "redhat-image-enhancer")
    String imageEnhancerTaskName;

    @ConfigProperty(name = "sbomer.service-account", defaultValue = "sbomer-sa")
    String serviceAccount;

    @ConfigProperty(name = "sbomer.storage.url")
    String storageUrl;

    public TaskRun createImageEnhancerTaskRun(EnhancementTask enhancementTask) {
        String enhancementId = enhancementTask.enhancementId();
        String generationId = enhancementTask.generationId();
        List<String> inputSbomUrls = enhancementTask.inputSbomUrls();

        // Extract config from the task object
        Map<String, String> options = enhancementTask.enhancerOptions();

        // Default to "undefined" but ideally this should be validated upstream
        String imageRef = options.getOrDefault("image-ref", "undefined");

        // Parse boolean safely (defaults to true if missing, adjusting to your preference)
        boolean rpms = Boolean.parseBoolean(options.getOrDefault("include-rpms", "true"));

        String processors = options.getOrDefault("processors", "default");

        // Default to "[]" so it is always valid JSON, even if empty.
        // Expects: A stringified JSON array, e.g., '["/opt/app", "/usr/bin"]'
        String paths = options.getOrDefault("paths", "[]");

        // 1. Prepare Parameters
        List<Param> params = new ArrayList<>();

        // ID & Storage
        params.add(new ParamBuilder().withName("enhancement-id").withValue(new ParamValue(enhancementId)).build());
        params.add(new ParamBuilder().withName("generation-id").withValue(new ParamValue(generationId)).build());
        params.add(new ParamBuilder().withName("storage-service-url").withValue(new ParamValue(storageUrl)).build());

        // Inputs
        params.add(new ParamBuilder().withName("input-sbom-urls").withValue(new ParamValue(inputSbomUrls)).build());

        // Logic Configuration
        params.add(new ParamBuilder().withName("image-ref").withValue(new ParamValue(imageRef)).build());
        params.add(new ParamBuilder().withName("rpms").withValue(new ParamValue(String.valueOf(rpms))).build());
        params.add(new ParamBuilder().withName("processors").withValue(new ParamValue(processors)).build());

        // Only add paths if strictly necessary or pass empty string (Tekton handles empty strings fine)
        params.add(new ParamBuilder().withName("paths").withValue(new ParamValue(paths)).build());

        // 2. Prepare Labels
        Map<String, String> labels = Map.of(
                LABEL_ENHANCEMENT_ID, enhancementId,
                LABEL_ENHANCER_TYPE, LABEL_ENHANCER_VALUE,
                "app.kubernetes.io/managed-by", "sbomer-redhat-enhancer"
        );

        // 3. Build Spec
        TaskRunSpecBuilder specBuilder = new TaskRunSpecBuilder()
                .withServiceAccountName(serviceAccount)
                .withParams(params)
                .withTaskRef(new TaskRefBuilder().withName(imageEnhancerTaskName).build())
                .withWorkspaces(
                        Collections.singletonList(
                                new WorkspaceBindingBuilder()
                                        .withName("data")
                                        .withEmptyDir(new EmptyDirVolumeSource())
                                        .build()
                        )
                );

        // 4. Memory Override
        if (enhancementTask.memoryOverride() != null) {
            specBuilder.addToStepOverrides(
                    new TaskRunStepOverrideBuilder()
                            .withName("prepare-and-enhance")
                            .withNewResources()
                            .withRequests(Map.of("memory", new Quantity(enhancementTask.memoryOverride())))
                            .withLimits(Map.of("memory", new Quantity(enhancementTask.memoryOverride())))
                            .endResources()
                            .build()
            );
        }

        // 5. Final Build
        return new TaskRunBuilder()
                .withNewMetadata()
                .withGenerateName("redhat-enh-" + shortenId(enhancementId) + "-")
                .withLabels(labels)
                .addToAnnotations("sbomer.jboss.org/retry-count", String.valueOf(enhancementTask.retryCount()))
                .endMetadata()
                .withSpec(specBuilder.build())
                .build();
    }

    private String shortenId(String id) {
        if (id == null) return "unknown";
        String safeId = id.toLowerCase();
        return safeId.length() > 8 ? safeId.substring(0, 8) : safeId;
    }
}
