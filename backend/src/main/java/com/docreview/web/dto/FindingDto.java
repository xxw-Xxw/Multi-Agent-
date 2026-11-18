package com.docreview.web.dto;

public record FindingDto(
        String id,
        String agentName,
        String ruleId,
        String level,
        String blockId,
        int page,
        String quote,
        String reason,
        String suggestion,
        boolean conflict,
        String verdict,
        String humanStatus
) {
}
