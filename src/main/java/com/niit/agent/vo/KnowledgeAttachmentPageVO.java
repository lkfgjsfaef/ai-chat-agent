package com.niit.agent.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class KnowledgeAttachmentPageVO {

    private long current;
    private long size;
    private long total;
    private long pages;
    private List<KnowledgeAttachmentVO> records;
    private Map<String, Long> scopeStats;
}
