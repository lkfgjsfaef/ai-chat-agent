package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.service.SessionTagService;
import com.niit.agent.vo.TagVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tag")
@RequiredArgsConstructor
public class SessionTagController {

    private final SessionTagService sessionTagService;

    private Long getUserId(HttpServletRequest request) {
        return Long.parseLong(request.getAttribute("userId").toString());
    }

    @GetMapping("/list")
    public Result<List<TagVO>> listTags(HttpServletRequest request) {
        return Result.ok(sessionTagService.listTags(getUserId(request)));
    }

    @PostMapping("/create")
    public Result<TagVO> createTag(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String name = body.get("name");
        String color = body.get("color");
        var tag = sessionTagService.createTag(getUserId(request), name, color);
        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setName(tag.getName());
        vo.setColor(tag.getColor());
        return Result.ok(vo);
    }

    @PutMapping("/{tagId}")
    public Result<Void> updateTag(@PathVariable Long tagId, @RequestBody Map<String, String> body) {
        sessionTagService.updateTag(tagId, body.get("name"), body.get("color"));
        return Result.ok();
    }

    @DeleteMapping("/{tagId}")
    public Result<Void> deleteTag(@PathVariable Long tagId) {
        sessionTagService.deleteTag(tagId);
        return Result.ok();
    }

    @PostMapping("/session/{sessionId}/{tagId}")
    public Result<Void> addTagToSession(@PathVariable Long sessionId, @PathVariable Long tagId) {
        sessionTagService.addTagToSession(sessionId, tagId);
        return Result.ok();
    }

    @DeleteMapping("/session/{sessionId}/{tagId}")
    public Result<Void> removeTagFromSession(@PathVariable Long sessionId, @PathVariable Long tagId) {
        sessionTagService.removeTagFromSession(sessionId, tagId);
        return Result.ok();
    }
}