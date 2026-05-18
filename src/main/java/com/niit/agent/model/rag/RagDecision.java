package com.niit.agent.model.rag;

public record RagDecision(boolean enabled, String reason, String retrievalScope) {

    public static final String SCOPE_FOCUSED = "focused";
    public static final String SCOPE_BROAD = "broad";
    public static final String SCOPE_FULL = "full";

    public RagDecision(boolean enabled, String reason) {
        this(enabled, reason, SCOPE_FOCUSED);
    }
}
