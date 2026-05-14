package com.niit.agent.service;

import com.niit.agent.vo.MessageVO;
import com.niit.agent.vo.SessionVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CacheService {

    void cacheSessionList(Long userId, List<SessionVO> sessions);

    List<SessionVO> getCachedSessionList(Long userId);

    void cacheMessages(Long sessionId, List<MessageVO> messages);

    List<MessageVO> getCachedMessages(Long sessionId);

    void cacheRagResult(Long sessionId, String cacheKey, Map<String, Object> ragResult, long ttlSeconds);

    Map<String, Object> getCachedRagResult(Long sessionId, String cacheKey);

    void evictSession(Long userId);

    void evictMessages(Long sessionId);

    void evictRagResults(Long sessionId);

    void evictRagResults(Collection<Long> sessionIds);

    void evictAllRagResults();
}
