package com.niit.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("session_tag_relation")
public class SessionTagRelation {
    @TableId
    private Long sessionId;
    private Long tagId;
}