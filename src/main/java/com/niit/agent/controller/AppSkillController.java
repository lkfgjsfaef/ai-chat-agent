package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.service.AppSkillService;
import com.niit.agent.vo.AppSkillVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/skill")
@RequiredArgsConstructor
public class AppSkillController {

    private final AppSkillService appSkillService;

    @GetMapping("/list")
    public Result<List<AppSkillVO>> list() {
        return Result.ok(appSkillService.listSkills());
    }
}
