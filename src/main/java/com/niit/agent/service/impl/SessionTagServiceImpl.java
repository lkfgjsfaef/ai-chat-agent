package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niit.agent.entity.SessionTag;
import com.niit.agent.entity.SessionTagRelation;
import com.niit.agent.mapper.SessionTagMapper;
import com.niit.agent.mapper.SessionTagRelationMapper;
import com.niit.agent.service.SessionTagService;
import com.niit.agent.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionTagServiceImpl implements SessionTagService {

    private final SessionTagMapper sessionTagMapper;
    private final SessionTagRelationMapper sessionTagRelationMapper;

    @Override
    public List<TagVO> listTags(Long userId) {
        LambdaQueryWrapper<SessionTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionTag::getUserId, userId).orderByDesc(SessionTag::getCreateTime);
        return sessionTagMapper.selectList(wrapper).stream().map(t -> {
            TagVO vo = new TagVO();
            BeanUtils.copyProperties(t, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public SessionTag createTag(Long userId, String name, String color) {
        SessionTag tag = new SessionTag();
        tag.setUserId(userId);
        tag.setName(name);
        tag.setColor(color != null ? color : "#818cf8");
        sessionTagMapper.insert(tag);
        return tag;
    }

    @Override
    public void updateTag(Long tagId, String name, String color) {
        SessionTag tag = sessionTagMapper.selectById(tagId);
        if (tag != null) {
            if (name != null) tag.setName(name);
            if (color != null) tag.setColor(color);
            sessionTagMapper.updateById(tag);
        }
    }

    @Override
    public void deleteTag(Long tagId) {
        sessionTagMapper.deleteById(tagId);
        LambdaQueryWrapper<SessionTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionTagRelation::getTagId, tagId);
        sessionTagRelationMapper.delete(wrapper);
    }

    @Override
    public void addTagToSession(Long sessionId, Long tagId) {
        LambdaQueryWrapper<SessionTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionTagRelation::getSessionId, sessionId).eq(SessionTagRelation::getTagId, tagId);
        if (sessionTagRelationMapper.selectCount(wrapper) == 0) {
            SessionTagRelation rel = new SessionTagRelation();
            rel.setSessionId(sessionId);
            rel.setTagId(tagId);
            sessionTagRelationMapper.insert(rel);
        }
    }

    @Override
    public void removeTagFromSession(Long sessionId, Long tagId) {
        LambdaQueryWrapper<SessionTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SessionTagRelation::getSessionId, sessionId).eq(SessionTagRelation::getTagId, tagId);
        sessionTagRelationMapper.delete(wrapper);
    }

    @Override
    public List<TagVO> getTagsBySessionId(Long sessionId) {
        return sessionTagMapper.getTagsBySessionId(sessionId).stream().map(t -> {
            TagVO vo = new TagVO();
            BeanUtils.copyProperties(t, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<Long, List<TagVO>> getTagsBySessionIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = sessionTagMapper.getTagsBySessionIds(sessionIds);
        Map<Long, List<TagVO>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long sessionId = ((Number) row.get("session_id")).longValue();
            TagVO vo = new TagVO();
            vo.setId(((Number) row.get("id")).longValue());
            // userId not needed for TagVO output
            vo.setName((String) row.get("name"));
            vo.setColor((String) row.get("color"));
            result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(vo);
        }
        return result;
    }
}