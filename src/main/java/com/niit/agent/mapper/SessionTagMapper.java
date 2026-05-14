package com.niit.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niit.agent.entity.SessionTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SessionTagMapper extends BaseMapper<SessionTag> {

    @Select("SELECT t.* FROM session_tag t JOIN session_tag_relation r ON t.id = r.tag_id WHERE r.session_id = #{sessionId}")
    List<SessionTag> getTagsBySessionId(@Param("sessionId") Long sessionId);

    @Select("<script>" +
            "SELECT r.session_id, t.* FROM session_tag t " +
            "JOIN session_tag_relation r ON t.id = r.tag_id " +
            "WHERE r.session_id IN " +
            "<foreach collection='sessionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Map<String, Object>> getTagsBySessionIds(@Param("sessionIds") List<Long> sessionIds);
}