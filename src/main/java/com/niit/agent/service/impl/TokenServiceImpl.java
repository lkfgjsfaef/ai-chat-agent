package com.niit.agent.service.impl;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.niit.agent.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TokenServiceImpl implements TokenService {

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    // 使用 CL100K_BASE 编码，适用于 GPT-4, GPT-3.5, GLM-4 等主流大模型
    private final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            log.warn("Token 计算失败, 降级使用估算值: {}", e.getMessage());
            return (int) Math.ceil(text.length() * 1.5);
        }
    }

    @Override
    public int countContextTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        // 计算总Token数，由于 List<Map> 中有的可能是字符串，有的是 Object，我们需要详细累加
        int totalTokens = 0;
        for (Map<String, Object> msg : messages) {
            totalTokens += 4; // role 和换行等基础开销
            
            Object contentObj = msg.get("content");
            if (contentObj instanceof String) {
                totalTokens += countTokens((String) contentObj);
            }
            
            Object toolCallsObj = msg.get("tool_calls");
            if (toolCallsObj instanceof List) {
                totalTokens += ((List<?>) toolCallsObj).size() * 20; 
            }
        }
        totalTokens += 3; // 助手回复的基础开销
        return totalTokens;
    }

    @Override
    public List<Map<String, Object>> truncateContext(List<Map<String, Object>> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int currentTokens = countContextTokens(messages);
        if (currentTokens <= maxTokens) {
            return messages;
        }

        log.info("当前上下文 Token 数 [{}] 超过最大限制 [{}], 执行滑动窗口截断", currentTokens, maxTokens);
        
        List<Map<String, Object>> systemMessages = new ArrayList<>();
        List<Map<String, Object>> nonSystemMessages = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if ("system".equals(role)) {
                systemMessages.add(msg);
            } else {
                nonSystemMessages.add(msg);
            }
        }

        int systemTokens = countContextTokens(systemMessages);
        int latestUserIndex = findLatestUserIndex(nonSystemMessages);
        if (systemTokens >= maxTokens) {
            log.warn("System 消息已经超过最大限制，优先保留最新用户问题");
            List<Map<String, Object>> result = trimSystemMessages(systemMessages, maxTokens);
            appendLatestUserIfPossible(result, nonSystemMessages, latestUserIndex, maxTokens);
            return result;
        }

        int availableTokens = maxTokens - systemTokens;
        List<Map<String, Object>> keptConversation = new ArrayList<>();
        int keptConversationTokens = 0;

        for (int i = nonSystemMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = nonSystemMessages.get(i);
            int msgTokens = countContextTokens(List.of(msg));

            if (i == latestUserIndex) {
                Map<String, Object> latestUserMessage = fitMessageWithinBudget(msg, availableTokens);
                if (latestUserMessage != null) {
                    keptConversation.add(0, latestUserMessage);
                    keptConversationTokens += countContextTokens(List.of(latestUserMessage));
                }
                continue;
            }

            if (keptConversationTokens + msgTokens > availableTokens) {
                continue;
            }
            keptConversation.add(0, msg);
            keptConversationTokens += msgTokens;
        }

        List<Map<String, Object>> finalContext = new ArrayList<>(systemMessages);
        finalContext.addAll(keptConversation);

        log.info("截断完成, 保留了 {} 条消息, 预计 Token 数: {}", finalContext.size(), countContextTokens(finalContext));
        return finalContext;
    }

    private int findLatestUserIndex(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return i;
            }
        }
        return -1;
    }

    private List<Map<String, Object>> trimSystemMessages(List<Map<String, Object>> systemMessages, int maxTokens) {
        List<Map<String, Object>> result = new ArrayList<>();
        int used = 0;
        for (Map<String, Object> systemMessage : systemMessages) {
            int tokens = countContextTokens(List.of(systemMessage));
            if (used + tokens <= maxTokens) {
                result.add(systemMessage);
                used += tokens;
                continue;
            }
            Map<String, Object> trimmed = fitMessageWithinBudget(systemMessage, Math.max(maxTokens - used, 0));
            if (trimmed != null) {
                result.add(trimmed);
            }
            break;
        }
        return result;
    }

    private void appendLatestUserIfPossible(List<Map<String, Object>> baseMessages, List<Map<String, Object>> nonSystemMessages,
                                            int latestUserIndex, int maxTokens) {
        if (latestUserIndex < 0 || latestUserIndex >= nonSystemMessages.size()) {
            return;
        }
        int currentTokens = countContextTokens(baseMessages);
        int remainingBudget = Math.max(maxTokens - currentTokens, 0);
        Map<String, Object> fitted = fitMessageWithinBudget(nonSystemMessages.get(latestUserIndex), remainingBudget);
        if (fitted != null) {
            baseMessages.add(fitted);
        }
    }

    private Map<String, Object> fitMessageWithinBudget(Map<String, Object> message, int availableTokens) {
        if (message == null || availableTokens <= 0) {
            return null;
        }
        int currentTokens = countContextTokens(List.of(message));
        if (currentTokens <= availableTokens) {
            return message;
        }
        Object contentObj = message.get("content");
        if (!(contentObj instanceof String content) || content.isBlank()) {
            return null;
        }

        int low = 0;
        int high = content.length();
        String best = "";
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidateContent = content.substring(Math.max(0, content.length() - mid)).trim();
            Map<String, Object> candidateMessage = new HashMap<>(message);
            candidateMessage.put("content", candidateContent);
            int candidateTokens = countContextTokens(List.of(candidateMessage));
            if (candidateTokens <= availableTokens) {
                best = candidateContent;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (best.isBlank()) {
            return null;
        }
        Map<String, Object> trimmedMessage = new HashMap<>(message);
        trimmedMessage.put("content", "[上下文过长，已保留末尾关键信息]\n" + best);
        return trimmedMessage;
    }
}
