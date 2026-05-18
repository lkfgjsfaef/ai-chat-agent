package com.niit.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.niit.agent.common.util.ZhipuAuthUtil;
import com.niit.agent.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ZhipuRerankServiceImpl implements RerankService {

    @Value("${spring.ai.zhipuai.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(20, 3, TimeUnit.MINUTES))
            .build();

    private final Cache<Integer, List<RerankResult>> rerankCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();

    @Override
    public List<RerankResult> rerankWithScores(String query, List<String> documents, int topK) {
        if (documents == null || documents.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return buildFallbackResults(documents, topK);
        }

        int cacheKey = Objects.hash(query, documents);
        List<RerankResult> cached = rerankCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("命中Reranker缓存: query={}, candidates={}", query, documents.size());
            return cached.size() <= topK ? cached : cached.subList(0, topK);
        }

        try {
            // 智谱官方 Rerank V4 接口标准
            String actualUrl = "https://open.bigmodel.cn/api/paas/v4/rerank";
            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("model", "rerank-1"); // 智谱官方唯一的重排模型标识
            bodyMap.put("query", query);
            bodyMap.put("documents", documents);
            bodyMap.put("top_n", Math.min(topK, documents.size())); // 防止 top_n 大于实际文档数导致 400 错误

            String jsonBody = objectMapper.writeValueAsString(bodyMap);
            
            // 智谱原生接口必须使用 JWT 鉴权
            String jwtToken = ZhipuAuthUtil.generateToken(apiKey);
            
            Request request = new Request.Builder()
                    .url(actualUrl)
                    .addHeader("Authorization", "Bearer " + jwtToken)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonNode rootNode = objectMapper.readTree(responseBody);
                    JsonNode resultsNode = rootNode.get("results");
                    
                    if (resultsNode != null && resultsNode.isArray()) {
                        List<RerankResult> rerankedDocs = new ArrayList<>();
                        for (JsonNode node : resultsNode) {
                            int index = node.get("index").asInt();
                            if (index >= 0 && index < documents.size()) {
                                double score = extractScore(node);
                                rerankedDocs.add(new RerankResult(documents.get(index), score));
                            }
                        }
                        rerankCache.put(cacheKey, rerankedDocs);
                        log.info("Rerank 完成, 从 {} 篇文档中重排提取了 {} 篇", documents.size(), rerankedDocs.size());
                        return rerankedDocs;
                    }
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "empty";
                    log.error("Rerank API 调用失败: HTTP {} - {}", response.code(), errorBody);
                }
            }
        } catch (Exception e) {
            log.error("Rerank 重排处理异常: {}", e.getMessage());
        }
        
        // 降级：如果 Rerank 失败，直接返回原始列表的前 Top K
        return buildFallbackResults(documents, topK);
    }

    private double extractScore(JsonNode node) {
        if (node.has("relevance_score")) {
            return node.get("relevance_score").asDouble(0.0);
        }
        if (node.has("score")) {
            return node.get("score").asDouble(0.0);
        }
        return 0.0;
    }

    private List<RerankResult> buildFallbackResults(List<String> documents, int topK) {
        List<RerankResult> results = new ArrayList<>();
        int limit = Math.min(topK, documents.size());
        for (int i = 0; i < limit; i++) {
            results.add(new RerankResult(documents.get(i), Math.max(0.0, 1.0 - i * 0.1)));
        }
        return results;
    }
}
