package com.niit.agent.service.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niit.agent.service.tools.ToolHandler;
import com.niit.agent.service.tools.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class AbstractOpenAiCompatibleModel implements AiModel {

    protected final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired(required = false)
    protected ToolRegistry toolRegistry;

    @Autowired
    @Qualifier("toolExecutor")
    protected ExecutorService toolExecutor;
    
    private final ConnectionSpec connectionSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .cipherSuites(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
            )
            .build();
            
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(100, 3, TimeUnit.MINUTES))
            .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionSpecs(Collections.singletonList(connectionSpec))
            .build();

    @jakarta.annotation.PostConstruct
    public void warmupConnection() {
        log.info("开始预热模型 API 连接: {}", getEndpoint());
        for (int i = 0; i < 3; i++) {
            final int index = i + 1;
            CompletableFuture.runAsync(() -> {
                try {
                    Request request = new Request.Builder()
                            .url(getEndpoint())
                            .head()
                            .build();
                    try (Response response = client.newCall(request).execute()) {
                        log.info("模型 {} API 连接预热完成 (通道 {}), status: {}", getModelName(), index, response.code());
                    }
                } catch (Exception e) {
                    log.warn("模型 {} API 连接预热失败 (通道 {}): {}", getModelName(), index, e.getMessage());
                }
            });
        }
    }

    protected abstract String getEndpoint();

    protected abstract String getApiKey();

    protected abstract String getActualModelName(String modelName);

    @Override
    public Flux<String> streamChat(List<Map<String, Object>> messages, String modelName, ChatOptions options) {
        ChatOptions resolvedOptions = options == null ? ChatOptions.DEFAULT : options;
        return streamChatWithRecursionLimit(messages, modelName, 0, resolvedOptions);
    }

    private Flux<String> streamChatWithRecursionLimit(List<Map<String, Object>> messages, String modelName,
                                                      int recursionDepth, ChatOptions options) {
        if (recursionDepth > 5) {
            return Flux.error(new RuntimeException("工具调用次数超过最大限制 (5次)"));
        }
        return Flux.create(sink -> {
            try {
                String requestBody = buildRequestBody(messages, getActualModelName(modelName), options);
                log.debug("AI请求体: {}", requestBody);

                Request request = new Request.Builder()
                        .url(getEndpoint())
                        .addHeader("Authorization", "Bearer " + getApiKey())
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                EventSource.Factory factory = EventSources.createFactory(client);
                
                // 用于累积 Tool Calls
                Map<Integer, ToolCallBuilder> toolCallBuilders = new HashMap<>();

                EventSource eventSource = factory.newEventSource(request, new EventSourceListener() {
                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        if ("[DONE]".equals(data)) {
                            // Let onClosed handle completion and tool calls
                            return;
                        }
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            JsonNode choices = node.get("choices");
                            if (choices != null && choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).get("delta");
                                if (delta != null) {
                                    // 1. 处理普通文本 (兼容推理模型的 reasoning_content)
                                    // 使用标记前缀区分: R=推理过程, C=正式回答
                                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                                        String rc = delta.get("reasoning_content").asText();
                                        if (!rc.isEmpty()) {
                                            sink.next("R" + rc);
                                        }
                                    }
                                    if (delta.has("content") && !delta.get("content").isNull()) {
                                        String content = delta.get("content").asText();
                                        if (!content.isEmpty()) {
                                            sink.next("C" + content);
                                        }
                                    }
                                    // 2. 处理 Tool Calls
                                    if (delta.has("tool_calls")) {
                                        JsonNode toolCallsNode = delta.get("tool_calls");
                                        for (JsonNode tcNode : toolCallsNode) {
                                            int index = tcNode.has("index") ? tcNode.get("index").asInt() : 0;
                                            ToolCallBuilder builder = toolCallBuilders.computeIfAbsent(index, k -> new ToolCallBuilder());
                                            
                                            if (tcNode.has("id")) {
                                                builder.id = tcNode.get("id").asText();
                                            }
                                            if (tcNode.has("function")) {
                                                JsonNode funcNode = tcNode.get("function");
                                                if (funcNode.has("name")) {
                                                    builder.name = funcNode.get("name").asText();
                                                }
                                                if (funcNode.has("arguments")) {
                                                    builder.arguments.append(funcNode.get("arguments").asText());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("解析SSE数据异常: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        log.error("SSE连接失败", t);
                        if (response != null) {
                            try {
                                String body = response.body() != null ? response.body().string() : "null";
                                log.error("错误响应: status={}, body={}", response.code(), body);
                            } catch (Exception ignored) {}
                        }
                        sink.error(new RuntimeException("AI接口调用失败: " + (t != null ? t.getMessage() : "未知错误")));
                    }

                    @Override
                    public void onClosed(EventSource eventSource) {
                        if (!toolCallBuilders.isEmpty()) {
                            if (!options.toolsEnabled()) {
                                sink.error(new RuntimeException("当前请求已禁用工具调用，但模型仍返回了 tool_calls"));
                                return;
                            }
                            log.info("检测到 Tool Calls，开始执行本地工具...");
                            
                            // 1. 构建 assistant 消息
                            Map<String, Object> assistantMessage = new HashMap<>();
                            assistantMessage.put("role", "assistant");
                            assistantMessage.put("content", null);
                            List<Map<String, Object>> toolCallsList = new ArrayList<>();
                            
                            List<ToolCallBuilder> orderedToolCalls = toolCallBuilders.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(Map.Entry::getValue)
                                    .toList();

                            for (ToolCallBuilder b : orderedToolCalls) {
                                Map<String, Object> tc = new HashMap<>();
                                tc.put("id", b.id);
                                tc.put("type", "function");
                                Map<String, Object> f = new HashMap<>();
                                f.put("name", b.name);
                                f.put("arguments", b.arguments.toString());
                                tc.put("function", f);
                                toolCallsList.add(tc);
                            }
                            assistantMessage.put("tool_calls", toolCallsList);
                            messages.add(assistantMessage);
                            
                            // 2. 并行执行工具并按模型返回顺序组装 tool 消息
                            List<CompletableFuture<Map<String, Object>>> toolMessageFutures = orderedToolCalls.stream()
                                    .map(toolCall -> CompletableFuture.supplyAsync(() -> buildToolMessage(toolCall), toolExecutor))
                                    .toList();

                            CompletableFuture.allOf(toolMessageFutures.toArray(new CompletableFuture[0])).join();
                            for (CompletableFuture<Map<String, Object>> future : toolMessageFutures) {
                                messages.add(future.join());
                            }
                            
                            // 3. 递归调用大模型获取最终回答，并将流数据转发到当前的 sink
                            log.info("工具执行完成，将结果发送给大模型继续生成...");
                            streamChatWithRecursionLimit(messages, modelName, recursionDepth + 1, options).subscribe(
                                chunk -> sink.next(chunk),
                                err -> sink.error(err),
                                () -> sink.complete()
                            );
                        } else {
                            sink.complete();
                        }
                    }
                });

                sink.onDispose(() -> {
                    log.debug("SSE连接已释放");
                    if (eventSource != null) {
                        eventSource.cancel();
                    }
                });
            } catch (Exception e) {
                log.error("创建SSE连接异常", e);
                sink.error(e);
            }
        });
    }

    private String buildRequestBody(List<Map<String, Object>> messages, String model, ChatOptions options) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        
        if (options.toolsEnabled() && toolRegistry != null) {
            List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions();
            if (toolDefs != null && !toolDefs.isEmpty()) {
                body.put("tools", toolDefs);
            }
        }
        
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> buildToolMessage(ToolCallBuilder toolCall) {
        String result;
        try {
            ToolHandler handler = toolRegistry != null ? toolRegistry.getTool(toolCall.name) : null;
            if (handler != null) {
                log.info("执行工具 [{}], 参数: {}", toolCall.name, toolCall.arguments);
                Map<String, Object> params = parseToolArguments(toolCall.arguments.toString());
                result = handler.execute(params);
            } else {
                log.warn("未找到工具: {}", toolCall.name);
                result = "Tool not found: " + toolCall.name;
            }
        } catch (Exception e) {
            log.error("执行工具异常", e);
            result = "Error executing tool: " + e.getMessage();
        }

        Map<String, Object> toolMessage = new HashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", toolCall.id);
        toolMessage.put("content", result);
        return toolMessage;
    }

    private Map<String, Object> parseToolArguments(String arguments) {
        Map<String, Object> params = new HashMap<>();
        if (arguments == null || arguments.isEmpty()) {
            return params;
        }

        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (Exception parseEx) {
            log.warn("工具参数 JSON 解析失败: {}", parseEx.getMessage());
            return params;
        }
    }
    
    private static class ToolCallBuilder {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
    }
}
