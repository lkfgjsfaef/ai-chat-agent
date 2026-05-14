package com.niit.agent.service.impl;

import com.niit.agent.service.CacheService;
import com.niit.agent.vo.MessageVO;
import com.niit.agent.vo.SessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String SESSION_KEY = "chat:session:";
    private static final String MESSAGE_KEY = "chat:messages:";
    private static final String RAG_KEY = "chat:rag:";
    private static final long CACHE_TTL = 30;

    @Override
    public void cacheSessionList(Long userId, List<SessionVO> sessions) {
        String key = SESSION_KEY + userId;
        redisTemplate.opsForValue().set(key, sessions, CACHE_TTL, TimeUnit.MINUTES);
        log.debug("缓存会话列表: userId={}", userId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SessionVO> getCachedSessionList(Long userId) {
        String key = SESSION_KEY + userId;
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof List) {
            return (List<SessionVO>) obj;
        }
        return null;
    }

    @Override
    public void cacheMessages(Long sessionId, List<MessageVO> messages) {
        String key = MESSAGE_KEY + sessionId;
        redisTemplate.opsForValue().set(key, messages, CACHE_TTL, TimeUnit.MINUTES);
        log.debug("缓存消息列表: sessionId={}", sessionId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MessageVO> getCachedMessages(Long sessionId) {
        String key = MESSAGE_KEY + sessionId;
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof List) {
            return (List<MessageVO>) obj;
        }
        return null;
    }

    @Override
    public void cacheRagResult(Long sessionId, String cacheKey, Map<String, Object> ragResult, long ttlSeconds) {
        String key = RAG_KEY + sessionId + ":" + cacheKey;
        redisTemplate.opsForValue().set(key, ragResult, ttlSeconds, TimeUnit.SECONDS);
        log.debug("缓存RAG结果: sessionId={}, cacheKey={}", sessionId, cacheKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCachedRagResult(Long sessionId, String cacheKey) {
        String key = RAG_KEY + sessionId + ":" + cacheKey;
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    @Override
    public void evictSession(Long userId) {
        redisTemplate.delete(SESSION_KEY + userId);
    }

    @Override
    public void evictMessages(Long sessionId) {
        redisTemplate.delete(MESSAGE_KEY + sessionId);
    }

    @Override
    public void evictRagResults(Long sessionId) {
        Set<String> keys = redisTemplate.keys(RAG_KEY + sessionId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("清理RAG缓存: sessionId={}, count={}", sessionId, keys.size());
        }
    }

    @Override
    public void evictRagResults(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (Long sessionId : sessionIds) {
            if (sessionId != null) {
                evictRagResults(sessionId);
            }
        }
    }

    @Override
    public void evictAllRagResults() {
        Set<String> keys = redisTemplate.keys(RAG_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("清理全部RAG缓存: count={}", keys.size());
        }
    }
}
