package com.niit.agent.service;

import com.niit.agent.entity.ChatSummary;

import java.util.List;
import java.util.Map;

public interface MemoryService {

    List<Map<String, Object>> buildContext(Long sessionId);

    default List<Map<String, Object>> buildContext(Long sessionId, String overrideSkillId) {
        return buildContext(sessionId);
    }

    void saveUserMessage(Long sessionId, String content);

    void saveAssistantMessage(Long sessionId, String content);

    void triggerSummaryIfNeeded(Long sessionId);

    ChatSummary getSummary(Long sessionId);

    void updateSummary(Long sessionId, String summary, Long lastSummarizedMessageId);

    Map<String, Object> getRagRuntimeMetrics();
}
