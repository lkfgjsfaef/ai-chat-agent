package com.niit.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.niit.agent.entity.ChatAttachment;
import com.niit.agent.entity.ChatSession;
import com.niit.agent.mapper.ChatAttachmentMapper;
import com.niit.agent.service.ChatAttachmentService;
import com.niit.agent.service.ChatSessionService;
import com.niit.agent.vo.KnowledgeAttachmentPageVO;
import com.niit.agent.vo.KnowledgeAttachmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentServiceImpl extends ServiceImpl<ChatAttachmentMapper, ChatAttachment> implements ChatAttachmentService {

    private static final String SCOPE_SESSION = "session";
    private static final String SCOPE_USER = "user";
    private static final String SCOPE_GLOBAL = "global";
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern NUMERIC_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+){0,4})[、.．\\s]+(.+)$");
    private static final Pattern CHINESE_CHAPTER_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百零两\\d]+[章节篇部分条])\\s*(.+)?$");
    private static final Pattern CHINESE_LIST_PATTERN = Pattern.compile("^([一二三四五六七八九十]+)[、.．]\\s*(.+)$");
    private static final int SECTION_MAX_CHARS = 1400;
    private static final int SECTION_MIN_CHARS = 320;
    private static final int SEMANTIC_CHUNK_TARGET_CHARS = 900;
    private static final int SEMANTIC_CHUNK_MAX_CHARS = 1200;
    private static final int SEMANTIC_CHUNK_MIN_CHARS = 240;
    private static final int SEMANTIC_OVERLAP_UNITS = 1;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    private final VectorStore vectorStore;
    private final ChatSessionService chatSessionService;
    private final Tika tika = new Tika();

    @Override
    public ChatAttachment uploadAndParse(MultipartFile file, Long sessionId, String scope, Long currentUserId) throws Exception {
        String resolvedScope = normalizeScope(scope, sessionId);
        ChatSession session = sessionId != null ? chatSessionService.getById(sessionId) : null;
        validateUploadScope(sessionId, resolvedScope, currentUserId, session);

        // Create directory
        String currentDir = System.getProperty("user.dir");
        Path uploadPath = Paths.get(currentDir, uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // Save file
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".") ? 
                originalFilename.substring(originalFilename.lastIndexOf(".")) : "";
        String newFileName = UUID.randomUUID().toString() + extension;
        Path filePath = uploadPath.resolve(newFileName).normalize().toAbsolutePath();
        
        // Tomcat's MultipartFile.transferTo() has a bug where it prepends the temp directory 
        // even if the path is absolute in some Spring Boot versions.
        // We use java.nio.file.Files.copy instead to be 100% safe.
        try (java.io.InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // Parse with Tika
        String extractedText = "";
        String vectorizableText = "";
        try {
            extractedText = tika.parseToString(filePath.toFile());
            if (extractedText != null && !extractedText.trim().isEmpty()) {
                // Remove null characters (\u0000) that can break JSON parsing
                extractedText = extractedText.replace("\u0000", "");
                vectorizableText = extractedText;

                // We no longer need to store the full text in DB or return it to frontend, 
                // just a short preview is enough.
                if (extractedText.length() > 500) {
                    extractedText = extractedText.substring(0, 500) + "\n...[文档内容已保存至知识库，可直接提问]";
                } else {
                    extractedText = extractedText + "\n\n[文档内容已保存至知识库，可直接提问]";
                }
            }
        } catch (Exception e) {
            log.warn("文档解析或向量化失败: {}", e.getMessage(), e);
            extractedText = "无法解析或向量化该文档内容";
        }

        // Save to DB
        ChatAttachment attachment = new ChatAttachment();
        attachment.setSessionId(SCOPE_SESSION.equals(resolvedScope) ? sessionId : null);
        attachment.setUserId(currentUserId);
        attachment.setScope(resolvedScope);
        attachment.setFileName(originalFilename);
        attachment.setFilePath(filePath.toString());
        attachment.setFileSize(file.getSize());
        attachment.setFileType(file.getContentType());
        attachment.setExtractedText(extractedText);
        attachment.setCreateTime(LocalDateTime.now());

        this.save(attachment);
        attachment.setVectorDocIds(storeAttachmentDocuments(attachment, vectorizableText));
        this.updateById(attachment);
        
        return attachment;
    }

    @Override
    public ChatAttachment deleteAttachment(Long attachmentId, Long currentUserId, boolean admin) {
        ChatAttachment attachment = this.getById(attachmentId);
        if (attachment == null) {
            throw new IllegalArgumentException("附件不存在");
        }
        if (!admin && (currentUserId == null || !currentUserId.equals(attachment.getUserId()))) {
            throw new IllegalArgumentException("无权删除该附件");
        }

        deleteVectorDocuments(attachment);
        deletePhysicalFile(attachment.getFilePath());
        this.removeById(attachmentId);
        return attachment;
    }

    @Override
    public KnowledgeAttachmentPageVO listAccessibleAttachments(Long currentUserId, boolean admin, String scope,
                                                               Long sessionId, Long targetUserId, String keyword,
                                                               long current, long size) {
        String normalizedScope = normalizeOptionalScope(scope);
        validateListRequest(currentUserId, admin, normalizedScope, sessionId, targetUserId);

        Page<ChatAttachment> page = new Page<>(current, size);
        LambdaQueryWrapper<ChatAttachment> pageWrapper = buildAttachmentQueryWrapper(
                currentUserId, admin, normalizedScope, sessionId, targetUserId, keyword);
        IPage<ChatAttachment> attachmentPage = this.page(page, pageWrapper);

        KnowledgeAttachmentPageVO result = new KnowledgeAttachmentPageVO();
        result.setCurrent(attachmentPage.getCurrent());
        result.setSize(attachmentPage.getSize());
        result.setTotal(attachmentPage.getTotal());
        result.setPages(attachmentPage.getPages());
        result.setRecords(attachmentPage.getRecords().stream()
                .map(attachment -> toKnowledgeAttachmentVO(attachment, currentUserId, admin))
                .toList());

        Map<String, Long> scopeStats = new LinkedHashMap<>();
        scopeStats.put(SCOPE_SESSION, countByScope(currentUserId, admin, SCOPE_SESSION, sessionId, targetUserId, keyword));
        scopeStats.put(SCOPE_USER, countByScope(currentUserId, admin, SCOPE_USER, sessionId, targetUserId, keyword));
        scopeStats.put(SCOPE_GLOBAL, countByScope(currentUserId, admin, SCOPE_GLOBAL, sessionId, targetUserId, keyword));
        result.setScopeStats(scopeStats);
        return result;
    }

    private String normalizeScope(String scope, Long sessionId) {
        if (scope == null || scope.isBlank()) {
            return sessionId != null ? SCOPE_SESSION : SCOPE_GLOBAL;
        }
        return normalizeOptionalScope(scope);
    }

    private String normalizeOptionalScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String normalized = scope.trim().toLowerCase();
        if (!SCOPE_SESSION.equals(normalized) && !SCOPE_USER.equals(normalized) && !SCOPE_GLOBAL.equals(normalized)) {
            throw new IllegalArgumentException("scope 仅支持 session、user、global");
        }
        return normalized;
    }

    private String resolveStoredScope(ChatAttachment attachment) {
        if (attachment == null || attachment.getScope() == null || attachment.getScope().isBlank()) {
            return SCOPE_SESSION;
        }
        return attachment.getScope();
    }

    private void validateUploadScope(Long sessionId, String scope, Long currentUserId, ChatSession session) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("上传知识库前请先登录");
        }
        if (SCOPE_SESSION.equals(scope)) {
            if (sessionId == null) {
                throw new IllegalArgumentException("session 作用域上传必须提供 sessionId");
            }
            if (session == null) {
                throw new IllegalArgumentException("会话不存在，无法上传到 session 知识域");
            }
            if (session.getUserId() == null || !currentUserId.equals(session.getUserId())) {
                throw new IllegalArgumentException("无权向该会话写入知识库");
            }
            return;
        }
        if (session != null && session.getUserId() != null && !currentUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权引用其他用户的会话上下文");
        }
    }

    private void validateListRequest(Long currentUserId, boolean admin, String scope, Long sessionId, Long targetUserId) {
        if (currentUserId == null) {
            throw new IllegalArgumentException("查询知识库前请先登录");
        }
        if (!admin && targetUserId != null && !currentUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("无权查看其他用户的知识库");
        }
        if (sessionId != null) {
            ChatSession session = chatSessionService.getById(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("会话不存在");
            }
            if (!admin && (session.getUserId() == null || !currentUserId.equals(session.getUserId()))) {
                throw new IllegalArgumentException("无权查看该会话知识库");
            }
            if (targetUserId != null && session.getUserId() != null && !targetUserId.equals(session.getUserId())) {
                throw new IllegalArgumentException("会话与目标用户不匹配");
            }
        }
        if (sessionId != null && scope != null && !SCOPE_SESSION.equals(scope)) {
            throw new IllegalArgumentException("只有 session 作用域支持按 sessionId 过滤");
        }
    }

    private LambdaQueryWrapper<ChatAttachment> buildAttachmentQueryWrapper(Long currentUserId, boolean admin,
                                                                           String scope, Long sessionId,
                                                                           Long targetUserId, String keyword) {
        LambdaQueryWrapper<ChatAttachment> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityFilter(wrapper, currentUserId, admin, scope, targetUserId);

        if (sessionId != null) {
            wrapper.eq(ChatAttachment::getSessionId, sessionId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ChatAttachment::getFileName, keyword.trim());
        }
        wrapper.orderByDesc(ChatAttachment::getCreateTime);
        return wrapper;
    }

    private void applyVisibilityFilter(LambdaQueryWrapper<ChatAttachment> wrapper, Long currentUserId,
                                       boolean admin, String scope, Long targetUserId) {
        if (admin) {
            if (targetUserId != null) {
                wrapper.eq(ChatAttachment::getUserId, targetUserId);
            }
            applyScopeFilter(wrapper, scope);
            return;
        }

        if (SCOPE_GLOBAL.equals(scope)) {
            wrapper.eq(ChatAttachment::getScope, SCOPE_GLOBAL);
            return;
        }

        if (scope == null || scope.isBlank()) {
            wrapper.and(w -> w.eq(ChatAttachment::getUserId, currentUserId)
                    .or()
                    .eq(ChatAttachment::getScope, SCOPE_GLOBAL));
            return;
        }

        wrapper.eq(ChatAttachment::getUserId, currentUserId);
        applyScopeFilter(wrapper, scope);
    }

    private void applyScopeFilter(LambdaQueryWrapper<ChatAttachment> wrapper, String scope) {
        if (scope == null || scope.isBlank()) {
            return;
        }
        if (SCOPE_SESSION.equals(scope)) {
            wrapper.and(w -> w.eq(ChatAttachment::getScope, SCOPE_SESSION).or().isNull(ChatAttachment::getScope));
            return;
        }
        wrapper.eq(ChatAttachment::getScope, scope);
    }

    private long countByScope(Long currentUserId, boolean admin, String scope, Long sessionId,
                              Long targetUserId, String keyword) {
        LambdaQueryWrapper<ChatAttachment> wrapper = buildAttachmentQueryWrapper(
                currentUserId, admin, scope, sessionId, targetUserId, keyword);
        return this.count(wrapper);
    }

    private KnowledgeAttachmentVO toKnowledgeAttachmentVO(ChatAttachment attachment, Long currentUserId, boolean admin) {
        KnowledgeAttachmentVO vo = new KnowledgeAttachmentVO();
        vo.setId(attachment.getId());
        vo.setSessionId(attachment.getSessionId());
        vo.setUserId(attachment.getUserId());
        vo.setScope(resolveStoredScope(attachment));
        vo.setFileName(attachment.getFileName());
        vo.setFileSize(attachment.getFileSize());
        vo.setFileType(attachment.getFileType());
        vo.setExtractedText(attachment.getExtractedText());
        vo.setCreateTime(attachment.getCreateTime());
        vo.setChunkCount(parseVectorDocIds(attachment.getVectorDocIds()).size());
        vo.setCanDelete(admin || (currentUserId != null && currentUserId.equals(attachment.getUserId())));
        return vo;
    }

    private String storeAttachmentDocuments(ChatAttachment attachment, String extractedText) {
        if (attachment == null || attachment.getId() == null || extractedText == null || extractedText.isBlank()) {
            return "";
        }

        try {
            log.info("开始分块并存储文档到向量数据库: {}", attachment.getFileName());
            List<Document> rawDocuments = buildStructuredDocuments(attachment, extractedText);
            List<String> vectorDocIds = new ArrayList<>(rawDocuments.size());
            List<Document> documents = new ArrayList<>(rawDocuments.size());
            for (int i = 0; i < rawDocuments.size(); i++) {
                Document rawDocument = rawDocuments.get(i);
                String docId = buildVectorDocId(attachment.getId(), i);
                vectorDocIds.add(docId);
                documents.add(new Document(docId, rawDocument.getContent(), rawDocument.getMetadata()));
            }
            if (!documents.isEmpty()) {
                vectorStore.add(documents);
            }
            log.info("成功存储 {} 个文档块到向量数据库, attachmentId={}", documents.size(), attachment.getId());
            return String.join(",", vectorDocIds);
        } catch (Exception e) {
            log.warn("文档向量化失败: {}", e.getMessage(), e);
            return "";
        }
    }

    private List<Document> buildStructuredDocuments(ChatAttachment attachment, String extractedText) {
        Map<String, Object> baseMetadata = buildBaseMetadata(attachment);
        String rootSourceName = resolveRootSourceName(attachment.getFileName());
        List<StructuredChunk> structuredChunks = splitByDocumentStructure(extractedText, rootSourceName);
        if (structuredChunks.isEmpty()) {
            structuredChunks = splitByParagraphWindows(extractedText, rootSourceName);
        }

        List<Document> documents = new ArrayList<>();
        for (StructuredChunk chunk : structuredChunks) {
            documents.addAll(toDocuments(chunk, baseMetadata));
        }
        if (documents.isEmpty()) {
            Map<String, Object> fallbackMetadata = new HashMap<>(baseMetadata);
            fallbackMetadata.put("source_path", rootSourceName + " > 语义片段1");
            fallbackMetadata.put("section_title", "语义片段1");
            documents.addAll(splitContentSemantically(extractedText, fallbackMetadata, rootSourceName + " > 语义片段1", "语义片段1"));
        }
        log.info("应用结构化 RAG 分块策略: rootSource={}, chunkCount={}", rootSourceName, documents.size());
        return documents;
    }

    private Map<String, Object> buildBaseMetadata(ChatAttachment attachment) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attachment_id", String.valueOf(attachment.getId()));
        metadata.put("file_name", attachment.getFileName() != null ? attachment.getFileName() : "unknown");
        metadata.put("scope", attachment.getScope());
        if (SCOPE_SESSION.equals(attachment.getScope()) && attachment.getSessionId() != null) {
            metadata.put("session_id", String.valueOf(attachment.getSessionId()));
        } else if (SCOPE_GLOBAL.equals(attachment.getScope())) {
            metadata.put("session_id", SCOPE_GLOBAL);
        }
        if (attachment.getUserId() != null) {
            metadata.put("user_id", String.valueOf(attachment.getUserId()));
        }
        return metadata;
    }

    private List<Document> toDocuments(StructuredChunk chunk, Map<String, Object> baseMetadata) {
        if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
            return List.of();
        }

        Map<String, Object> chunkMetadata = new HashMap<>(baseMetadata);
        chunkMetadata.put("source_path", chunk.sourcePath());
        chunkMetadata.put("section_title", chunk.sectionTitle());
        return splitContentSemantically(chunk.content(), chunkMetadata, chunk.sourcePath(), chunk.sectionTitle());
    }

    private List<Document> splitContentSemantically(String content, Map<String, Object> baseMetadata,
                                                    String sourcePath, String sectionTitle) {
        String normalized = normalizeDocumentText(content);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= SEMANTIC_CHUNK_MAX_CHARS) {
            return List.of(new Document(normalized, new HashMap<>(baseMetadata)));
        }

        List<String> units = splitSemanticUnits(normalized);
        if (units.isEmpty()) {
            return List.of(new Document(normalized, new HashMap<>(baseMetadata)));
        }

        List<Document> documents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> currentUnits = new ArrayList<>();
        int part = 1;
        for (String unit : units) {
            if (unit.isBlank()) {
                continue;
            }
            boolean needFlush = current.length() >= SEMANTIC_CHUNK_MIN_CHARS
                    && current.length() + unit.length() > SEMANTIC_CHUNK_MAX_CHARS;
            if (needFlush) {
                documents.add(buildSemanticDocument(sourcePath, sectionTitle, baseMetadata, current.toString(), part++));
                currentUnits = tailOverlapUnits(currentUnits);
                current = rebuildBuffer(currentUnits);
            }
            if (current.length() > 0) {
                current.append(current.toString().endsWith("\n\n") ? "" : "\n\n");
            }
            current.append(unit.trim());
            currentUnits.add(unit.trim());

            if (current.length() >= SEMANTIC_CHUNK_TARGET_CHARS && current.length() >= SEMANTIC_CHUNK_MIN_CHARS) {
                documents.add(buildSemanticDocument(sourcePath, sectionTitle, baseMetadata, current.toString(), part++));
                currentUnits = tailOverlapUnits(currentUnits);
                current = rebuildBuffer(currentUnits);
            }
        }
        if (current.length() > 0) {
            documents.add(buildSemanticDocument(sourcePath, sectionTitle, baseMetadata, current.toString(), part));
        }
        return documents;
    }

    private List<String> splitSemanticUnits(String content) {
        List<String> paragraphs = java.util.Arrays.stream(content.split("\\n\\s*\\n+"))
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .toList();
        List<String> units = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= SEMANTIC_CHUNK_MAX_CHARS) {
                units.add(paragraph);
                continue;
            }
            units.addAll(splitLongParagraphBySentence(paragraph));
        }
        return units;
    }

    private List<String> splitLongParagraphBySentence(String paragraph) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = Pattern.compile(".*?(?:[。！？!?；;]|(?<=\\.)\\s+|$)", Pattern.DOTALL).matcher(paragraph);
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            return List.of(paragraph);
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() >= SEMANTIC_CHUNK_MIN_CHARS
                    && current.length() + sentence.length() > SEMANTIC_CHUNK_MAX_CHARS) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private Document buildSemanticDocument(String sourcePath, String sectionTitle, Map<String, Object> baseMetadata,
                                           String content, int part) {
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        if (part > 1) {
            metadata.put("source_path", sourcePath + " > 分片" + part);
        } else {
            metadata.put("source_path", sourcePath);
        }
        metadata.put("section_title", sectionTitle);
        return new Document(content.trim(), metadata);
    }

    private List<String> tailOverlapUnits(List<String> units) {
        if (units.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, units.size() - SEMANTIC_OVERLAP_UNITS);
        return new ArrayList<>(units.subList(start, units.size()));
    }

    private StringBuilder rebuildBuffer(List<String> units) {
        StringBuilder builder = new StringBuilder();
        for (String unit : units) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(unit);
        }
        return builder;
    }

    private List<StructuredChunk> splitByDocumentStructure(String extractedText, String rootSourceName) {
        String normalizedText = normalizeDocumentText(extractedText);
        if (normalizedText.isBlank()) {
            return List.of();
        }

        List<StructuredChunk> chunks = new ArrayList<>();
        Deque<HeadingNode> headingStack = new ArrayDeque<>();
        StringBuilder currentContent = new StringBuilder();
        String currentSourcePath = rootSourceName + " > 引言";
        String currentSectionTitle = "引言";
        boolean sawHeading = false;

        for (String line : normalizedText.split("\n")) {
            HeadingInfo heading = detectHeading(line);
            if (heading != null) {
                flushStructuredChunk(chunks, currentContent, currentSourcePath, currentSectionTitle);
                updateHeadingStack(headingStack, heading.level(), heading.title());
                currentSourcePath = buildSourcePath(rootSourceName, headingStack);
                currentSectionTitle = heading.title();
                currentContent.append(heading.rawLine()).append('\n');
                sawHeading = true;
                continue;
            }
            currentContent.append(line).append('\n');
        }
        flushStructuredChunk(chunks, currentContent, currentSourcePath, currentSectionTitle);

        if (!sawHeading) {
            return List.of();
        }
        return chunks;
    }

    private List<StructuredChunk> splitByParagraphWindows(String extractedText, String rootSourceName) {
        String normalizedText = normalizeDocumentText(extractedText);
        if (normalizedText.isBlank()) {
            return List.of();
        }

        String[] paragraphs = normalizedText.split("\\n\\s*\\n+");
        List<StructuredChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 1;
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() > SECTION_MIN_CHARS && current.length() + trimmed.length() > SECTION_MAX_CHARS) {
                chunks.add(new StructuredChunk(rootSourceName + " > 语义片段" + index, "语义片段" + index, current.toString().trim()));
                current.setLength(0);
                index++;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            chunks.add(new StructuredChunk(rootSourceName + " > 语义片段" + index, "语义片段" + index, current.toString().trim()));
        }
        return chunks;
    }

    private void flushStructuredChunk(List<StructuredChunk> chunks, StringBuilder currentContent,
                                      String currentSourcePath, String currentSectionTitle) {
        if (currentContent == null || currentContent.isEmpty()) {
            return;
        }
        String content = currentContent.toString().trim();
        currentContent.setLength(0);
        if (content.isEmpty()) {
            return;
        }
        chunks.add(new StructuredChunk(currentSourcePath, currentSectionTitle, content));
    }

    private HeadingInfo detectHeading(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.length() > 120) {
            return null;
        }

        Matcher markdownMatcher = MARKDOWN_HEADING_PATTERN.matcher(trimmed);
        if (markdownMatcher.matches()) {
            return new HeadingInfo(markdownMatcher.group(1).length(), cleanupHeading(markdownMatcher.group(2)), trimmed);
        }

        Matcher numericMatcher = NUMERIC_HEADING_PATTERN.matcher(trimmed);
        if (numericMatcher.matches()) {
            int level = numericMatcher.group(1).split("\\.").length;
            return new HeadingInfo(level, cleanupHeading(numericMatcher.group(2)), trimmed);
        }

        Matcher chapterMatcher = CHINESE_CHAPTER_PATTERN.matcher(trimmed);
        if (chapterMatcher.matches()) {
            String prefix = cleanupHeading(chapterMatcher.group(1));
            String suffix = cleanupHeading(chapterMatcher.group(2));
            return new HeadingInfo(resolveChineseHeadingLevel(prefix), joinHeadingParts(prefix, suffix), trimmed);
        }

        Matcher chineseListMatcher = CHINESE_LIST_PATTERN.matcher(trimmed);
        if (chineseListMatcher.matches()) {
            return new HeadingInfo(2, cleanupHeading(chineseListMatcher.group(1) + "、" + chineseListMatcher.group(2)), trimmed);
        }
        return null;
    }

    private int resolveChineseHeadingLevel(String prefix) {
        if (prefix.contains("章") || prefix.contains("篇") || prefix.contains("部分")) {
            return 1;
        }
        if (prefix.contains("节")) {
            return 2;
        }
        if (prefix.contains("条")) {
            return 3;
        }
        return 1;
    }

    private String joinHeadingParts(String prefix, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return prefix;
        }
        return prefix + " " + suffix;
    }

    private String cleanupHeading(String heading) {
        if (heading == null) {
            return "";
        }
        return heading.trim().replaceAll("[：:]+$", "");
    }

    private void updateHeadingStack(Deque<HeadingNode> headingStack, int level, String title) {
        while (!headingStack.isEmpty() && headingStack.peekLast().level() >= level) {
            headingStack.pollLast();
        }
        headingStack.addLast(new HeadingNode(level, title));
    }

    private String buildSourcePath(String rootSourceName, Deque<HeadingNode> headingStack) {
        StringBuilder path = new StringBuilder(rootSourceName);
        for (HeadingNode node : headingStack) {
            path.append(" > ").append(node.title());
        }
        return path.toString();
    }

    private String normalizeDocumentText(String extractedText) {
        if (extractedText == null) {
            return "";
        }
        return extractedText.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\u0000", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String resolveRootSourceName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown";
        }
        return fileName.trim();
    }

    private String buildVectorDocId(Long attachmentId, int chunkIndex) {
        return "attachment:" + attachmentId + ":chunk:" + chunkIndex;
    }

    private void deleteVectorDocuments(ChatAttachment attachment) {
        List<String> vectorDocIds = parseVectorDocIds(attachment.getVectorDocIds());
        if (vectorDocIds.isEmpty()) {
            return;
        }
        try {
            vectorStore.delete(vectorDocIds);
            log.info("删除附件向量文档成功: attachmentId={}, chunkCount={}", attachment.getId(), vectorDocIds.size());
        } catch (Exception e) {
            throw new IllegalStateException("删除向量知识失败: " + e.getMessage(), e);
        }
    }

    private List<String> parseVectorDocIds(String vectorDocIds) {
        if (vectorDocIds == null || vectorDocIds.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(vectorDocIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
    }

    private void deletePhysicalFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (Exception e) {
            throw new IllegalStateException("删除附件文件失败: " + e.getMessage(), e);
        }
    }

    private record StructuredChunk(String sourcePath, String sectionTitle, String content) {
    }

    private record HeadingInfo(int level, String title, String rawLine) {
    }

    private record HeadingNode(int level, String title) {
    }
}
