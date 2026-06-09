package com.niit.agent.service.cleaning;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TextCleaningPipeline {

    private final List<CleaningRule> defaultRules;
    private final Map<String, CleaningRule> optionalRules;
    private Set<String> enabledOptionalRuleIds = Set.of();

    @Value("${text-cleaning.optional-rules:}")
    private String optionalRulesConfig;

    public TextCleaningPipeline() {
        this.defaultRules = List.of(
                new RemoveControlCharsRule(),
                new FixPipeSymbolsRule(),
                new RemoveBomRule()
        );
        this.optionalRules = List.of(
                new RemoveExtraSpacesRule(),
                new RemoveUrlsEmailsRule()
        ).stream().collect(Collectors.toUnmodifiableMap(CleaningRule::getId, Function.identity()));
    }

    @PostConstruct
    void init() {
        if (optionalRulesConfig != null && !optionalRulesConfig.isBlank()) {
            this.enabledOptionalRuleIds = Arrays.stream(optionalRulesConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        final int beforeLen = text.length();
        String result = text;

        for (CleaningRule rule : defaultRules) {
            int before = result.length();
            result = rule.apply(result);
            if (result.length() != before) {
                log.debug("规则 [{}] 文本长度变化: {} → {}", rule.getId(), before, result.length());
            }
        }

        for (String ruleId : enabledOptionalRuleIds) {
            CleaningRule rule = optionalRules.get(ruleId);
            if (rule != null) {
                int before = result.length();
                result = rule.apply(result);
                if (result.length() != before) {
                    log.debug("规则 [{}] 文本长度变化: {} → {}", rule.getId(), before, result.length());
                }
            }
        }

        if (result.length() != beforeLen) {
            log.info("清洗完成: 原始={} 字符, 清洗后={} 字符, 缩减={}", beforeLen, result.length(), beforeLen - result.length());
        }
        return result;
    }

    public Set<String> getEnabledOptionalRuleIds() {
        return Collections.unmodifiableSet(enabledOptionalRuleIds);
    }
}
