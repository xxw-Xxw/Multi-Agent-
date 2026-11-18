package com.docreview.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public List<RawParagraph> parse(File file) throws Exception {
        List<RawParagraph> result = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file)) {
            int pages = document.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);
                for (String line : text.split("\\r?\\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(new RawParagraph(page, trimmed, ParagraphUtils.isHeading(trimmed)));
                    }
                }
            }
        }
        return result;
    }
}
