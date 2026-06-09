package com.niit.agent.service.cleaning;

/**
 * 修复 Tika 在解析某些文档时误将其它字符转义为管道符号的情况。
 * &lt;| -- &gt; &lt; , |&gt; -- &gt; &gt;
 */
public class FixPipeSymbolsRule implements CleaningRule {

    @Override
    public String getId() {
        return "fix_pipe_symbols";
    }

    @Override
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("<|", "<")
                   .replace("|>", ">");
    }
}
