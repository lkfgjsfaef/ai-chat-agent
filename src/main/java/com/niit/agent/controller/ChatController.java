package com.niit.agent.controller;

import com.niit.agent.service.CacheService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.TokenService;
import com.niit.agent.service.UserService;
import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.AppSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niit.agent.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    private void executeStreamChat(SseEmitter emitter, Long sessionId, String modelName,
                                   String effectiveSkillId, ScheduledFuture<?> heartbeat,
                                   HttpServletRequest request, String actionName) {
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            StringBuilder fullResponse = new StringBuilder();
            try {
                long contextStart = System.currentTimeMillis();
                List<Map<String, Object>> context = memoryService.buildContext(sessionId, effectiveSkillId);
                long contextTime = System.currentTimeMillis() - contextStart;
                log.info("会话[{}]上下文构建完成, 消息数: {}, 耗时: {}ms", sessionId, context.size(), contextTime);
                long modelStart = System.currentTimeMillis();
                boolean[] firstToken = {true};
                var disposable = aiModelRouterService.streamChat(modelName, context,
                                event -> emitQueueStatus(emitter, event),
                                buildChatOptions(context))
                        .doOnNext(chunk -> {
                            fullResponse.append(chunk);
                            if (firstToken[0]) {
                                long ttft = System.currentTimeMillis() - modelStart;
                                log.info("会话[{}]首Token到达, TTFT: {}ms", sessionId, ttft);
                                firstToken[0] = false;
                            }
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IllegalStateException | IOException e) {
                                log.debug("SSE发送被中断 (可能前端已断开): {}", e.getMessage());
                                throw new RuntimeException("CLIENT_DISCONNECTED", e);
                            }
                        })
                        .doOnComplete(() -> {
                            if (heartbeat != null) heartbeat.cancel(true);
                            try {
                                long modelTime = System.currentTimeMillis() - modelStart;
                                memoryService.saveAssistantMessage(sessionId, fullResponse.toString());
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
                                        sessionId, actionName, contextTime, modelTime, totalTime, fullResponse.length());
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
                
            } catch (Exception e) {
                if (heartbeat != null) heartbeat.cancel(true);
                log.error("创建流式调用异常", e);
                emitter.completeWithError(e);
            }
        }, contextExecutor);
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
