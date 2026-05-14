package com.niit.agent.service;

import com.niit.agent.entity.ChatSession;
import com.niit.agent.vo.SessionVO;

import java.util.List;

public interface ChatSessionService {

    ChatSession createSession(Long userId, String title, String systemPrompt, String skillId);

    List<SessionVO> listSessions(Long userId);

    ChatSession getById(Long sessionId);

    void updateTitle(Long sessionId, String title);

    void updateSystemPrompt(Long sessionId, String systemPrompt);

    void updateSkill(Long sessionId, String skillId);

    void deleteSession(Long sessionId);

    List<SessionVO> searchSessions(Long userId, String keyword);
}
