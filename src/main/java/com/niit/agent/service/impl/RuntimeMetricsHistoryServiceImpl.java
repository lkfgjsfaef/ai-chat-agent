package com.niit.agent.service.impl;

import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.RuntimeMetricsHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeMetricsHistoryServiceImpl implements RuntimeMetricsHistoryService {

    private static final String RUNTIME_HISTORY_KEY = "metrics:history:runtime";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AiModelRouterService aiModelRouterService;
    private final MemoryService memoryService;

    @Value("${ai.metrics-history.enabled:true}")
    private boolean metricsHistoryEnabled;

    @Value("${ai.metrics-history.max-points:720}")
    private int maxPoints;

    @Value("${ai.metrics-history.ttl-hours:24}")
    private long ttlHours;

    @Override
    @Scheduled(fixedDelayString = "${ai.metrics-history.snapshot-interval-ms:10000}")
    public void snapshotRuntimeMetrics() {
        if (!metricsHistoryEnabled) {
            return;
        }

        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("timestamp", Instant.now().toString());
            snapshot.put("epochMillis", System.currentTimeMillis());
            snapshot.put("modelRuntime", aiModelRouterService.getRuntimeMetrics());
            snapshot.put("ragRuntime", memoryService.getRagRuntimeMetrics());

            redisTemplate.opsForList().leftPush(RUNTIME_HISTORY_KEY, snapshot);
            redisTemplate.opsForList().trim(RUNTIME_HISTORY_KEY, 0, Math.max(maxPoints - 1, 0));
            redisTemplate.expire(RUNTIME_HISTORY_KEY, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入运行时历史指标失败: {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRuntimeHistory(int limit) {
        int safeLimit = limit <= 0 ? 60 : Math.min(limit, maxPoints);
        try {
            List<Object> raw = redisTemplate.opsForList().range(RUNTIME_HISTORY_KEY, 0, safeLimit - 1);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> history = new ArrayList<>();
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    history.add((Map<String, Object>) map);
                }
            }
            Collections.reverse(history);
            return history;
        } catch (Exception e) {
            log.warn("读取运行时历史指标失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
