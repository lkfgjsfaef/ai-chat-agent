package com.niit.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("session_tag")
public class SessionTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String color;

    private LocalDateTime createTime;
}