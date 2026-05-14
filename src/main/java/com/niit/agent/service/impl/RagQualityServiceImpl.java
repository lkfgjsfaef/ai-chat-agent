package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niit.agent.common.constant.Constants;
import com.niit.agent.common.util.TextUtils;
import com.niit.agent.entity.ChatMessage;
import com.niit.agent.mapper.ChatMessageMapper;
import com.niit.agent.service.RagQualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQualityServiceImpl implements RagQualityService {

    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> getQualityMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(ChatMessage::getRagChunkIds)
                .isNotNull(ChatMessage::getFeedback)
                .eq(ChatMessage::getRole, Constants.ROLE_ASSISTANT);
        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);

        if (messages.isEmpty()) {
            result.put("summary", buildEmptySummary());
            result.put("byTimeWindow", Map.of());
            result.put("byScope", Map.of());
            return result;
        }

        // Parse chunk-level feedback data
        List<ChunkFeedback> allChunkFeedback = new ArrayList<>();
        for (ChatMessage msg : messages) {
            List<ChunkFeedback> parsed = parseChunkFeedback(msg);
            allChunkFeedback.addAll(parsed);
        }

        result.put("summary", buildSummary(messages, allChunkFeedback));
        result.put("byTimeWindow", buildTimeWindowMetrics(messages, allChunkFeedback));
        result.put("mostDislikedChunks", buildTopDislikedChunks(allChunkFeedback, 10));
        return result;
    }

    private List<ChunkFeedback> parseChunkFeedback(ChatMessage message) {
        if (message.getRagChunkIds() == null || message.getRagChunkScores() == null) {
            return List.of();
        }
        try {
            List<String> chunkIds = objectMapper.readValue(message.getRagChunkIds(),
                    new TypeReference<List<String>>() {});
            List<Double> chunkScores = objectMapper.readValue(message.getRagChunkScores(),
                    new TypeReference<List<Double>>() {});

            boolean isRelevant = "like".equalsIgnoreCase(message.getFeedback());
            List<ChunkFeedback> results = new ArrayList<>();
            for (int i = 0; i < chunkIds.size(); i++) {
                double score = i < chunkScores.size() ? chunkScores.get(i) : 0.0;
                results.add(new ChunkFeedback(chunkIds.get(i), score, isRelevant,
                        message.getFeedback(), message.getCreateTime()));
            }
            return results;
        } catch (Exception e) {
            log.warn("解析消息 {} 的RAG chunk归属数据失败", message.getId(), e);
            return List.of();
        }
    }

    private Map<String, Object> buildEmptySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalFeedbackMessages", 0);
        summary.put("likeCount", 0);
        summary.put("dislikeCount", 0);
        summary.put("messagePrecision", 0.0);
        summary.put("chunkPrecision", 0.0);
        summary.put("avgChunksPerMessage", 0.0);
        summary.put("avgRelevanceScoreLiked", 0.0);
        summary.put("avgRelevanceScoreDisliked", 0.0);
        return summary;
    }

    private Map<String, Object> buildSummary(List<ChatMessage> messages, List<ChunkFeedback> chunkFeedbacks) {
        long likeCount = messages.stream().filter(m -> "like".equalsIgnoreCase(m.getFeedback())).count();
        long dislikeCount = messages.stream().filter(m -> "dislike".equalsIgnoreCase(m.getFeedback())).count();
        long totalFeedback = likeCount + dislikeCount;

        long relevantChunks = chunkFeedbacks.stream().filter(ChunkFeedback::relevant).count();
        long totalChunks = chunkFeedbacks.size();

        double avgChunksPerMessage = messages.isEmpty() ? 0 :
                (double) totalChunks / messages.size();

        double avgScoreLiked = chunkFeedbacks.stream()
                .filter(ChunkFeedback::relevant)
                .mapToDouble(ChunkFeedback::score)
                .average().orElse(0.0);
        double avgScoreDisliked = chunkFeedbacks.stream()
                .filter(c -> !c.relevant())
                .mapToDouble(ChunkFeedback::score)
                .average().orElse(0.0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalFeedbackMessages", totalFeedback);
        summary.put("likeCount", likeCount);
        summary.put("dislikeCount", dislikeCount);
        summary.put("messagePrecision", TextUtils.safeRatio(likeCount, totalFeedback));
        summary.put("chunkPrecision", TextUtils.safeRatio(relevantChunks, totalChunks));
        summary.put("avgChunksPerMessage", Math.round(avgChunksPerMessage * 10000.0) / 10000.0);
        summary.put("avgRelevanceScoreLiked", Math.round(avgScoreLiked * 10000.0) / 10000.0);
        summary.put("avgRelevanceScoreDisliked", Math.round(avgScoreDisliked * 10000.0) / 10000.0);
        return summary;
    }

    private Map<String, Object> buildTimeWindowMetrics(List<ChatMessage> messages, List<ChunkFeedback> chunkFeedbacks) {
        Map<String, Object> windows = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();

        windows.put("last1h", buildWindowMetrics(messages, chunkFeedbacks, now.minusHours(1), now));
        windows.put("last24h", buildWindowMetrics(messages, chunkFeedbacks, now.minusHours(24), now));
        windows.put("last7d", buildWindowMetrics(messages, chunkFeedbacks, now.minusDays(7), now));
        return windows;
    }

    private Map<String, Object> buildWindowMetrics(List<ChatMessage> messages, List<ChunkFeedback> chunkFeedbacks,
                                                    LocalDateTime from, LocalDateTime to) {
        List<ChatMessage> windowMsgs = messages.stream()
                .filter(m -> !m.getCreateTime().isBefore(from) && !m.getCreateTime().isAfter(to))
                .toList();

        List<ChunkFeedback> windowChunks = chunkFeedbacks.stream()
                .filter(c -> c.messageCreateTime() != null && !c.messageCreateTime().isBefore(from)
                        && !c.messageCreateTime().isAfter(to))
                .toList();

        long likes = windowMsgs.stream().filter(m -> "like".equalsIgnoreCase(m.getFeedback())).count();
        long dislikes = windowMsgs.stream().filter(m -> "dislike".equalsIgnoreCase(m.getFeedback())).count();
        long total = likes + dislikes;
        long relevantChunks = windowChunks.stream().filter(ChunkFeedback::relevant).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalMessages", windowMsgs.size());
        metrics.put("likeCount", likes);
        metrics.put("dislikeCount", dislikes);
        metrics.put("precision", TextUtils.safeRatio(likes, total));
        metrics.put("chunkPrecision", TextUtils.safeRatio(relevantChunks, windowChunks.size()));
        return metrics;
    }

    private List<Map<String, Object>> buildTopDislikedChunks(List<ChunkFeedback> chunkFeedbacks, int topN) {
        Map<String, Long> dislikeCounts = chunkFeedbacks.stream()
                .filter(c -> !c.relevant())
                .collect(Collectors.groupingBy(ChunkFeedback::chunkId, Collectors.counting()));

        return dislikeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", entry.getKey());
                    item.put("dislikeCount", entry.getValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private record ChunkFeedback(String chunkId, double score, boolean relevant,
                                  String feedback, LocalDateTime messageCreateTime) {}
}
