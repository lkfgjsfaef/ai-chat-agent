package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.entity.PromptTemplate;
import com.niit.agent.mapper.PromptTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/template")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateMapper promptTemplateMapper;

    @GetMapping("/list")
    public Result<List<PromptTemplate>> list() {
        return Result.ok(promptTemplateMapper.selectList(null));
    }
}