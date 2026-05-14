package com.niit.agent.service;

import com.niit.agent.service.model.AiModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface AiModelRouterService {

    default Flux<String> streamChat(String modelName, List<Map<String, Object>> messages) {
        return streamChat(modelName, messages, QueueStatusListener.NO_OP, ChatOptions.DEFAULT);
    }

    default Flux<String> streamChat(String modelName, List<Map<String, Object>> messages, QueueStatusListener queueStatusListener) {
        return streamChat(modelName, messages, queueStatusListener, ChatOptions.DEFAULT);
    }

    Flux<String> streamChat(String modelName, List<Map<String, Object>> messages,
                            QueueStatusListener queueStatusListener, ChatOptions options);

    List<String> listAvailableModels();

    Map<String, Object> getRuntimeMetrics();

    record ChatOptions(boolean toolsEnabled, TaskLane taskLane) {
        public static final ChatOptions DEFAULT = new ChatOptions(true, TaskLane.PRIMARY);
        public static final ChatOptions AUXILIARY_NO_TOOLS = new ChatOptions(false, TaskLane.AUXILIARY);
    }

    enum TaskLane {
        PRIMARY,
        AUXILIARY
    }

    @FunctionalInterface
    interface QueueStatusListener {
        QueueStatusListener NO_OP = event -> {
        };

        void onEvent(QueueStatusEvent event);
    }

    record QueueStatusEvent(String phase, String requestedModel, long waitedMs, int activeRequests, int waitingRequests, String message) {
    }
}
