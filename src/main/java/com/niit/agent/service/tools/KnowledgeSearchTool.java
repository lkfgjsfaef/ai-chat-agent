package com.niit.agent.service.tools;

import com.niit.agent.common.constant.Constants;
import com.niit.agent.common.util.TextUtils;
import com.niit.agent.service.KnowledgeSearchCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeSearchTool implements ToolHandler {

    @Autowired
    private KnowledgeSearchCore searchCore;

    @Override
    public String getName() {
        return "search_knowledge";
    }

    @Override
    public String getDescription() {
        return "在当前会话关联的知识库、用户知识库和全局知识库中搜索相关内容。可用于查找文档中的具体信息、配置参数、操作步骤等。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "搜索查询关键词或问题");
        properties.put("query", query);

        Map<String, Object> scope = new HashMap<>();
        scope.put("type", "string");
        scope.put("description", "知识库范围: session(当前会话), user(个人), global(全局), 不填则搜索所有范围");
        scope.put("enum", List.of(Constants.SCOPE_SESSION, Constants.SCOPE_USER, Constants.SCOPE_GLOBAL));
        properties.put("scope", scope);

        Map<String, Object> topK = new HashMap<>();
        topK.put("type", "integer");
        topK.put("description", "返回结果数量，默认5");
        properties.put("topK", topK);

        parameters.put("properties", properties);
        parameters.put("required", List.of("query"));
        return parameters;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String query = (String) params.get("query");
        if (query == null || query.trim().isEmpty()) {
            return "错误：必须提供 query 参数";
        }

        String scopeFilter = (String) params.get("scope");
        int topK = params.get("topK") instanceof Number n ? n.intValue() : 5;
        topK = Math.max(1, Math.min(topK, 10));

        try {
            List<SearchResult> results = hybridSearch(query.trim(), topK, scopeFilter);

            if (results.isEmpty()) {
                return "未在知识库中搜索到与 \"" + query.trim() + "\" 相关的内容。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("知识库检索结果（共 ").append(results.size()).append(" 条）：\n\n");
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append("--- 结果 ").append(i + 1);
                if (r.source != null && !r.source.isEmpty()) {
                    sb.append(" [来源: ").append(r.source).append("]");
                }
                sb.append(" ---\n");
                sb.append(TextUtils.truncate(r.content, 1200)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("KnowledgeSearchTool 搜索失败: {}", e.getMessage());
            return "知识库搜索失败: " + e.getMessage();
        }
    }

    private List<SearchResult> hybridSearch(String query, int topK, String scopeFilter) {
        Map<String, SearchResult> merged = new LinkedHashMap<>();

        List<Document> docs = searchCore.vectorSearch(query, topK * 2);
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            String scope = stringValue(meta.get("scope"));
            if (scopeFilter != null && !scopeFilter.equals(scope)) continue;
            String content = doc.getContent();
            if (content == null || content.isBlank()) continue;
            merged.putIfAbsent(content, new SearchResult(content,
                    buildSourceLabel(scope, stringValue(meta.get("file_name"))), meta));
        }

        List<redis.clients.jedis.search.Document> keywordDocs = searchCore.keywordSearch(query, topK * 2, scopeFilter);
        for (redis.clients.jedis.search.Document doc : keywordDocs) {
            String scope = doc.getString("scope");
            if (scopeFilter != null && !scopeFilter.equals(scope)) continue;
            String content = doc.getString("content");
            if (content == null || content.isBlank()) continue;
            merged.putIfAbsent(content, new SearchResult(content,
                    buildSourceLabel(scope, doc.getString("file_name")), null));
        }

        return merged.values().stream().limit(topK).collect(Collectors.toList());
    }

    private String buildSourceLabel(String scope, String fileName) {
        if (fileName != null && !fileName.isEmpty()) {
            return (scope != null ? scope : "") + "/" + fileName;
        }
        return scope;
    }

    private String stringValue(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private record SearchResult(String content, String source, Map<String, Object> meta) {}
}
