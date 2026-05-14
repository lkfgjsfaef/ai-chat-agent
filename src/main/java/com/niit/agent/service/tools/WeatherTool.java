package com.niit.agent.service.tools;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WeatherTool implements ToolHandler {

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "获取指定城市或地区的当前天气信息";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> location = new HashMap<>();
        location.put("type", "string");
        location.put("description", "城市名称，例如：北京、上海、广州");
        properties.put("location", location);
        
        parameters.put("properties", properties);
        parameters.put("required", List.of("location"));
        
        return parameters;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String location = (String) params.get("location");
        if (location == null || location.trim().isEmpty()) {
            return "错误：必须提供 location 参数";
        }
        
        // 模拟返回天气数据，实际项目中可以调用第三方天气 API，如高德地图天气、和风天气等
        return String.format("【%s】今天天气晴朗，气温 20℃ ~ 26℃，微风，空气质量优。", location);
    }
}
