package com.niit.agent.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, ToolHandler> toolMap = new HashMap<>();
    private final List<Map<String, Object>> toolDefinitions = new ArrayList<>();

    public ToolRegistry(List<ToolHandler> handlers) {
        for (ToolHandler handler : handlers) {
            toolMap.put(handler.getName(), handler);
            
            // Build OpenAI compatible tool definition
            Map<String, Object> functionDef = new HashMap<>();
            functionDef.put("name", handler.getName());
            functionDef.put("description", handler.getDescription());
            functionDef.put("parameters", handler.getParameters());
            
            Map<String, Object> toolDef = new HashMap<>();
            toolDef.put("type", "function");
            toolDef.put("function", functionDef);
            
            toolDefinitions.add(toolDef);
        }
        log.info("已注册 {} 个 AI Agent 工具: {}", handlers.size(), toolMap.keySet());
    }

    public ToolHandler getTool(String name) {
        return toolMap.get(name);
    }

    public List<Map<String, Object>> getToolDefinitions() {
        return toolDefinitions;
    }
}
