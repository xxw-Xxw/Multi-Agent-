package com.docreview.web.dto;

/** 历史审查记录：一条 review_task 即一条记录。 */
public record HistoryItemDto(
        String taskId,
        String documentId,
        String documentName,
        String status,
        String docType,
        int progress,
        int findingCount,
        int highCount,
        int mediumCount,
        int lowCount,
        String sessionId,
        String createdAt
) {
}
