package com.niit.agent.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.UnifiedJedis;

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

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        // Jedis client is used internally by RedisVectorStoreConfig if we use the URI
        String redisUri = "redis://:" + password + "@" + host + ":" + port;
        
        RedisVectorStore.RedisVectorStoreConfig config = RedisVectorStore.RedisVectorStoreConfig.builder()
                .withURI(redisUri)
                .withIndexName(indexName)
                .withPrefix(prefix)
                .build();
                
        // In M1 version, RedisVectorStore constructor takes: (config, embeddingModel, initializeSchema)
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
        JedisClientConfig config = DefaultJedisClientConfig.builder()
                .password(password)
                .build();
        return new UnifiedJedis(address, config);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String redisAddress = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(redisAddress)
                .setDatabase(database)
                .setPassword(password == null || password.isBlank() ? null : password);
        return Redisson.create(config);
    }
}
