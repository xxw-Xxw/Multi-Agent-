package com.docreview.web.dto;

public record DocumentDto(String id, String name, String contentType, long sizeBytes, int blockCount) {
}
