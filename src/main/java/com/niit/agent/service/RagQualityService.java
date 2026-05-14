package com.niit.agent.service;

import java.util.Map;

public interface RagQualityService {

    /**
     * 获取 RAG 检索质量指标，包括：
     * - 总体 recall@K, precision@K
     * - 按时间维度（1h, 24h, 7d）的趋势
     * - 按 scope 分组
     */
    Map<String, Object> getQualityMetrics();
}
