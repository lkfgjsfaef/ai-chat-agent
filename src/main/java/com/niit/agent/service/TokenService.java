package com.niit.agent.service;

import java.util.List;
import java.util.Map;

public interface TokenService {

    /**
     * 精确计算给定文本的 Token 数量
     */
    int countTokens(String text);

    /**
     * 精确计算整个消息上下文的 Token 数量
     */
    int countContextTokens(List<Map<String, Object>> messages);

    /**
     * 基于滑动窗口策略，截断消息上下文使其不超过最大 Token 限制
     * 保留 system 消息，从最早的 user/assistant 消息开始移除
     */
    List<Map<String, Object>> truncateContext(List<Map<String, Object>> messages, int maxTokens);
}
