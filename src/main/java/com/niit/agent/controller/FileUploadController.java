package com.niit.agent.controller;

import com.niit.agent.common.result.Result;
import com.niit.agent.entity.ChatAttachment;
import com.niit.agent.service.CacheService;
import com.niit.agent.service.ChatAttachmentService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.vo.KnowledgeAttachmentPageVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final ChatAttachmentService chatAttachmentService;
    private final CacheService cacheService;
    private final ChatSessionService chatSessionService;

    @PostMapping("/image")
    public Result<ChatAttachment> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest request) {
        try {
            if (file.isEmpty()) {
                return Result.fail("图片不能为空");
            }
            Long currentUserId = parseLongAttribute(request.getAttribute("userId"));
            if (currentUserId == null) {
                return Result.fail("未识别当前用户");
            }
            String resolvedScope = resolveScope(scope, sessionId);
            if ("global".equals(resolvedScope) && !isAdmin(request)) {
                return Result.fail("全局知识库仅限管理员上传");
            }
            ChatAttachment attachment = chatAttachmentService.uploadImage(file, sessionId, resolvedScope, currentUserId);
            return Result.ok(attachment);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail("图片上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/file")
    public Result<ChatAttachment> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest request) {
        try {
            if (file.isEmpty()) {
                return Result.fail("文件不能为空");
            }
            // limit size 10MB
            if (file.getSize() > 10 * 1024 * 1024) {
                return Result.fail("文件大小不能超过 10MB");
            }
            Long currentUserId = parseLongAttribute(request.getAttribute("userId"));
            if (currentUserId == null) {
                return Result.fail("未识别当前用户");
            }
            String resolvedScope = resolveScope(scope, sessionId);
            if ("global".equals(resolvedScope) && !isAdmin(request)) {
                return Result.fail("全局知识库仅限管理员上传");
            }

            ChatAttachment attachment = chatAttachmentService.uploadAndParse(file, sessionId, resolvedScope, currentUserId);
            evictRelevantRagCache(attachment, currentUserId);
            return Result.ok(attachment);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail("文件上传或解析失败: " + e.getMessage());
        }
    }

    @GetMapping("/files")
    public Result<KnowledgeAttachmentPageVO> listFiles(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "userId", required = false) Long targetUserId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "10") Long size,
            HttpServletRequest request) {
        try {
            Long currentUserId = parseLongAttribute(request.getAttribute("userId"));
            if (currentUserId == null) {
                return Result.fail("未识别当前用户");
            }
            boolean admin = isAdmin(request);
            return Result.ok(chatAttachmentService.listAccessibleAttachments(
                    currentUserId, admin, scope, sessionId, targetUserId, keyword, current, size));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail("知识库列表查询失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/file/{attachmentId}")
    public Result<Void> deleteFile(@PathVariable Long attachmentId, HttpServletRequest request) {
        try {
            Long currentUserId = parseLongAttribute(request.getAttribute("userId"));
            if (currentUserId == null) {
                return Result.fail("未识别当前用户");
            }
            ChatAttachment deletedAttachment = chatAttachmentService.deleteAttachment(attachmentId, currentUserId, isAdmin(request));
            evictRelevantRagCache(deletedAttachment, deletedAttachment.getUserId());
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail("文件删除失败: " + e.getMessage());
        }
    }

    private void evictRelevantRagCache(ChatAttachment attachment, Long targetUserId) {
        if (attachment == null || attachment.getScope() == null) {
            return;
        }
        switch (attachment.getScope()) {
            case "session" -> {
                if (attachment.getSessionId() != null) {
                    cacheService.evictRagResults(attachment.getSessionId());
                }
            }
            case "user" -> {
                if (targetUserId == null) {
                    return;
                }
                List<Long> sessionIds = chatSessionService.listSessions(targetUserId).stream()
                        .map(session -> session.getId())
                        .toList();
                cacheService.evictRagResults(sessionIds);
            }
            case "global" -> cacheService.evictAllRagResults();
            default -> {
            }
        }
    }

    private String resolveScope(String scope, Long sessionId) {
        if (scope == null || scope.isBlank()) {
            return sessionId != null ? "session" : "user";
        }
        String normalized = scope.trim().toLowerCase();
        if (!"session".equals(normalized) && !"user".equals(normalized) && !"global".equals(normalized)) {
            throw new IllegalArgumentException("scope 仅支持 session、user、global");
        }
        return normalized;
    }

    private boolean isAdmin(HttpServletRequest request) {
        Object role = request.getAttribute("role");
        return role != null && "admin".equals(String.valueOf(role));
    }

    private Long parseLongAttribute(Object attribute) {
        if (attribute == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(attribute));
    }
}
