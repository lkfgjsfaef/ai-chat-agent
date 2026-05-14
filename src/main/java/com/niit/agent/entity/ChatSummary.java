package com.niit.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_summary")
public class ChatSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String summary;

    private Long lastSummarizedMessageId;

    private LocalDateTime updateTime;
}
