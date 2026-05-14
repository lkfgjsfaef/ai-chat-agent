package com.niit.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_attachment")
public class ChatAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private Long messageId;
    private String scope;
    private String vectorDocIds;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private String extractedText;
    private LocalDateTime createTime;
}
