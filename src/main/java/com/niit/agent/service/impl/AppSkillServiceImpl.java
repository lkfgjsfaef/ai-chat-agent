package com.niit.agent.service.impl;

import com.niit.agent.service.AppSkillService;
import com.niit.agent.vo.AppSkillVO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AppSkillServiceImpl implements AppSkillService {

    private final Map<String, AppSkillVO> skillMap = new LinkedHashMap<>();
    private static final Set<String> SUMMARY_KEYWORDS = Set.of("总结", "概括", "提炼", "摘要", "梳理", "归纳", "总结一下", "要点");
    private static final Set<String> PACKAGING_KEYWORDS = Set.of("简历", "README", "项目介绍", "亮点", "面试", "包装", "项目简介");
    private static final Set<String> DEEP_RETRIEVAL_KEYWORDS = Set.of("根据知识库", "结合资料", "引用来源", "排查", "故障", "原因", "依据", "证据");

    public AppSkillServiceImpl() {
        register(new AppSkillVO(
                "knowledge-qa",
                "知识问答",
                "适合通用知识问答与企业知识库查询，回答要准确、清晰、直接。",
                "chat",
                "例如：帮我解释这份文档的重点，或根据知识库回答这个问题。"));
        register(new AppSkillVO(
                "deep-retrieval",
                "深度检索",
                "适合复杂知识追问、排障分析和需要严格来源依据的问题，优先给出证据链和引用。",
                "rag",
                "例如：结合知识库排查这个故障，并说明依据来自哪些资料。"));
        register(new AppSkillVO(
                "doc-summary",
                "文档总结",
                "适合总结上传文档、提炼要点、输出分层摘要和行动清单。",
                "document",
                "例如：请把我上传的文档总结成要点、风险和待办。"));
        register(new AppSkillVO(
                "project-packager",
                "项目包装",
                "适合把技术内容整理成 README、简历项目描述、亮点总结或面试话术。",
                "packaging",
                "例如：把当前项目整理成一段简历简介，突出问题、方案和效果。"));
    }

    @Override
    public List<AppSkillVO> listSkills() {
        return List.copyOf(skillMap.values());
    }

    @Override
    public boolean isValidSkill(String skillId) {
        return skillId == null || skillId.isBlank() || skillMap.containsKey(skillId.trim());
    }

    @Override
    public Optional<AppSkillVO> findSkill(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillMap.get(skillId.trim()));
    }

    @Override
    public String resolveSkillId(String overrideSkillId, String sessionSkillId, String latestUserQuestion) {
        String manualOverride = normalizeSkillId(overrideSkillId);
        if (manualOverride != null) {
            return manualOverride;
        }

        String sessionDefault = normalizeSkillId(sessionSkillId);
        if (sessionDefault != null) {
            return sessionDefault;
        }

        return routeSkillByQuestion(latestUserQuestion);
    }

    @Override
    public String buildSkillInstruction(String skillId, String latestUserQuestion) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        return switch (skillId.trim()) {
            case "knowledge-qa" -> """
                    当前对话模式为【知识问答】。
                    回答要求：
                    1. 优先直接回答用户问题，再补充必要解释。
                    2. 如果命中知识库，尽量基于检索证据作答，不要无根据扩写。
                    3. 表达简洁、清晰，适合普通用户阅读。
                    """;
            case "deep-retrieval" -> """
                    当前对话模式为【深度检索】。
                    回答要求：
                    1. 优先基于检索证据回答，必要时说明依据、原因、步骤和风险。
                    2. 对关键结论尽量附带来源引用或证据描述。
                    3. 如果证据不足，要明确指出缺口，不要编造。
                    4. 如果问题是排障类，请优先给出“现象 -> 可能原因 -> 排查步骤 -> 建议处理”。
                    """;
            case "doc-summary" -> """
                    当前对话模式为【文档总结】。
                    回答要求：
                    1. 优先总结用户上传或命中的文档内容，不要泛泛而谈。
                    2. 默认使用结构化输出：核心结论、关键要点、风险/限制、下一步建议。
                    3. 如果用户要求更短或更长，再调整输出长度。
                    """;
            case "project-packager" -> """
                    当前对话模式为【项目包装】。
                    回答要求：
                    1. 优先把技术内容转换为项目介绍、简历亮点、README 文案或面试表达。
                    2. 采用“问题 -> 方案 -> 实现 -> 效果”的讲法。
                    3. 避免空泛堆术语，突出高含金量设计和落地价值。
                    4. 如果用户没有指定长度，默认输出简洁版本。
                    """;
            default -> "";
        };
    }

    private void register(AppSkillVO skill) {
        skillMap.put(skill.getId(), skill);
    }

    private String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String trimmed = skillId.trim();
        return skillMap.containsKey(trimmed) ? trimmed : null;
    }

    private String routeSkillByQuestion(String latestUserQuestion) {
        if (latestUserQuestion == null || latestUserQuestion.isBlank()) {
            return null;
        }
        String question = latestUserQuestion.trim();

        if (containsAny(question, PACKAGING_KEYWORDS)) {
            return "project-packager";
        }
        if (containsAny(question, SUMMARY_KEYWORDS)) {
            return "doc-summary";
        }
        if (containsAny(question, DEEP_RETRIEVAL_KEYWORDS)) {
            return "deep-retrieval";
        }
        return "knowledge-qa";
    }

    private boolean containsAny(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
