package com.niit.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeAttachmentVO {

    private Long id;
    private Long sessionId;
    private Long userId;
    private String scope;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String extractedText;
    private LocalDateTime createTime;
    private Integer chunkCount;
    private Boolean canDelete;
}
