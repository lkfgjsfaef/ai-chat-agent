package com.niit.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.niit.agent.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}