package com.niit.agent.model.rag;

public record RankedRetrievalCandidate(String content, String sourceLabel, double score) {
}
