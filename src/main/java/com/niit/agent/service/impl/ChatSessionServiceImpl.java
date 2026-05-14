package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niit.agent.entity.ChatSession;
import com.niit.agent.mapper.ChatSessionMapper;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.service.SessionTagService;
import com.niit.agent.vo.SessionVO;
import com.niit.agent.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final SessionTagService sessionTagService;

    @Override
    public ChatSession createSession(Long userId, String title, String systemPrompt, String skillId) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null ? title : "新对话");
        session.setSystemPrompt(systemPrompt);
        session.setSkillId(skillId);
        chatSessionMapper.insert(session);
        return session;
    }

    @Override
    public List<SessionVO> listSessions(Long userId) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getCreateTime);
        List<ChatSession> sessions = chatSessionMapper.selectList(wrapper);
        List<Long> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        Map<Long, List<TagVO>> tagsMap = sessionTagService.getTagsBySessionIds(sessionIds);
        return sessions.stream().map(s -> {
            SessionVO vo = new SessionVO();
            BeanUtils.copyProperties(s, vo);
            vo.setTags(tagsMap.getOrDefault(s.getId(), List.of()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public ChatSession getById(Long sessionId) {
        return chatSessionMapper.selectById(sessionId);
    }

    @Override
    public void updateTitle(Long sessionId, String title) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session != null) {
            session.setTitle(title);
            chatSessionMapper.updateById(session);
        }
    }

    @Override
    public void updateSystemPrompt(Long sessionId, String systemPrompt) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session != null) {
            session.setSystemPrompt(systemPrompt);
            chatSessionMapper.updateById(session);
        }
    }

    @Override
    public void updateSkill(Long sessionId, String skillId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session != null) {
            session.setSkillId(skillId);
            chatSessionMapper.updateById(session);
        }
    }

    @Override
    public void deleteSession(Long sessionId) {
        chatSessionMapper.deleteById(sessionId);
    }

    @Override
    public List<SessionVO> searchSessions(Long userId, String keyword) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatSession::getUserId, userId)
               .like(ChatSession::getTitle, keyword)
               .orderByDesc(ChatSession::getCreateTime);
        List<ChatSession> sessions = chatSessionMapper.selectList(wrapper);
        List<Long> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        Map<Long, List<TagVO>> tagsMap = sessionTagService.getTagsBySessionIds(sessionIds);
        return sessions.stream().map(s -> {
            SessionVO vo = new SessionVO();
            BeanUtils.copyProperties(s, vo);
            vo.setTags(tagsMap.getOrDefault(s.getId(), List.of()));
            return vo;
        }).collect(Collectors.toList());
    }
}
