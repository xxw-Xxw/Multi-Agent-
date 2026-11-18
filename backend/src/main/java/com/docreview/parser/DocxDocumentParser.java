package com.docreview.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".docx");
    }

    @Override
    public List<RawParagraph> parse(File file) throws Exception {
        List<RawParagraph> result = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase();
                boolean heading = style.startsWith("heading") || style.contains("标题")
                        || ParagraphUtils.isHeading(text);
                result.add(new RawParagraph(0, text, heading));
            }
        }
        return result;
    }
}
