package com.niit.agent.config;

import com.niit.agent.common.util.JwtUtil;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private static final String RATE_LIMIT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            return {current, ttl}
            """;

    private final Cache<String, Bucket> localBuckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();
    private final JwtUtil jwtUtil;
    private final UnifiedJedis unifiedJedis;

    @Value("${rate-limit.default.capacity:60}")
    private int defaultCapacity;

    @Value("${rate-limit.default.window-seconds:60}")
    private int defaultWindowSeconds;

    @Value("${rate-limit.stream.capacity:20}")
    private int streamCapacity;

    @Value("${rate-limit.stream.window-seconds:60}")
    private int streamWindowSeconds;

    @Value("${rate-limit.upload.capacity:10}")
    private int uploadCapacity;

    @Value("${rate-limit.upload.window-seconds:60}")
    private int uploadWindowSeconds;

    @Value("${rate-limit.auth.capacity:10}")
    private int authCapacity;

    @Value("${rate-limit.auth.window-seconds:60}")
    private int authWindowSeconds;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitPolicy policy = resolvePolicy(httpRequest);
        String key = buildRateLimitKey(httpRequest, policy);
        RateLimitDecision decision = tryConsumeDistributed(key, policy);
        if (decision == null) {
            decision = tryConsumeLocal(key, policy);
        }

        applyLimitHeaders(httpResponse, decision, policy);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write("{\"code\":429,\"msg\":\"请求过于频繁，请稍后再试\",\"data\":null}");
    }

    private String buildRateLimitKey(HttpServletRequest request, RateLimitPolicy policy) {
        return "rate_limit:" + policy.name + ":" + identifyClient(request) + ":" + request.getMethod() + ":" + request.getRequestURI();
    }

    private String identifyClient(HttpServletRequest request) {
        Long userId = extractUserId(request);
        if (userId != null) {
            return "user:" + userId;
        }
        return "ip:" + extractClientIp(request);
    }

    private Long extractUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                return jwtUtil.getUserId(auth.substring(7));
            } catch (Exception e) {
                log.debug("限流阶段解析用户身份失败，回退为IP限流: {}", e.getMessage());
            }
        }
        return null;
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            int commaIndex = ip.indexOf(',');
            return commaIndex >= 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
        }
        return request.getRemoteAddr();
    }

    private RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/chat/stream") || uri.startsWith("/chat/regenerate")) {
            return new RateLimitPolicy("stream", streamCapacity, streamWindowSeconds);
        }
        if (uri.startsWith("/file") || uri.contains("upload")) {
            return new RateLimitPolicy("upload", uploadCapacity, uploadWindowSeconds);
        }
        if (uri.startsWith("/user/login") || uri.startsWith("/user/register")) {
            return new RateLimitPolicy("auth", authCapacity, authWindowSeconds);
        }
        return new RateLimitPolicy("default", defaultCapacity, defaultWindowSeconds);
    }

    @SuppressWarnings("unchecked")
    private RateLimitDecision tryConsumeDistributed(String key, RateLimitPolicy policy) {
        try {
            Object result = unifiedJedis.eval(RATE_LIMIT_SCRIPT, 1, key, String.valueOf(policy.windowSeconds));
            if (!(result instanceof java.util.List<?> values) || values.size() < 2) {
                return null;
            }

            long current = Long.parseLong(String.valueOf(values.get(0)));
            long ttl = Long.parseLong(String.valueOf(values.get(1)));
            return new RateLimitDecision(current <= policy.capacity, policy.capacity, current, Math.max(ttl, 0));
        } catch (Exception e) {
            log.warn("Redis限流执行失败，回退本地限流: {}", e.getMessage());
            return null;
        }
    }

    private RateLimitDecision tryConsumeLocal(String key, RateLimitPolicy policy) {
        Bucket bucket = localBuckets.get(key, k -> createBucket(policy));
        long availableBefore = bucket.getAvailableTokens();
        boolean allowed = bucket.tryConsume(1);
        long remaining = allowed ? Math.max(bucket.getAvailableTokens(), 0) : Math.max(availableBefore, 0);
        return new RateLimitDecision(allowed, policy.capacity, policy.capacity - remaining, policy.windowSeconds);
    }

    private Bucket createBucket(RateLimitPolicy policy) {
        Bandwidth limit = Bandwidth.classic(policy.capacity, Refill.greedy(policy.capacity, Duration.ofSeconds(policy.windowSeconds)));
        return Bucket.builder().addLimit(limit).build();
    }

    private void applyLimitHeaders(HttpServletResponse response, RateLimitDecision decision, RateLimitPolicy policy) {
        long remaining = Math.max(policy.capacity - decision.currentRequests, 0);
        response.setHeader("X-RateLimit-Limit", String.valueOf(policy.capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(decision.retryAfterSeconds, 1)));
        }
    }

    private record RateLimitPolicy(String name, int capacity, int windowSeconds) {
    }

    private record RateLimitDecision(boolean allowed, long limit, long currentRequests, long retryAfterSeconds) {
    }
}
