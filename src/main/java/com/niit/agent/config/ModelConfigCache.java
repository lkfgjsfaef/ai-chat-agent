package com.niit.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.niit.agent.entity.ModelConfig;
import com.niit.agent.mapper.ModelConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ModelConfigCache {

    private final ModelConfigMapper modelConfigMapper;
    private final ConcurrentMap<String, ModelConfig> cache;

    public ModelConfigCache(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(50)
                .<String, ModelConfig>build()
                .asMap();
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        List<ModelConfig> configs = modelConfigMapper.selectList(new LambdaQueryWrapper<>());
        log.debug("刷新模型配置缓存，加载 {} 条配置", configs.size());
        cache.clear();
        configs.forEach(c -> cache.put(c.getModelName(), c));
    }

    public ModelConfig getConfig(String modelName) {
        ModelConfig config = cache.get(modelName);
        if (config == null) {
            config = modelConfigMapper.selectOne(
                    new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getModelName, modelName));
            if (config != null) {
                cache.put(modelName, config);
            }
        }
        return config;
    }

    public boolean isModelAvailable(String modelName) {
        ModelConfig config = getConfig(modelName);
        if (config == null) return false;
        if (config.getStatus() == null || config.getStatus() != 1) return false;
        return config.getEndpoint() != null && !config.getEndpoint().isBlank();
    }

    public List<String> getAvailableModelNames() {
        return cache.values().stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .filter(c -> c.getEndpoint() != null && !c.getEndpoint().isBlank())
                .map(ModelConfig::getModelName)
                .toList();
    }
}
