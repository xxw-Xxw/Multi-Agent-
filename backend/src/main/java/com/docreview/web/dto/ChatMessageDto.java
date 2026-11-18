package com.docreview.web.dto;

import java.util.List;

public record ChatMessageDto(
        String id,
        String role,
        String content,
        List<CitationDto> citations,
        String status,
        String createdAt
) {
}
