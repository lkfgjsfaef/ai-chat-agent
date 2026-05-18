package com.niit.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.niit.agent.entity.ChatAttachment;
import com.niit.agent.vo.KnowledgeAttachmentPageVO;
import org.springframework.web.multipart.MultipartFile;

public interface ChatAttachmentService extends IService<ChatAttachment> {
    ChatAttachment uploadAndParse(MultipartFile file, Long sessionId, String scope, Long currentUserId) throws Exception;

    ChatAttachment deleteAttachment(Long attachmentId, Long currentUserId, boolean admin);

    KnowledgeAttachmentPageVO listAccessibleAttachments(Long currentUserId, boolean admin, String scope,
                                                        Long sessionId, Long targetUserId, String keyword,
                                                        long current, long size);

    String getFullText(Long attachmentId);
}
