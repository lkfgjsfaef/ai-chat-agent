package com.niit.agent.common.util;

import java.util.*;

public final class RetrievalUtil {

    private RetrievalUtil() {}

    public static String sanitizeQueryVariant(String query) {
        if (query == null) return "";
        String normalized = query.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    public static String summarizeQuestion(String question) {
        return summarizeQuestion(question, 60);
    }

    public static String summarizeQuestion(String question, int maxLen) {
        if (question == null) return "null";
        return question.length() > maxLen ? question.substring(0, maxLen) + "..." : question;
    }

    public static List<String> parseExpandedQueries(String responseText, int maxVariants) {
        if (responseText == null || responseText.isBlank()) return List.of();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (String line : responseText.split("\\r?\\n")) {
            String normalized = line.trim()
                    .replaceFirst("^[\\-•*\\d\\s.、()（）]+", "")
                    .replace("\"", "")
                    .trim();
            if (!normalized.isEmpty()) {
                variants.add(sanitizeQueryVariant(normalized));
            }
            if (variants.size() >= maxVariants) break;
        }
        return variants.stream().toList();
    }
}
