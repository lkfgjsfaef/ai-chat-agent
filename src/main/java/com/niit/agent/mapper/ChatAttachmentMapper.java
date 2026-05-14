package com.niit.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niit.agent.entity.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {

    @Select("SELECT " +
            "SUM(CASE WHEN (scope = 'session' OR scope IS NULL) AND session_id = #{sessionId} THEN 1 ELSE 0 END) as session_count, " +
            "SUM(CASE WHEN scope = 'user' AND user_id = #{userId} THEN 1 ELSE 0 END) as user_count, " +
            "SUM(CASE WHEN scope = 'global' THEN 1 ELSE 0 END) as global_count " +
            "FROM chat_attachment")
    Map<String, Object> countKnowledgeByScopes(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
