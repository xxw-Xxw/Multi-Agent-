package com.docreview.web.dto;

public record BlockDto(String id, int page, String sectionPath, int seq, String text, String type) {
}
