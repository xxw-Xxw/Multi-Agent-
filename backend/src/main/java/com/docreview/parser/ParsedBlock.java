package com.docreview.parser;

public record ParsedBlock(
        String blockId,
        int page,
        String sectionPath,
        int seq,
        String text,
        String type
) {
}
