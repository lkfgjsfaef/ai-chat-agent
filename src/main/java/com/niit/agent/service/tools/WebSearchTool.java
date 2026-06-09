package com.niit.agent.service.tools;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(name = "ai.tools.web-search.enabled", havingValue = "true")
public class WebSearchTool implements ToolHandler {

    @Autowired
    private ConnectionPool connectionPool;

    private OkHttpClient client;

    @PostConstruct
    public void initClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .connectionPool(connectionPool)
                .build();
    }

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result-link[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<td[^>]*class=\"result-snippet[^\"]*\"[^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Value("${ai.tools.web-search.max-results:5}")
    private int maxResults;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "在互联网上搜索最新的信息、新闻、技术文档等。当知识库无法回答时使用，或查询需要最新信息的问题。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "搜索关键词，尽量使用精确的技术术语");
        properties.put("query", query);

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

        try {
            List<WebResult> results = search(query.trim());
            if (results.isEmpty()) {
                return "未搜索到与 \"" + query.trim() + "\" 相关的结果。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("网络搜索结果（共 ").append(results.size()).append(" 条）：\n\n");
            for (int i = 0; i < results.size(); i++) {
                WebResult r = results.get(i);
                sb.append(i + 1).append(". ").append(r.title).append("\n");
                sb.append("   URL: ").append(r.url).append("\n");
                sb.append("   摘要: ").append(r.snippet).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("WebSearchTool 搜索失败: {}", e.getMessage());
            return "网络搜索失败: " + e.getMessage();
        }
    }

    private List<WebResult> search(String query) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Collections.emptyList();
            }
            String html = response.body().string();
            return parseResults(html);
        }
    }

    private List<WebResult> parseResults(String html) {
        List<WebResult> results = new ArrayList<>();

        // Parse result links
        Matcher linkMatcher = LINK_PATTERN.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find() && links.size() < maxResults) {
            String href = linkMatcher.group(1);
            String title = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();
            links.add(new String[]{href, title});
        }

        // Parse snippets
        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find() && snippets.size() < maxResults) {
            String snippet = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
            if (!snippet.isEmpty()) {
                snippets.add(snippet);
            }
        }

        int count = Math.min(links.size(), snippets.size());
        for (int i = 0; i < count; i++) {
            results.add(new WebResult(links.get(i)[0], links.get(i)[1], snippets.get(i)));
        }
        return results;
    }

    private record WebResult(String url, String title, String snippet) {}
}
