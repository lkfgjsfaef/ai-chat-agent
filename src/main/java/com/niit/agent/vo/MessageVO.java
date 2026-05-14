package com.niit.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String feedback;
    private LocalDateTime createTime;
}
