package com.docreview.web.dto;

public record TaskDto(
        String id,
        String documentId,
        String status,
        String docType,
        int progress,
        String stageMessage,
        String errorMessage,
        long latencyMs,
        String createdAt
) {
}
