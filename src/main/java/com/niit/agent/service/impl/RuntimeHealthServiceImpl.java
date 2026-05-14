package com.niit.agent.service.impl;

import com.niit.agent.service.AiModelRouterService;
import com.niit.agent.service.MemoryService;
import com.niit.agent.service.RuntimeHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeHealthServiceImpl implements RuntimeHealthService {

    private final AiModelRouterService aiModelRouterService;
    private final MemoryService memoryService;

    @Value("${ai.health.queue-wait-warning-ms:3000}")
    private double queueWaitWarningMs;

    @Value("${ai.health.queue-wait-critical-ms:8000}")
    private double queueWaitCriticalMs;

    @Value("${ai.health.queue-usage-warning:0.6}")
    private double queueUsageWarning;

    @Value("${ai.health.queue-usage-critical:0.9}")
    private double queueUsageCritical;

    @Value("${ai.health.model-failure-warning:1}")
    private long modelFailureWarning;

    @Value("${ai.health.model-failure-critical:3}")
    private long modelFailureCritical;

    @Value("${ai.health.rag-hit-warning:0.3}")
    private double ragHitWarning;

    @Value("${ai.health.rag-hit-critical:0.15}")
    private double ragHitCritical;

    @Value("${ai.health.rag-error-warning:1}")
    private long ragErrorWarning;

    @Value("${ai.health.rag-error-critical:3}")
    private long ragErrorCritical;

    @Override
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> modelRuntime = aiModelRouterService.getRuntimeMetrics();
        Map<String, Object> ragRuntime = memoryService.getRagRuntimeMetrics();

        List<Map<String, Object>> alerts = new ArrayList<>();
        int severityScore = 0;

        Map<String, Object> modelRecent5m = mapValue(modelRuntime.get("recent5m"));
        Map<String, Object> ragRecent5m = mapValue(ragRuntime.get("recent5m"));

        double avgWaitMs = doubleValue(modelRecent5m.get("avgWaitMs"));
        long queueCapacity = longValue(modelRuntime.get("queueCapacity"));
        long waitingRequests = longValue(modelRuntime.get("waitingRequests"));
        double queueUsage = queueCapacity <= 0 ? 0D : (waitingRequests * 1.0 / queueCapacity);
        long modelFailures = longValue(modelRecent5m.get("modelFailures"));
        long ragErrors = longValue(ragRecent5m.get("ragErrorCount"));
        double ragHitRate = doubleValue(ragRecent5m.get("ragHitRate"));

        severityScore = Math.max(severityScore, evaluateQueueWait(alerts, avgWaitMs));
        severityScore = Math.max(severityScore, evaluateQueueUsage(alerts, queueUsage, waitingRequests, queueCapacity));
        severityScore = Math.max(severityScore, evaluateModelFailures(alerts, modelFailures));
        severityScore = Math.max(severityScore, evaluateRagHitRate(alerts, ragHitRate, ragRecent5m));
        severityScore = Math.max(severityScore, evaluateRagErrors(alerts, ragErrors));
        severityScore = Math.max(severityScore, evaluateCircuitStates(alerts, modelRuntime));

        String status = switch (severityScore) {
            case 2 -> "critical";
            case 1 -> "warning";
            default -> "healthy";
        };

        Map<String, Object> summary = new HashMap<>();
        summary.put("status", status);
        summary.put("score", severityScore);
        summary.put("alerts", alerts);
        summary.put("summary", buildSummaryText(status, alerts));
        summary.put("modelRuntime", modelRuntime);
        summary.put("ragRuntime", ragRuntime);
        return summary;
    }

    private int evaluateQueueWait(List<Map<String, Object>> alerts, double avgWaitMs) {
        if (avgWaitMs >= queueWaitCriticalMs) {
            alerts.add(alert("critical", "queue_wait", "模型平均排队时长过高", "最近5分钟平均等待 " + avgWaitMs + " ms"));
            return 2;
        }
        if (avgWaitMs >= queueWaitWarningMs) {
            alerts.add(alert("warning", "queue_wait", "模型平均排队时长升高", "最近5分钟平均等待 " + avgWaitMs + " ms"));
            return 1;
        }
        return 0;
    }

    private int evaluateQueueUsage(List<Map<String, Object>> alerts, double queueUsage, long waitingRequests, long queueCapacity) {
        if (queueUsage >= queueUsageCritical) {
            alerts.add(alert("critical", "queue_usage", "模型等待队列接近打满", "当前等待 " + waitingRequests + " / " + queueCapacity));
            return 2;
        }
        if (queueUsage >= queueUsageWarning) {
            alerts.add(alert("warning", "queue_usage", "模型等待队列压力较高", "当前等待 " + waitingRequests + " / " + queueCapacity));
            return 1;
        }
        return 0;
    }

    private int evaluateModelFailures(List<Map<String, Object>> alerts, long modelFailures) {
        if (modelFailures >= modelFailureCritical) {
            alerts.add(alert("critical", "model_failure", "最近5分钟模型失败次数偏高", "失败次数 " + modelFailures));
            return 2;
        }
        if (modelFailures >= modelFailureWarning) {
            alerts.add(alert("warning", "model_failure", "最近5分钟出现模型失败", "失败次数 " + modelFailures));
            return 1;
        }
        return 0;
    }

    private int evaluateRagHitRate(List<Map<String, Object>> alerts, double ragHitRate, Map<String, Object> ragRecent5m) {
        long enabled = longValue(ragRecent5m.get("ragEnabledCount"));
        if (enabled == 0) {
            return 0;
        }
        if (ragHitRate <= ragHitCritical) {
            alerts.add(alert("critical", "rag_hit_rate", "RAG 最近命中率过低", "最近5分钟命中率 " + ragHitRate));
            return 2;
        }
        if (ragHitRate <= ragHitWarning) {
            alerts.add(alert("warning", "rag_hit_rate", "RAG 最近命中率偏低", "最近5分钟命中率 " + ragHitRate));
            return 1;
        }
        return 0;
    }

    private int evaluateRagErrors(List<Map<String, Object>> alerts, long ragErrors) {
        if (ragErrors >= ragErrorCritical) {
            alerts.add(alert("critical", "rag_error", "最近5分钟 RAG 错误次数偏高", "错误次数 " + ragErrors));
            return 2;
        }
        if (ragErrors >= ragErrorWarning) {
            alerts.add(alert("warning", "rag_error", "最近5分钟出现 RAG 错误", "错误次数 " + ragErrors));
            return 1;
        }
        return 0;
    }

    private int evaluateCircuitStates(List<Map<String, Object>> alerts, Map<String, Object> modelRuntime) {
        List<Map<String, Object>> circuitStates = listOfMaps(modelRuntime.get("circuitStates"));
        long openCircuits = circuitStates.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("circuitOpen")))
                .count();
        if (openCircuits > 0) {
            alerts.add(alert("warning", "circuit_open", "存在熔断中的模型", "当前熔断模型数 " + openCircuits));
            return 1;
        }
        return 0;
    }

    private Map<String, Object> alert(String level, String code, String title, String detail) {
        Map<String, Object> item = new HashMap<>();
        item.put("level", level);
        item.put("code", code);
        item.put("title", title);
        item.put("detail", detail);
        return item;
    }

    private String buildSummaryText(String status, List<Map<String, Object>> alerts) {
        if (alerts.isEmpty()) {
            return "系统整体运行健康，当前未发现明显异常。";
        }
        Map<String, Object> first = alerts.get(0);
        return switch (status) {
            case "critical" -> "系统存在高优先级风险，需优先处理：" + first.get("title");
            case "warning" -> "系统运行存在告警项，建议关注：" + first.get("title");
            default -> "系统整体运行健康。";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private double doubleValue(Object value) {
        if (value == null) {
            return 0D;
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
