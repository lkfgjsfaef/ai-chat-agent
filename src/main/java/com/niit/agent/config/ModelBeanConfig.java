package com.niit.agent.config;

import com.niit.agent.service.model.ConfigDrivenModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelBeanConfig {

    @Bean
    public ConfigDrivenModel deepSeekModel(ModelConfigCache modelConfigCache) {
        return new ConfigDrivenModel(modelConfigCache, "deepseek", "deepseek-chat");
    }

    @Bean
    public ConfigDrivenModel glm47Model(ModelConfigCache modelConfigCache) {
        return new ConfigDrivenModel(modelConfigCache, "glm-4.7", "glm-4.7");
    }

    @Bean
    public ConfigDrivenModel glm46vModel(ModelConfigCache modelConfigCache) {
        return new ConfigDrivenModel(modelConfigCache, "glm-4.6v", "glm-4.6v");
    }

    @Bean
    public ConfigDrivenModel glm45AirModel(ModelConfigCache modelConfigCache) {
        return new ConfigDrivenModel(modelConfigCache, "glm-4.5-air", "glm-4.5-air");
    }

    @Bean
    public ConfigDrivenModel openAiModel(ModelConfigCache modelConfigCache) {
        return new ConfigDrivenModel(modelConfigCache, "openai", "gpt-3.5-turbo");
    }
}
