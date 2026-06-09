package com.niit.agent.service.cleaning;

/**
 * 去除 ASCII 控制字符 0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F, 0x7F，
 * 以及 Unicode U+FFFE（BOM 的 UTF-16 变体）。
 * 保留 \n (0x0A)、\r (0x0D)、\t (0x09) 因为它们是有效的格式化字符。
 */
public class RemoveControlCharsRule implements CleaningRule {

    private static final String CONTROL_CHARS_REGEX = "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\uFFFE]";

    @Override
    public String getId() {
        return "remove_control_chars";
    }

    @Override
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll(CONTROL_CHARS_REGEX, "");
    }
}
