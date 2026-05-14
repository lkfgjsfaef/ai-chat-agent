package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.service.CacheService;
import com.niit.agent.service.ChatMessageService;
import com.niit.agent.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/message")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;
    private final CacheService cacheService;

    @GetMapping("/list")
    public Result<List<MessageVO>> list(@RequestParam Long sessionId) {
        List<MessageVO> cached = cacheService.getCachedMessages(sessionId);
        if (cached != null) {
            return Result.ok(cached);
        }
        List<MessageVO> messages = chatMessageService.listMessages(sessionId);
        cacheService.cacheMessages(sessionId, messages);
        return Result.ok(messages);
    }

    @PutMapping("/{messageId}")
    public Result<Void> updateMessage(@PathVariable Long messageId, @RequestBody java.util.Map<String, String> body, @RequestParam Long sessionId) {
        String content = body.get("content");
        chatMessageService.updateMessage(messageId, content);
        cacheService.evictMessages(sessionId);
        return Result.ok();
    }

    @DeleteMapping("/after")
    public Result<Void> deleteAfterMessage(@RequestParam Long sessionId, @RequestParam Long afterMessageId) {
        chatMessageService.deleteAfterMessage(sessionId, afterMessageId);
        cacheService.evictMessages(sessionId);
        return Result.ok();
    }

    @PutMapping("/{messageId}/feedback")
    public Result<Void> updateFeedback(@PathVariable Long messageId,
                                       @RequestBody java.util.Map<String, String> body,
                                       @RequestParam Long sessionId,
                                       jakarta.servlet.http.HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return Result.fail(401, "用户未登录");
        }
        String feedback = body.get("feedback");
        if (feedback != null && !feedback.isBlank()
                && !"like".equalsIgnoreCase(feedback)
                && !"dislike".equalsIgnoreCase(feedback)) {
            return Result.fail(400, "反馈值仅支持 like / dislike / null");
        }
        boolean updated = chatMessageService.updateFeedback(Long.parseLong(userIdObj.toString()), sessionId, messageId, feedback);
        if (!updated) {
            return Result.fail(403, "无权反馈该消息或消息不存在");
        }
        cacheService.evictMessages(sessionId);
        return Result.ok();
    }

    @GetMapping("/search")
    public Result<List<MessageVO>> search(@RequestParam String keyword, jakarta.servlet.http.HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return Result.fail("用户未登录");
        }
        Long userId = Long.parseLong(userIdObj.toString());
        List<MessageVO> messages = chatMessageService.searchMessages(userId, keyword);
        return Result.ok(messages);
    }
}
