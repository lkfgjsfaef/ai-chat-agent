package com.niit.agent.controller;

import com.niit.agent.service.CacheService;
import com.niit.agent.service.ChatAttachmentService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.TokenService;
import com.niit.agent.service.UserService;
import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.AppSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niit.agent.common.result.Result;
import com.niit.agent.entity.ChatAttachment;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final AiModelRouterService aiModelRouterService;
    private final MemoryService memoryService;
    private final ChatSessionService chatSessionService;
    private final ChatAttachmentService chatAttachmentService;
    private final CacheService cacheService;
    private final UserService userService;
    private final TokenService tokenService;
    private final AppSkillService appSkillService;
    private final ObjectMapper objectMapper;
    private final ExecutorService chatExecutor;
    private final ExecutorService contextExecutor;
    private final ScheduledExecutorService heartbeatExecutor;

    public ChatController(
            AiModelRouterService aiModelRouterService,
            MemoryService memoryService,
            ChatSessionService chatSessionService,
            ChatAttachmentService chatAttachmentService,
            CacheService cacheService,
            UserService userService,
            TokenService tokenService,
            AppSkillService appSkillService,
            ObjectMapper objectMapper,
            @Qualifier("chatExecutor") ExecutorService chatExecutor,
            @Qualifier("contextExecutor") ExecutorService contextExecutor,
            @Qualifier("heartbeatExecutor") ScheduledExecutorService heartbeatExecutor) {
        this.aiModelRouterService = aiModelRouterService;
        this.memoryService = memoryService;
        this.chatSessionService = chatSessionService;
        this.chatAttachmentService = chatAttachmentService;
        this.cacheService = cacheService;
        this.userService = userService;
        this.tokenService = tokenService;
        this.appSkillService = appSkillService;
        this.objectMapper = objectMapper;
        this.chatExecutor = chatExecutor;
        this.contextExecutor = contextExecutor;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam(required = false) Long sessionId,
            @RequestParam String content,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String skillId,
            HttpServletRequest request) {

        SseEmitter emitter = new SseEmitter(180000L);

        if (sessionId == null) {
            emitter.completeWithError(new IllegalArgumentException("sessionId is required"));
            return emitter;
        }

        memoryService.saveUserMessage(sessionId, content);
        cacheService.evictMessages(sessionId);
        cacheService.evictRagResults(sessionId);

        try {
            var session = chatSessionService.getById(sessionId);
            if (session != null && "新对话".equals(session.getTitle())) {
                String title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
                chatSessionService.updateTitle(sessionId, title);
                cacheService.evictSession(session.getUserId());
            }
        } catch (Exception ignored) {}

        String effectiveSkillId = normalizeSkillId(skillId);
        if (!appSkillService.isValidSkill(effectiveSkillId)) {
            emitter.completeWithError(new IllegalArgumentException("skillId 不存在"));
            return emitter;
        }

        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug("心跳发送失败，连接可能已关闭");
            }
        }, 15, 15, TimeUnit.SECONDS);

        executeStreamChat(emitter, sessionId, modelName, effectiveSkillId, heartbeat, request, "会话");

        emitter.onTimeout(() -> {
            heartbeat.cancel(true);
            log.warn("SSE连接超时: sessionId={}", sessionId);
            emitter.complete();
        });

        emitter.onError(ex -> {
            heartbeat.cancel(true);
            log.warn("SSE连接异常: {}", ex.getMessage());
        });

        emitter.onCompletion(() -> heartbeat.cancel(true));

        return emitter;
    }

    @PostMapping("/regenerate")
    public SseEmitter regenerate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;
        String skillId = body.get("skillId") != null ? body.get("skillId").toString() : null;

        SseEmitter emitter = new SseEmitter(180000L);

        String effectiveSkillId = normalizeSkillId(skillId);
        if (!appSkillService.isValidSkill(effectiveSkillId)) {
            emitter.completeWithError(new IllegalArgumentException("skillId 不存在"));
            return emitter;
        }
        cacheService.evictMessages(sessionId);

        executeStreamChat(emitter, sessionId, modelName, effectiveSkillId, null, request, "重新生成");

        return emitter;
    }

    @PostMapping("/summarize-file")
    public SseEmitter summarizeFile(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        Long attachmentId = Long.valueOf(body.get("attachmentId").toString());
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;

        SseEmitter emitter = new SseEmitter(300000L);

        var session = chatSessionService.getById(sessionId);
        if (session == null) {
            emitter.completeWithError(new IllegalArgumentException("会话不存在"));
            return emitter;
        }

        ChatAttachment attachment = chatAttachmentService.getById(attachmentId);
        if (attachment == null) {
            emitter.completeWithError(new IllegalArgumentException("附件不存在"));
            return emitter;
        }

        String fullText;
        try {
            fullText = chatAttachmentService.getFullText(attachmentId);
        } catch (Exception e) {
            log.error("读取文件全文失败: attachmentId={}", attachmentId, e);
            emitter.completeWithError(e);
            return emitter;
        }

        memoryService.saveUserMessage(sessionId, "请总结文件：" + attachment.getFileName());
        cacheService.evictMessages(sessionId);

        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug("心跳发送失败，连接可能已关闭");
            }
        }, 15, 15, TimeUnit.SECONDS);

        emitter.onTimeout(() -> {
            heartbeat.cancel(true);
            emitter.complete();
        });
        emitter.onError(ex -> heartbeat.cancel(true));
        emitter.onCompletion(() -> heartbeat.cancel(true));

        int tokenCount = tokenService.countTokens(fullText);
        if (tokenCount <= 10000) {
            executeDirectSummarize(emitter, sessionId, modelName, attachment.getFileName(),
                    fullText, heartbeat, request);
        } else {
            executeIterativeSummarize(emitter, sessionId, modelName, attachment.getFileName(),
                    fullText, heartbeat, request);
        }

        return emitter;
    }

    private void executeStreamChat(SseEmitter emitter, Long sessionId, String modelName,
                                   String effectiveSkillId, ScheduledFuture<?> heartbeat,
                                   HttpServletRequest request, String actionName) {
        CompletableFuture.runAsync(() -> {
            try {
                long contextStart = System.currentTimeMillis();
                List<Map<String, Object>> context = memoryService.buildContext(sessionId, effectiveSkillId);
                long contextTime = System.currentTimeMillis() - contextStart;
                log.info("会话[{}]上下文构建完成, 消息数: {}, 耗时: {}ms", sessionId, context.size(), contextTime);
                doStreamResponse(emitter, sessionId, modelName, context, buildChatOptions(context),
                        heartbeat, request, actionName, contextTime);
            } catch (Exception e) {
                if (heartbeat != null) heartbeat.cancel(true);
                log.error("创建流式调用异常", e);
                emitter.completeWithError(e);
            }
        }, contextExecutor);
    }

    private void doStreamResponse(SseEmitter emitter, Long sessionId, String modelName,
                                   List<Map<String, Object>> context,
                                   AiModelRouterService.ChatOptions options,
                                   ScheduledFuture<?> heartbeat,
                                   HttpServletRequest request, String actionName,
                                   long contextTimeMs) {
        long startTime = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        long modelStart = System.currentTimeMillis();
        boolean[] firstToken = {true};
        var disposable = aiModelRouterService.streamChat(modelName, context,
                        event -> emitQueueStatus(emitter, event), options)
                .doOnNext(chunk -> {
                    String type = "C";
                    String text = chunk;
                    if (chunk.startsWith("R")) {
                        type = "R";
                        text = chunk.substring(1);
                        reasoningBuilder.append(text);
                    } else if (chunk.startsWith("C")) {
                        text = chunk.substring(1);
                    }
                    if ("C".equals(type)) {
                        fullResponse.append(text);
                    }
                    if (firstToken[0]) {
                        long ttft = System.currentTimeMillis() - modelStart;
                        log.info("会话[{}]首Token到达, TTFT: {}ms, type={}", sessionId, ttft, type);
                        firstToken[0] = false;
                    }
                    try {
                        if ("R".equals(type)) {
                            emitter.send(SseEmitter.event().name("reasoning").data(text));
                        } else {
                            emitter.send(SseEmitter.event().data(text));
                        }
                    } catch (IllegalStateException | IOException e) {
                        log.debug("SSE发送被中断 (可能前端已断开): {}", e.getMessage());
                        throw new RuntimeException("CLIENT_DISCONNECTED", e);
                    }
                })
                .doOnComplete(() -> {
                    if (heartbeat != null) heartbeat.cancel(true);
                    try {
                        long modelTime = System.currentTimeMillis() - modelStart;
                        String reply = !fullResponse.isEmpty() ? fullResponse.toString() : reasoningBuilder.toString();
                        memoryService.saveAssistantMessage(sessionId, reply);
                        memoryService.triggerSummaryIfNeeded(sessionId);
                        cacheService.evictMessages(sessionId);

                        Object userIdObj = request.getAttribute("userId");
                        if (userIdObj != null) {
                            int promptTokens = tokenService.countContextTokens(context);
                            int completionTokens = tokenService.countTokens(fullResponse.toString());
                            int totalTokens = promptTokens + completionTokens;
                            userService.incrementTokenUsage(Long.parseLong(userIdObj.toString()), totalTokens);
                            log.debug("Tokens 消耗: Prompt={}, Completion={}, Total={}", promptTokens, completionTokens, totalTokens);
                        }

                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                        long totalTime = System.currentTimeMillis() - startTime;
                        log.info("会话[{}]{}回答完成, 上下文: {}ms, 模型调用: {}ms, 总耗时: {}ms, 长度: {}",
                                sessionId, actionName, contextTimeMs, modelTime, totalTime, fullResponse.length());
                    } catch (Exception e) {
                        log.error("{}完成处理异常: {}", actionName, e.getMessage());
                        sendError(emitter, actionName + "保存或统计时发生错误");
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(e -> {
                    if (heartbeat != null) heartbeat.cancel(true);
                    if ("CLIENT_DISCONNECTED".equals(e.getMessage())) {
                        log.info("会话[{}]已中断，提前终止生成", sessionId);
                        return;
                    }
                    log.error("{}流式调用异常: {}", actionName, e.getMessage());
                    sendError(emitter, "AI回复生成失败: " + e.getMessage());
                    emitter.completeWithError(e);
                })
                .subscribe();

        Runnable cancelTask = () -> {
            if (heartbeat != null) heartbeat.cancel(true);
            if (!disposable.isDisposed()) {
                disposable.dispose();
                log.debug("主动释放底层的流请求资源: sessionId={}", sessionId);
            }
        };
        emitter.onTimeout(cancelTask);
        emitter.onError(ex -> cancelTask.run());
        emitter.onCompletion(cancelTask);
    }

    private void executeDirectSummarize(SseEmitter emitter, Long sessionId, String modelName,
                                         String fileName, String fullText,
                                         ScheduledFuture<?> heartbeat, HttpServletRequest request) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> context = new ArrayList<>();
                context.add(Map.of("role", "system", "content",
                    "你是一个专业的文档分析助手。请对用户提供的文档进行全面、结构化的总结分析。\n" +
                    "总结要求：\n" +
                    "1. 概述文档主题和目的\n" +
                    "2. 梳理文档结构和关键内容要点\n" +
                    "3. 提炼重要结论、数据或建议（如有）\n" +
                    "请使用清晰的中文进行总结，使用适当的标题和列表组织内容。"));
                context.add(Map.of("role", "user", "content",
                    "请总结以下文档《" + fileName + "》的完整内容：\n\n" + fullText));

                var options = new AiModelRouterService.ChatOptions(false, AiModelRouterService.TaskLane.PRIMARY);
                doStreamResponse(emitter, sessionId, modelName, context, options,
                        heartbeat, request, "文件总结", 0);
            } catch (Exception e) {
                if (heartbeat != null) heartbeat.cancel(true);
                log.error("文件总结异常", e);
                emitter.completeWithError(e);
            }
        }, contextExecutor);
    }

    private void executeIterativeSummarize(SseEmitter emitter, Long sessionId, String modelName,
                                            String fileName, String fullText,
                                            ScheduledFuture<?> heartbeat, HttpServletRequest request) {
        CompletableFuture.runAsync(() -> {
            try {
                List<String> textChunks = splitTextByTokens(fullText, 6000);
                int totalTokens = tokenService.countTokens(fullText);
                log.info("文件总结: 文件过大 ({} tokens), 分 {} 段进行迭代总结", totalTokens, textChunks.size());

                sendProgressEvent(emitter, "文档较长（" + totalTokens + " tokens），正在分段分析中...");

                List<String> chunkSummaries = new ArrayList<>();
                for (int i = 0; i < textChunks.size(); i++) {
                    sendProgressEvent(emitter, "正在分析第 " + (i + 1) + "/" + textChunks.size() + " 部分...");
                    String summary = summarizeChunk(modelName, fileName, textChunks.get(i), i + 1, textChunks.size());
                    chunkSummaries.add(summary);
                }

                sendProgressEvent(emitter, "分段分析完成，正在整合总结...");

                StringBuilder combined = new StringBuilder();
                for (int i = 0; i < chunkSummaries.size(); i++) {
                    combined.append("### 第").append(i + 1).append("部分摘要\n");
                    combined.append(chunkSummaries.get(i)).append("\n\n");
                }

                List<Map<String, Object>> context = new ArrayList<>();
                context.add(Map.of("role", "system", "content",
                    "你是一个专业的文档分析助手。用户提供了一份较长文档的各部分摘要，请整合成一份完整的文档总结。\n" +
                    "要求：\n" +
                    "1. 概述文档整体主题和目的\n" +
                    "2. 梳理文档结构和各部分关键内容\n" +
                    "3. 提炼重要结论、数据或建议\n" +
                    "请使用清晰的中文，用标题和列表组织内容。"));
                context.add(Map.of("role", "user", "content",
                    "请整合以下文档《" + fileName + "》的各部分摘要，生成一份完整的总结：\n\n" + combined));

                var options = new AiModelRouterService.ChatOptions(false, AiModelRouterService.TaskLane.PRIMARY);
                doStreamResponse(emitter, sessionId, modelName, context, options,
                        heartbeat, request, "文件总结", 0);
            } catch (Exception e) {
                if (heartbeat != null) heartbeat.cancel(true);
                log.error("迭代文件总结异常", e);
                sendError(emitter, "文件总结失败: " + e.getMessage());
                emitter.completeWithError(e);
            }
        }, contextExecutor);
    }

    private String summarizeChunk(String modelName, String fileName, String chunk,
                                   int partNum, int totalParts) {
        List<Map<String, Object>> context = new ArrayList<>();
        context.add(Map.of("role", "system", "content",
            "你是一个文档分析助手。请用简洁的语言总结文档片段的关键信息，保留重要的事实、数据和结论。"));
        context.add(Map.of("role", "user", "content",
            "请总结文档《" + fileName + "》第" + partNum + "/" + totalParts + "部分的关键内容：\n\n" + chunk));

        return collectAiResponse(modelName, context);
    }

    private String collectAiResponse(String modelName, List<Map<String, Object>> context) {
        try {
            List<String> chunks = aiModelRouterService.streamChat(modelName, context,
                    event -> {},
                    new AiModelRouterService.ChatOptions(false, AiModelRouterService.TaskLane.AUXILIARY))
                .collectList()
                .block(Duration.ofSeconds(120));
            if (chunks == null || chunks.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks) {
                if (chunk.startsWith("R") || chunk.startsWith("C")) {
                    sb.append(chunk.substring(1));
                } else {
                    sb.append(chunk);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("收集AI响应失败: {}", e.getMessage());
            return "[本段分析失败: " + e.getMessage() + "]";
        }
    }

    private List<String> splitTextByTokens(String text, int maxTokensPerChunk) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int roughCharLimit = maxTokensPerChunk * 3;
        while (start < text.length()) {
            int end = Math.min(start + roughCharLimit, text.length());
            if (end < text.length()) {
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > start && paraBreak > end - maxTokensPerChunk * 2) {
                    end = paraBreak;
                } else {
                    int sentenceBreak = -1;
                    for (char c : new char[]{'。', '！', '？', '\n'}) {
                        int idx = text.lastIndexOf(c, end);
                        if (idx > sentenceBreak && idx > start) {
                            sentenceBreak = idx;
                        }
                    }
                    if (sentenceBreak > start && sentenceBreak > end - maxTokensPerChunk * 2) {
                        end = sentenceBreak + 1;
                    }
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }

    private void sendProgressEvent(SseEmitter emitter, String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "summarize_progress");
            payload.put("message", message);
            emitter.send(SseEmitter.event()
                    .name("summarize_progress")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("发送进度事件失败: {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            Result<Void> result = Result.fail(message);
            // 封装为 JSON 格式返回，方便前端统一解析
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(result)));
        } catch (Exception e) {
            log.error("发送 SSE 错误消息失败", e);
        }
    }

    private void emitQueueStatus(SseEmitter emitter, AiModelRouterService.QueueStatusEvent event) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "queue_status");
            payload.put("phase", event.phase());
            payload.put("requestedModel", event.requestedModel());
            payload.put("waitedMs", event.waitedMs());
            payload.put("activeRequests", event.activeRequests());
            payload.put("waitingRequests", event.waitingRequests());
            payload.put("message", event.message());
            emitter.send(SseEmitter.event()
                    .name("queue_status")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("发送排队状态失败: {}", e.getMessage());
        }
    }

    private AiModelRouterService.ChatOptions buildChatOptions(List<Map<String, Object>> context) {
        if (isSimpleChat(context)) {
            return new AiModelRouterService.ChatOptions(false, AiModelRouterService.TaskLane.PRIMARY);
        }
        return AiModelRouterService.ChatOptions.DEFAULT;
    }

    private boolean isSimpleChat(List<Map<String, Object>> context) {
        String latestUserMsg = null;
        for (int i = context.size() - 1; i >= 0; i--) {
            if ("user".equals(context.get(i).get("role"))) {
                latestUserMsg = (String) context.get(i).get("content");
                break;
            }
        }
        if (latestUserMsg == null || latestUserMsg.isBlank()) {
            return false;
        }
        String q = latestUserMsg.trim().toLowerCase();
        if (q.length() > 15) {
            return false;
        }
        String[] simplePatterns = {
            "你好", "hello", "hi", "在吗", "谢谢", "感谢", "早上好", "晚上好", "午安",
            "哈哈", "好的", "行", "收到", "ok", "嗯", "哦", "再见", "拜拜", "bye",
            "晚安", "好的谢谢", "多谢", "不客气", "没事", "没关系"
        };
        for (String pattern : simplePatterns) {
            if (q.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        return skillId.trim();
    }
}
