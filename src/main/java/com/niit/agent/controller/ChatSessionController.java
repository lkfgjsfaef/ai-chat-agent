package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.service.AppSkillService;
import com.niit.agent.service.CacheService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.vo.SessionVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final CacheService cacheService;
    private final AppSkillService appSkillService;

    @PostMapping("/create")
    public Result<SessionVO> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserId(request);
        String title = body.getOrDefault("title", "新对话");
        String systemPrompt = body.get("systemPrompt");
        String skillId = normalizeSkillId(body.get("skillId"));
        if (!appSkillService.isValidSkill(skillId)) {
            return Result.fail("skillId 不存在");
        }
        var session = chatSessionService.createSession(userId, title, systemPrompt, skillId);
        cacheService.evictSession(userId);
        SessionVO vo = new SessionVO();
        vo.setId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setSystemPrompt(session.getSystemPrompt());
        vo.setSkillId(session.getSkillId());
        vo.setCreateTime(session.getCreateTime());
        return Result.ok(vo);
    }

    @GetMapping("/list")
    public Result<List<SessionVO>> list(HttpServletRequest request) {
        Long userId = getUserId(request);
        List<SessionVO> cached = cacheService.getCachedSessionList(userId);
        if (cached != null) {
            return Result.ok(cached);
        }
        List<SessionVO> sessions = chatSessionService.listSessions(userId);
        cacheService.cacheSessionList(userId, sessions);
        return Result.ok(sessions);
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable Long sessionId, HttpServletRequest request) {
        Long userId = getUserId(request);
        chatSessionService.deleteSession(sessionId);
        cacheService.evictSession(userId);
        cacheService.evictMessages(sessionId);
        return Result.ok();
    }

    @PutMapping("/{sessionId}/title")
    public Result<Void> rename(@PathVariable Long sessionId, @RequestParam String title, HttpServletRequest request) {
        Long userId = getUserId(request);
        chatSessionService.updateTitle(sessionId, title);
        cacheService.evictSession(userId);
        return Result.ok();
    }

    @PutMapping("/{sessionId}/prompt")
    public Result<Void> updateSystemPrompt(@PathVariable Long sessionId, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserId(request);
        String systemPrompt = body.get("systemPrompt");
        chatSessionService.updateSystemPrompt(sessionId, systemPrompt);
        cacheService.evictSession(userId);
        return Result.ok();
    }

    @PutMapping("/{sessionId}/skill")
    public Result<Void> updateSkill(@PathVariable Long sessionId, @RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserId(request);
        String skillId = normalizeSkillId(body.get("skillId"));
        if (!appSkillService.isValidSkill(skillId)) {
            return Result.fail("skillId 不存在");
        }
        chatSessionService.updateSkill(sessionId, skillId);
        cacheService.evictSession(userId);
        return Result.ok();
    }

    @GetMapping("/search")
    public Result<List<SessionVO>> search(@RequestParam String keyword, HttpServletRequest request) {
        Long userId = getUserId(request);
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        List<SessionVO> sessions = chatSessionService.searchSessions(userId, keyword);
        return Result.ok(sessions);
    }

    private Long getUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId != null ? Long.parseLong(userId.toString()) : null;
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        return skillId.trim();
    }
}
