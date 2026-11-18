package com.docreview.parser;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".txt");
    }

    @Override
    public List<RawParagraph> parse(File file) throws Exception {
        String text = readText(file);
        List<RawParagraph> result = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(new RawParagraph(0, trimmed, ParagraphUtils.isHeading(trimmed)));
        }
        return result;
    }

    private String readText(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignored) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }
}
