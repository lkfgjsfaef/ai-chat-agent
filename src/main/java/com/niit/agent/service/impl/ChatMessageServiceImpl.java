package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niit.agent.entity.ChatMessage;
import com.niit.agent.entity.ChatSession;
import com.niit.agent.mapper.ChatSessionMapper;
import com.niit.agent.mapper.ChatMessageMapper;
import com.niit.agent.service.ChatMessageService;
import com.niit.agent.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    public ChatMessage saveMessage(Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        chatMessageMapper.insert(message);
        return message;
    }

    @Override
    public void updateMessageFields(ChatMessage message) {
        chatMessageMapper.updateById(message);
    }

    @Override
    public List<MessageVO> listMessages(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreateTime);
        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);
        return messages.stream().map(m -> {
            MessageVO vo = new MessageVO();
            BeanUtils.copyProperties(m, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessage> getRecentMessages(Long sessionId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getCreateTime)
                .last("LIMIT " + limit);
        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<ChatMessage> getMessagesAfterId(Long sessionId, Long lastMessageId, int limit) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
                .gt(lastMessageId != null && lastMessageId > 0, ChatMessage::getId, lastMessageId)
                .orderByAsc(ChatMessage::getId);
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return chatMessageMapper.selectList(wrapper);
    }

    @Override
    public int countBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId);
        return Math.toIntExact(chatMessageMapper.selectCount(wrapper));
    }

    @Override
    public void deleteBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId);
        chatMessageMapper.delete(wrapper);
    }

    @Override
    public void updateMessage(Long messageId, String content) {
        ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message != null) {
            message.setContent(content);
            chatMessageMapper.updateById(message);
        }
    }

    @Override
    public void deleteAfterMessage(Long sessionId, Long messageId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getSessionId, sessionId)
               .gt(ChatMessage::getId, messageId);
        chatMessageMapper.delete(wrapper);
    }

    @Override
    public boolean updateFeedback(Long userId, Long sessionId, Long messageId, String feedback) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || userId == null || !Objects.equals(session.getUserId(), userId)) {
            return false;
        }
        ChatMessage message = chatMessageMapper.selectById(messageId);
        if (message == null || !Objects.equals(message.getSessionId(), sessionId)) {
            return false;
        }
        if (!"assistant".equalsIgnoreCase(message.getRole())) {
            return false;
        }
        message.setFeedback(feedback);
        chatMessageMapper.updateById(message);
        return true;
    }

    @Override
    public List<MessageVO> searchMessages(Long userId, String keyword) {
        List<ChatMessage> messages = chatMessageMapper.searchMessages(userId, keyword);
        return messages.stream().map(m -> {
            MessageVO vo = new MessageVO();
            BeanUtils.copyProperties(m, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getFeedbackStats(int recentLimit) {
        int safeRecentLimit = Math.max(1, recentLimit);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getRole, "assistant")
                .orderByDesc(ChatMessage::getCreateTime);
        List<ChatMessage> assistantMessages = chatMessageMapper.selectList(wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        if (assistantMessages.isEmpty()) {
            result.put("summary", buildSummary(0, 0, 0, 0));
            result.put("trend7d", List.of());
            result.put("topDislikedSessions", List.of());
            result.put("recentDislikedMessages", List.of());
            return result;
        }

        Set<Long> sessionIds = assistantMessages.stream()
                .map(ChatMessage::getSessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, ChatSession> sessionMap = sessionIds.isEmpty()
                ? Map.of()
                : chatSessionMapper.selectBatchIds(sessionIds).stream()
                .collect(Collectors.toMap(ChatSession::getId, session -> session));

        long likeCount = assistantMessages.stream().filter(message -> "like".equalsIgnoreCase(message.getFeedback())).count();
        long dislikeCount = assistantMessages.stream().filter(message -> "dislike".equalsIgnoreCase(message.getFeedback())).count();
        long feedbackCount = likeCount + dislikeCount;

        result.put("summary", buildSummary(assistantMessages.size(), feedbackCount, likeCount, dislikeCount));
        result.put("trend7d", buildTrend(assistantMessages));
        result.put("topDislikedSessions", buildTopDislikedSessions(assistantMessages, sessionMap));
        result.put("recentDislikedMessages", buildRecentDislikedMessages(assistantMessages, sessionMap, safeRecentLimit));
        return result;
    }

    private Map<String, Object> buildSummary(long totalAssistantMessages, long feedbackCount, long likeCount, long dislikeCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAssistantMessages", totalAssistantMessages);
        summary.put("feedbackCount", feedbackCount);
        summary.put("likeCount", likeCount);
        summary.put("dislikeCount", dislikeCount);
        summary.put("feedbackCoverage", percentage(feedbackCount, totalAssistantMessages));
        summary.put("likeRate", percentage(likeCount, feedbackCount));
        summary.put("dislikeRate", percentage(dislikeCount, feedbackCount));
        return summary;
    }

    private List<Map<String, Object>> buildTrend(List<ChatMessage> assistantMessages) {
        Map<LocalDate, long[]> dailyBuckets = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            dailyBuckets.put(today.minusDays(i), new long[]{0L, 0L});
        }

        assistantMessages.stream()
                .filter(message -> message.getCreateTime() != null)
                .filter(message -> message.getFeedback() != null && !message.getFeedback().isBlank())
                .forEach(message -> {
                    LocalDate date = message.getCreateTime().toLocalDate();
                    long[] bucket = dailyBuckets.get(date);
                    if (bucket == null) {
                        return;
                    }
                    if ("like".equalsIgnoreCase(message.getFeedback())) {
                        bucket[0]++;
                    } else if ("dislike".equalsIgnoreCase(message.getFeedback())) {
                        bucket[1]++;
                    }
                });

        return dailyBuckets.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", entry.getKey().toString());
                    item.put("likeCount", entry.getValue()[0]);
                    item.put("dislikeCount", entry.getValue()[1]);
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopDislikedSessions(List<ChatMessage> assistantMessages, Map<Long, ChatSession> sessionMap) {
        Map<Long, Long> sessionDislikeCount = assistantMessages.stream()
                .filter(message -> "dislike".equalsIgnoreCase(message.getFeedback()))
                .filter(message -> message.getSessionId() != null)
                .collect(Collectors.groupingBy(ChatMessage::getSessionId, Collectors.counting()));

        return sessionDislikeCount.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(5)
                .map(entry -> {
                    ChatSession session = sessionMap.get(entry.getKey());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", entry.getKey());
                    item.put("sessionTitle", session != null ? session.getTitle() : "未知会话");
                    item.put("userId", session != null ? session.getUserId() : null);
                    item.put("dislikeCount", entry.getValue());
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> buildRecentDislikedMessages(List<ChatMessage> assistantMessages,
                                                                  Map<Long, ChatSession> sessionMap,
                                                                  int recentLimit) {
        return assistantMessages.stream()
                .filter(message -> "dislike".equalsIgnoreCase(message.getFeedback()))
                .limit(recentLimit)
                .map(message -> {
                    ChatSession session = sessionMap.get(message.getSessionId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("messageId", message.getId());
                    item.put("sessionId", message.getSessionId());
                    item.put("sessionTitle", session != null ? session.getTitle() : "未知会话");
                    item.put("userId", session != null ? session.getUserId() : null);
                    item.put("feedback", message.getFeedback());
                    item.put("contentPreview", summarizeContent(message.getContent()));
                    item.put("createTime", message.getCreateTime());
                    return item;
                })
                .toList();
    }

    private double percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String summarizeContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }
}
