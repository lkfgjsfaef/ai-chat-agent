package com.niit.agent.service.cleaning;

import java.util.regex.Pattern;

/**
 * 去除 URL 和邮箱地址。
 * 保留 Markdown 图片 URL（如 ![alt](url)）中的 URL 作为占位符 [图片引用]。
 */
public class RemoveUrlsEmailsRule implements CleaningRule {

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[([^]]*)]\\(([^)]+)\\)");

    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s`\\[\\]()'\"]+(?:\\[[^\\]]*])?");

    @Override
    public String getId() {
        return "remove_urls_emails";
    }

    @Override
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 先处理 Markdown 图片：把图片 URL 替换为占位符
        String result = MARKDOWN_IMAGE.matcher(text).replaceAll("[图片引用]");

        // 去掉邮箱
        result = EMAIL.matcher(result).replaceAll("");

        // 去掉 URL
        result = URL.matcher(result).replaceAll("");

        // 清理可能遗留的空括号对
        result = result.replaceAll("\\(\\)", "");
        result = result.replaceAll("\\[]", "");

        return result;
    }
}
