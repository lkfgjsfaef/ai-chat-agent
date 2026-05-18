package com.niit.agent.service.impl;

import com.niit.agent.config.ModelConfigCache;
import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.model.AiModel;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiModelRouterServiceImpl implements AiModelRouterService {

    private static final List<String> ROTATION_MODELS = List.of("glm-4.7", "glm-4.6v", "glm-4.5-air");
    private static final String DEEPSEEK_MODEL = "deepseek";

    private final List<AiModel> models;
    private final Map<String, AiModel> modelMap;
    private final ModelConfigCache modelConfigCache;
    private final RedissonClient redissonClient;
    private final Deque<ModelRuntimeEvent> recentRuntimeEvents = new ConcurrentLinkedDeque<>();
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RSemaphore modelExecutionSemaphore;
    private RAtomicLong waitingRequests;
    private RAtomicLong activeRequests;
    private RSemaphore auxiliaryExecutionSemaphore;
    private RAtomicLong auxiliaryWaitingRequests;
    private RAtomicLong auxiliaryActiveRequests;
    private static final long METRIC_WINDOW_5M_MS = TimeUnit.MINUTES.toMillis(5);

    @Value("${ai.default-model:deepseek}")
    private String defaultModel;

    @Value("${ai.router.failure-threshold:2}")
    private int failureThreshold;

    @Value("${ai.router.circuit-open-seconds:60}")
    private long circuitOpenSeconds;

    @Value("${ai.router.circuit.failure-rate-threshold:50}")
    private float circuitFailureRateThreshold;

    @Value("${ai.router.circuit.sliding-window-size:10}")
    private int circuitSlidingWindowSize;

    @Value("${ai.router.circuit.permitted-half-open-calls:2}")
    private int circuitPermittedHalfOpenCalls;

    @Value("${ai.router.max-concurrent-calls:6}")
    private int maxConcurrentCalls;

    @Value("${ai.router.queue-capacity:24}")
    private int queueCapacity;

    @Value("${ai.router.queue-timeout-ms:12000}")
    private long queueTimeoutMs;

    @Value("${ai.router.aux-max-concurrent-calls:2}")
    private int auxiliaryMaxConcurrentCalls;

    @Value("${ai.router.aux-queue-capacity:8}")
    private int auxiliaryQueueCapacity;

    @Value("${ai.router.aux-queue-timeout-ms:3000}")
    private long auxiliaryQueueTimeoutMs;

    @Value("${ai.router.distributed-key-prefix:ai:model:gate}")
    private String gateKeyPrefix;

    public AiModelRouterServiceImpl(List<AiModel> models, ModelConfigCache modelConfigCache, RedissonClient redissonClient) {
        this.models = models;
        this.modelConfigCache = modelConfigCache;
        this.redissonClient = redissonClient;
        this.modelMap = models.stream()
                .collect(Collectors.toMap(AiModel::getModelName, m -> m));
        log.info("已加载AI模型: {}", modelMap.keySet());
    }

    @PostConstruct
    public void initConcurrencyGate() {
        this.modelExecutionSemaphore = redissonClient.getSemaphore(gateKeyPrefix + ":semaphore");
        this.waitingRequests = redissonClient.getAtomicLong(gateKeyPrefix + ":waiting");
        this.activeRequests = redissonClient.getAtomicLong(gateKeyPrefix + ":active");
        this.auxiliaryExecutionSemaphore = redissonClient.getSemaphore(gateKeyPrefix + ":aux:semaphore");
        this.auxiliaryWaitingRequests = redissonClient.getAtomicLong(gateKeyPrefix + ":aux:waiting");
        this.auxiliaryActiveRequests = redissonClient.getAtomicLong(gateKeyPrefix + ":aux:active");
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(buildCircuitBreakerConfig());
        initializeDistributedPermits(modelExecutionSemaphore, activeRequests, maxConcurrentCalls, gateKeyPrefix + ":init-lock");
        initializeDistributedPermits(auxiliaryExecutionSemaphore, auxiliaryActiveRequests, auxiliaryMaxConcurrentCalls, gateKeyPrefix + ":aux:init-lock");
        log.info("模型并发闸门已启用: primary(maxConcurrentCalls={}, queueCapacity={}, queueTimeoutMs={}), auxiliary(maxConcurrentCalls={}, queueCapacity={}, queueTimeoutMs={})",
                maxConcurrentCalls, queueCapacity, queueTimeoutMs,
                auxiliaryMaxConcurrentCalls, auxiliaryQueueCapacity, auxiliaryQueueTimeoutMs);
    }

    @Override
    public Flux<String> streamChat(String modelName, List<Map<String, Object>> messages,
                                   QueueStatusListener queueStatusListener, ChatOptions options) {
        String name = (modelName == null || modelName.isEmpty()) ? defaultModel : modelName;
        ChatOptions resolvedOptions = options == null ? ChatOptions.DEFAULT : options;

        List<String> fallbackChain = buildFallbackChain(name);
        if (fallbackChain.isEmpty()) {
            return Flux.error(new RuntimeException("无可用的AI模型"));
        }

        log.info("本次模型降级链: {}", fallbackChain);
        QueueStatusListener listener = queueStatusListener == null ? QueueStatusListener.NO_OP : queueStatusListener;
        return Mono.fromCallable(() -> acquireExecutionSlot(name, listener, resolvedOptions.taskLane()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(slot -> tryModelChain(fallbackChain, messages, 0, resolvedOptions)
                        .doOnSubscribe(subscription -> log.info(
                                "模型请求已获取执行资格: requestedModel={}, lane={}, waitedMs={}, active={}, waiting={}",
                                slot.requestedModel(), slot.taskLane(), slot.waitedMs(),
                                getActiveRequests(slot.taskLane()), getWaitingRequests(slot.taskLane())))
                        .doFinally(signalType -> releaseExecutionSlot(slot, signalType.name(), listener)));
    }

    @Override
    public List<String> listAvailableModels() {
        return models.stream().map(AiModel::getModelName).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getRuntimeMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("defaultModel", defaultModel);
        metrics.put("activeRequests", getActiveRequests());
        metrics.put("waitingRequests", getWaitingRequests());
        metrics.put("maxConcurrentCalls", maxConcurrentCalls);
        metrics.put("queueCapacity", queueCapacity);
        metrics.put("queueTimeoutMs", queueTimeoutMs);
        metrics.put("auxActiveRequests", getActiveRequests(TaskLane.AUXILIARY));
        metrics.put("auxWaitingRequests", getWaitingRequests(TaskLane.AUXILIARY));
        metrics.put("auxMaxConcurrentCalls", auxiliaryMaxConcurrentCalls);
        metrics.put("auxQueueCapacity", auxiliaryQueueCapacity);
        metrics.put("auxQueueTimeoutMs", auxiliaryQueueTimeoutMs);
        metrics.put("circuitOpenSeconds", circuitOpenSeconds);
        metrics.put("circuitFailureRateThreshold", circuitFailureRateThreshold);
        metrics.put("circuitSlidingWindowSize", circuitSlidingWindowSize);
        metrics.put("loadedModels", listAvailableModels());
        metrics.put("circuitStates", buildCircuitStates());
        metrics.put("recent1m", buildRecentRuntimeMetrics(TimeUnit.MINUTES.toMillis(1)));
        metrics.put("recent5m", buildRecentRuntimeMetrics(METRIC_WINDOW_5M_MS));
        return metrics;
    }

    private Flux<String> tryModelChain(List<String> fallbackChain, List<Map<String, Object>> messages,
                                       int index, ChatOptions options) {
        if (index >= fallbackChain.size()) {
            return Flux.error(new RuntimeException("所有候选模型都不可用"));
        }

        String currentModelName = fallbackChain.get(index);
        AiModel model = modelMap.get(currentModelName);
        if (model == null) {
            log.warn("候选模型[{}]未加载，跳过", currentModelName);
            return tryModelChain(fallbackChain, messages, index + 1, options);
        }

        CircuitBreaker circuitBreaker = getCircuitBreaker(currentModelName);
        if (isCircuitOpen(circuitBreaker)) {
            recordRuntimeEvent("circuit_open_skip", currentModelName, 0);
            log.warn("模型[{}]当前处于熔断/打开状态，跳过本次调用", currentModelName);
            return tryModelChain(fallbackChain, messages, index + 1, options);
        }

        log.info("尝试使用模型: {}", currentModelName);
        long modelCallStart = System.currentTimeMillis();
        return model.streamChat(messages, currentModelName, new AiModel.ChatOptions(options.toolsEnabled()))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - modelCallStart;
                    recordSuccess(currentModelName);
                    log.info("模型[{}]响应完成, 耗时: {}ms", currentModelName, elapsed);
                })
                .onErrorResume(ex -> {
                    long elapsed = System.currentTimeMillis() - modelCallStart;
                    if (ex instanceof CallNotPermittedException) {
                        recordRuntimeEvent("circuit_call_rejected", currentModelName, elapsed);
                        log.warn("模型[{}]被标准熔断器拒绝，准备降级到下一个模型 (耗时: {}ms)", currentModelName, elapsed);
                        return tryModelChain(fallbackChain, messages, index + 1, options);
                    }
                    recordFailure(currentModelName, ex);
                    log.warn("模型[{}]调用失败，准备降级到下一个模型: {} (耗时: {}ms)", currentModelName, ex.getMessage(), elapsed);
                    return tryModelChain(fallbackChain, messages, index + 1, options);
                });
    }

    private List<String> buildFallbackChain(String primaryModelName) {
        Set<String> orderedCandidates = new LinkedHashSet<>();

        if (primaryModelName != null && !primaryModelName.isBlank()) {
            orderedCandidates.add(primaryModelName);
        }

        if (ROTATION_MODELS.contains(primaryModelName)) {
            for (String modelName : ROTATION_MODELS) {
                if (!modelName.equals(primaryModelName)) {
                    orderedCandidates.add(modelName);
                }
            }
        } else {
            orderedCandidates.addAll(ROTATION_MODELS);
        }

        if (!DEEPSEEK_MODEL.equals(primaryModelName)) {
            orderedCandidates.add(DEEPSEEK_MODEL);
        }

        List<String> availableChain = new ArrayList<>();
        for (String candidate : orderedCandidates) {
            if (isModelAvailable(candidate)) {
                availableChain.add(candidate);
            }
        }
        return availableChain;
    }

    private boolean isModelAvailable(String modelName) {
        if (!modelMap.containsKey(modelName)) {
            return false;
        }
        return modelConfigCache.isModelAvailable(modelName);
    }

    private void recordSuccess(String modelName) {
        recordRuntimeEvent("model_success", modelName, 0);
    }

    private void recordFailure(String modelName, Throwable throwable) {
        recordRuntimeEvent("model_failure", modelName, 0);
        CircuitBreaker circuitBreaker = getCircuitBreaker(modelName);
        log.warn("模型[{}]调用失败，当前熔断状态={}, failureRate={}%, bufferedCalls={}, 最近错误: {}",
                modelName,
                circuitBreaker.getState(),
                circuitBreaker.getMetrics().getFailureRate(),
                circuitBreaker.getMetrics().getNumberOfBufferedCalls(),
                throwable.getMessage());
    }

    private ExecutionSlot acquireExecutionSlot(String requestedModel, QueueStatusListener listener, TaskLane taskLane) {
        GateContext gate = gateContext(taskLane);
        long waiting = gate.waitingCounter().incrementAndGet();
        if (waiting > gate.queueCapacity()) {
            long effectiveCapacity = gate.queueCapacity();
            gate.waitingCounter().decrementAndGet();
            log.warn("模型请求队列已满: requestedModel={}, lane={}, waiting={}, capacity={}", requestedModel, taskLane, waiting - 1, effectiveCapacity);
            recordRuntimeEvent("queue_rejected", requestedModel, 0);
            listener.onEvent(new QueueStatusEvent("queue_rejected", requestedModel, 0, safeInt(getActiveRequests(taskLane)),
                    safeInt(Math.max(waiting - 1, 0)), gate.queueRejectedMessage()));
            throw new RuntimeException("当前模型请求排队已满，请稍后再试");
        }

        long start = System.nanoTime();
        try {
            log.info("模型请求进入排队: requestedModel={}, lane={}, active={}, waiting={}",
                    requestedModel, taskLane, getActiveRequests(taskLane), waiting);
            listener.onEvent(new QueueStatusEvent("queued", requestedModel, 0, safeInt(getActiveRequests(taskLane)),
                    safeInt(waiting), gate.queuedMessage()));
            boolean acquired = gate.semaphore().tryAcquire(gate.queueTimeoutMs(), TimeUnit.MILLISECONDS);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (!acquired) {
                log.warn("模型请求排队超时: requestedModel={}, lane={}, waitedMs={}, active={}, waiting={}",
                        requestedModel, taskLane, waitedMs, getActiveRequests(taskLane), getWaitingRequests(taskLane));
                recordRuntimeEvent("queue_timeout", requestedModel, waitedMs);
                listener.onEvent(new QueueStatusEvent("queue_timeout", requestedModel, waitedMs, safeInt(getActiveRequests(taskLane)),
                        safeInt(getWaitingRequests(taskLane)), gate.queueTimeoutMessage()));
                throw new RuntimeException("当前模型服务繁忙，排队超时，请稍后重试");
            }

            long active = gate.activeCounter().incrementAndGet();
            recordRuntimeEvent("acquired", requestedModel, waitedMs);
            listener.onEvent(new QueueStatusEvent("acquired", requestedModel, waitedMs, safeInt(active), safeInt(getWaitingRequests(taskLane)),
                    gate.acquiredMessage()));
            return new ExecutionSlot(requestedModel, waitedMs, safeInt(active), taskLane);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordRuntimeEvent("queue_interrupted", requestedModel, 0);
            listener.onEvent(new QueueStatusEvent("queue_interrupted", requestedModel, 0, safeInt(getActiveRequests(taskLane)),
                    safeInt(getWaitingRequests(taskLane)), "模型请求排队被中断"));
            throw new RuntimeException("模型请求排队被中断");
        } finally {
            gate.waitingCounter().decrementAndGet();
        }
    }

    private void releaseExecutionSlot(ExecutionSlot slot, String signalType, QueueStatusListener listener) {
        if (slot == null) {
            return;
        }

        GateContext gate = gateContext(slot.taskLane());
        gate.semaphore().release();
        long active = gate.activeCounter().decrementAndGet();
        if (active < 0) {
            gate.activeCounter().set(0);
            active = 0;
        }
        recordRuntimeEvent("released", slot.requestedModel(), slot.waitedMs());
        listener.onEvent(new QueueStatusEvent("released", slot.requestedModel(), slot.waitedMs(), safeInt(active), safeInt(getWaitingRequests(slot.taskLane())),
                gate.releasedMessage()));
        log.info("模型请求释放执行资格: requestedModel={}, lane={}, signal={}, active={}, waiting={}",
                slot.requestedModel(), slot.taskLane(), signalType, active, getWaitingRequests(slot.taskLane()));
    }

    private List<Map<String, Object>> buildCircuitStates() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> circuitStates = new ArrayList<>();
        for (String modelName : modelMap.keySet()) {
            CircuitBreaker circuitBreaker = getCircuitBreaker(modelName);
            Map<String, Object> item = new HashMap<>();
            item.put("modelName", modelName);
            item.put("available", isModelAvailable(modelName));
            item.put("circuitState", circuitBreaker.getState().name());
            item.put("circuitOpen", isCircuitOpen(circuitBreaker));
            item.put("failureRate", circuitBreaker.getMetrics().getFailureRate());
            item.put("bufferedCalls", circuitBreaker.getMetrics().getNumberOfBufferedCalls());
            item.put("failedCalls", circuitBreaker.getMetrics().getNumberOfFailedCalls());
            item.put("slowCalls", circuitBreaker.getMetrics().getNumberOfSlowCalls());
            circuitStates.add(item);
        }
        return circuitStates;
    }

    private Map<String, Object> buildRecentRuntimeMetrics(long windowMs) {
        pruneExpiredRuntimeEvents();
        long now = System.currentTimeMillis();
        long acquired = 0;
        long released = 0;
        long queueRejected = 0;
        long queueTimeout = 0;
        long queueInterrupted = 0;
        long modelFailures = 0;
        long modelSuccess = 0;
        long waitSamples = 0;
        long totalWaitMs = 0;

        for (ModelRuntimeEvent event : recentRuntimeEvents) {
            if (now - event.timestampMs() > windowMs) {
                continue;
            }
            switch (event.phase()) {
                case "acquired" -> {
                    acquired++;
                    waitSamples++;
                    totalWaitMs += event.waitedMs();
                }
                case "released" -> released++;
                case "queue_rejected" -> queueRejected++;
                case "queue_timeout" -> queueTimeout++;
                case "queue_interrupted" -> queueInterrupted++;
                case "model_failure" -> modelFailures++;
                case "model_success" -> modelSuccess++;
                case "circuit_call_rejected", "circuit_open_skip" -> queueRejected++;
                default -> {
                }
            }
        }

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("requestsAcquired", acquired);
        metrics.put("requestsReleased", released);
        metrics.put("queueRejected", queueRejected);
        metrics.put("queueTimeout", queueTimeout);
        metrics.put("queueInterrupted", queueInterrupted);
        metrics.put("modelFailures", modelFailures);
        metrics.put("modelSuccess", modelSuccess);
        metrics.put("avgWaitMs", waitSamples == 0 ? 0D : Math.round((totalWaitMs * 100.0 / waitSamples)) / 100.0);
        return metrics;
    }

    private void recordRuntimeEvent(String phase, String modelName, long waitedMs) {
        recentRuntimeEvents.addLast(new ModelRuntimeEvent(System.currentTimeMillis(), phase, modelName, waitedMs));
        pruneExpiredRuntimeEvents();
    }

    private void pruneExpiredRuntimeEvents() {
        long expireBefore = System.currentTimeMillis() - METRIC_WINDOW_5M_MS;
        while (true) {
            ModelRuntimeEvent first = recentRuntimeEvents.peekFirst();
            if (first == null || first.timestampMs() >= expireBefore) {
                return;
            }
            recentRuntimeEvents.pollFirst();
        }
    }

    private void initializeDistributedPermits(RSemaphore semaphore, RAtomicLong activeCounter, int configuredPermits, String lockKey) {
        RLock initLock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = initLock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("初始化分布式模型闸门锁失败，沿用现有 Redis 配置");
                return;
            }

            boolean initialized = semaphore.trySetPermits(configuredPermits);
            if (initialized) {
                return;
            }

            long active = activeCounter.get();
            if (active == 0) {
                semaphore.drainPermits();
                semaphore.release(configuredPermits);
                return;
            }

            long currentTotalPermits = semaphore.availablePermits() + active;
            if (currentTotalPermits != configuredPermits) {
                log.warn("检测到分布式并发闸门配置与当前活动请求不一致: configured={}, currentTotal={}, active={}",
                        configuredPermits, currentTotalPermits, active);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("初始化分布式模型闸门被中断");
        } finally {
            if (locked) {
                initLock.unlock();
            }
        }
    }

    private CircuitBreakerConfig buildCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(circuitFailureRateThreshold)
                .minimumNumberOfCalls(Math.max(failureThreshold, 1))
                .slidingWindowSize(Math.max(circuitSlidingWindowSize, Math.max(failureThreshold, 1)))
                .permittedNumberOfCallsInHalfOpenState(Math.max(circuitPermittedHalfOpenCalls, 1))
                .waitDurationInOpenState(java.time.Duration.ofSeconds(Math.max(circuitOpenSeconds, 1)))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(throwable -> !(throwable instanceof CallNotPermittedException))
                .slowCallDurationThreshold(java.time.Duration.ofSeconds(30))
                .slowCallRateThreshold(50)
                .build();
    }

    private CircuitBreaker getCircuitBreaker(String modelName) {
        return circuitBreakerRegistry.circuitBreaker(modelName, buildCircuitBreakerConfig());
    }

    private boolean isCircuitOpen(CircuitBreaker circuitBreaker) {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    private long getActiveRequests() {
        return activeRequests.get();
    }

    private long getActiveRequests(TaskLane taskLane) {
        return TaskLane.AUXILIARY.equals(taskLane) ? auxiliaryActiveRequests.get() : activeRequests.get();
    }

    private long getWaitingRequests() {
        return waitingRequests.get();
    }

    private long getWaitingRequests(TaskLane taskLane) {
        return TaskLane.AUXILIARY.equals(taskLane) ? auxiliaryWaitingRequests.get() : waitingRequests.get();
    }

    private GateContext gateContext(TaskLane taskLane) {
        if (TaskLane.AUXILIARY.equals(taskLane)) {
            return new GateContext(auxiliaryExecutionSemaphore, auxiliaryWaitingRequests, auxiliaryActiveRequests,
                    auxiliaryQueueCapacity, auxiliaryQueueTimeoutMs,
                    "辅助 AI 请求已进入队列，正在等待执行资格",
                    "辅助 AI 请求已获取执行资格，开始执行",
                    "辅助 AI 请求已结束并释放执行资格",
                    "当前辅助 AI 请求排队已满，请稍后再试",
                    "当前辅助 AI 服务繁忙，排队超时，请稍后重试");
        }
        return new GateContext(modelExecutionSemaphore, waitingRequests, activeRequests,
                queueCapacity, queueTimeoutMs,
                "请求已进入模型队列，正在等待执行资格",
                "已获取模型执行资格，开始生成回答",
                "模型请求已结束并释放执行资格",
                "当前模型请求排队已满，请稍后再试",
                "当前模型服务繁忙，排队超时，请稍后重试");
    }

    private int safeInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private record ExecutionSlot(String requestedModel, long waitedMs, int activeAfterAcquire, TaskLane taskLane) {
    }

    private record GateContext(RSemaphore semaphore, RAtomicLong waitingCounter, RAtomicLong activeCounter,
                               long queueCapacity, long queueTimeoutMs,
                               String queuedMessage, String acquiredMessage, String releasedMessage,
                               String queueRejectedMessage, String queueTimeoutMessage) {
    }

    private record ModelRuntimeEvent(long timestampMs, String phase, String modelName, long waitedMs) {
    }
}
