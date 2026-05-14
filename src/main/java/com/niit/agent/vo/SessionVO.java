package com.niit.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SessionVO {

    private Long id;
    private String title;
    private String systemPrompt;
    private String skillId;
    private LocalDateTime createTime;
    private List<TagVO> tags;
}
