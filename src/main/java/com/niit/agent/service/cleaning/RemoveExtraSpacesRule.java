package com.niit.agent.service.cleaning;

/**
 * 压缩多余空白：
 * - 3 个以上连续换行 → 2 个换行
 * - 连续空白字符（非换行）→ 单个空格
 */
public class RemoveExtraSpacesRule implements CleaningRule {

    @Override
    public String getId() {
        return "remove_extra_spaces";
    }

    @Override
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 3+ 换行压缩为 2 换行（保留段落间隔）
        String result = text.replaceAll("\\n{3,}", "\n\n");
        // 每个行内多余的空白压缩为单空格
        result = result.replaceAll("[ \\f\\t\\v]+", " ");
        // 去掉行首行尾多余空格（每行）
        result = result.replaceAll("(?m)^[ \\t]+", "");
        result = result.replaceAll("(?m)[ \\t]+$", "");
        return result;
    }
}
