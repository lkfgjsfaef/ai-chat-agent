package com.niit.agent.service.model;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface AiModel {

    default Flux<String> streamChat(List<Map<String, Object>> messages, String modelName) {
        return streamChat(messages, modelName, ChatOptions.DEFAULT);
    }

    Flux<String> streamChat(List<Map<String, Object>> messages, String modelName, ChatOptions options);

    String getModelName();

    record ChatOptions(boolean toolsEnabled) {
        public static final ChatOptions DEFAULT = new ChatOptions(true);
        public static final ChatOptions NO_TOOLS = new ChatOptions(false);
    }
}
