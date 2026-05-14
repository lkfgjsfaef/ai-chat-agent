package com.niit.agent.service.tools;

import java.util.Map;

/**
 * AI Agent 工具调用接口
 */
public interface ToolHandler {
    
    /**
     * 工具名称 (必须符合大模型 Function Calling 的命名规范，如英文、数字、下划线)
     */
    String getName();
    
    /**
     * 工具描述 (供大模型理解该工具的用途)
     */
    String getDescription();
    
    /**
     * 工具参数的 JSON Schema 描述
     */
    Map<String, Object> getParameters();
    
    /**
     * 执行工具逻辑
     * @param params 大模型解析出的参数
     * @return 执行结果，将作为内容回传给大模型
     */
    String execute(Map<String, Object> params);
}
