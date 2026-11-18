package com.docreview.web.dto;

public record TraceDto(
        String id,
        String agentName,
        String status,
        String input,
        String output,
        long tokens,
        long latencyMs,
        String errorMessage,
        String createdAt
) {
}
