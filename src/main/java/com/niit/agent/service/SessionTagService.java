package com.niit.agent.service;

import com.niit.agent.entity.SessionTag;
import com.niit.agent.vo.TagVO;

import java.util.List;
import java.util.Map;

public interface SessionTagService {

    List<TagVO> listTags(Long userId);

    SessionTag createTag(Long userId, String name, String color);

    void updateTag(Long tagId, String name, String color);

    void deleteTag(Long tagId);

    void addTagToSession(Long sessionId, Long tagId);

    void removeTagFromSession(Long sessionId, Long tagId);

    List<TagVO> getTagsBySessionId(Long sessionId);

    Map<Long, List<TagVO>> getTagsBySessionIds(List<Long> sessionIds);
}