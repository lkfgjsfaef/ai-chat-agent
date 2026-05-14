package com.niit.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niit.agent.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
