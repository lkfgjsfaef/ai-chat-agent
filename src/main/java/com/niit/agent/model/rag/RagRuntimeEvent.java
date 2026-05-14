package com.niit.agent.model.rag;

public record RagRuntimeEvent(long timestampMs, boolean enabled, String outcome, boolean cacheHit) {
}
