package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niit.agent.common.constant.Constants;
import com.niit.agent.common.util.RetrievalUtil;
import com.niit.agent.common.util.TextUtils;
import com.niit.agent.entity.ChatMessage;
import com.niit.agent.entity.ChatSession;
import com.niit.agent.entity.ChatSummary;
import com.niit.agent.mapper.ChatAttachmentMapper;
import com.niit.agent.model.rag.RagChunkAttribution;
import com.niit.agent.model.rag.RagDecision;
import com.niit.agent.model.rag.RagRuntimeEvent;
import com.niit.agent.model.rag.RankedRetrievalCandidate;
import com.niit.agent.mapper.ChatSummaryMapper;
import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.AppSkillService;
import com.niit.agent.service.CacheService;
import com.niit.agent.entity.ChatAttachment;
import com.niit.agent.service.ChatAttachmentService;
import com.niit.agent.service.ChatMessageService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.service.KnowledgeSearchCore;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.RerankService;
import com.niit.agent.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MemoryServiceImpl implements MemoryService {

    private final ChatMessageService chatMessageService;
    private final ChatAttachmentService chatAttachmentService;
    private final ChatAttachmentMapper chatAttachmentMapper;
    private final CacheService cacheService;
    private final ChatSummaryMapper chatSummaryMapper;
    private final AiModelRouterService aiModelRouterService;
    private final AppSkillService appSkillService;
    private final ChatSessionService chatSessionService;
    private final KnowledgeSearchCore knowledgeSearchCore;
    private final TokenService tokenService;
    private final ExecutorService ragExecutor;
    private final ExecutorService summaryExecutor;
    private final Set<Long> summarySessionsInProgress = ConcurrentHashMap.newKeySet();
    private final AtomicLong contextBuildCount = new AtomicLong();
    private final AtomicLong ragEnabledCount = new AtomicLong();
    private final AtomicLong ragSkippedCount = new AtomicLong();
    private final AtomicLong ragHitCount = new AtomicLong();
    private final AtomicLong ragNoHitCount = new AtomicLong();
    private final AtomicLong ragFilteredNoHitCount = new AtomicLong();
    private final AtomicLong ragErrorCount = new AtomicLong();
    private final AtomicLong ragCacheHitCount = new AtomicLong();
    private final Deque<RagRuntimeEvent> recentRagEvents = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<Long, TimedAttribution> pendingChunkAttributions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CachedExpansion> expansionCache = new ConcurrentHashMap<>();
    private static final long RAG_METRIC_WINDOW_5M_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long EXPANSION_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(3);

    @Autowired(required = false)
    private RerankService rerankService;

    @Value("${ai.context.max-rounds:8}")
    private int maxContextRounds;

    @Value("${ai.context.max-length:2000}")
    private int maxContextLength;

    @Value("${ai.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${ai.summary.model:glm-4.5-air}")
    private String summaryModelName;

    @Value("${ai.summary.trigger-tokens:3000}")
    private int summaryTriggerTokens;

    @Value("${ai.summary.batch-size:20}")
    private int summaryBatchSize;

    @Value("${ai.summary.min-new-messages:6}")
    private int summaryMinNewMessages;

    @Value("${ai.rag.vector-quota:6}")
    private int ragVectorQuota;

    @Value("${ai.rag.keyword-quota:6}")
    private int ragKeywordQuota;

    @Value("${ai.rag.top-k:4}")
    private int ragTopK;

    @Value("${ai.rag.min-score:0.1}")
    private double ragMinScore;

    @Value("${ai.rag.query-expansion.enabled:true}")
    private boolean ragQueryExpansionEnabled;

    @Value("${ai.rag.query-expansion.variants:4}")
    private int ragQueryExpansionVariants;

    @Value("${ai.rag.query-expansion.model:glm-4.5-air}")
    private String ragQueryExpansionModel;

    @Value("${ai.rag.query-expansion.timeout-seconds:15}")
    private long ragQueryExpansionTimeoutSeconds;

    @Value("${ai.rag.scope.session-quota:4}")
    private int ragSessionScopeQuota;

    @Value("${ai.rag.scope.user-quota:3}")
    private int ragUserScopeQuota;

    @Value("${ai.rag.scope.global-quota:2}")
    private int ragGlobalScopeQuota;

    @Value("${ai.rag.intent.enabled:true}")
    private boolean ragIntentEnabled;

    @Value("${ai.rag.observability.enabled:true}")
    private boolean ragObservabilityEnabled;

    @Value("${ai.rag.cache.enabled:true}")
    private boolean ragCacheEnabled;

    @Value("${ai.rag.cache.ttl-seconds:120}")
    private long ragCacheTtlSeconds;

    @Value("${ai.rag.vector-search-top-k:20}")
    private int ragVectorSearchTopK;

    @Value("${ai.rag.broad-top-k:12}")
    private int ragBroadTopK;

    @Value("${ai.rag.broad-vector-quota:12}")
    private int ragBroadVectorQuota;

    @Value("${ai.rag.broad-keyword-quota:12}")
    private int ragBroadKeywordQuota;

    @Value("${ai.rag.broad-scope.session-quota:8}")
    private int ragBroadSessionScopeQuota;

    @Value("${ai.rag.broad-scope.user-quota:6}")
    private int ragBroadUserScopeQuota;

    @Value("${ai.rag.broad-scope.global-quota:4}")
    private int ragBroadGlobalScopeQuota;

    @Value("${ai.rag.full-max-chars:20000}")
    private int ragFullMaxChars;

    @Value("${ai.rag.soft-fallback.enabled:true}")
    private boolean ragSoftFallbackEnabled;

    @Value("${ai.rag.intent.llm-fallback.enabled:true}")
    private boolean ragIntentLlmFallbackEnabled;

    @Value("${ai.rag.pipeline-timeout-seconds:4}")
    private long ragPipelineTimeoutSeconds;

    @Value("${ai.rag.chunk-attribution-ttl-minutes:5}")
    private long ragChunkAttributionTtlMinutes;

    public MemoryServiceImpl(
            ChatMessageService chatMessageService,
            ChatAttachmentService chatAttachmentService,
            ChatAttachmentMapper chatAttachmentMapper,
            CacheService cacheService,
            ChatSummaryMapper chatSummaryMapper,
            AiModelRouterService aiModelRouterService,
            AppSkillService appSkillService,
            ChatSessionService chatSessionService,
            KnowledgeSearchCore knowledgeSearchCore,
            TokenService tokenService,
            @Qualifier("ragExecutor") ExecutorService ragExecutor,
            @Qualifier("summaryExecutor") ExecutorService summaryExecutor) {
        this.chatMessageService = chatMessageService;
        this.chatAttachmentService = chatAttachmentService;
        this.chatAttachmentMapper = chatAttachmentMapper;
        this.cacheService = cacheService;
        this.chatSummaryMapper = chatSummaryMapper;
        this.aiModelRouterService = aiModelRouterService;
        this.appSkillService = appSkillService;
        this.chatSessionService = chatSessionService;
        this.knowledgeSearchCore = knowledgeSearchCore;
        this.tokenService = tokenService;
        this.ragExecutor = ragExecutor;
        this.summaryExecutor = summaryExecutor;
    }

    @Override
    public List<Map<String, Object>> buildContext(Long sessionId) {
        return buildContext(sessionId, null);
    }

    @Override
    public List<Map<String, Object>> buildContext(Long sessionId, String overrideSkillId) {
        List<Map<String, Object>> context = new ArrayList<>();
        contextBuildCount.incrementAndGet();

        ChatSession session = chatSessionService.getById(sessionId);
        KnowledgeScopeStats knowledgeScopeStats = getKnowledgeScopeStats(session);
        
        appendSystemPrompt(context, session);

        ChatSummary summary = getSummary(sessionId);
        appendSummaryPrompt(context, summary);

        List<ChatMessage> recentMessages = chatMessageService.getRecentMessages(sessionId, maxContextRounds);
        String latestUserQuestion = extractLatestUserQuestion(recentMessages);
        
        appendSkillInstruction(context, session, overrideSkillId, latestUserQuestion);

        RagDecision ragDecision = evaluateRagDecision(latestUserQuestion, recentMessages, knowledgeScopeStats);

        final String retrievalQuery;
        final List<String> retrievalQueries;
        final CompletableFuture<List<String>> expansionFuture;
        final String ragCacheKey;
        if (ragDecision.enabled()) {
            retrievalQuery = buildRetrievalQuery(recentMessages, summary);
            RetrievalQueryPlan plan = buildRetrievalQueries(retrievalQuery, latestUserQuestion, recentMessages, summary);
            retrievalQueries = plan.baseQueries();
            expansionFuture = plan.expansionFuture();
            ragCacheKey = buildRagCacheKey(sessionId, retrievalQueries, summary, recentMessages, knowledgeScopeStats);
        } else {
            retrievalQuery = "";
            retrievalQueries = List.of();
            expansionFuture = CompletableFuture.completedFuture(List.of());
            ragCacheKey = "";
        }

        if (ragDecision.enabled() && !latestUserQuestion.isEmpty()
                && (!retrievalQuery.isEmpty() || RagDecision.SCOPE_FULL.equals(ragDecision.retrievalScope()))) {
            try {
                CompletableFuture.supplyAsync(() -> {
                    executeRagPipeline(context, sessionId, session, latestUserQuestion, retrievalQuery, retrievalQueries, expansionFuture, ragDecision, ragCacheKey);
                    return true;
                }, ragExecutor)
                .orTimeout(ragPipelineTimeoutSeconds, TimeUnit.SECONDS)
                .join();
            } catch (Exception e) {
                log.warn("RAG pipeline 超时或异常 ({}s), 跳过知识库检索, sessionId={}", ragPipelineTimeoutSeconds, sessionId);
                recordRagMetrics(ragDecision, "timeout", false);
            }
        } else {
            executeRagPipeline(context, sessionId, session, latestUserQuestion, retrievalQuery, retrievalQueries, expansionFuture, ragDecision, ragCacheKey);
        }

        for (ChatMessage msg : recentMessages) {
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", msg.getRole());
            userMsg.put("content", msg.getContent());
            context.add(userMsg);
        }
        
        // 5. 应用 Token 精算与滑动窗口截断机制
        // 假设大模型的最大上下文 Token 限制为 8000（预留 1000 给生成的回答，所以输入限制 7000）
        return tokenService.truncateContext(context, 7000);
    }

    private void appendSystemPrompt(List<Map<String, Object>> context, ChatSession session) {
        String systemPrompt = "你是一个智能AI助手，请根据上下文和用户的问题给出准确、有用的回答。";
        if (session != null && session.getSystemPrompt() != null && !session.getSystemPrompt().trim().isEmpty()) {
            systemPrompt = session.getSystemPrompt().trim();
        }
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        context.add(systemMsg);
    }

    private void appendSummaryPrompt(List<Map<String, Object>> context, ChatSummary summary) {
        if (summary != null && summary.getSummary() != null && !summary.getSummary().isEmpty()) {
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "以下是之前对话的摘要，请参考：\n" + summary.getSummary());
            context.add(summaryMsg);
        }
    }

    private void appendSkillInstruction(List<Map<String, Object>> context, ChatSession session, String overrideSkillId, String latestUserQuestion) {
        String effectiveSkillId = resolveEffectiveSkillId(session, overrideSkillId, latestUserQuestion);
        if (effectiveSkillId != null) {
            String skillInstruction = appSkillService.buildSkillInstruction(effectiveSkillId, latestUserQuestion);
            if (!skillInstruction.isBlank()) {
                Map<String, Object> skillMsg = new HashMap<>();
                skillMsg.put("role", "system");
                skillMsg.put("content", skillInstruction);
                context.add(skillMsg);
            }
        }
    }

    private void executeRagPipeline(List<Map<String, Object>> context, Long sessionId, ChatSession session,
                                    String latestUserQuestion, String retrievalQuery, List<String> retrievalQueries,
                                    CompletableFuture<List<String>> expansionFuture,
                                    RagDecision ragDecision, String ragCacheKey) {
        long ragStartNanos = System.nanoTime();
        int vectorCandidateCount = 0;
        int keywordCandidateCount = 0;
        int mergedCandidateCount = 0;
        int rerankedCandidateCount = 0;
        int finalCandidateCount = 0;
        String ragOutcome = "skipped";
        boolean ragCacheHit = false;

        if (ragDecision.enabled() && !latestUserQuestion.isEmpty()
                && (!retrievalQuery.isEmpty() || RagDecision.SCOPE_FULL.equals(ragDecision.retrievalScope()))) {
            try {
                if (RagDecision.SCOPE_FULL.equals(ragDecision.retrievalScope())) {
                    String fullDocContext = retrieveFullDocumentText(sessionId, session);
                    if (!fullDocContext.isEmpty()) {
                        ragOutcome = "hit_full";
                        finalCandidateCount = 1;
                        Map<String, Object> ragSysMsg = new HashMap<>();
                        ragSysMsg.put("role", "system");
                        ragSysMsg.put("content", fullDocContext);
                        context.add(ragSysMsg);
                        cacheRagPayload(sessionId, ragCacheKey, ragOutcome, fullDocContext,
                                0, 0, 0, 0, finalCandidateCount);
                        log.info("RAG全文档检索完成, sessionId={}, 文本长度={}", sessionId, fullDocContext.length());
                    } else {
                        ragOutcome = "full_no_document";
                        context.add(buildNoHitSystemMessage());
                    }
                } else {
                Map<String, Object> cachedRagResult = getCachedRagPayload(sessionId, ragCacheKey);
                if (cachedRagResult != null) {
                    ragCacheHit = true;
                    vectorCandidateCount = parseIntValue(cachedRagResult.get("vectorCount"));
                    keywordCandidateCount = parseIntValue(cachedRagResult.get("keywordCount"));
                    mergedCandidateCount = parseIntValue(cachedRagResult.get("mergedCount"));
                    rerankedCandidateCount = parseIntValue(cachedRagResult.get("rerankedCount"));
                    finalCandidateCount = parseIntValue(cachedRagResult.get("finalCount"));
                    ragOutcome = stringValue(cachedRagResult.get("outcome"));
                    applyCachedRagPayload(context, cachedRagResult);
                    log.info("命中RAG缓存: sessionId={}, cacheKey={}, outcome={}", sessionId, ragCacheKey, ragOutcome);
                    logRagTrace(sessionId, latestUserQuestion, ragDecision, vectorCandidateCount, keywordCandidateCount,
                            mergedCandidateCount, rerankedCandidateCount, finalCandidateCount, ragOutcome, ragStartNanos, true);
                } else {
                    log.info("执行RAG混合检索, sessionId={}, reason={}, scope={}, queries={}", sessionId, ragDecision.reason(), ragDecision.retrievalScope(), retrievalQueries);

                    boolean broad = RagDecision.SCOPE_BROAD.equals(ragDecision.retrievalScope());
                    int effVectorQuota = broad ? ragBroadVectorQuota : ragVectorQuota;
                    int effKeywordQuota = broad ? ragBroadKeywordQuota : ragKeywordQuota;
                    int effTopK = broad ? ragBroadTopK : ragTopK;
                    int effSessionQuota = broad ? ragBroadSessionScopeQuota : ragSessionScopeQuota;
                    int effUserQuota = broad ? ragBroadUserScopeQuota : ragUserScopeQuota;
                    int effGlobalQuota = broad ? ragBroadGlobalScopeQuota : ragGlobalScopeQuota;

                    CompletableFuture<List<RetrievalCandidate>> vectorFuture = CompletableFuture
                            .supplyAsync(() -> searchVectorKnowledge(retrievalQueries, session, effVectorQuota, effSessionQuota, effUserQuota, effGlobalQuota), ragExecutor)
                            .completeOnTimeout(Collections.emptyList(), 2, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                log.warn("向量检索异步执行失败: {}", ex.getMessage());
                                return Collections.emptyList();
                            });

                    CompletableFuture<List<RetrievalCandidate>> keywordFuture = CompletableFuture
                            .supplyAsync(() -> searchKeywordKnowledge(retrievalQueries, session, effKeywordQuota, effSessionQuota, effUserQuota, effGlobalQuota), ragExecutor)
                            .completeOnTimeout(Collections.emptyList(), 2, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                log.warn("BM25 检索异步执行失败: {}", ex.getMessage());
                                return Collections.emptyList();
                            });

                    CompletableFuture.allOf(vectorFuture, keywordFuture).join();
                    List<RetrievalCandidate> vectorCandidates = vectorFuture.join();
                    List<RetrievalCandidate> keywordCandidates = keywordFuture.join();

                    // 等待查询扩展完成，补充扩展查询的搜索结果
                    List<String> expandedQueries;
                    try {
                        expandedQueries = expansionFuture.get(2, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        expandedQueries = List.of();
                    }
                    if (!expandedQueries.isEmpty()) {
                        List<String> novelExpanded = expandedQueries.stream()
                                .filter(q -> !retrievalQueries.contains(q))
                                .toList();
                        if (!novelExpanded.isEmpty()) {
                            CompletableFuture<List<RetrievalCandidate>> expandedVectorFuture = CompletableFuture
                                    .supplyAsync(() -> searchVectorKnowledge(novelExpanded, session, effVectorQuota, effSessionQuota, effUserQuota, effGlobalQuota), ragExecutor)
                                    .completeOnTimeout(Collections.emptyList(), 2, TimeUnit.SECONDS)
                                    .exceptionally(ex -> {
                                        log.warn("扩展查询向量检索失败: {}", ex.getMessage());
                                        return Collections.emptyList();
                                    });
                            CompletableFuture<List<RetrievalCandidate>> expandedKeywordFuture = CompletableFuture
                                    .supplyAsync(() -> searchKeywordKnowledge(novelExpanded, session, effKeywordQuota, effSessionQuota, effUserQuota, effGlobalQuota), ragExecutor)
                                    .completeOnTimeout(Collections.emptyList(), 2, TimeUnit.SECONDS)
                                    .exceptionally(ex -> {
                                        log.warn("扩展查询BM25检索失败: {}", ex.getMessage());
                                        return Collections.emptyList();
                                    });
                            CompletableFuture.allOf(expandedVectorFuture, expandedKeywordFuture).join();
                            vectorCandidates = new ArrayList<>(vectorCandidates);
                            vectorCandidates.addAll(expandedVectorFuture.join());
                            keywordCandidates = new ArrayList<>(keywordCandidates);
                            keywordCandidates.addAll(expandedKeywordFuture.join());
                            log.info("扩展查询完成, 新增向量候选={}, BM25候选={}", expandedVectorFuture.join().size(), expandedKeywordFuture.join().size());
                        }
                    }
                    vectorCandidateCount = vectorCandidates.size();
                    keywordCandidateCount = keywordCandidates.size();

                    Map<String, RetrievalCandidate> candidateMap = mergeCandidatesWithRrf(vectorCandidates, keywordCandidates);
                    List<RetrievalCandidate> combinedCandidates = new ArrayList<>(candidateMap.values());
                    mergedCandidateCount = combinedCandidates.size();
                    
                    if (!combinedCandidates.isEmpty()) {
                        List<String> candidateContents = combinedCandidates.stream()
                                .map(RetrievalCandidate::content)
                                .toList();

                        List<RerankService.RerankResult> rerankedResults = performRerank(retrievalQuery, candidateContents, effTopK);
                        rerankedCandidateCount = rerankedResults.size();

                        List<RankedRetrievalCandidate> finalCandidates = rerankedResults.stream()
                                .filter(result -> result.score() >= ragMinScore)
                                .map(result -> toRankedCandidate(result, candidateMap))
                                .filter(Objects::nonNull)
                                .toList();
                        finalCandidateCount = finalCandidates.size();

                        if (!finalCandidates.isEmpty()) {
                            ragOutcome = "hit";
                            String ragContext = buildFinalRagContext(latestUserQuestion, finalCandidates);
                            Map<String, Object> ragSysMsg = new HashMap<>();
                            ragSysMsg.put("role", "system");
                            ragSysMsg.put("content", ragContext);
                            context.add(ragSysMsg);

                            trackChunkAttribution(sessionId, finalCandidates);

                            cacheRagPayload(sessionId, ragCacheKey, ragOutcome, ragContext,
                                    vectorCandidateCount, keywordCandidateCount, mergedCandidateCount,
                                    rerankedCandidateCount, finalCandidateCount);
                            log.info("RAG混合检索命中 {} 个候选片段, 过滤后保留 {} 个, sessionId={}",
                                    combinedCandidates.size(), finalCandidates.size(), sessionId);
                        } else if (ragSoftFallbackEnabled && !rerankedResults.isEmpty()) {
                            // Soft fallback: take top-1 even if below threshold
                            RerankService.RerankResult topResult = rerankedResults.get(0);
                            RetrievalCandidate topCandidate = candidateMap.get(topResult.document());
                            if (topCandidate != null) {
                                RankedRetrievalCandidate fallback = new RankedRetrievalCandidate(
                                        topCandidate.content(), topCandidate.sourceLabel(), topResult.score());
                                String ragContext = buildFinalRagContext(latestUserQuestion, List.of(fallback));
                                Map<String, Object> ragSysMsg = new HashMap<>();
                                ragSysMsg.put("role", "system");
                                ragSysMsg.put("content", ragContext);
                                context.add(ragSysMsg);
                                trackChunkAttribution(sessionId, List.of(fallback));
                                ragOutcome = "hit_soft_fallback";
                                finalCandidateCount = 1;
                                cacheRagPayload(sessionId, ragCacheKey, ragOutcome, ragContext,
                                        vectorCandidateCount, keywordCandidateCount, mergedCandidateCount,
                                        rerankedCandidateCount, finalCandidateCount);
                                log.info("RAG软降级: 所有候选低于阈值({}), 使用top-1 (score={}), sessionId={}",
                                        ragMinScore, topResult.score(), sessionId);
                            } else {
                                ragOutcome = "filtered_no_hit";
                                context.add(buildNoHitSystemMessage());
                            }
                        } else {
                            ragOutcome = "filtered_no_hit";
                            context.add(buildNoHitSystemMessage());
                            cacheRagPayload(sessionId, ragCacheKey, ragOutcome, null,
                                    vectorCandidateCount, keywordCandidateCount, mergedCandidateCount,
                                    rerankedCandidateCount, finalCandidateCount);
                            log.info("RAG候选片段全部低于阈值, sessionId={}, 阈值={}", sessionId, ragMinScore);
                        }
                    } else {
                        ragOutcome = "no_hit";
                        context.add(buildNoHitSystemMessage());
                        cacheRagPayload(sessionId, ragCacheKey, ragOutcome, null,
                                vectorCandidateCount, keywordCandidateCount, mergedCandidateCount,
                                rerankedCandidateCount, finalCandidateCount);
                        log.info("RAG混合检索未命中有效片段, sessionId={}", sessionId);
                    }
                }
                }
            } catch (Exception e) {
                ragOutcome = "error";
                log.warn("RAG向量检索失败: {}", e.getMessage());
            }
        } else {
            ragOutcome = "skipped";
            log.info("本轮请求跳过RAG: sessionId={}, reason={}, question={}", sessionId, ragDecision.reason(), latestUserQuestion);
        }

        if (!ragCacheHit) {
            logRagTrace(sessionId, latestUserQuestion, ragDecision, vectorCandidateCount, keywordCandidateCount,
                    mergedCandidateCount, rerankedCandidateCount, finalCandidateCount, ragOutcome, ragStartNanos, false);
        }
        recordRagMetrics(ragDecision, ragOutcome, ragCacheHit);
    }

    private List<RerankService.RerankResult> performRerank(String retrievalQuery, List<String> candidateContents, int topK) {
        if (rerankService != null) {
            return rerankService.rerankWithScores(retrievalQuery, candidateContents, topK);
        }
        return candidateContents.stream()
                .limit(topK)
                .map(content -> new RerankService.RerankResult(content, 1.0))
                .toList();
    }

    private String buildFinalRagContext(String latestUserQuestion, List<RankedRetrievalCandidate> finalCandidates) {
        StringBuilder ragContext = new StringBuilder(buildRagInstruction(latestUserQuestion))
                .append("\n[知识库检索结果开始]\n");
        for (int i = 0; i < finalCandidates.size(); i++) {
            RankedRetrievalCandidate candidate = finalCandidates.get(i);
            ragContext.append(String.format("--- 片段 %d | 来源: %s | 相关性: %.1f ---\n%s\n\n",
                    i + 1, candidate.sourceLabel(), candidate.score(), candidate.content()));
        }
        ragContext.append("[知识库检索结果结束]\n");
        return ragContext.toString();
    }

    private String resolveEffectiveSkillId(ChatSession session, String overrideSkillId, String latestUserQuestion) {
        String sessionSkillId = session != null ? session.getSkillId() : null;
        return appSkillService.resolveSkillId(overrideSkillId, sessionSkillId, latestUserQuestion);
    }

    @Override
    public void saveUserMessage(Long sessionId, String content) {
        chatMessageService.saveMessage(sessionId, Constants.ROLE_USER, content);
    }

    @Override
    public void saveAssistantMessage(Long sessionId, String content) {
        ChatMessage message = chatMessageService.saveMessage(sessionId, Constants.ROLE_ASSISTANT, content);
        applyChunkAttribution(message);
    }

    @Override
    public void triggerSummaryIfNeeded(Long sessionId) {
        if (!summaryEnabled) {
            return;
        }

        ChatSummary existingSummary = getSummary(sessionId);
        Long lastSummarizedMessageId = existingSummary != null ? existingSummary.getLastSummarizedMessageId() : null;
        List<ChatMessage> unsummarizedMessages = chatMessageService.getMessagesAfterId(sessionId, lastSummarizedMessageId, summaryBatchSize);
        if (unsummarizedMessages.isEmpty()) {
            return;
        }

        int messageCount = chatMessageService.countBySessionId(sessionId);
        long totalLength = unsummarizedMessages.stream()
                .mapToLong(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                .sum();
        int recentTokenCount = tokenService.countContextTokens(convertMessagesToContext(unsummarizedMessages));

        if (messageCount > maxContextRounds * 2
                || unsummarizedMessages.size() >= summaryMinNewMessages
                || totalLength > maxContextLength
                || recentTokenCount > summaryTriggerTokens) {
            if (!summarySessionsInProgress.add(sessionId)) {
                log.debug("会话摘要任务已在执行中，跳过重复触发: sessionId={}", sessionId);
                return;
            }
            log.info("触发自动摘要: sessionId={}, 总消息数={}, 新消息数={}, 总长度={}, Token数={}",
                    sessionId, messageCount, unsummarizedMessages.size(), totalLength, recentTokenCount);
            summaryExecutor.execute(() -> generateSummary(sessionId));
        }
    }

    private void generateSummary(Long sessionId) {
        try {
            ChatSummary existingSummary = getSummary(sessionId);
            Long lastSummarizedMessageId = existingSummary != null ? existingSummary.getLastSummarizedMessageId() : null;
            List<ChatMessage> newMessages = chatMessageService.getMessagesAfterId(sessionId, lastSummarizedMessageId, summaryBatchSize);
            if (newMessages.isEmpty()) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : newMessages) {
                sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }

            StringBuilder summaryPrompt = new StringBuilder("请对以下对话进行精简摘要，保留用户需求、关键信息、已确认约束和阶段性结论，不要遗漏重要细节。");
            if (existingSummary != null && existingSummary.getSummary() != null && !existingSummary.getSummary().isBlank()) {
                summaryPrompt.append("\n\n已有摘要：\n").append(existingSummary.getSummary());
                summaryPrompt.append("\n\n请在已有摘要基础上增量更新，不要简单重复。");
            }
            summaryPrompt.append("\n\n本次新增对话：\n").append(sb);

            Map<String, Object> sysMap = new HashMap<>();
            sysMap.put("role", "system");
            sysMap.put("content", "你是一个对话摘要助手，请精简准确地总结对话内容。");
            
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("role", "user");
            userMap.put("content", summaryPrompt.toString());

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(sysMap);
            messages.add(userMap);

            List<String> summaryChunks = aiModelRouterService.streamChat(
                            summaryModelName,
                            messages,
                            AiModelRouterService.QueueStatusListener.NO_OP,
                            AiModelRouterService.ChatOptions.AUXILIARY_NO_TOOLS)
                    .collectList()
                    .block(Duration.ofSeconds(90));
            String summaryText = joinStreamChunks(summaryChunks).trim();
            if (!summaryText.isEmpty()) {
                Long newBoundaryMessageId = newMessages.get(newMessages.size() - 1).getId();
                log.info("摘要生成完成，长度: {}, sessionId={}, 边界消息ID={}", summaryText.length(), sessionId, newBoundaryMessageId);
                updateSummary(sessionId, summaryText, newBoundaryMessageId);
            }
        } catch (Exception e) {
            log.error("触发摘要异常: {}", e.getMessage());
        } finally {
            summarySessionsInProgress.remove(sessionId);
        }
    }

    @Override
    public ChatSummary getSummary(Long sessionId) {
        LambdaQueryWrapper<ChatSummary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSummary::getSessionId, sessionId);
        return chatSummaryMapper.selectOne(wrapper);
    }

    @Override
    public void updateSummary(Long sessionId, String summary, Long lastSummarizedMessageId) {
        ChatSummary existing = getSummary(sessionId);
        if (existing != null) {
            existing.setSummary(summary);
            existing.setLastSummarizedMessageId(lastSummarizedMessageId);
            chatSummaryMapper.updateById(existing);
        } else {
            ChatSummary newSummary = new ChatSummary();
            newSummary.setSessionId(sessionId);
            newSummary.setSummary(summary);
            newSummary.setLastSummarizedMessageId(lastSummarizedMessageId);
            chatSummaryMapper.insert(newSummary);
        }
    }

    @Override
    public Map<String, Object> getRagRuntimeMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        long totalContexts = contextBuildCount.get();
        long enabled = ragEnabledCount.get();
        long skipped = ragSkippedCount.get();
        long hit = ragHitCount.get();
        long noHit = ragNoHitCount.get();
        long filteredNoHit = ragFilteredNoHitCount.get();
        long errors = ragErrorCount.get();
        long cacheHits = ragCacheHitCount.get();

        metrics.put("contextBuildCount", totalContexts);
        metrics.put("ragEnabledCount", enabled);
        metrics.put("ragSkippedCount", skipped);
        metrics.put("ragHitCount", hit);
        metrics.put("ragNoHitCount", noHit);
        metrics.put("ragFilteredNoHitCount", filteredNoHit);
        metrics.put("ragErrorCount", errors);
        metrics.put("ragCacheHitCount", cacheHits);
        metrics.put("ragEnableRate", TextUtils.safeRatio(enabled, totalContexts));
        metrics.put("ragHitRate", TextUtils.safeRatio(hit, enabled));
        metrics.put("ragCacheHitRate", TextUtils.safeRatio(cacheHits, enabled));
        metrics.put("ragNoHitRate", TextUtils.safeRatio(noHit + filteredNoHit, enabled));
        metrics.put("recent1m", buildRecentRagMetrics(TimeUnit.MINUTES.toMillis(1)));
        metrics.put("recent5m", buildRecentRagMetrics(RAG_METRIC_WINDOW_5M_MS));
        return metrics;
    }

    private List<RetrievalCandidate> searchVectorKnowledge(List<String> retrievalQueries, ChatSession session,
                                                          int vectorQuota, int sessionQuota, int userQuota, int globalQuota) {
        Map<String, RetrievalCandidate> mergedCandidates = new LinkedHashMap<>();
        for (String retrievalQuery : retrievalQueries) {
            List<Document> similarDocs = knowledgeSearchCore.vectorSearch(retrievalQuery, ragVectorSearchTopK);
            if (similarDocs.isEmpty()) {
                continue;
            }

            List<RetrievalCandidate> scopedCandidates = similarDocs.stream()
                    .map(doc -> toVectorCandidate(doc, session))
                    .filter(Objects::nonNull)
                    .filter(candidate -> candidate.content() != null && !candidate.content().isBlank())
                    .toList();
            mergeCandidates(mergedCandidates, scopedCandidates);
        }
        return prioritizeScopedCandidates(new ArrayList<>(mergedCandidates.values()), vectorQuota, sessionQuota, userQuota, globalQuota);
    }

    private List<RetrievalCandidate> searchKeywordKnowledge(List<String> retrievalQueries, ChatSession session,
                                                           int keywordQuota, int sessionQuota, int userQuota, int globalQuota) {
        Map<String, RetrievalCandidate> mergedCandidates = new LinkedHashMap<>();
        for (String retrievalQuery : retrievalQueries) {
            List<redis.clients.jedis.search.Document> keywordDocs =
                    knowledgeSearchCore.keywordSearch(retrievalQuery, 20, null);
            if (keywordDocs.isEmpty()) {
                continue;
            }

            List<RetrievalCandidate> contents = new ArrayList<>();
            try {
                for (redis.clients.jedis.search.Document doc : keywordDocs) {
                    String content = doc.getString("content");
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    RetrievalCandidate candidate = toKeywordCandidate(content, doc, session);
                    if (candidate != null) {
                        contents.add(candidate);
                    }
                }
                mergeCandidates(mergedCandidates, contents);
            } catch (Exception e) {
                log.warn("BM25 关键字检索失败 (可能索引不存在或语法错误), query={}, error={}", RetrievalUtil.summarizeQuestion(retrievalQuery), e.getMessage());
            }
        }
        return prioritizeScopedCandidates(new ArrayList<>(mergedCandidates.values()), keywordQuota, sessionQuota, userQuota, globalQuota);
    }

    private void mergeCandidates(Map<String, RetrievalCandidate> candidateMap, List<RetrievalCandidate> candidates) {
        for (RetrievalCandidate candidate : candidates) {
            if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }

            candidateMap.merge(candidate.content(), candidate, (existing, incoming) ->
                    new RetrievalCandidate(
                            existing.content(),
                            mergeSourceLabels(existing.sourceLabel(), incoming.sourceLabel()),
                            preferredScope(existing.scope(), incoming.scope())));
        }
    }

    private String mergeSourceLabels(String left, String right) {
        if (left.equals(right)) {
            return left;
        }
        Set<String> labels = new LinkedHashSet<>();
        labels.addAll(List.of(left.split("\\+")));
        labels.addAll(List.of(right.split("\\+")));
        return String.join("+", labels);
    }

    private Map<String, RetrievalCandidate> mergeCandidatesWithRrf(
            List<RetrievalCandidate> vectorCandidates,
            List<RetrievalCandidate> keywordCandidates) {
        final double k = 60.0;
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> candidateByContent = new LinkedHashMap<>();

        for (int i = 0; i < vectorCandidates.size(); i++) {
            RetrievalCandidate c = vectorCandidates.get(i);
            if (c == null || c.content() == null || c.content().isBlank()) {
                continue;
            }
            String content = c.content();
            rrfScores.merge(content, 1.0 / (k + i + 1), Double::sum);
            candidateByContent.putIfAbsent(content, c);
        }

        for (int i = 0; i < keywordCandidates.size(); i++) {
            RetrievalCandidate c = keywordCandidates.get(i);
            if (c == null || c.content() == null || c.content().isBlank()) {
                continue;
            }
            String content = c.content();
            rrfScores.merge(content, 1.0 / (k + i + 1), Double::sum);
            if (candidateByContent.containsKey(content)) {
                RetrievalCandidate existing = candidateByContent.get(content);
                candidateByContent.put(content, new RetrievalCandidate(
                        existing.content(),
                        mergeSourceLabels(existing.sourceLabel(), c.sourceLabel()),
                        preferredScope(existing.scope(), c.scope())));
            } else {
                candidateByContent.putIfAbsent(content, c);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        candidateByContent::get,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private RankedRetrievalCandidate toRankedCandidate(RerankService.RerankResult result, Map<String, RetrievalCandidate> candidateMap) {
        RetrievalCandidate candidate = candidateMap.get(result.document());
        if (candidate == null) {
            return null;
        }
        return new RankedRetrievalCandidate(candidate.content(), candidate.sourceLabel(), result.score());
    }

    private Map<String, Object> buildNoHitSystemMessage() {
        Map<String, Object> noHitMsg = new HashMap<>();
        noHitMsg.put("role", "system");
        noHitMsg.put("content", "当前知识库未检索到足够相关的证据片段。本轮回答必须明确说明「无相关信息」或「知识库未提供足够证据」，禁止补充未检索到的事实、步骤或配置。");
        return noHitMsg;
    }

    private String retrieveFullDocumentText(Long sessionId, ChatSession session) {
        try {
            List<ChatAttachment> attachments = chatAttachmentMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatAttachment>()
                            .eq(ChatAttachment::getSessionId, sessionId));
            if (attachments == null || attachments.isEmpty()) {
                log.info("Full doc retrieval: no attachments for sessionId={}", sessionId);
                return "";
            }
            StringBuilder fullText = new StringBuilder(buildRagInstruction(""))
                    .append("\n[Full document content from knowledge base]\n");
            int totalChars = 0;
            for (ChatAttachment attachment : attachments) {
                try {
                    String text = chatAttachmentService.getFullText(attachment.getId());
                    if (text != null && !text.isBlank()) {
                        if (totalChars + text.length() > ragFullMaxChars) {
                            int remaining = ragFullMaxChars - totalChars;
                            if (remaining > 200) {
                                fullText.append("\n=== ").append(attachment.getFileName())
                                        .append(" (truncated) ===\n");
                                fullText.append(text, 0, remaining);
                                fullText.append("\n...[document too long, truncated]...\n");
                            }
                            break;
                        }
                        fullText.append("\n=== ").append(attachment.getFileName()).append(" ===\n");
                        fullText.append(text).append("\n");
                        totalChars += text.length();
                    }
                } catch (Exception e) {
                    log.warn("Full doc retrieval failed for attachmentId={}, fileName={}, error={}",
                            attachment.getId(), attachment.getFileName(), e.getMessage());
                }
            }
            fullText.append("[End of full document content]\n");
            log.info("Full doc retrieval done: sessionId={}, files={}, totalChars={}", sessionId, attachments.size(), totalChars);
            return fullText.toString();
        } catch (Exception e) {
            log.warn("Full doc retrieval failed: sessionId={}, error={}", sessionId, e.getMessage());
            return "";
        }
    }

    private String extractLatestUserQuestion(List<ChatMessage> recentMessages) {
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = recentMessages.get(i);
            if (Constants.ROLE_USER.equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                return msg.getContent().trim();
            }
        }
        return "";
    }

    private String buildRetrievalQuery(List<ChatMessage> recentMessages, ChatSummary summary) {
        String latestUserQuestion = extractLatestUserQuestion(recentMessages);
        if (latestUserQuestion.isEmpty()) {
            return "";
        }

        List<String> recentUserMessages = recentMessages.stream()
                .filter(msg -> Constants.ROLE_USER.equals(msg.getRole()))
                .map(ChatMessage::getContent)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(content -> !content.isEmpty())
                .collect(Collectors.toList());

        int startIndex = Math.max(0, recentUserMessages.size() - 3);
        List<String> lastUserMessages = recentUserMessages.subList(startIndex, recentUserMessages.size());

        StringBuilder queryBuilder = new StringBuilder();
        if (summary != null && summary.getSummary() != null && !summary.getSummary().isBlank()) {
            queryBuilder.append("历史摘要：")
                    .append(summary.getSummary(), 0, Math.min(summary.getSummary().length(), 200))
                    .append('\n');
        }
        if (lastUserMessages.size() > 1) {
            queryBuilder.append("最近问题：")
                    .append(String.join("；", lastUserMessages.subList(0, lastUserMessages.size() - 1)))
                    .append('\n');
        }
        queryBuilder.append("当前问题：").append(latestUserQuestion);
        return queryBuilder.toString();
    }

    private RetrievalQueryPlan buildRetrievalQueries(String retrievalQuery, String latestUserQuestion,
                                                      List<ChatMessage> recentMessages, ChatSummary summary) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addQueryVariant(queries, latestUserQuestion);
        addQueryVariant(queries, retrievalQuery);
        CompletableFuture<List<String>> expansionFuture = CompletableFuture.completedFuture(List.of());

        if (ragQueryExpansionEnabled) {
            int cacheKey = Objects.hash(latestUserQuestion);
            CachedExpansion cached = expansionCache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.createdAtMs) < EXPANSION_CACHE_TTL_MS) {
                for (String expandedQuery : cached.queries()) {
                    addQueryVariant(queries, expandedQuery);
                }
            } else {
                expansionFuture = CompletableFuture.supplyAsync(
                        () -> expandAndCache(latestUserQuestion, recentMessages, summary), ragExecutor);
            }
        }

        List<String> baseQueries = queries.stream()
                .limit(Math.max(2, ragQueryExpansionVariants + 2L))
                .toList();
        return new RetrievalQueryPlan(baseQueries, expansionFuture);
    }

    private List<String> expandAndCache(String latestUserQuestion, List<ChatMessage> recentMessages, ChatSummary summary) {
        List<String> expanded = expandQueryVariantsByLlm(latestUserQuestion, recentMessages, summary);
        if (!expanded.isEmpty()) {
            int cacheKey = Objects.hash(latestUserQuestion);
            expansionCache.put(cacheKey, new CachedExpansion(expanded, System.currentTimeMillis()));
        }
        return expanded;
    }

    private void addQueryVariant(LinkedHashSet<String> queries, String query) {
        String normalized = RetrievalUtil.sanitizeQueryVariant(query);
        if (!normalized.isEmpty()) {
            queries.add(normalized);
        }
    }

    private List<String> expandQueryVariantsByLlm(String latestUserQuestion, List<ChatMessage> recentMessages, ChatSummary summary) {
        if (!ragQueryExpansionEnabled || latestUserQuestion == null || latestUserQuestion.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> sysMsg = new HashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", "你是一个知识库检索查询改写助手。请把用户问题改写为适合检索的不同表达。输出3到5行，每行一个查询，不要编号，不要解释。要求保留实体名、配置项、报错现象和关键术语，同时兼顾口语化说法与书面化说法。");

            StringBuilder userPrompt = new StringBuilder();
            if (summary != null && summary.getSummary() != null && !summary.getSummary().isBlank()) {
                userPrompt.append("历史摘要：")
                        .append(summary.getSummary(), 0, Math.min(summary.getSummary().length(), 160))
                        .append('\n');
            }
            List<String> recentUserMessages = recentMessages.stream()
                    .filter(msg -> Constants.ROLE_USER.equals(msg.getRole()))
                    .map(ChatMessage::getContent)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(content -> !content.isEmpty())
                    .toList();
            if (recentUserMessages.size() > 1) {
                int start = Math.max(0, recentUserMessages.size() - 3);
                userPrompt.append("最近上下文：")
                        .append(String.join("；", recentUserMessages.subList(start, recentUserMessages.size() - 1)))
                        .append('\n');
            }
            userPrompt.append("当前问题：").append(latestUserQuestion).append('\n')
                    .append("请输出更适合检索的不同表达，覆盖口语化、书面化、故障描述、操作描述等角度。");

            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt.toString());

            List<String> chunks = aiModelRouterService.streamChat(
                            ragQueryExpansionModel,
                            List.of(sysMsg, userMsg),
                            AiModelRouterService.QueueStatusListener.NO_OP,
                            AiModelRouterService.ChatOptions.AUXILIARY_NO_TOOLS)
                    .collectList()
                    .block(Duration.ofSeconds(Math.max(ragQueryExpansionTimeoutSeconds, 5)));
            String responseText = joinStreamChunks(chunks);
            return RetrievalUtil.parseExpandedQueries(responseText, Math.max(ragQueryExpansionVariants, 3));
        } catch (Exception e) {
            log.warn("查询扩展失败，回退原始查询: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildRagInstruction(String latestUserQuestion) {
        StringBuilder instruction = new StringBuilder("""
                你是一个严格依据知识库证据回答问题的企业知识助手。
                回答规则：
                1. 只能使用[知识库检索结果]中的事实回答，禁止补充片段中未出现的步骤、配置、原因或结论。
                2. 如果证据不足、没有直接答案或资料缺步骤，请明确回答"无相关信息"或"知识库未提供足够证据"，禁止猜测和编造。
                3. 每个关键结论、原因、步骤后都必须标注来源，格式为 [来源: xxx]。
                4. 如果多个片段有冲突，优先采用更贴近问题、步骤更完整、来源更具体的片段，并标注来源。
                5. 不要输出检索分数，不要声称自己查阅了外部网页或数据库。
                """);
        instruction.append("回答格式要求：").append(buildAnswerFormatInstruction(latestUserQuestion)).append("\n\n");
        return instruction.toString();
    }

    private String buildAnswerFormatInstruction(String latestUserQuestion) {
        String normalized = latestUserQuestion == null ? "" : latestUserQuestion.toLowerCase(Locale.ROOT);
        if (TextUtils.containsAny(normalized, new String[]{"报错", "异常", "挂了", "故障", "排查", "无法", "失败", "连接不上"})) {
            return "先给故障判断，再分点列出可能原因、排查步骤和处理建议；每个原因或步骤后都附来源。";
        }
        if (TextUtils.containsAny(normalized, new String[]{"怎么", "如何", "步骤", "流程", "操作", "配置", "部署", "切换", "执行"})) {
            return "先给一句结论，再按步骤回答；每一步单独一行并附来源。";
        }
        if (TextUtils.containsAny(normalized, new String[]{"什么是", "原理", "作用", "区别", "含义", "为什么", "介绍"})) {
            return "先给定义或结论，再分点展开说明；每个要点都附来源。";
        }
        return "先直接回答，再补充支撑依据；每个关键结论都附来源。";
    }

    private RagDecision evaluateRagDecision(String latestUserQuestion, List<ChatMessage> recentMessages,
                                            KnowledgeScopeStats knowledgeScopeStats) {
        if (!ragIntentEnabled) {
            return new RagDecision(true, "intent_gate_disabled");
        }
        if (latestUserQuestion == null || latestUserQuestion.isBlank()) {
            return new RagDecision(false, "empty_question");
        }

        String normalizedQuestion = latestUserQuestion.trim().toLowerCase(Locale.ROOT);
        if (isSimpleChatIntent(normalizedQuestion)) {
            return new RagDecision(false, "simple_chat");
        }
        if (isToolLikeIntent(normalizedQuestion)) {
            return new RagDecision(false, "tool_like");
        }

        if (!knowledgeScopeStats.hasAnyKnowledge()) {
            return new RagDecision(false, "no_accessible_knowledge");
        }
        if (containsKnowledgeKeyword(normalizedQuestion)) {
            String scope = isFullDocumentIntent(normalizedQuestion) ? RagDecision.SCOPE_FULL : RagDecision.SCOPE_FOCUSED;
            return new RagDecision(true, "knowledge_keyword", scope);
        }

        if (looksLikeKnowledgeQuestion(normalizedQuestion) || isFollowUpQuestion(normalizedQuestion, recentMessages)) {
            String scope = isFullDocumentIntent(normalizedQuestion) ? RagDecision.SCOPE_FULL : RagDecision.SCOPE_FOCUSED;
            return new RagDecision(true, "scoped_knowledge_follow_up", scope);
        }

        // LLM fallback for ambiguous cases — 合并意图判断+查询扩展为一次调用
        if (ragIntentLlmFallbackEnabled && latestUserQuestion.length() >= 8 && knowledgeScopeStats.hasAnyKnowledge()) {
            try {
                CombinedIntentResult combined = combinedIntentAndExpansion(latestUserQuestion);
                if (combined.needsKnowledge()) {
                    if (!combined.expandedQueries().isEmpty()) {
                        int cacheKey = Objects.hash(latestUserQuestion);
                        expansionCache.put(cacheKey, new CachedExpansion(combined.expandedQueries(), System.currentTimeMillis()));
                    }
                    return new RagDecision(true, "llm_fallback", combined.retrievalScope());
                }
            } catch (Exception e) {
                log.debug("合并意图+扩展调用失败，回退到规则判断: {}", e.getMessage());
            }
        }

        return new RagDecision(false, "non_knowledge_intent");
    }

    private CombinedIntentResult combinedIntentAndExpansion(String question) {
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", """
            你是一个知识库检索助手，请完成以下任务：
            1. 判断用户问题是否需要检索知识库来获取准确答案
            2. 如果需要检索，输出3-5个适合检索的不同表达（覆盖口语化、书面化、故障描述、操作描述等角度）
            3. 判断检索范围：
               - focused: 用户问具体事实/细节/单个问题（如"文档里提到Python了吗"）
               - broad: 用户想了解某一方面或章节（如"工作经历部分详细说说"）
               - full: 用户要求总结/概括/了解整体内容（如"给我讲讲这个文档"、"文件里有什么"）

            输出格式（严格遵守）：
            第一行：INTENT: YES 或 INTENT: NO
            第二行：SCOPE: focused 或 SCOPE: broad 或 SCOPE: full
            如果需要检索，后续每行一个查询改写（不要编号，不要解释）
            如果不需要检索，只输出 INTENT: NO，不要输出SCOPE和其他内容""");

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "问题：" + question);

        try {
            List<String> chunks = aiModelRouterService.streamChat(
                            ragQueryExpansionModel,
                            List.of(sysMsg, userMsg),
                            AiModelRouterService.QueueStatusListener.NO_OP,
                            AiModelRouterService.ChatOptions.AUXILIARY_NO_TOOLS)
                    .collectList()
                    .block(Duration.ofSeconds(8));
            String response = joinStreamChunks(chunks).trim();
            return parseCombinedResult(response);
        } catch (Exception e) {
            log.debug("合并意图+扩展调用失败: {}", e.getMessage());
            return new CombinedIntentResult(false, List.of());
        }
    }

    private CombinedIntentResult parseCombinedResult(String response) {
        if (response == null || response.isBlank()) {
            return new CombinedIntentResult(false, List.of());
        }

        String[] lines = response.split("\\n");
        boolean needsKnowledge = false;
        String scope = RagDecision.SCOPE_FOCUSED;
        List<String> queries = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String upperTrimmed = trimmed.toUpperCase(Locale.ROOT);
            if (upperTrimmed.startsWith("INTENT:")) {
                needsKnowledge = upperTrimmed.contains("YES");
            } else if (upperTrimmed.startsWith("SCOPE:")) {
                String scopeValue = trimmed.substring(6).trim().toLowerCase(Locale.ROOT);
                if (RagDecision.SCOPE_FULL.equals(scopeValue) || RagDecision.SCOPE_BROAD.equals(scopeValue)) {
                    scope = scopeValue;
                }
            } else if (needsKnowledge && !upperTrimmed.startsWith("INTENT") && !upperTrimmed.startsWith("SCOPE")) {
                String cleaned = RetrievalUtil.sanitizeQueryVariant(trimmed);
                if (!cleaned.isEmpty()) {
                    queries.add(cleaned);
                }
            }
        }

        return new CombinedIntentResult(needsKnowledge, queries, scope);
    }

    private KnowledgeScopeStats getKnowledgeScopeStats(ChatSession session) {
        if (session == null) {
            return new KnowledgeScopeStats(0, 0, 0);
        }
        Map<String, Object> counts = chatAttachmentMapper.countKnowledgeByScopes(session.getId(), session.getUserId());
        long sessionCount = ((Number) counts.getOrDefault("session_count", 0)).longValue();
        long userCount = ((Number) counts.getOrDefault("user_count", 0)).longValue();
        long globalCount = ((Number) counts.getOrDefault("global_count", 0)).longValue();
        return new KnowledgeScopeStats(sessionCount, userCount, globalCount);
    }

    private boolean containsKnowledgeKeyword(String question) {
        String[] keywords = {
                "文档", "资料", "文件", "附件", "知识库", "上传", "制度", "规范", "规则", "流程",
                "接口", "配置", "参数", "说明", "总结", "内容", "依据", "手册", "合同", "方案"
        };
        return TextUtils.containsAny(question, keywords);
    }

    private boolean looksLikeKnowledgeQuestion(String question) {
        String[] patterns = {
                "什么", "怎么", "如何", "为什么", "是否", "哪些", "区别", "对比", "介绍", "说明",
                "总结", "梳理", "实现", "原理", "作用", "配置", "规则", "流程", "步骤", "含义", "?"
        };
        return TextUtils.containsAny(question, patterns) || question.length() >= 12;
    }

    private boolean isFollowUpQuestion(String question, List<ChatMessage> recentMessages) {
        if (!TextUtils.containsAny(question, new String[]{"这个", "那个", "它", "上述", "上面", "前面", "继续", "刚才", "这里"})) {
            return false;
        }

        long userMessageCount = recentMessages.stream()
                .filter(msg -> Constants.ROLE_USER.equals(msg.getRole()))
                .count();
        return userMessageCount >= 2;
    }

    private boolean isSimpleChatIntent(String question) {
        return TextUtils.containsAny(question, new String[]{
                "你好", "hello", "hi", "在吗", "谢谢", "感谢", "早上好", "晚上好", "午安",
                "哈哈", "好的", "行", "收到", "ok", "嗯", "哦"
        }) && question.length() <= 12;
    }

    private boolean isToolLikeIntent(String question) {
        return TextUtils.containsAny(question, new String[]{
                "几点", "时间", "天气", "下雨", "温度", "weather", "time"
        });
    }

    private boolean isFullDocumentIntent(String question) {
        String[] fullIntentKeywords = {"总结", "概括", "梳理", "讲讲", "介绍", "所有", "全部", "完整", "整体", "里面", "都有", "包含", "有什么"};
        String[] documentRefKeywords = {"文档", "文件", "简历", "资料", "合同", "这个", "那个", "上传", "附件", "知识库"};
        return TextUtils.containsAny(question, fullIntentKeywords) && TextUtils.containsAny(question, documentRefKeywords);
    }

    private void logRagTrace(Long sessionId, String latestUserQuestion, RagDecision ragDecision,
                             int vectorCandidateCount, int keywordCandidateCount, int mergedCandidateCount,
                             int rerankedCandidateCount, int finalCandidateCount, String ragOutcome, long ragStartNanos,
                             boolean cacheHit) {
        if (!ragObservabilityEnabled) {
            return;
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ragStartNanos);
        log.info("RAG观测 sessionId={} enabled={} reason={} outcome={} cacheHit={} vector={} keyword={} merged={} reranked={} final={} elapsedMs={} question={}",
                sessionId, ragDecision.enabled(), ragDecision.reason(), ragOutcome, cacheHit, vectorCandidateCount, keywordCandidateCount,
                mergedCandidateCount, rerankedCandidateCount, finalCandidateCount, elapsedMs, RetrievalUtil.summarizeQuestion(latestUserQuestion));
    }

    private void recordRagMetrics(RagDecision ragDecision, String ragOutcome, boolean cacheHit) {
        recentRagEvents.addLast(new RagRuntimeEvent(System.currentTimeMillis(), ragDecision.enabled(), ragOutcome, cacheHit));
        pruneExpiredRagEvents();

        if (ragDecision.enabled()) {
            ragEnabledCount.incrementAndGet();
        } else {
            ragSkippedCount.incrementAndGet();
        }

        if (cacheHit) {
            ragCacheHitCount.incrementAndGet();
        }

        switch (ragOutcome) {
            case "hit", "hit_soft_fallback" -> ragHitCount.incrementAndGet();
            case "no_hit" -> ragNoHitCount.incrementAndGet();
            case "filtered_no_hit" -> ragFilteredNoHitCount.incrementAndGet();
            case "error" -> ragErrorCount.incrementAndGet();
            default -> {
            }
        }
    }

    private String joinStreamChunks(List<String> chunks) {
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
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CHUNK_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final ThreadLocal<java.security.MessageDigest> MD5_DIGEST =
            ThreadLocal.withInitial(() -> {
                try { return java.security.MessageDigest.getInstance("MD5"); }
                catch (java.security.NoSuchAlgorithmException e) { throw new RuntimeException(e); }
            });

    private void trackChunkAttribution(Long sessionId, List<RankedRetrievalCandidate> candidates) {
        try {
            List<String> chunkIds = new ArrayList<>();
            List<Double> chunkScores = new ArrayList<>();
            java.security.MessageDigest md = MD5_DIGEST.get();
            md.reset();
            for (RankedRetrievalCandidate c : candidates) {
                byte[] digest = md.digest(c.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                chunkIds.add(sb.toString());
                chunkScores.add(Math.round(c.score() * 10000.0) / 10000.0);
            }
            cleanExpiredAttributions();
            pendingChunkAttributions.put(sessionId, new TimedAttribution(new RagChunkAttribution(
                    CHUNK_OBJECT_MAPPER.writeValueAsString(chunkIds),
                    CHUNK_OBJECT_MAPPER.writeValueAsString(chunkScores)), System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("记录RAG chunk归属失败: {}", e.getMessage());
        }
    }

    private void cleanExpiredAttributions() {
        long expireBefore = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ragChunkAttributionTtlMinutes);
        pendingChunkAttributions.entrySet().removeIf(entry -> entry.getValue().createdAtMs < expireBefore);
    }

    private void applyChunkAttribution(ChatMessage message) {
        TimedAttribution timed = pendingChunkAttributions.remove(message.getSessionId());
        if (timed != null) {
            RagChunkAttribution attribution = timed.attribution;
            message.setRagChunkIds(attribution.chunkIds());
            message.setRagChunkScores(attribution.chunkScores());
            chatMessageService.updateMessageFields(message);
        }
    }

    private Map<String, Object> buildRecentRagMetrics(long windowMs) {
        pruneExpiredRagEvents();
        long now = System.currentTimeMillis();
        long totalContexts = 0;
        long enabled = 0;
        long skipped = 0;
        long hit = 0;
        long noHit = 0;
        long filteredNoHit = 0;
        long errors = 0;
        long cacheHits = 0;

        for (RagRuntimeEvent event : recentRagEvents) {
            if (now - event.timestampMs() > windowMs) {
                continue;
            }
            totalContexts++;
            if (event.enabled()) {
                enabled++;
            } else {
                skipped++;
            }
            if (event.cacheHit()) {
                cacheHits++;
            }
            switch (event.outcome()) {
                case "hit" -> hit++;
                case "no_hit" -> noHit++;
                case "filtered_no_hit" -> filteredNoHit++;
                case "error" -> errors++;
                default -> {
                }
            }
        }

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("contextBuildCount", totalContexts);
        metrics.put("ragEnabledCount", enabled);
        metrics.put("ragSkippedCount", skipped);
        metrics.put("ragHitCount", hit);
        metrics.put("ragNoHitCount", noHit);
        metrics.put("ragFilteredNoHitCount", filteredNoHit);
        metrics.put("ragErrorCount", errors);
        metrics.put("ragCacheHitCount", cacheHits);
        metrics.put("ragEnableRate", TextUtils.safeRatio(enabled, totalContexts));
        metrics.put("ragHitRate", TextUtils.safeRatio(hit, enabled));
        metrics.put("ragCacheHitRate", TextUtils.safeRatio(cacheHits, enabled));
        metrics.put("ragNoHitRate", TextUtils.safeRatio(noHit + filteredNoHit, enabled));
        return metrics;
    }

    private void pruneExpiredRagEvents() {
        long expireBefore = System.currentTimeMillis() - RAG_METRIC_WINDOW_5M_MS;
        while (true) {
            RagRuntimeEvent first = recentRagEvents.peekFirst();
            if (first == null || first.timestampMs() >= expireBefore) {
                return;
            }
            recentRagEvents.pollFirst();
        }
    }

    private String buildRagCacheKey(Long sessionId, List<String> retrievalQueries, ChatSummary summary,
                                    List<ChatMessage> recentMessages, KnowledgeScopeStats knowledgeScopeStats) {
        long lastMessageId = recentMessages.isEmpty() ? 0L : recentMessages.get(recentMessages.size() - 1).getId();
        long summaryBoundary = summary != null && summary.getLastSummarizedMessageId() != null
                ? summary.getLastSummarizedMessageId() : 0L;
        String querySignature = retrievalQueries == null ? "" : String.join("||", retrievalQueries);
        int queryHash = Objects.hash(sessionId, querySignature, summaryBoundary, lastMessageId, knowledgeScopeStats.cacheSignature());
        return Integer.toHexString(queryHash);
    }

    private Map<String, Object> getCachedRagPayload(Long sessionId, String ragCacheKey) {
        if (!ragCacheEnabled) {
            return null;
        }
        return cacheService.getCachedRagResult(sessionId, ragCacheKey);
    }

    private void cacheRagPayload(Long sessionId, String ragCacheKey, String outcome, String ragContext,
                                 int vectorCount, int keywordCount, int mergedCount, int rerankedCount, int finalCount) {
        if (!ragCacheEnabled) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("outcome", outcome);
        payload.put("ragContext", ragContext);
        payload.put("vectorCount", vectorCount);
        payload.put("keywordCount", keywordCount);
        payload.put("mergedCount", mergedCount);
        payload.put("rerankedCount", rerankedCount);
        payload.put("finalCount", finalCount);
        cacheService.cacheRagResult(sessionId, ragCacheKey, payload, ragCacheTtlSeconds);
    }

    private void applyCachedRagPayload(List<Map<String, Object>> context, Map<String, Object> cachedRagResult) {
        String outcome = stringValue(cachedRagResult.get("outcome"));
        String ragContext = stringValue(cachedRagResult.get("ragContext"));
        if ("hit".equals(outcome) && !ragContext.isBlank()) {
            Map<String, Object> ragSysMsg = new HashMap<>();
            ragSysMsg.put("role", "system");
            ragSysMsg.put("content", ragContext);
            context.add(ragSysMsg);
            return;
        }

        if ("no_hit".equals(outcome) || "filtered_no_hit".equals(outcome)) {
            context.add(buildNoHitSystemMessage());
        }
    }

    private int parseIntValue(Object value) {
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean matchesScope(String sessionId, String userId, String scope, ChatSession session) {
        if (session == null) {
            return true;
        }
        String resolvedScope = resolveKnowledgeScope(sessionId, userId, scope);
        String currentSessionId = String.valueOf(session.getId());
        String currentUserId = session.getUserId() != null ? String.valueOf(session.getUserId()) : "";

        return switch (resolvedScope) {
            case Constants.SCOPE_GLOBAL -> true;
            case Constants.SCOPE_SESSION -> !sessionId.isBlank() && currentSessionId.equals(sessionId);
            case Constants.SCOPE_USER -> !userId.isBlank() && currentUserId.equals(userId);
            default -> false;
        };
    }

    private RetrievalCandidate toVectorCandidate(Document document, ChatSession session) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String sessionId = stringValue(metadata.get("session_id"));
        String userId = stringValue(metadata.get("user_id"));
        String scope = stringValue(metadata.get("scope"));
        String sourcePath = stringValue(metadata.get("source_path"));
        String fileName = stringValue(metadata.get("file_name"));
        if (!matchesScope(sessionId, userId, scope, session)) {
            return null;
        }
        String resolvedScope = resolveKnowledgeScope(sessionId, userId, scope);
        return new RetrievalCandidate(document.getContent(),
                buildKnowledgeSourceLabel(resolvedScope, "向量检索", sourcePath, fileName), resolvedScope);
    }

    private RetrievalCandidate toKeywordCandidate(String content, redis.clients.jedis.search.Document document, ChatSession session) {
        String sessionId = document.getString("session_id");
        String userId = document.getString("user_id");
        String scope = document.getString("scope");
        String sourcePath = document.getString("source_path");
        String fileName = document.getString("file_name");
        if (!matchesScope(sessionId, userId, scope, session)) {
            return null;
        }
        String resolvedScope = resolveKnowledgeScope(sessionId, userId, scope);
        return new RetrievalCandidate(content,
                buildKnowledgeSourceLabel(resolvedScope, "关键词检索", sourcePath, fileName), resolvedScope);
    }

    private List<RetrievalCandidate> prioritizeScopedCandidates(List<RetrievalCandidate> candidates, int totalQuota,
                                                                int sessionQuota, int userQuota, int globalQuota) {
        if (candidates == null || candidates.isEmpty() || totalQuota <= 0) {
            return Collections.emptyList();
        }
        List<RetrievalCandidate> prioritized = new ArrayList<>(Math.min(candidates.size(), totalQuota));
        Set<String> selectedContents = new LinkedHashSet<>();
        appendScopedCandidates(prioritized, selectedContents, candidates, Constants.SCOPE_SESSION, sessionQuota, totalQuota);
        appendScopedCandidates(prioritized, selectedContents, candidates, Constants.SCOPE_USER, userQuota, totalQuota);
        appendScopedCandidates(prioritized, selectedContents, candidates, Constants.SCOPE_GLOBAL, globalQuota, totalQuota);
        if (prioritized.size() < totalQuota) {
            for (RetrievalCandidate candidate : candidates) {
                if (prioritized.size() >= totalQuota) {
                    break;
                }
                if (selectedContents.add(candidate.content())) {
                    prioritized.add(candidate);
                }
            }
        }
        return prioritized;
    }

    private void appendScopedCandidates(List<RetrievalCandidate> prioritized, Set<String> selectedContents,
                                        List<RetrievalCandidate> candidates, String scope, int scopeQuota, int totalQuota) {
        if (scopeQuota <= 0 || prioritized.size() >= totalQuota) {
            return;
        }
        int appended = 0;
        for (RetrievalCandidate candidate : candidates) {
            if (prioritized.size() >= totalQuota || appended >= scopeQuota) {
                return;
            }
            if (!scope.equals(candidate.scope())) {
                continue;
            }
            if (selectedContents.add(candidate.content())) {
                prioritized.add(candidate);
                appended++;
            }
        }
    }

    private String resolveKnowledgeScope(String sessionId, String userId, String scope) {
        String normalizedScope = stringValue(scope).toLowerCase(Locale.ROOT);
        if (Constants.SCOPE_SESSION.equals(normalizedScope) || Constants.SCOPE_USER.equals(normalizedScope) || Constants.SCOPE_GLOBAL.equals(normalizedScope)) {
            return normalizedScope;
        }
        if (Constants.SCOPE_GLOBAL.equalsIgnoreCase(sessionId)) {
            return Constants.SCOPE_GLOBAL;
        }
        if (!stringValue(sessionId).isBlank()) {
            return Constants.SCOPE_SESSION;
        }
        if (!stringValue(userId).isBlank()) {
            return Constants.SCOPE_USER;
        }
        return Constants.SCOPE_GLOBAL;
    }

    private String buildKnowledgeSourceLabel(String scope, String channel, String sourcePath, String fileName) {
        String scopeLabel = switch (scope) {
            case Constants.SCOPE_SESSION -> "会话知识";
            case Constants.SCOPE_USER -> "用户知识";
            case Constants.SCOPE_GLOBAL -> "全局知识";
            default -> "知识";
        };
        String location = !sourcePath.isBlank() ? sourcePath : (!fileName.isBlank() ? fileName : "未命名片段");
        return scopeLabel + "·" + channel + "·" + location;
    }

    private String preferredScope(String leftScope, String rightScope) {
        return scopePriority(leftScope) <= scopePriority(rightScope) ? leftScope : rightScope;
    }

    private int scopePriority(String scope) {
        return switch (scope) {
            case Constants.SCOPE_SESSION -> 0;
            case Constants.SCOPE_USER -> 1;
            case Constants.SCOPE_GLOBAL -> 2;
            default -> 3;
        };
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> convertMessagesToContext(List<ChatMessage> messages) {
        List<Map<String, Object>> context = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            Map<String, Object> item = new HashMap<>();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            context.add(item);
        }
        return context;
    }

    private record RetrievalCandidate(String content, String sourceLabel, String scope) {
    }

    private record TimedAttribution(RagChunkAttribution attribution, long createdAtMs) {
    }

    private record KnowledgeScopeStats(long sessionCount, long userCount, long globalCount) {
        private long totalCount() {
            return sessionCount + userCount + globalCount;
        }

        private boolean hasAnyKnowledge() {
            return totalCount() > 0;
        }

        private String cacheSignature() {
            return sessionCount + ":" + userCount + ":" + globalCount;
        }
    }

    private record CombinedIntentResult(boolean needsKnowledge, List<String> expandedQueries, String retrievalScope) {
        CombinedIntentResult(boolean needsKnowledge, List<String> expandedQueries) {
            this(needsKnowledge, expandedQueries, RagDecision.SCOPE_FOCUSED);
        }
    }

    private record CachedExpansion(List<String> queries, long createdAtMs) {}

    private record RetrievalQueryPlan(List<String> baseQueries, CompletableFuture<List<String>> expansionFuture) {}
}
