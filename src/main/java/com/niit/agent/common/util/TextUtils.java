package com.niit.agent.common.util;

import java.util.Set;

public final class TextUtils {

    private TextUtils() {}

    public static boolean containsAny(String text, String[] keywords) {
        if (text == null || keywords == null) return false;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && text.contains(kw)) return true;
        }
        return false;
    }

    public static boolean containsAny(String text, Set<String> keywords) {
        if (text == null || keywords == null) return false;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && text.contains(kw)) return true;
        }
        return false;
    }

    public static double safeRatio(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return Math.round((double) numerator / denominator * 10000.0) / 10000.0;
    }

    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
