package com.docreview.parser;

import java.io.File;
import java.util.List;

public interface DocumentParser {

    boolean supports(String fileName);

    List<RawParagraph> parse(File file) throws Exception;

    /**
     * 解析出的原始段落，page 未知时填 0。
     */
    record RawParagraph(int page, String text, boolean heading) {
    }
}
