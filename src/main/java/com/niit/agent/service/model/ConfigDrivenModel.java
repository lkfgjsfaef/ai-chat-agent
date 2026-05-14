package com.niit.agent.service.model;

import com.niit.agent.config.ModelConfigCache;
import com.niit.agent.entity.ModelConfig;

public class ConfigDrivenModel extends AbstractOpenAiCompatibleModel {

    private final ModelConfigCache modelConfigCache;
    private final String modelName;
    private final String actualModelName;

    public ConfigDrivenModel(ModelConfigCache modelConfigCache, String modelName, String actualModelName) {
        this.modelConfigCache = modelConfigCache;
        this.modelName = modelName;
        this.actualModelName = actualModelName;
    }

    @Override
    protected String getEndpoint() {
        ModelConfig config = modelConfigCache.getConfig(modelName);
        return config != null ? config.getEndpoint() : null;
    }

    @Override
    protected String getApiKey() {
        ModelConfig config = modelConfigCache.getConfig(modelName);
        return config != null ? config.getApiKey() : null;
    }

    @Override
    protected String getActualModelName(String unused) {
        return actualModelName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
