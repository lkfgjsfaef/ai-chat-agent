package com.niit.agent.service;

import java.util.List;
import java.util.Map;

public interface RuntimeMetricsHistoryService {

    void snapshotRuntimeMetrics();

    List<Map<String, Object>> getRuntimeHistory(int limit);
}
