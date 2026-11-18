package com.docreview.parser;

import com.docreview.config.AppProperties;
import com.docreview.domain.entity.DocumentBlock;
import com.docreview.repository.DocumentBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.List;

@Service
public class DocumentParseService {

    private final List<DocumentParser> parsers;
    private final DocumentBlockRepository blockRepository;
    private final AppProperties props;

    public DocumentParseService(List<DocumentParser> parsers,
                                DocumentBlockRepository blockRepository,
                                AppProperties props) {
        this.parsers = parsers;
        this.blockRepository = blockRepository;
        this.props = props;
    }

    /**
     * 解析文档并落库，返回按顺序排列的块。
     */
    @Transactional
    public List<DocumentBlock> parseAndSave(String docId, File file, String fileName) {
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("暂不支持的文件类型: " + fileName));

        List<DocumentParser.RawParagraph> paragraphs;
        try {
            paragraphs = parser.parse(file);
        } catch (Exception e) {
            throw new IllegalStateException("文档解析失败: " + e.getMessage(), e);
        }

        blockRepository.deleteByDocId(docId);

        // 块 ID 需全局唯一（主键），用文档 ID 前 8 位做前缀
        String docPrefix = docId.replace("-", "");
        docPrefix = docPrefix.length() >= 8 ? docPrefix.substring(0, 8) : docPrefix;

        String section = "正文";
        int seq = 0;
        java.util.ArrayList<DocumentBlock> blocks = new java.util.ArrayList<>();
        for (DocumentParser.RawParagraph paragraph : paragraphs) {
            if (paragraph.heading()) {
                section = paragraph.text();
            }
            List<String> pieces = new java.util.ArrayList<>();
            ParagraphUtils.appendSplit(pieces, paragraph.text(), props.getReview().getSectionMaxChars());
            for (String piece : pieces) {
                seq++;
                DocumentBlock block = new DocumentBlock();
                block.setId(String.format("%s_b%04d", docPrefix, seq));
                block.setDocId(docId);
                block.setPage(paragraph.page());
                block.setSectionPath(section);
                block.setSeq(seq);
                block.setText(piece);
                block.setType(paragraph.heading() ? "HEADING" : "TEXT");
                blocks.add(block);
            }
        }
        blockRepository.saveAll(blocks);
        return blockRepository.findByDocIdOrderBySeqAsc(docId);
    }
}
