package com.niit.agent.service;

import com.niit.agent.entity.ChatMessage;
import com.niit.agent.vo.MessageVO;

import java.util.List;
import java.util.Map;

public interface ChatMessageService {

    ChatMessage saveMessage(Long sessionId, String role, String content);

    List<MessageVO> listMessages(Long sessionId);

    List<ChatMessage> getRecentMessages(Long sessionId, int limit);

    List<ChatMessage> getMessagesAfterId(Long sessionId, Long lastMessageId, int limit);

    int countBySessionId(Long sessionId);

    void deleteBySessionId(Long sessionId);

    void updateMessage(Long messageId, String content);

    void deleteAfterMessage(Long sessionId, Long messageId);

    boolean updateFeedback(Long userId, Long sessionId, Long messageId, String feedback);

    List<MessageVO> searchMessages(Long userId, String keyword);

    Map<String, Object> getFeedbackStats(int recentLimit);

    void updateMessageFields(ChatMessage message);
}
