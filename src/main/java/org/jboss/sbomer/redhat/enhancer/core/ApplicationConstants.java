package org.jboss.sbomer.redhat.enhancer.core;

public class ApplicationConstants {
    private ApplicationConstants() {}

    public static final String COMPONENT_NAME = "redhat-enhancer";

    // --- LABELS ---
    public static final String LABEL_ENHANCEMENT_ID = "sbomer.jboss.org/enhancement-id";
    public static final String LABEL_ENHANCER_TYPE = "sbomer.jboss.org/enhancer-type";

    // This is the generic value your Reconciler is now watching for
    public static final String LABEL_ENHANCER_VALUE = "redhat-enhancer";

    // Keep this specific constant for Factory logic (dispatching)
    // This matches the 'type' field in the JSON request from the Orchestrator
    public static final String REDHAT_IMAGE_ENHANCER_SUBTYPE = "redhat-image-enhancer";
}
