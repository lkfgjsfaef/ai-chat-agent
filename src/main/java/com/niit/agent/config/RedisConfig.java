package com.niit.agent.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.autoconfigure.vectorstore.redis.RedisVectorStoreProperties;
import org.springframework.ai.vectorstore.RedisVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:123456}")
    private String password;

    @Value("${spring.ai.vectorstore.redis.index:vector_index}")
    private String indexName;

    @Value("${spring.ai.vectorstore.redis.prefix:vector:}")
    private String prefix;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:3000ms}")
    private String redisTimeout;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        String redisUri = "redis://:" + password + "@" + host + ":" + port;

        RedisVectorStore.RedisVectorStoreConfig config = RedisVectorStore.RedisVectorStoreConfig.builder()
                .withURI(redisUri)
                .withIndexName(indexName)
                .withPrefix(prefix)
                .build();

        return new RedisVectorStore(config, embeddingModel, true);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        om.registerModule(new JavaTimeModule());

        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(om, Object.class);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        return template;
    }

    @Bean
    public UnifiedJedis unifiedJedis() {
        HostAndPort address = new HostAndPort(host, port);
        int timeoutMillis = parseTimeoutMillis(redisTimeout);
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(password)
                .connectionTimeoutMillis(timeoutMillis)
                .socketTimeoutMillis(timeoutMillis)
                .build();
        return new JedisPooled(address, clientConfig);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String redisAddress = "redis://" + host + ":" + port;
        int timeoutMillis = parseTimeoutMillis(redisTimeout);
        config.useSingleServer()
                .setAddress(redisAddress)
                .setDatabase(database)
                .setPassword(password == null || password.isBlank() ? null : password)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(16)
                .setConnectTimeout(timeoutMillis)
                .setTimeout(timeoutMillis)
                .setRetryAttempts(3)
                .setRetryInterval(1000);
        return Redisson.create(config);
    }

    @PostConstruct
    public void warmupRedisConnections() {
        log.info("开始预热 Redis 连接: {}:{}", host, port);
        try {
            UnifiedJedis warmupJedis = unifiedJedis();
            String ping = warmupJedis.ping();
            log.info("JedisPooled 连接预热完成, ping: {}", ping);

            redissonClient().getBucket("__warmup__").set("1");
            redissonClient().getBucket("__warmup__").delete();
            log.info("Redisson 连接预热完成");
        } catch (Exception e) {
            log.warn("Redis 连接预热异常: {}", e.getMessage());
        }
    }

    private int parseTimeoutMillis(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return 3000;
        }
        String trimmed = timeout.trim().toLowerCase();
        if (trimmed.endsWith("ms")) {
            return Integer.parseInt(trimmed.substring(0, trimmed.length() - 2));
        }
        if (trimmed.endsWith("s")) {
            return Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)) * 1000;
        }
        return Integer.parseInt(trimmed);
    }
}
