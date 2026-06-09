package com.niit.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Query;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class KnowledgeSearchCore {

    @Autowired
    private VectorStore vectorStore;

    @Autowired(required = false)
    private UnifiedJedis unifiedJedis;

    @Value("${spring.ai.vectorstore.redis.index:vector_index}")
    private String redisIndexName;

    public List<Document> vectorSearch(String query, int topK) {
        try {
            return vectorStore.similaritySearch(SearchRequest.query(query).withTopK(topK));
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<redis.clients.jedis.search.Document> keywordSearch(String query, int topK, String scopeFilter) {
        if (unifiedJedis == null) {
            return Collections.emptyList();
        }
        try {
            String escaped = query.replaceAll("([^a-zA-Z0-9\\u4e00-\\u9fa5])", "\\\\$1");
            String filter = scopeFilter != null && !scopeFilter.isEmpty()
                    ? "@scope:{" + scopeFilter + "}"
                    : "@scope:{session|user|global}";
            String fullQuery = "(" + escaped + ") " + filter;
            redis.clients.jedis.search.SearchResult sr = unifiedJedis.ftSearch(
                    redisIndexName, new Query(fullQuery).limit(0, topK));
            if (sr != null && sr.getDocuments() != null) {
                return sr.getDocuments();
            }
        } catch (Exception e) {
            log.warn("关键词检索失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
