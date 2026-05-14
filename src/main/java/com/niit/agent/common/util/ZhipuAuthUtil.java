package com.niit.agent.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 智谱 AI V4 接口 JWT 生成工具类
 */
@Slf4j
public class ZhipuAuthUtil {

    private static final long EXPIRE_MILLIS = 3600 * 1000; // Token 有效期 1 小时

    /**
     * 将智谱的 API Key (格式: id.secret) 转换为 JWT Token
     */
    public static String generateToken(String apiKey) {
        if (apiKey == null || !apiKey.contains(".")) {
            log.error("无效的智谱 API Key 格式");
            return apiKey;
        }

        try {
            String[] parts = apiKey.split("\\.");
            String id = parts[0];
            String secret = parts[1];

            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            Date expireDate = new Date(nowMillis + EXPIRE_MILLIS);

            Map<String, Object> header = new HashMap<>();
            header.put("alg", "HS256");
            header.put("sign_type", "SIGN");

            return JWT.create()
                    .withHeader(header)
                    .withClaim("api_key", id)
                    .withClaim("timestamp", nowMillis)
                    .withExpiresAt(expireDate)
                    .sign(Algorithm.HMAC256(secret.getBytes("UTF-8")));

        } catch (Exception e) {
            log.error("生成智谱 JWT Token 失败", e);
            return apiKey; // 降级返回原 key
        }
    }
}