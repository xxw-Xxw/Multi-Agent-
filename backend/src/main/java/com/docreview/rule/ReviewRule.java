package com.docreview.rule;

import com.docreview.domain.RiskLevel;

import java.util.List;

/**
 * 一条审查规则。真实模式下作为提示词中的"审查清单"，mock 模式下作为本地规则引擎。
 */
public record ReviewRule(
        String id,
        String domain,
        String title,
        RiskLevel level,
        List<String> keywords,
        String reason,
        String suggestion
) {
}
